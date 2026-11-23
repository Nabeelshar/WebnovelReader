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
 * KolNovel — https://kolnovel.com (Arabic, "ملوك الروايات")
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * catalog, search, book and chapter pages). The site has been
 * rebuilt since the lightnovel-crawler source was written: it no
 * longer uses the MangaStream/Madara markup that crawler targets,
 * but a custom WordPress theme with kol-* classes, so neither app
 * base template applies.
 * - Catalog: /series/, paginated as /series/?paged=<n> (which
 *   redirects to /series/page/<n>/), 20 items per page. Items are
 *   article.kol-series-archive__item: the book link is
 *   a.kol-series-archive__cover (its img src is the cover), the
 *   title is the item's h3. Pages past the end return no items;
 *   the pager links to ?paged=<n+1> while more pages exist.
 * - Search: the site header form is the WordPress search,
 *   GET /?s=<query>&post_type=series; results reuse the catalog
 *   item markup.
 * - Book page /series/<slug>/: title in
 *   h1.kol-series-editorial__title, cover in
 *   .kol-series-editorial__cover img, synopsis in
 *   .kol-series-editorial__synopsis .kol-series-editorial__prose
 *   (the element carrying itemprop="description").
 * - Chapter list: the plain book page only renders part of the
 *   list (per-volume groups, newest first). Asking the book page
 *   with ?chapter_volume=__kol_all__&chapter_order=oldest&
 *   chapter_page=<n> renders one page of the full list, oldest
 *   first, 24 chapters per page; the total page count is the
 *   first [data-kol-volume-pages] attribute on the page. Items
 *   are li.kol-series-chapter: link a.kol-series-chapter__link,
 *   clean title in .kol-series-chapter__title-copy (its title
 *   attribute; the sibling .kol-series-chapter__volume span is
 *   junk), chapter number in .kol-series-chapter__number.
 * - Chapter page: the chapter title is
 *   h1.kol-chapter-context__title (outside the body, so text
 *   extraction never endangers it); the story is .entry-content
 *   (div.epcontent.entry-content), a clean run of <p> paragraphs
 *   with no ads/scripts interleaved.
 */
class KolNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "kolnovel"
    override val nameStrId = R.string.source_name_kolnovel
    override val baseUrl = "https://kolnovel.com/"
    override val catalogUrl = "https://kolnovel.com/series/"
    override val language = LanguageCode.ARABIC

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup:
     * article.kol-series-archive__item with the cover anchor
     * a.kol-series-archive__cover linking to /series/<slug>/, the
     * cover as its img src, and the title in the item's h3.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("article.kol-series-archive__item")) {
            val coverLink = item.selectFirst("a.kol-series-archive__cover")
            val titleLink = item.selectFirst("h3 a[href]") ?: coverLink
            val href = coverLink?.attr("href")?.ifBlank { null }
                ?: titleLink?.attr("href")
                ?: continue
            if (!href.contains("/series/") || href.contains("/login")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = item.selectFirst("h3")?.text()?.trim()?.ifBlank { null }
                ?: titleLink?.text()?.trim()?.ifBlank { null }
                ?: item.selectFirst("img")?.attr("alt")?.trim()
                ?: continue
            if (title.isBlank()) continue
            val img = coverLink?.selectFirst("img") ?: item.selectFirst("img")
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
            doc.selectFirst("h1.kol-chapter-context__title")?.text()?.trim()
                ?.ifBlank { null }
                ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".epcontent")
                ?: doc.selectFirst(".entry-content")
                ?: return@withContext ""
            // Work on a clone so the original Document stays intact
            // for getChapterTitle (the title h1 lives outside this
            // container, but stay consistent with the other sources).
            val body = container.clone()
            body.select("script, iframe, ins, form, button").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".kol-series-editorial__cover img")
                val cover = img?.attr("src")?.ifBlank { null }
                    ?: img?.attr("data-src")
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                cover?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".kol-series-editorial__prose")
                    ?: doc.selectFirst("[itemprop=description]")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()

            fun pageUrl(page: Int): String {
                val separator = if (bookUrl.contains("?")) "&" else "?"
                return bookUrl + separator +
                    "chapter_volume=__kol_all__&chapter_order=oldest" +
                    "&chapter_page=$page"
            }

            fun parsePage(doc: Document): Int {
                var added = 0
                for (item in doc.select("li.kol-series-chapter")) {
                    val link = item.selectFirst("a.kol-series-chapter__link")
                        ?: item.selectFirst("a[href]")
                        ?: continue
                    val url = resolve(link.attr("href"))
                    if (url.isBlank() || !seen.add(url)) continue
                    val titleEl = item.selectFirst(".kol-series-chapter__title-copy")
                    val title = titleEl?.attr("title")?.trim()?.ifBlank { null }
                        ?: titleEl?.text()?.trim()?.ifBlank { null }
                        ?: item.selectFirst(".kol-series-chapter__number")
                            ?.text()?.trim()
                        ?: continue
                    if (title.isBlank()) continue
                    chapters += ChapterResult(title = title, url = url)
                    added++
                }
                return added
            }

            val firstDoc = networkClient.get(pageUrl(1)).toDocument()
            parsePage(firstDoc)
            val totalPages = firstDoc.selectFirst("[data-kol-volume-pages]")
                ?.attr("data-kol-volume-pages")?.toIntOrNull()
                ?: 1
            // Pages past the end repeat the last page, so stop as
            // soon as a page contributes no new chapters.
            for (page in 2..totalPages) {
                val doc = networkClient.get(pageUrl(page)).toDocument()
                if (parsePage(doc) == 0) break
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}?paged=$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").any {
                    it.attr("href").endsWith("paged=${page + 1}")
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
                    .add("s", input)
                    .add("post_type", "series")
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
