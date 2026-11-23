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
 * Novel de Glace — https://noveldeglace.com
 *
 * French fan-translation site (WordPress). Site structure verified
 * live 2026-11-23 with curl, cross-checked against the
 * lightnovel-crawler project's NovelDeGlace source (whose book-page
 * selectors are partly outdated — the chapter list no longer lives
 * in div.su-tabs-panes rows):
 * - Catalog: /roman/ lists every project (71 books) as
 *   article.roman on a single page — /roman/page/2/ returns the
 *   identical listing, so there is no catalog pagination. Title in
 *   h2.entry-title a (book URL /roman/<slug>), cover in
 *   .image-project img.
 * - Search: the WordPress search restricted to the book post type,
 *   /?s=<query>&post_type=roman (the unrestricted search is swamped
 *   by chapter posts). Results reuse the article.roman markup and
 *   paginate as /page/<n>/?s=<query>&post_type=roman, 10 per page.
 * - Book page /roman/<slug>: cover in
 *   .project-large .su-column-size-1-4 img (the volume 1 cover),
 *   synopsis in the .su-tabs-pane[data-title=Synopsis] tab, and the
 *   full table of contents in the "Tomes" tab as li.chpt a links to
 *   /chapitre/<slug> — in document order, which is the reading
 *   order (prologue first, newest chapter last; interludes and
 *   epilogues sit right after the chapter they follow). The lone
 *   /chapitre/ link outside li.chpt is the "latest chapter" header
 *   link and is a duplicate of the last TOC entry.
 * - Chapter page: the story is div.content-tome (inside
 *   div.entry-content-chapitre, next to the .mistape_caption
 *   typo-report notice, which is not part of the text). The chapter
 *   title is the <h2> inside .content-tome and matches the TOC
 *   title; the page <h1> is "<book> – Tome N – Chapitre M".
 */
class NovelDeGlace(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "noveldeglace"
    override val nameStrId = R.string.source_name_noveldeglace
    override val baseUrl = "https://noveldeglace.com/"
    override val catalogUrl = "https://noveldeglace.com/roman/"
    override val iconUrl = "https://noveldeglace.com/favicon.ico"
    override val language = LanguageCode.FRENCH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** The site pads titles with non-breaking spaces. */
    private fun String.cleanText(): String =
        replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * Catalog and book-search results share one item markup: an
     * article.roman whose h2.entry-title a is the book link and whose
     * .image-project img is the cover.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (article in doc.select("article.roman")) {
            val link = article.selectFirst("h2.entry-title a[href]")
                ?: article.selectFirst("a[rel=bookmark][href]")
                ?: continue
            val url = resolve(link.attr("href"))
            if (!url.contains("/roman/") || !seen.add(url)) continue
            val title = link.text().cleanText()
            if (title.isBlank()) continue
            val img = article.selectFirst(".image-project img")
                ?: article.selectFirst("img[src*=\"img.noveldeglace.com\"]")
                ?: article.selectFirst("img")
            val cover = img?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            } ?: ""
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
            // The chapter title is the <h2> inside the story block
            // (identical to the table-of-contents title). It survives
            // getChapterText because that only edits a clone.
            doc.selectFirst(".content-tome h2")?.text()?.cleanText()
                ?: doc.selectFirst(".entry-content-chapitre h2")?.text()?.cleanText()
                ?: doc.selectFirst("h1.entry-title")?.text()?.cleanText()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".content-tome")
                ?: doc.selectFirst(".entry-content-chapitre")
                ?: return@withContext ""
            // Clean a clone: the <h2> chapter title is removed from
            // the text, but getChapterTitle still needs it from this
            // same document afterwards. The .mistape_caption notice
            // (a sibling of .content-tome, inside the fallback
            // container) is not story text either.
            val body = container.clone()
            body.select("h2, script, iframe, .mistape_caption").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".project-large .su-column-size-1-4 img")
                    ?: doc.selectFirst(".image-project img")
                val cover = img?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
                cover?.let { resolve(it) }
                    ?: doc.selectFirst("meta[property=og:image]")
                        ?.attr("content")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val pane = doc.selectFirst(".su-tabs-pane[data-title=Synopsis]")
                    ?: doc.selectFirst(".su-tabs-pane")
                pane?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            // The whole table of contents is server-rendered on the
            // book page as li.chpt links, already oldest to newest.
            doc.select("li.chpt a[href]")
                .toList()
                .filter { it.attr("href").contains("/chapitre/") }
                .map {
                    ChapterResult(
                        title = it.text().cleanText(),
                        url = resolve(it.attr("href"))
                    )
                }
                .filter { it.title.isNotBlank() }
                .distinctBy { it.url }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                // The /roman/ archive lists every book on one page
                // (paginated URLs return the same listing).
                if (index > 0) return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                val books = parseBookList(doc)
                PagedList(list = books, index = index, isLastPage = true)
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
                if (page > 1) builder.addPath("page", page.toString())
                val url = builder
                    .add("s", input)
                    .add("post_type", "roman")
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").toList().any {
                    it.attr("href").contains("/page/${page + 1}/")
                }
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNext
                )
            }
        }
}
