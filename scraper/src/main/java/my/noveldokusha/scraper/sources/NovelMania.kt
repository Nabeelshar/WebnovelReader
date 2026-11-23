package my.noveldokusha.scraper.sources

import com.google.gson.JsonArray
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
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Novel Mania — https://novelmania.com.br
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * catalog, book and chapter pages plus the site's own JSON API,
 * which its React frontend uses):
 * - The site is a React SPA whose data comes from a public JSON API
 *   under /api (no login needed for reads):
 *   - Catalog: GET /api/novels?page=<n>&limit=24 ->
 *     {data: [{title, slug, cover: {original, thumb, small, large}}],
 *      meta: {page, pages, count}}. Book URLs are /novels/<slug>.
 *   - Search: GET /api/novels?q=<query>&page=<n>&limit=24 — the
 *     same endpoint with the "q" parameter (the ?search= and
 *     ?title= parameters are silently ignored by the API).
 *   - Book detail: GET /api/novels/<slug> -> {data: {title, cover,
 *     synopsis (HTML), chaptersCount, ...}}.
 *   - Chapter list: GET /api/novels/<slug>/chapters?page=<n>&limit=50
 *     — the API caps limit at 50 per page; meta.pages gives the page
 *     count. Chapters come ordered by position, oldest first
 *     (prologues carry fractional positions like 0.5). Chapter URLs
 *     are /novels/<slug>/capitulos/<chapter-slug>.
 * - The HTML book page only renders the first 50 chapters, so the
 *   API is the only way to get a full chapter list.
 * - Chapter page: the story is div.rich-text-content, a clean run
 *   of <p> paragraphs with no ads or navigation inside. The <h1>
 *   carries the chapter title as its visible text, followed by a
 *   hidden span.sr-only holding " – <book title>"; dropping the
 *   hidden elements leaves the exact chapter title (a JSON-LD
 *   breadcrumb suffix-strip is kept as fallback).
 */
class NovelMania(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelmania"
    override val nameStrId = R.string.source_name_novelmania
    override val baseUrl = "https://novelmania.com.br/"
    override val catalogUrl = "https://novelmania.com.br/novels"
    override val iconUrl = "https://novelmania.com.br/favicon.ico"
    override val language = LanguageCode.PORTUGUESE

    private val apiBaseUrl = "https://novelmania.com.br/api"

    private val apiHeaders: Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Referer", baseUrl)
        .build()

    private suspend fun getApiJson(url: String): JsonObject =
        networkClient.call(getRequest(url, headers = apiHeaders)).toJson().asJsonObject

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.obj(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

    private fun JsonObject.arr(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

    private fun JsonObject.int(key: String): Int? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asInt }.getOrNull()
        else null

    private fun bookSlugFromUrl(bookUrl: String): String =
        bookUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')

    private fun parseNovel(novel: JsonObject): BookResult? {
        val slug = novel.str("slug")
        val title = novel.str("title")
        if (slug.isBlank() || title.isBlank()) return null
        val cover = novel.obj("cover")
        val coverUrl = cover?.str("small")?.ifBlank { null }
            ?: cover?.str("thumb")?.ifBlank { null }
            ?: cover?.str("original")?.ifBlank { null }
            ?: cover?.str("large")
            ?: ""
        return BookResult(
            title = title,
            url = "${baseUrl}novels/$slug",
            coverImageUrl = coverUrl
        )
    }

    private suspend fun getNovelsPage(
        index: Int,
        search: String?
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val page = index + 1
            val builder = "$apiBaseUrl/novels".toUrlBuilderSafe()
                .add("page", page)
                .add("limit", 24)
            if (!search.isNullOrBlank()) builder.add("q", search)
            val json = getApiJson(builder.toString())
            val books = json.arr("data")
                ?.mapNotNull { parseNovel(it.asJsonObject) } ?: emptyList()
            val totalPages = json.obj("meta")?.int("pages")
            val isLastPage = books.isEmpty() ||
                (totalPages != null && page >= totalPages)
            PagedList(list = books, index = index, isLastPage = isLastPage)
        }
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val heading = doc.selectFirst("h1") ?: return@withContext null
            // The <h1> is the chapter title followed by a visually
            // hidden <span class="sr-only"> with " – <book title>"
            // for screen readers / SEO. Drop hidden elements from a
            // clone and the visible text is the exact chapter title.
            val visible = heading.clone()
            visible.select(".sr-only").remove()
            visible.text().trim().ifBlank { null }
                ?: fallbackChapterTitle(heading)
        }

    /**
     * Fallback for a changed heading markup: read the whole <h1>
     * ("<chapter title> – <book title>") and strip the book-title
     * suffix, recovering the book title from the BreadcrumbList
     * JSON-LD (its position 3 entry).
     */
    private fun fallbackChapterTitle(heading: org.jsoup.nodes.Element): String? {
        val text = heading.text().trim().ifBlank { return null }
        val bookTitle = breadcrumbBookTitle(heading.ownerDocument() ?: return text)
        return if (bookTitle != null && text.endsWith(" – $bookTitle")) {
            text.removeSuffix(" – $bookTitle").trim().ifBlank { null }
        } else {
            text
        }
    }

    private fun breadcrumbBookTitle(doc: Document): String? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val json = runCatching {
                com.google.gson.JsonParser.parseString(script.data()).asJsonObject
            }.getOrNull() ?: continue
            if (json.str("@type") != "BreadcrumbList") continue
            val items = json.arr("itemListElement") ?: continue
            for (item in items) {
                val entry = item.asJsonObject
                if (entry.int("position") == 3) return entry.str("name").ifBlank { null }
            }
        }
        return null
    }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".rich-text-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select("script, iframe, style").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = bookSlugFromUrl(bookUrl)
                val data = getApiJson("$apiBaseUrl/novels/$slug").obj("data")
                val cover = data?.obj("cover")
                cover?.str("large")?.ifBlank { null }
                    ?: cover?.str("original")?.ifBlank { null }
                    ?: cover?.str("small")?.ifBlank { null }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val slug = bookSlugFromUrl(bookUrl)
                val data = getApiJson("$apiBaseUrl/novels/$slug").obj("data")
                val synopsis = data?.str("synopsis")?.ifBlank { null }
                    ?: return@tryConnect null
                // The synopsis is an HTML fragment; extract its text.
                val fragment = Jsoup.parseBodyFragment(synopsis, baseUrl)
                TextExtractor.get(fragment.body()).ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val slug = bookSlugFromUrl(bookUrl)
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            var page = 1
            var totalPages = 1
            // The API caps the page size at 50; walk every page.
            // The hard cap only guards against a broken meta.
            while (page <= totalPages && page <= 500) {
                val url = "$apiBaseUrl/novels/$slug/chapters".toUrlBuilderSafe()
                    .add("page", page)
                    .add("limit", 50)
                    .toString()
                val json = getApiJson(url)
                totalPages = json.obj("meta")?.int("pages") ?: page
                val items = json.arr("data") ?: break
                if (items.size() == 0) break
                for (item in items) {
                    val chapter = item.asJsonObject
                    val chapterSlug = chapter.str("slug")
                    if (chapterSlug.isBlank()) continue
                    val chapterUrl = "${baseUrl}novels/$slug/capitulos/$chapterSlug"
                    if (!seen.add(chapterUrl)) continue
                    val title = chapter.str("title").ifBlank { chapterSlug }
                    chapters += ChapterResult(title = title, url = chapterUrl)
                }
                page++
            }
            // The API returns chapters ordered by position, oldest
            // first — exactly the order the app expects.
            chapters
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
