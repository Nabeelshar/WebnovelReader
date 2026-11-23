package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.FormBody
import okhttp3.Headers
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Jaomix — https://jaomix.ru (Russian ranobe translations, WordPress)
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * search, book and chapter pages plus the site's own AJAX endpoint,
 * cross-checked against the lightnovel-crawler project's Jaomix
 * source):
 * - Catalog: the homepage book grid paginates through the filter
 *   form's "gpage" parameter (/?gpage=<n>), 18 books per page,
 *   hundreds of pages. Items are .block-home .one blocks: the title
 *   link wraps .title-home, the cover is .img-home img. The last
 *   page number is the largest input value inside .pagi-home.
 * - Search: the same grid filtered by the form's title field,
 *   /?searchrn=<query>, paginated by gpage exactly like the catalog.
 * - Book page /<slug>/: title in .desc-book h1, cover in
 *   div.img-book img, synopsis in div#desc-tab.
 * - Chapter list: the book page's table of contents (.block-toc-out
 *   .flex-dow-txt a) carries only the newest 50 chapters. Older
 *   blocks load through the site's own pagination: a .sel-toc
 *   <select> whose option count is the number of TOC pages, each
 *   fetched by POSTing action=loadpagenavchapstt&page=<n> to
 *   /wp-admin/admin-ajax.php with the book page as Referer (the
 *   handler identifies the book from the Referer). Pages run newest
 *   to oldest, so the merged list is reversed for the app.
 * - Chapter page: the title is h1.entry-title (identical to the
 *   chapter-list title); the story is the <p> children of
 *   .entry-content — the .entry wrapper the old crawler used is
 *   gone. The site injects short self-promo paragraphs ("…на
 *   сайте…", "…dot com…") into the text; those are filtered out.
 */
class Jaomix(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "jaomix"
    override val nameStrId = R.string.source_name_jaomix
    override val baseUrl = "https://jaomix.ru/"
    override val catalogUrl = "https://jaomix.ru/"
    override val language = LanguageCode.RUSSIAN

    private val ajaxUrl = "https://jaomix.ru/wp-admin/admin-ajax.php"

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup: a
     * .block-home .one block whose .img-home anchor links to
     * /<book-slug>/ and holds the cover, with the title text in
     * .title-home (also the anchor's title attribute).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select(".block-home .one")) {
            val link = item.selectFirst(".img-home a[href]")
                ?: item.selectFirst("a[href]")
                ?: continue
            val url = resolve(link.attr("href"))
            if (url.isBlank() || !seen.add(url)) continue
            val title = item.selectFirst(".title-home")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) continue
            val cover = item.selectFirst(".img-home img")?.attr("src") ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    /**
     * The grid's pagination block (.pagi-home) carries one submit
     * input per reachable page number; the largest value is the
     * last page. No block means a single page.
     */
    private fun lastGridPage(doc: Document): Int =
        doc.select(".pagi-home .pagiinput input")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull() ?: 1

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".entry-content")
                ?: return@withContext ""
            // Work on a clone so the original Document stays intact
            // (getChapterTitle is called on it afterwards).
            val body = container.clone()
            body.select("script, iframe").remove()
            // The site injects short self-promo paragraphs into the
            // story text ("Следите за новыми главами на сайте dot
            // com.", "Читайте дальше на сайте ."). They are plain
            // <p> elements, identifiable only by their text; real
            // prose in these novels never mentions a website.
            body.select("p").toList()
                .filter {
                    val text = it.text()
                    text.contains("на сайте") || text.contains("dot com")
                }
                .forEach { it.remove() }
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst("div.img-book img")
                img?.let { resolve(it.attr("src")) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("div#desc-tab")
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
                val scoped = root.select(".block-toc-out .flex-dow-txt a[href]")
                val links = if (scoped.isNotEmpty()) scoped
                else root.select(".flex-dow-txt a[href]")
                for (anchor in links) {
                    val url = resolve(anchor.attr("href"))
                    if (url.isBlank() || !seen.add(url)) continue
                    val title = anchor.text().trim()
                    if (title.isBlank()) continue
                    chapters += ChapterResult(title = title, url = url)
                }
            }

            collectFrom(doc)

            // The book page shows only the newest 50 chapters; the
            // remaining TOC pages come from the site's own AJAX
            // pagination (its page count is the .sel-toc option
            // count). The handler identifies the book by Referer.
            val tocPages = doc.select(".sel-toc option").size
            for (page in 2..tocPages) {
                val fragment = try {
                    val body = FormBody.Builder()
                        .add("action", "loadpagenavchapstt")
                        .add("page", page.toString())
                        .build()
                    val headers = Headers.Builder()
                        .add("Referer", bookUrl)
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build()
                    networkClient.call(
                        postRequest(ajaxUrl, headers = headers, body = body)
                    ).toDocument()
                } catch (e: Exception) {
                    null
                } ?: continue
                collectFrom(fragment)
            }

            // Pages run newest to oldest; the app expects the list
            // ordered from the first chapter to the newest one.
            chapters.reverse()
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = baseUrl.toUrlBuilderSafe()
                    .add("gpage", page)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || page >= lastGridPage(doc)
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
                val url = baseUrl.toUrlBuilderSafe()
                    .add("searchrn", input)
                    .add("gpage", page)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || page >= lastGridPage(doc)
                )
            }
        }
}
