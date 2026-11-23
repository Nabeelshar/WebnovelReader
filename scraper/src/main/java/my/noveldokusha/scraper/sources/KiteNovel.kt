package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI

/**
 * KiteNovel — https://kitenovel.com
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * homepage, listing, search, book and chapter pages, plus the
 * site's own JSON API). The site has been rebuilt since the
 * lightnovel-crawler project's KiteNovel source was written: the
 * old /read/ chapter URLs and Bootstrap book-page selectors are
 * gone, and neither BaseMadaraScraper nor BaseNovelFullScraper
 * matches the current markup.
 * - Catalog: /novel-detail ("Latest Novel"), 20 books per page,
 *   paginated /novel-detail/page/<n>. Items are div.card blocks:
 *   the book anchor points to /novel-detail/<slug>, the title is
 *   the card's <h4> (text and title attribute), the cover is the
 *   card's first <img> (src under /nov/images/...).
 * - Search: the header form is GET /novel-detail?search=<query>;
 *   results reuse the catalog card markup and paginate as
 *   /novel-detail/page/<n>?search=<query> (the pager's own links
 *   drop the query, so the URLs are built here instead). The end
 *   of a listing is where the pager stops linking to page <n+1>.
 * - Book page /novel-detail/<slug>: title in <h1>, cover in
 *   img.cover-img, synopsis in section.summary-section (minus its
 *   <h2> heading). The page renders NO chapter links: the list is
 *   loaded by the page's JS from a JSON API.
 * - Chapter list: POST https://kitenovel.com/api/chaptersListKitenovel
 *   with a JSON body {"post_id": "<book slug>", "orderBy": "desc"}
 *   and the X-API-TOKEN header whose value is the public token
 *   embedded in every book page's inline script (no CSRF token or
 *   cookies are needed — verified live). The response is
 *   {"result": [{"href", "title", "release_date"}, ...]} with the
 *   FULL list, always newest first (the orderBy field is ignored
 *   by the server), so it is reversed here to oldest → newest.
 *   Chapter URLs are <book-url>/<href>.
 * - Chapter page /novel-detail/<slug>/<chapter-slug>: the chapter
 *   title is the <h1>; the story is div.chapter-content, a clean
 *   run of <p> paragraphs whose only junk is an <h3> repeating the
 *   chapter title, removed on a clone.
 */
class KiteNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "kitenovel"
    override val nameStrId = R.string.source_name_kitenovel
    override val baseUrl = "https://kitenovel.com/"
    override val catalogUrl = "https://kitenovel.com/novel-detail"
    override val language = LanguageCode.ENGLISH

    private val chapterListApiUrl = "https://kitenovel.com/api/chaptersListKitenovel"

    // Public API token embedded in the inline script of every book
    // page (the site's own frontend sends it as X-API-TOKEN).
    private val apiToken = "9971149585"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * A book href is /novel-detail/<slug> with exactly one path
     * segment after "novel-detail" (chapter URLs have two, pager
     * URLs start with the "page" segment).
     */
    private fun bookSlugFromHref(href: String): String? {
        val path = runCatching { URI(href).path }.getOrNull() ?: href
        val segments = path.split("/").filter { it.isNotEmpty() }
        if (segments.size != 2 || segments[0] != "novel-detail") return null
        if (segments[1] == "page") return null
        return segments[1]
    }

    /**
     * Catalog and search results share one card markup: a div.card
     * holding several anchors to the book page, an <h4> title and
     * the cover <img>.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in doc.select("div.card")) {
            val link = card.select("a[href]").toList()
                .firstOrNull { bookSlugFromHref(it.attr("href")) != null }
                ?: continue
            val url = resolve(link.attr("href"))
            if (!seen.add(url)) continue
            val heading = card.selectFirst("h4")
            val title = heading?.attr("title")?.trim()?.ifBlank { null }
                ?: heading?.text()?.trim()
                ?: link.text().trim()
            if (title.isBlank()) continue
            val cover = card.selectFirst("img")?.attr("src") ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[href]").toList().any {
            it.attr("href").contains("/novel-detail/page/${page + 1}")
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // The chapter page <h1> is the chapter title itself
            // (the book title only appears in the <title> tag).
            doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: doc.title().substringBefore(" | ").trim().ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".chapter-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards. The content's <h3> repeats
            // the chapter title inside the story text.
            val body = container.clone()
            body.select("h3, script, iframe").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst("img.cover-img")
                    ?: doc.selectFirst("img[src*=/nov/images/]")
                img?.let { resolve(it.attr("src")) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val section = doc.selectFirst("section.summary-section")
                if (section != null) {
                    val clone = section.clone()
                    clone.select("h2").remove()
                    TextExtractor.get(clone).ifBlank { null }
                } else {
                    doc.selectFirst("meta[name=description]")
                        ?.attr("content")
                        ?.ifBlank { null }
                }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val cleanBookUrl = bookUrl.trimEnd('/')
            val slug = cleanBookUrl.substringAfterLast('/')
            val payload = JsonObject().apply {
                addProperty("post_id", slug)
                addProperty("orderBy", "desc")
            }.toString().toRequestBody(jsonMediaType)
            val headers = Headers.Builder()
                .add("X-API-TOKEN", apiToken)
                .add("Accept", "application/json")
                .build()
            val request = postRequest(
                chapterListApiUrl,
                headers = headers,
                body = payload
            )
            val json = networkClient.call(request).toJson().asJsonObject
            val result = json.getAsJsonArray("result")
                ?: return@tryConnect emptyList()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            for (item in result) {
                val obj = item.asJsonObject
                val href = obj.get("href")?.takeIf { !it.isJsonNull }
                    ?.asString?.trim().orEmpty()
                if (href.isBlank()) continue
                val url = "$cleanBookUrl/$href"
                if (!seen.add(url)) continue
                val title = obj.get("title")?.takeIf { !it.isJsonNull }
                    ?.asString?.trim().orEmpty()
                chapters += ChapterResult(
                    title = title.ifBlank { href },
                    url = url
                )
            }
            // The API always returns the full list newest first;
            // the app expects chapters oldest to newest.
            chapters.reverse()
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl/page/$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNextPage(doc, page)
                )
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                if (input.isBlank())
                    return@tryConnect PagedList.createEmpty(index = index)
                val page = index + 1
                val builder = baseUrl.toUrlBuilderSafe()
                    .addPath("novel-detail")
                if (page > 1) builder.addPath("page", page.toString())
                val url = builder.add("search", input).toString()

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNextPage(doc, page)
                )
            }
        }
}
