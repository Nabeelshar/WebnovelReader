package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Chireads — https://chireads.com
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * catalog, search, book and chapter pages, cross-checked against the
 * lightnovel-crawler project's Chireads source; the site has since
 * been redesigned around a "refresh" theme):
 * - WordPress site. Books live under /category/translatedtales/<slug>/
 *   (translated novels, used as the catalog) and /category/original/.
 *   The catalog paginates as /category/translatedtales/page/<n>/,
 *   30 books per page. Each book is an <article> card: title link in
 *   .news-list-tit a (its text is the site's "FR | EN | original"
 *   display title), cover in the card's <img> src.
 * - Search is the header form: GET /search?name=<query>. Results
 *   reuse the catalog's <article> cards and are not paginated.
 * - Book page: title in h1.refresh-detail-title, cover in
 *   .refresh-detail-cover img, synopsis in
 *   .refresh-detail-summary-content. Chapters are grouped in
 *   div.refresh-detail-segment blocks ("Chapitres 1 à 100", ...),
 *   each holding a ul.refresh-detail-chapter-list whose links are
 *   already ordered oldest to newest; concatenating the segments in
 *   page order gives the full list (verified: 1,597 chapters for
 *   Battle Through the Heavens, 979 for Martial God Asura). A
 *   segment labelled "Annexe" holds announcement posts (trailers,
 *   soundtracks), not chapters, and is skipped.
 * - Chapter page: the chapter title is h1.article-title and the
 *   story text is div#content.article-font — plain <p> paragraphs
 *   with no ads or scripts inside (verified on chapter 1).
 */
class Chireads(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "chireads"
    override val nameStrId = R.string.source_name_chireads
    override val baseUrl = "https://chireads.com/"
    override val catalogUrl = "https://chireads.com/category/translatedtales/"
    override val iconUrl = "https://chireads.com/favicon.ico"
    override val language = LanguageCode.FRENCH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup: an
     * <article> card whose .news-list-tit anchor links to the book
     * page (/category/.../<slug>/) and whose <img> is the cover.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("article")) {
            val link = item.selectFirst(".news-list-tit a[href]")
                ?: item.selectFirst("a[href]")
                ?: continue
            val href = link.attr("href")
            if (!href.contains("/category/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
                .ifBlank { link.attr("title").trim() }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val cover = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")
                ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.article-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("#content.article-font")
                ?: doc.selectFirst("#content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards, so nothing in the original
            // may be removed. (The title <h1> lives outside #content,
            // but keep the invariant anyway.)
            val body = container.clone()
            body.select("script, iframe, ins, style").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".refresh-detail-cover img")
                    ?: doc.selectFirst("meta[property=og:image]")
                img?.let {
                    val raw = if (it.tagName() == "meta") it.attr("content")
                    else it.attr("data-src").ifBlank { it.attr("src") }
                    resolve(raw)
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".refresh-detail-summary-content")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()

            fun collectFrom(root: Element) {
                for (anchor in root.select("ul.refresh-detail-chapter-list a[href]")) {
                    val url = resolve(anchor.attr("href"))
                    if (!seen.add(url)) continue
                    val title = anchor.text().trim()
                        .replace('\u00A0', ' ')
                        .ifBlank { anchor.attr("title").trim() }
                    if (title.isBlank()) continue
                    chapters += ChapterResult(title = title, url = url)
                }
            }

            // Chapters are grouped in labelled segments in reading
            // order; the "Annexe" segment holds announcement posts,
            // not chapters, so it is skipped.
            val segments = doc.select("div.refresh-detail-segment")
            if (segments.isNotEmpty()) {
                for (segment in segments) {
                    val label = segment
                        .selectFirst(".refresh-detail-segment-toggle span")
                        ?.text()?.trim().orEmpty()
                    if (label.equals("Annexe", ignoreCase = true)) continue
                    collectFrom(segment)
                }
            } else {
                collectFrom(doc)
            }
            // Segments are already ordered oldest to newest.
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}page/$page/"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").toList().any {
                    it.attr("href").endsWith("/category/translatedtales/page/${page + 1}/")
                }

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNext
                )
            }
        }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                if (input.isBlank() || index > 0)
                    return@tryConnect PagedList.createEmpty(index = index)
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .add("name", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
