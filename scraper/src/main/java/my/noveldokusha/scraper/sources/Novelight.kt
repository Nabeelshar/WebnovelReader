package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.getRequest
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
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Novelight — https://novelight.net/
 *
 * Custom (Django) site, not a Madara / NovelFull clone.
 * Verified live 2026-11-13:
 * - Catalog: GET /catalog/?page=N (1-based), items are `a.item`
 *   (title in `.title`, cover `img[src]`, link `/book/<slug>`).
 *   Search is the same page with `?search=<query>`.
 * - Book page: `/book/<slug>`, `<h1>` title, description in
 *   `section.text-info.section`, cover in `.second-information .poster img`.
 * - Chapters are NOT all in the book page HTML: the page only shows the
 *   newest 50. The full list comes from
 *   GET /book/ajax/chapter-pagination?book_id=<id>&page=<n> (XHR header
 *   required, returns JSON `{html: "<a class=\"chapter\" ...>"}`).
 *   Page 1 = newest 50 chapters, last page = oldest.
 * - Chapter text is NOT in the chapter page HTML either: it loads from
 *   GET /book/ajax/read-chapter/<chapter-id> (XHR header required,
 *   returns JSON `{class, content: "<div class=\"chapter-text ...\">"}`).
 *   Locked / premium chapters return an empty content div.
 */
