package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toJson
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.nodes.Document

/**
 * Novel Archive — https://novelarchive.st/
 *
 * JSON API, not HTML scraping. Verified live 2026-11-13:
 * - GET /api/novels?search=&page=N&per_page=24
 *   -> {novels: [{id, title, cover_url, description, total_chapters}],
 *       pagination: {page, per_page, total, total_pages, has_next}}
 * - GET /api/novels/{id} -> {novel: {..., chapter_names: ["Chapter 1 ..."]}}
 * - GET /api/novels/{id}/chapters/{n}?prefetch=1
 *   -> {chapter: {number, name, content}} — content is plain text,
 *      paragraphs separated by blank lines.
 * - GET /api/novels/{id}/cover?w=640&q=72&format=jpg -> cover image.
 *
 * Public reads need no login, but the site sits behind Cloudflare: bare
 * requests get a 403, full browser headers (Accept, Referer, Sec-Fetch-*)
 * are required. The app's NetworkClient (User-Agent + Cloudflare
 * interceptor) supplies the rest.
 *
 * Book URLs are the site's reader URLs (`/novel?id=<id>`); chapter URLs
 * add `&chapter=<n>`. [transformChapterUrl] rewrites a chapter URL to its
 * API URL before the download step fetches it, so [getChapterText] /
 * [getChapterTitle] re-request the API from the document location.
 */
class NovelArchive(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novel_archive"
    override val nameStrId = R.string.source_name_novel_archive
    override val baseUrl = "https://novelarchive.st/"
    override val catalogUrl = "https://novelarchive.st/"
    override val iconUrl = "https://novelarchive.st/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val apiBaseUrl = "https://novelarchive.st/api"

    private val apiHeaders: Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Referer", baseUrl)
        .add("Origin", "https://novelarchive.st")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Dest", "empty")
        .build()

    private suspend fun getApiJson(url: String): JsonObject =
        networkClient.call(getRequest(url, headers = apiHeaders)).toJson().asJsonObject

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun resolveApiPath(path: String): String =
        if (path.startsWith("http")) path else "https://novelarchive.st$path"

    private fun parseNovel(novel: JsonObject): BookResult {
        val id = novel.str("id")
        val cover = novel.str("cover_url").ifBlank { novel.str("image_url") }
        return BookResult(
            title = novel.str("title"),
            url = "${baseUrl}novel?id=$id",
            coverImageUrl = if (cover.isBlank()) "" else resolveApiPath(cover),
            description = novel.str("description")
        )
    }

    private suspend fun getNovelDetail(novelId: String): JsonObject =
        getApiJson("$apiBaseUrl/novels/$novelId").getAsJsonObject("novel")

    private fun novelIdFromUrl(url: String): String? =
        Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)

    private fun chapterNumberFromUrl(url: String): Int? =
        Regex("[?&]chapter=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun getChapterJson(doc: Document): JsonObject? {
        val location = doc.location()
        val novelId = novelIdFromUrl(location) ?: return null
        val chapter = chapterNumberFromUrl(location) ?: return null
        return runCatching {
            getApiJson("$apiBaseUrl/novels/$novelId/chapters/$chapter?prefetch=1")
        }.getOrNull()
    }

    override suspend fun transformChapterUrl(url: String): String {
        val novelId = novelIdFromUrl(url) ?: return url
        val chapter = chapterNumberFromUrl(url) ?: return url
        return "$apiBaseUrl/novels/$novelId/chapters/$chapter?prefetch=1"
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            getChapterJson(doc)?.getAsJsonObject("chapter")?.str("name")
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            getChapterJson(doc)?.getAsJsonObject("chapter")?.str("content").orEmpty()
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val novelId = novelIdFromUrl(bookUrl) ?: return@tryConnect null
                runCatching {
                    resolveApiPath(getNovelDetail(novelId).str("cover_url"))
                        .takeIf { it != "https://novelarchive.st" }
                }.getOrNull()
                    ?: "$apiBaseUrl/novels/$novelId/cover?w=640&q=72&format=jpg"
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val novelId = novelIdFromUrl(bookUrl) ?: return@tryConnect null
                getNovelDetail(novelId).str("description").ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val novelId = novelIdFromUrl(bookUrl)
                ?: throw Exception("Invalid Novel Archive book URL: $bookUrl")
            val chapterNames = getNovelDetail(novelId)
                .getAsJsonArray("chapter_names")
                ?.map { it.asString } ?: emptyList()
            chapterNames.mapIndexed { index, name ->
                ChapterResult(
                    title = name,
                    url = "$bookUrl&chapter=${index + 1}"
                )
            }
        }
    }

    private suspend fun getNovelsPage(
        index: Int,
        search: String?
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val page = index + 1
            val builder = "$apiBaseUrl/novels".toUrlBuilderSafe()
                .add("page", page)
                .add("per_page", 24)
            if (!search.isNullOrBlank()) builder.add("search", search)
            val json = getApiJson(builder.toString())
            val books = json.getAsJsonArray("novels")
                ?.map { parseNovel(it.asJsonObject) } ?: emptyList()
            val hasNext = json.getAsJsonObject("pagination")
                ?.get("has_next")?.asBoolean ?: books.isNotEmpty()
            PagedList(list = books, index = index, isLastPage = !hasNext)
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = getNovelsPage(index, search = null)

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        if (input.isBlank()) withContext(Dispatchers.Default) {
            Response.Success(PagedList.createEmpty(index = index))
        } else {
            getNovelsPage(index, search = input)
        }
}
