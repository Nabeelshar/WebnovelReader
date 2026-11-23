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
 * NovelCool — https://www.novelcool.com
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * homepage, listing, search, book and chapter pages, cross-checked
 * against the lightnovel-crawler project's NovelCool source):
 * - Catalog: /category/latest_<n>.html, 40 items per page, with a
 *   server-rendered pager linking onwards (the bare
 *   /category/latest.html variant carries 80 items but no pager, so
 *   the _<n> form is used for every page including the first).
 *   Pages past the end redirect to the homepage, so the end is
 *   detected from the pager's link to the next page instead. Items
 *   are .category-book-list .book-item: title link in .book-info a
 *   (title text in .book-name), cover in .book-pic img — the real
 *   cover URL is the img's lazy_url attribute; its src is a default
 *   placeholder. The site mixes novels and manga in one list.
 * - Search: the header form is GET /search with the query in the
 *   "name" field (a /search/?wd=<q> variant also works). Results
 *   reuse the catalog's .book-item markup in .category-book-list.
 * - Book page /novel/<Slug>.html: title in h1.bookinfo-title, cover
 *   in div.bookinfo-pic img (src is the real URL here), synopsis in
 *   .bk-summary-txt (some books genuinely carry "N/A" on the site).
 * - Chapter list: .chapter-item-list a, title in .chapter-item-title
 *   inside each anchor, listed NEWEST first — reversed here so the
 *   app gets chapters oldest to newest.
 * - Chapter page: the chapter title is h2.chapter-title; the story
 *   is the parent element of p.chapter-start-mark (a
 *   div.overflow-hidden full of <p> paragraphs), minus the title,
 *   the start/end marks, the report block, share block and ad boxes
 *   interleaved in the text.
 */
class NovelCool(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelcool"
    override val nameStrId = R.string.source_name_novelcool
    override val baseUrl = "https://www.novelcool.com/"
    override val catalogUrl = "https://www.novelcool.com/category/latest_1.html"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup: a
     * .book-item whose .book-info anchor links to /novel/<slug>.html
     * and whose .book-pic img carries the real cover in lazy_url
     * (src is a placeholder image).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val scoped = doc.select(".category-book-list .book-item")
        val items = if (scoped.isNotEmpty()) scoped else doc.select(".book-item")
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val link = item.selectFirst(".book-info a[href]")
                ?: item.selectFirst(".book-pic a[href]")
                ?: continue
            val href = link.attr("href")
            if (!href.contains("/novel/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = item.selectFirst(".book-name")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) continue
            val img = item.selectFirst(".book-pic img") ?: item.selectFirst("img")
            val cover = img?.attr("lazy_url")?.ifBlank { null }
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
            doc.selectFirst(".chapter-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".chapter-start-mark")?.parent()
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards, so the title element must
            // survive in the original.
            val body = container.clone()
            body.select(
                ".chapter-title, .chapter-start-mark, .chapter-end-mark"
            ).remove()
            body.select("div[model_target_name=report]").remove()
            body.select(
                ".chapter-section-report, .bookinfo-share, .adsbox, " +
                    "script, iframe"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst("div.bookinfo-pic img")
                img?.let {
                    resolve(it.attr("src").ifBlank { it.attr("lazy_url") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".bk-summary-txt")
                    ?: doc.selectFirst(".bookinfo-summary")
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
            for (anchor in doc.select(".chapter-item-list a[href]")) {
                val url = resolve(anchor.attr("href"))
                if (!seen.add(url)) continue
                val title = anchor.selectFirst(".chapter-item-title")?.text()?.trim()
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
                val page = index + 1
                val url = "https://www.novelcool.com/category/latest_$page.html"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                // Pages past the end redirect to the homepage, so
                // the end is where the pager stops linking onwards.
                val hasNext = doc.select("a[href]").any {
                    it.attr("href").endsWith("/category/latest_${page + 1}.html")
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
