package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
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
import java.net.URI

/**
 * Litnet — https://litnet.com (Russian)
 *
 * Site structure verified live 2026-11-23. The site now has two
 * stacks: the book/catalog pages are an Angular app (server-side
 * rendered, so the HTML carries the data), while the reader is still
 * the legacy stack the lightnovel-crawler Litnet source was written
 * against:
 * - Catalog: /ru/top/all, paginated as /ru/top/all?page=<n>,
 *   20 books per page. Items are .book-card blocks: title link in
 *   .book-card__title a, cover in .book-card__cover img,
 *   annotation in .book-card__description.
 * - Search: GET /ru/search?q=<query> renders the same .book-card
 *   markup, but the server only ever renders the first page of
 *   results (a page parameter is ignored), so search is a single
 *   page of 20.
 * - Book page /ru/book/<alias>: title in <h1>, cover in the
 *   og:image meta, description in .view-book-page-annotation.
 * - Chapter list: the reader page /ru/reader/<alias> (the book URL
 *   with /book/ replaced by /reader/) carries a
 *   <select name="chapter"> whose <option value="<chapterId>">
 *   entries are the full chapter list in reading order.
 *   Chapter URLs are <reader-url>?c=<chapterId>.
 * - Chapter body: POST https://litnet.com/reader/get-page with
 *   form fields chapterId + page returns JSON
 *   {status, chapterTitle, data, totalPages}; data is the chapter
 *   HTML for that page and the pages are concatenated. Verified to
 *   work fully anonymously — no cookies or CSRF token needed,
 *   despite the reader page carrying a csrf meta for its own JS.
 *   Paid chapters simply return no usable data.
 *
 * [getChapterText] / [getChapterTitle] ignore the fetched reader
 * page and re-request the body from the get-page endpoint using the
 * chapter id in the document location (the NovelArchive pattern),
 * so neither mutates the shared document.
 */
class Litnet(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "litnet"
    override val nameStrId = R.string.source_name_litnet
    override val baseUrl = "https://litnet.com/"
    override val catalogUrl = "https://litnet.com/ru/top/all"
    override val iconUrl = "https://litnet.com/favicon.ico"
    override val language = LanguageCode.RUSSIAN

    private val getPageUrl = "https://litnet.com/reader/get-page"

    private val xhrHeaders: Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", baseUrl)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun parseBookList(doc: Document): List<BookResult> =
        doc.select(".book-card").toList().mapNotNull { card ->
            val titleLink = card.selectFirst(".book-card__title a")
                ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = resolve(titleLink.attr("href"))
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val cover = card.selectFirst(".book-card__cover img")
                ?.attr("src").orEmpty()
            val description = card.selectFirst(".book-card__description")
                ?.let { TextExtractor.get(it) }.orEmpty()
            BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover),
                description = description
            )
        }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl?page=$page"
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                // The search page only ever renders its first page
                // of results server-side.
                if (input.isBlank() || index > 0)
                    return@tryConnect PagedList.createEmpty(index = index)
                val url = "https://litnet.com/ru/search".toUrlBuilderSafe()
                    .add("q", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("img[src*=covers]")?.attr("src")
                        ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst(".view-book-page-annotation")
                    ?.let { TextExtractor.get(it) }
                    ?.takeIf { it.isNotBlank() }
            }
        }

    private fun readerUrlFor(bookUrl: String): String =
        bookUrl.substringBefore("?").replace("/book/", "/reader/")

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val readerUrl = readerUrlFor(bookUrl)
            val doc = networkClient.get(readerUrl).toDocument()
            doc.select("select[name=chapter] option").toList()
                .filter { it.attr("value").isNotBlank() }
                .map { option ->
                    ChapterResult(
                        title = option.text().trim(),
                        url = "$readerUrl?c=${option.attr("value")}"
                    )
                }
        }
    }

    private fun chapterIdFromUrl(url: String): String? =
        Regex("[?&]c=(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun getChapterPageJson(
        chapterId: String,
        page: Int
    ): JsonObject? {
        val request = postRequest(getPageUrl, headers = xhrHeaders)
            .postPayload {
                add("chapterId", chapterId)
                add("page", page.toString())
            }
        return runCatching {
            networkClient.call(request).toJson().asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.int(key: String, default: Int): Int =
        if (has(key) && !get(key).isJsonNull) get(key).asInt else default

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val chapterId = chapterIdFromUrl(doc.location())
                ?: return@withContext null
            val json = getChapterPageJson(chapterId, page = 1)
                ?: return@withContext null
            if (json.int("status", 0) != 1) return@withContext null
            json.str("chapterTitle").takeIf { it.isNotBlank() }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val chapterId = chapterIdFromUrl(doc.location())
                ?: return@withContext ""
            val firstPage = getChapterPageJson(chapterId, page = 1)
                ?: return@withContext ""
            if (firstPage.int("status", 0) != 1) return@withContext ""
            val totalPages = maxOf(firstPage.int("totalPages", 1), 1)
            val html = StringBuilder(firstPage.str("data"))
            for (page in 2..totalPages) {
                val json = getChapterPageJson(chapterId, page) ?: continue
                if (json.int("status", 0) == 1) html.append(json.str("data"))
            }
            if (html.isBlank()) return@withContext ""
            val fragment = Jsoup.parseBodyFragment(html.toString(), baseUrl)
            TextExtractor.get(fragment.body())
        }
}
