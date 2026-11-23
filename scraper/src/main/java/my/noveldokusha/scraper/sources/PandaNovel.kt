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
import java.net.URI

/**
 * PandaNovel — https://pandanovel.co
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * catalog, search, chapters-list pages, cross-checked against the
 * lightnovel-crawler project's PandaNovelCo / NovelFire sources):
 * - Catalog: /genre-all/sort-new/status-all/all-novel, paginated
 *   with ?page=<n> (24 books per page, 800+ pages; the pager
 *   carries a rel=next link while more pages exist). Items are
 *   .novel-list .novel-item: the anchor links to /book/<slug> and
 *   carries the title in its title attribute, the title text is in
 *   .novel-title, and the cover <img> keeps the real URL in
 *   data-src (src is a 1px placeholder gif).
 * - Search: the /search page's JS calls GET
 *   /ajax/searchLive?inputContent=<q>, which answers JSON {html}
 *   whose fragment reuses the catalog's .novel-item markup. (The
 *   form's old POST /lnsearchlive route is dead — it returns a
 *   Laravel 404.)
 * - Book page: /book/<slug> itself 302-redirects to the same book
 *   on novelfire.net (same platform/backend — both crawlers share
 *   the chapter-list markup and the #content chapter body). The
 *   chapter LIST, however, is served on pandanovel.co itself at
 *   /book/<slug>/chapters: ul.chapter-list li a, 100 chapters per
 *   page (?page=<n>), already OLDEST first, each anchor's title
 *   attribute holding the clean chapter title. Pagination ends
 *   where the rel=next link disappears.
 * - Cover: the chapters page carries the book's cover in its
 *   og:image meta tag (https://pandanovel.co/server-1/<slug>.jpg),
 *   so the cover is read from there.
 * - Description: the synopsis only exists on the novelfire.net
 *   book page (the redirect target of /book/<slug>), in the
 *   NovelPub-family .summary .content block; it is parsed from
 *   the fetched (redirected) book page.
 * - Chapter page: /book/<slug>/chapter-<n> also 302-redirects to
 *   novelfire.net, where the story text is div#content (per both
 *   lncrawl crawlers). Extraction cleans a clone of that
 *   container; novelfire.net blocks this build machine's IP, so
 *   the body parsing follows the crawlers' verified selectors —
 *   the same situation as LibRead/BedNovel, whose chapter pages
 *   redirect to a host curl cannot exercise from here.
 */
class PandaNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "pandanovel"
    override val nameStrId = R.string.source_name_pandanovel
    override val baseUrl = "https://pandanovel.co/"
    override val catalogUrl = "https://pandanovel.co/genre-all/sort-new/status-all/all-novel"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun xhrHeaders(referer: String): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()

    /**
     * Catalog and search results share one item markup:
     * li.novel-item whose anchor links to /book/<slug> (title in
     * the anchor's title attribute and in .novel-title) and whose
     * cover img keeps the real URL in data-src.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select(".novel-list .novel-item")) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = link.attr("href")
            if (!href.contains("/book/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = item.selectFirst(".novel-title")?.text()?.trim()
                ?.ifBlank { null }
                ?: link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) continue
            val img = item.selectFirst("img")
            var cover = img?.attr("data-src").orEmpty()
            if (cover.isBlank()) {
                cover = img?.attr("src").orEmpty()
                if (cover.startsWith("data:")) cover = ""
            }
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
            // The chapter-list titles (from the list anchors' title
            // attributes) are clean and authoritative; the chapter
            // page heading is only a fallback when it exists.
            doc.selectFirst(".chapter-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // The document is the novelfire.net chapter page the
            // pandanovel.co chapter URL redirects to; the story is
            // div#content there.
            val container = doc.selectFirst("#content")
                ?: doc.selectFirst(".chapter-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards, so nothing may be removed
            // from the original.
            val body = container.clone()
            body.select(
                "script, iframe, ins, .adsbox, .ad-container, .ad, " +
                    ".OUTBRAIN, .noveltopads, .chadsticky"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                // The book page itself redirects away, but the
                // chapters page stays on pandanovel.co and carries
                // the cover in its og:image meta tag.
                val chaptersUrl = bookUrl.trimEnd('/') + "/chapters"
                val doc = networkClient.get(chaptersUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.ifBlank { null }
                    ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                // Fetching the book URL follows the site's redirect
                // to the novelfire.net book page, whose synopsis is
                // the NovelPub-family .summary .content block.
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".summary .content")
                    ?: doc.selectFirst(".summary")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            val baseChaptersUrl = bookUrl.trimEnd('/') + "/chapters"
            var page = 1
            while (true) {
                val url = if (page == 1) baseChaptersUrl
                else "$baseChaptersUrl?page=$page"
                val doc = networkClient.get(url).toDocument()
                var added = 0
                for (anchor in doc.select("ul.chapter-list li a[href]")) {
                    val chapterUrl = resolve(anchor.attr("href"))
                    if (!seen.add(chapterUrl)) continue
                    val title = anchor.attr("title").ifBlank {
                        anchor.selectFirst(".chapter-title")?.text()?.trim()
                            ?: anchor.text().trim()
                    }
                    if (title.isBlank()) continue
                    chapters += ChapterResult(title = title, url = chapterUrl)
                    added++
                }
                // Pages are already oldest first. The list ends
                // where a page adds nothing or the pager stops
                // offering a rel=next link.
                val hasNext = doc.selectFirst("a[rel=next]") != null
                if (added == 0 || !hasNext) break
                page++
                // Safety bound: 100 chapters per page.
                if (page > 200) break
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl?page=$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.selectFirst("a[rel=next]") != null

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
                    .addPath("ajax")
                    .addPath("searchLive")
                    .add("inputContent", input)
                    .toString()
                val html = networkClient.call(
                    getRequest(url, headers = xhrHeaders(baseUrl + "search"))
                ).toJson().asJsonObject.get("html")?.asString.orEmpty()
                if (html.isBlank())
                    return@tryConnect PagedList.createEmpty(index = index)
                val fragment = Jsoup.parseBodyFragment(html, baseUrl)
                val books = parseBookList(fragment)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
