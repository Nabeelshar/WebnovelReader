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
import java.net.URI

/**
 * IFreedom — https://ifreedom.su
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * catalog, search, book and chapter pages). The lightnovel-crawler
 * reference targets the site's old WordPress markup
 * (h1.entry-title, .menu-ranobe, div.entry-content) — all of it is
 * gone after a redesign; everything below is the current markup.
 * - Catalog: /vse-knigi/, paginated as ?bpage=<n>, 20 books per
 *   page. Items are .item-book-slide, but ONLY the ones inside
 *   .booksearch are real results: every page also carries an
 *   <aside> slider with the same item markup (22 popular books,
 *   identical on all pages), which must not leak in. Item: book
 *   link a.link-book-slide (/ranobe/<slug>/), title in
 *   .block-book-slide-title, cover .block-book-slide-img img (src).
 * - Search: the catalog page's own form — GET /vse-knigi/ with the
 *   query in the "searchname" field, paginated with &bpage=<n>;
 *   results reuse the catalog item markup.
 * - Book page /ranobe/<slug>/: title in the plain <h1>, full-size
 *   cover in .book-page img, description in the first tab
 *   (.book-info .tab-content > .active, the "Описание" tab).
 * - Chapter list: .chapterlinks .chapterinfo a, listed NEWEST
 *   first, all on the book page — reversed here. Chapter URLs live
 *   OUTSIDE /ranobe/: /<book-slug>/<chapter-slug>/. Paid chapters
 *   do not link to their chapter at all (they link to
 *   /koshelek/?id=... instead), so filtering by the chapter prefix
 *   drops them; they cannot be read without buying them anyway.
 * - Chapter page: title in <h1>, story in .chapter-content. The
 *   container interleaves <script> tags and empty Yandex ad
 *   placeholders (.pc-adv, div[id^=yandex_rtb]) — removed on a
 *   clone before extracting, so the document keeps its <h1> for
 *   getChapterTitle (which the app calls after getChapterText).
 */
class IFreedom(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "ifreedom"
    override val nameStrId = R.string.source_name_ifreedom
    override val baseUrl = "https://ifreedom.su/"
    override val catalogUrl = "https://ifreedom.su/vse-knigi/"
    override val iconUrl = "https://ifreedom.su/favicon.ico"
    override val language = LanguageCode.RUSSIAN

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup. Scoped to
     * .booksearch: the pages' <aside> popular-books slider uses the
     * same .item-book-slide markup and repeats identically on every
     * page (it produced a fake 22-book overlap between pages when
     * left unscoped during verification).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val scoped = doc.select(".booksearch .item-book-slide")
        val items = if (scoped.isNotEmpty()) scoped
        else doc.select(".item-book-slide")
        return items.toList().mapNotNull { item ->
            val link = item.selectFirst("a.link-book-slide")
                ?: item.selectFirst("a[href]") ?: return@mapNotNull null
            val title = item.selectFirst(".block-book-slide-title")
                ?.text()?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val img = item.selectFirst(".block-book-slide-img img")
                ?: item.selectFirst("img")
            val cover = img?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }.orEmpty()
            BookResult(
                title = title,
                url = resolve(link.attr("href")),
                coverImageUrl = resolve(cover)
            )
        }
    }

    /** True when the page's pager links to [page] via ?bpage=. */
    private fun hasPage(doc: Document, page: Int): Boolean =
        doc.select("a[href]").toList().any { link ->
            Regex("[?&]bpage=(\\d+)").find(link.attr("href"))
                ?.groupValues?.get(1)?.toIntOrNull() == page
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content = doc.selectFirst(".chapter-content")
                ?: return@withContext ""
            // Clean a clone: the app calls getChapterTitle on this
            // same document afterwards. Scripts are essential to
            // strip — TextExtractor would otherwise emit their code
            // as chapter text.
            val clone = content.clone()
            clone.select("script, iframe, style, .pc-adv, div[id^=yandex_rtb]")
                .remove()
            TextExtractor.get(clone)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".book-page img")
                img?.let {
                    resolve(it.attr("src").ifBlank { it.attr("data-src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".book-info .tab-content > .active")
                    ?: doc.selectFirst(".book-info .tab-content > div")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            // Chapters live at /<book-slug>/<chapter-slug>/ while
            // the book page is /ranobe/<book-slug>/.
            val slug = bookUrl.trimEnd('/').substringAfterLast('/')
            val chapterPrefix = baseUrl + slug + "/"
            val scoped = doc.select(".chapterlinks .chapterinfo a[href]")
            val candidates = if (scoped.isNotEmpty()) scoped
            else doc.select(".chapterinfo a[href]")
            candidates
                .toList()
                .map {
                    ChapterResult(
                        title = it.text().trim(),
                        url = resolve(it.attr("href"))
                    )
                }
                // Paid chapters link to /koshelek/?id=... instead of
                // the chapter URL; the prefix filter drops them.
                .filter { it.url.startsWith(chapterPrefix) }
                .distinctBy { it.url }
                // The site lists chapters newest first.
                .reversed()
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val builder = baseUrl.toUrlBuilderSafe().addPath("vse-knigi")
                if (page > 1) builder.add("bpage", page)
                val doc = networkClient.get(builder.toString()).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasPage(doc, page + 1)
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
                    .addPath("vse-knigi")
                    .add("searchname", input)
                if (page > 1) builder.add("bpage", page)
                val doc = networkClient.get(builder.toString()).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasPage(doc, page + 1)
                )
            }
        }
}
