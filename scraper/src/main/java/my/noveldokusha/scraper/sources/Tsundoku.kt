package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Tsundoku Traduções — https://tsundoku.com.br
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * novels listing, search, book and chapter pages; lightnovel-crawler
 * has no Tsundoku source, everything was derived from the live site):
 * - WordPress with a MangaReader-style theme. Series live under
 *   /manga/<slug>/ regardless of type; the novels index is /novels/.
 * - Catalog: /novels/ is a SINGLE page (48 series, every item
 *   labelled "Novel"; /novels/page/2/ returns the identical list —
 *   the page segment is ignored). Items are .listupd .bs: the anchor
 *   links to /manga/<slug>/, the title is in .tt (anchor title attr
 *   as fallback), the cover is the item's <img> src.
 * - Search: the header form is WordPress GET /?s=<query>; results
 *   reuse the .listupd .bs markup, 10 per page, paginated as
 *   /page/<n>/?s=<query> with a .pagination pager.
 * - Book page /manga/<slug>/: cover in .thumb img, synopsis in the
 *   [itemprop=description] block (.entry-content-single).
 * - Chapter list: #chapterlist li a, title in .chapternum, listed
 *   NEWEST first (700 chapters for the test book) — reversed here so
 *   the app gets chapters oldest to newest.
 * - Chapter page: title is h1.entry-title (book name + volume +
 *   chapter + chapter name). The story is #readerarea
 *   .div_principal; everything after it inside #readerarea is
 *   credits, a supporters list (ul.listaApoiadores) and site promo,
 *   so the fallback path drops that tail.
 */
class Tsundoku(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "tsundoku"
    override val nameStrId = R.string.source_name_tsundoku
    override val baseUrl = "https://tsundoku.com.br/"
    override val catalogUrl = "https://tsundoku.com.br/novels/"
    override val language = LanguageCode.PORTUGUESE

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup: a .bs card
     * whose anchor links to /manga/<slug>/, with the title in .tt
     * and the cover in the card's <img>.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select(".listupd .bs")) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            if (!href.contains("/manga/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = item.selectFirst(".tt")?.text()?.trim()?.ifBlank { null }
                ?: link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            val cover = img?.attr("src")?.ifBlank { null }
                ?: img?.attr("data-src")
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
            doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val reader = doc.selectFirst("#readerarea")
                ?: return@withContext ""
            // The story lives in .div_principal; everything after it
            // in #readerarea is credits/supporters/promo. Work on a
            // clone so the original document stays intact.
            val main = reader.selectFirst(".div_principal")
            if (main != null) {
                val body = main.clone()
                body.select("script, iframe, ins, style").remove()
                return@withContext TextExtractor.get(body)
            }
            val body = reader.clone()
            body.select(
                ".listaApoiadores, script, iframe, ins, style"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".thumb img")
                img?.let {
                    resolve(it.attr("src").ifBlank { it.attr("data-src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("[itemprop=description]")
                    ?: doc.selectFirst(".infox .desc")
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
            for (anchor in doc.select("#chapterlist li a[href]")) {
                val url = resolve(anchor.attr("href"))
                if (!seen.add(url)) continue
                val title = anchor.selectFirst(".chapternum")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: anchor.text().trim()
                if (title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
            // The site lists chapters newest first; the app expects
            // them oldest to newest.
            chapters.reverse()
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                // The novels index is a single page; the site
                // ignores any /page/<n>/ segment on it.
                if (index > 0)
                    return@tryConnect PagedList.createEmpty(index = index)
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
                val url = if (page == 1) {
                    baseUrl.toUrlBuilderSafe()
                        .add("s", input)
                        .toString()
                } else {
                    baseUrl.toUrlBuilderSafe()
                        .appendPath("page")
                        .appendPath(page.toString())
                        .add("s", input)
                        .toString()
                }
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select(".pagination a[href]").toList().any {
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
