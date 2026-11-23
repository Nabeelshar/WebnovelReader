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
 * LabNovel — https://labnovel.ru
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * homepage, catalog page 2, search pages, two book pages and a
 * chapter page, cross-checked against the lightnovel-crawler
 * project's Labnovel source):
 * - A WordPress site; books live at the root: /<book-slug>/ and
 *   chapters at /<book-slug>/<chapter-slug>/.
 * - Catalog and search share one listing, driven by the site's
 *   search form (GET on /): the catalog is paginated with
 *   ?labpagebook=<n> (12 books per page, ~167 pages), search adds
 *   ?labnamebook=<query> and paginates with the same labpagebook
 *   parameter. Items are .main-block-book blocks: title link in
 *   .main-block-book__title a (an <h2> inside), cover in
 *   .main-block-book__img img (src is the real cover). Whether a
 *   next page exists is read from the pager itself: it renders
 *   input/button[name=labpagebook] elements whose values are the
 *   reachable page numbers, plus a "next" button valued page+1.
 *   (WordPress /page/<n>/ URLs just repeat page 1.)
 * - Book page: title in .main-block-book__title h1, cover in
 *   .main-block-book__img img, synopsis in div[class*=_desc]
 *   (.main-block-book__desc — some books only carry a stub there).
 *   The full chapter list is server-rendered in div.chapter-list a
 *   (title in the anchor's title attribute), ordered by post date
 *   NEWEST first, so it is reversed here. Interleaved non-chapter
 *   posts (e.g. holiday letters to readers) and re-posted chapters
 *   keep the site's own publication order after the reversal.
 * - Chapter page: the single <h1> is the chapter title; the story
 *   is div.editblock, a flat run of <p> paragraphs plus an empty
 *   Yandex ad slot (div.js-adas) that is stripped before extraction.
 */
class LabNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "labnovel"
    override val nameStrId = R.string.source_name_labnovel
    override val baseUrl = "https://labnovel.ru/"
    override val catalogUrl = "https://labnovel.ru/"
    override val language = LanguageCode.RUSSIAN

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** Book URLs are single-segment root paths: /<book-slug>/ */
    private fun isBookHref(href: String): Boolean {
        val path = href.removePrefix(baseUrl).removePrefix("https://labnovel.ru")
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size != 1) return false
        val slug = segments.single()
        return !slug.startsWith("lab-") && !slug.startsWith("wp-") &&
            !slug.contains('.')
    }

    /**
     * Catalog and search results share one item markup: a
     * .main-block-book whose .main-block-book__title anchor links to
     * the book page and whose .main-block-book__img img is the cover.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select(".main-block-book")) {
            val link = item.selectFirst(".main-block-book__title a[href]")
                ?: continue
            val href = link.attr("href")
            if (!isBookHref(href)) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val cover = item.selectFirst(".main-block-book__img img")
                ?.attr("src") ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    /**
     * The pager renders one input/button[name=labpagebook] per
     * reachable page (plus a "next" button valued current+1), so a
     * next page exists iff some pager element carries its number.
     */
    private fun hasPage(doc: Document, page: Int): Boolean =
        doc.select("input[name=labpagebook], button[name=labpagebook]")
            .toList()
            .any { it.attr("value") == page.toString() }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // Chapter pages carry exactly one <h1>: the chapter title.
            doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("div.editblock")
                ?: return@withContext ""
            // Work on a clone so the passed Document is never
            // mutated (getChapterTitle runs on it afterwards).
            val body = container.clone()
            body.select("script, iframe, ins, .js-adas").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".main-block-book__img img")
                img?.let { resolve(it.attr("src")) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("div[class*=_desc]")
                    ?: doc.selectFirst(".main-block-book__desc")
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
            for (anchor in doc.select("div.chapter-list a[href]")) {
                val url = resolve(anchor.attr("href"))
                if (!seen.add(url)) continue
                val title = anchor.attr("title").ifBlank {
                    anchor.text().trim()
                }
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
                val url = if (page == 1) catalogUrl
                else baseUrl.toUrlBuilderSafe()
                    .add("labpagebook", page)
                    .toString()

                val doc = networkClient.get(url).toDocument()
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
                    .add("labnamebook", input)
                if (page > 1) builder.add("labpagebook", page)
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
