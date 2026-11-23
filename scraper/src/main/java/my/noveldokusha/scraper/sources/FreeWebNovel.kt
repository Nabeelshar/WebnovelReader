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
 * FreeWebNovel — https://freewebnovel.com
 *
 * Site structure verified live 2026-11-23 (browser inspection of the
 * live pages, cross-checked against the lightnovel-crawler project's
 * FreeWebNovel source):
 * - The old catalog path /completed-novel/ is dead (the site returns
 *   its own 404 page). The completed-novels catalog now lives at
 *   /sort/completed-novel, paginated as /sort/completed-novel/<n>,
 *   20 books per page. Book items are anchored by an <h3> title link
 *   to /novel/<slug>, with the cover <img> (src, /files/article/...)
 *   in the same item container.
 * - Search works as GET /search?keyword=<query> (the header form's
 *   field is named "keyword"); results reuse the catalog item markup.
 *   (The previous implementation POSTed a "searchkey" field and parsed
 *   the results with the catalog's old list selector.)
 * - Book page /novel/<slug>: title in .m-desc h1.tit, cover in
 *   .m-imgtxt img, description in .m-desc .txt.
 * - The chapter list is paginated by AJAX, 40 chapters per page: the
 *   book page carries a window.chapterPagination script (totalPage /
 *   pageSize, mirrored by the #indexselect <select>) and each page
 *   loads from <book-url>?ajax=chapters&page=<n>&pageSize=<s> as JSON
 *   {html}. Reading only the book page HTML yields just the first 40
 *   chapters.
 * - Chapter page: the <h1> is the BOOK title; the chapter title is an
 *   <h4> inside .m-read, and the story text is .m-read .txt. The site
 *   hides a self-promo paragraph with a <style> p:nth-last-child(..)
 *   rule — the elements that style block hides are removed before
 *   extracting the text.
 */
class FreeWebNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "freewebnovel"
    override val nameStrId = R.string.source_name_freewebnovel
    override val baseUrl = "https://freewebnovel.com/"
    override val catalogUrl = "https://freewebnovel.com/sort/completed-novel"
    override val iconUrl = "https://freewebnovel.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun xhrHeaders(referer: String): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun isBookHref(href: String): Boolean =
        href.contains("/novel/") && !href.contains("/chapter-")

    /**
     * Catalog and search results share one item markup: an <h3> title
     * link to /novel/<slug>, with the cover <img> in the same item
     * container. The container's class is not stable, so the cover is
     * found by climbing ancestors while they still contain only this
     * one book's links.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        // Scope to the main list when present: catalog pages also
        // carry a sidebar ranking (.ul-list6) whose <h3> book links
        // would otherwise leak into the results.
        val mainList = doc.selectFirst(".ul-list1")
        val scoped = mainList?.let { parseHeadingLinks(it) }.orEmpty()
        val books = scoped.ifEmpty { parseHeadingLinks(doc) }
        if (books.isNotEmpty()) return books
        // Legacy markup fallback (pre-redesign list).
        return doc.select(".ul-list1 .li-row").mapNotNull { element ->
            val link = element.selectFirst(".tit a") ?: return@mapNotNull null
            val coverUrl = element.selectFirst(".pic img")
                ?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""
            BookResult(
                title = link.text(),
                url = resolve(link.attr("href")),
                coverImageUrl = resolve(coverUrl)
            )
        }
    }

    private fun parseHeadingLinks(root: Element): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (link in root.select("h3 a[href]")) {
            val href = link.attr("href")
            if (!isBookHref(href)) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(findItemCover(link))
            )
        }
        return books
    }

    private fun findItemCover(titleLink: Element): String {
        var node: Element? = titleLink.parent()
        while (node != null) {
            val bookUrls = node.select("a[href]")
                .map { it.attr("href") }
                .filter { isBookHref(it) }
                .toSet()
            if (bookUrls.size > 1) break
            val img = node.selectFirst("img")
            if (img != null) {
                return img.attr("data-src").ifBlank { img.attr("src") }
            }
            node = node.parent()
        }
        return ""
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // The chapter page <h1> is the BOOK title; the chapter
            // title is the <h4> inside the reading block that is not
            // part of the story text (.txt) itself.
            val inReadBlock = doc.select(".m-read h4")
            val candidates = if (inReadBlock.isNotEmpty()) inReadBlock
            else doc.select("h4")
            candidates
                .firstOrNull { it.closest(".txt") == null && it.text().isNotBlank() }
                ?.text()
                ?: doc.selectFirst(".chapter-title")?.text()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val txtEl = doc.selectFirst(".m-read .txt")
                ?: doc.selectFirst(".txt")
                ?: doc.selectFirst(".m-read")
                ?: return@withContext ""
            txtEl.select("script, iframe, .ads").remove()
            // h4/sub inside the story text are promo junk. When txtEl
            // is the .m-read fallback, its <h4> is the chapter title,
            // which getChapterTitle still needs from this same
            // document afterwards — so only strip them inside a real
            // .txt container.
            if (txtEl.hasClass("txt")) {
                txtEl.select("h4").remove()
                txtEl.select("sub").remove()
            }
            removeHiddenPromo(doc, txtEl)
            TextExtractor.get(txtEl)
        }

    /**
     * The site hides a self-promo paragraph in each chapter with a
     * <style> rule built around p:nth-last-child(..), listing the
     * promo elements' selectors alongside it. Remove the elements
     * those sibling selectors point at. The nth-last-child selector
     * itself is skipped: which paragraph it hides varies per chapter,
     * and the app's extractor does not evaluate CSS anyway.
     */
    private fun removeHiddenPromo(doc: Document, txtEl: Element) {
        val style = doc.select("style").firstOrNull {
            it.data().contains("nth-last-child")
        } ?: return
        Regex("([^{}]+)\\{").findAll(style.data()).forEach { match ->
            match.groupValues[1].split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.contains("nth-last-child") && it != "img" }
                .forEach { selector ->
                    try {
                        txtEl.select(selector).remove()
                    } catch (_: Exception) {
                        // Not every CSS selector is valid for jsoup.
                    }
                }
        }
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".m-imgtxt img")
                    ?: doc.selectFirst(".pic img")
                    ?: doc.selectFirst("img[src*=/files/article/image/]")
                img?.let {
                    resolve(it.attr("data-src").ifBlank { it.attr("src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".m-desc .txt")
                    ?: doc.selectFirst("h4.abstract")?.parent()
                        ?.selectFirst(".txt")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val (totalPage, pageSize) = chapterPagination(doc)
            if (totalPage <= 1) {
                return@tryConnect parseChapterLinks(doc)
            }
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            val cleanUrl = bookUrl.substringBefore("?")
            for (page in 1..totalPage) {
                val url = "$cleanUrl?ajax=chapters&page=$page&pageSize=$pageSize"
                val html = try {
                    networkClient.call(
                        getRequest(url, headers = xhrHeaders(bookUrl))
                    ).toJson().asJsonObject.get("html")?.asString.orEmpty()
                } catch (e: Exception) {
                    ""
                }
                if (html.isBlank()) continue
                val fragment = Jsoup.parseBodyFragment(html, baseUrl)
                for (chapter in parseChapterLinks(fragment)) {
                    if (seen.add(chapter.url)) chapters += chapter
                }
            }
            // If every AJAX page failed, fall back to whatever the
            // book page itself carries (its first page of chapters).
            if (chapters.isEmpty()) parseChapterLinks(doc) else chapters
        }
    }

    /**
     * Reads the chapter pagination the site embeds in the book page:
     * a window.chapterPagination script with totalPage / pageSize,
     * mirrored by the #indexselect page <select>. Returns
     * (totalPage, pageSize); defaults to a single page of 40.
     */
    private fun chapterPagination(doc: Document): Pair<Int, Int> {
        val script = doc.select("script").firstOrNull {
            it.data().contains("chapterPagination")
        }?.data()
        if (script != null) {
            val totalPage = Regex("totalPage\\s*:\\s*(\\d+)").find(script)
                ?.groupValues?.get(1)?.toIntOrNull()
            val pageSize = Regex("pageSize\\s*:\\s*(\\d+)").find(script)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (totalPage != null) return totalPage to (pageSize ?: 40)
        }
        val optionCount = doc.select("#indexselect option").size
        return maxOf(optionCount, 1) to 40
    }

    private fun parseChapterLinks(root: Element): List<ChapterResult> {
        // Prefer the chapter-list container when present: the book
        // page also carries a "latest chapters" section whose links
        // must not leak into the list (they would break the order).
        val scoped = root.select("#idData a[href]")
        val candidates = if (scoped.isNotEmpty()) scoped
        else root.select("a[href]")
        return candidates
            .toList()
            .filter { it.attr("href").contains("/chapter-") }
            .map {
                ChapterResult(
                    title = it.attr("title").ifBlank { it.text() },
                    url = resolve(it.attr("href"))
                )
            }
            .distinctBy { it.url }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl/$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").any {
                    it.attr("href").endsWith("/sort/completed-novel/${page + 1}")
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
                    .add("keyword", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