class Novelight(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelight"
    override val nameStrId = R.string.source_name_novelight
    override val baseUrl = "https://novelight.net/"
    override val catalogUrl = "https://novelight.net/catalog/"
    override val iconUrl = "https://novelight.net/static/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun xhrHeaders(referer: String): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()

    private fun resolve(url: String): String =
        if (url.startsWith("http")) url else URI(baseUrl).resolve(url).toString()

    private fun parseCatalogPage(doc: Document, index: Int): PagedList<BookResult> {
        val books = doc.select("a.item").mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: element
            val href = element.attr("href").ifBlank { return@mapNotNull null }
            val title = element.selectFirst(".title")?.text()?.trim()
                ?: link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            val cover = element.selectFirst("img[src]")?.attr("src") ?: ""
            BookResult(
                title = title,
                url = resolve(href),
                coverImageUrl = if (cover.isBlank()) "" else resolve(cover)
            )
        }
        val maxPage = doc.select(".page-pagination a[href*=page=]")
            .mapNotNull { Regex("[?&]page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        return PagedList(
            list = books,
            index = index,
            isLastPage = books.isEmpty() || (index + 1) >= maxPage
        )
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // <title> is "<Book title> - 2 vol. 653 chapter - <Chapter name>"
            // (or "<Book> - 8 chapter - <Chapter name>"): strip the
            // leading "N chapter - " / "V vol. N chapter - " counter,
            // it is not part of the chapter name.
            doc.title()
                .substringAfter(" - ", "")
                .replaceFirst(Regex("""^(\d+\s+vol\.\s+)?\d+\s+chapter\s+-\s+"""), "")
                .ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val chapterId = Regex("/book/chapter/(\\d+)").find(doc.location())
                ?.groupValues?.get(1)
            if (chapterId != null) {
                try {
                    // NOTE: do NOT build this URL with "$baseUrl/book/..."
                    // — baseUrl ends with "/", which produced a double
                    // slash ("//book/ajax/...") and the site answers 404
                    // for it, so every free chapter came back with an
                    // empty body. The builder normalises the path.
                    val chapterUrl = baseUrl.toUrlBuilderSafe()
                        .addPath("book", "ajax", "read-chapter", chapterId)
                        .toString()
                    val json = networkClient.call(
                        getRequest(
                            chapterUrl,
                            headers = xhrHeaders(doc.location())
                        )
                    ).toJson().asJsonObject
                    val contentHtml = json.get("content")?.asString.orEmpty()
                    if (contentHtml.isNotBlank()) {
                        val contentDoc = Jsoup.parseBodyFragment(contentHtml, baseUrl)
                        val text = contentDoc.selectFirst(".chapter-text")
                            ?.let { TextExtractor.get(it) }.orEmpty()
                        if (text.isNotBlank()) return@withContext text
                    }
                } catch (_: Exception) {
                    // Fall through to the preview paragraphs in the page HTML.
                }
            }
            // Locked chapters return an empty AJAX body; the page itself only
            // carries a short preview (if any).
            doc.selectFirst(".chapter-text")?.let { TextExtractor.get(it) } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst(".second-information .poster img[src]")
                    ?.attr("src")?.takeIf { it.isNotBlank() }?.let { resolve(it) }
                    ?: Regex("url\\((/media/book/poster/[^)]+)\\)")
                        .find(doc.selectFirst(".page-panel")?.attr("style").orEmpty())
                        ?.groupValues?.get(1)?.let { resolve(it) }
                    ?: doc.selectFirst(".page-panel")?.attr("style")
                        ?.let { Regex("url\\(([^)]+)\\)").find(it)?.groupValues?.get(1) }
                        ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl).toDocument()
                    .selectFirst("section.text-info.section")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val bookId = Regex("BOOK_ID\\s*=\\s*\"(\\d+)\"").find(doc.html())
                ?.groupValues?.get(1)
            val maxPage = doc.select("#select-pagination-chapter option")
                .mapNotNull { it.attr("value").toIntOrNull() }
                .maxOrNull() ?: 1

            if (bookId == null) {
                return@tryConnect doc.select("#chapters .chapters a.chapter")
                    .map { parseChapterLink(it.attr("href"), extractChapterTitle(it)) }
                    .reversed()
            }

            val chapters = mutableListOf<ChapterResult>()
            for (page in 1..maxPage) {
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("book", "ajax", "chapter-pagination")
                    .add("book_id", bookId)
                    .add("page", page)
                    .toString()
                val html = networkClient.call(
                    getRequest(url, headers = xhrHeaders(bookUrl))
                ).toJson().asJsonObject.get("html")?.asString.orEmpty()
                if (html.isBlank()) break
                Jsoup.parseBodyFragment(html, baseUrl)
                    .select("a.chapter")
                    .mapTo(chapters) {
                        parseChapterLink(
                            it.attr("href"),
                            extractChapterTitle(it)
                        )
                    }
            }
            // Site lists newest first; the app expects oldest first.
            chapters.reversed()
        }
    }

    /**
     * The site renders each chapter link as
     * `<div class="title">13 vol. 7 chapter - <span>Three Zones</span></div>`
     * (or `1 chapter - <span>Chapter 1.1 Safe City Binding</span>` when the
     * book has no volumes). The leading "N chapter - " /
     * "V vol. N chapter - " is a sequential counter, not part of the chapter
     * name, so take the name from the inner span when present, otherwise
     * strip the counter from the full text.
     */
    private fun extractChapterTitle(link: Element): String {
        link.selectFirst(".title span")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return link.selectFirst(".title")?.text()?.trim().orEmpty()
            .replaceFirst(Regex("""^(\d+\s+vol\.\s+)?\d+\s+chapter\s+-\s+"""), "")
    }

    private fun parseChapterLink(href: String, title: String): ChapterResult =
        ChapterResult(title = title.trim(), url = resolve(href))

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val page = index + 1
            val url = baseUrl.toUrlBuilderSafe()
                .addPath("catalog")
                .add("page", page)
                .toString()
            parseCatalogPage(networkClient.get(url).toDocument(), index)
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank()) return@tryConnect PagedList.createEmpty(index = index)
            val page = index + 1
            val url = baseUrl.toUrlBuilderSafe()
                .addPath("catalog")
                .add("search", input)
                .add("page", page)
                .toString()
            parseCatalogPage(networkClient.get(url).toDocument(), index)
        }
    }
}
