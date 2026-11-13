package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI

/**
 * FanMTL — https://www.fanmtl.com/
 *
 * EmpireCMS site. Verified live 2026-11-13:
 * - Catalog is NOT /novels (404). The real list is
 *   /list/all/all-newstime-<page>.html (page 0-based, 30 per page);
 *   the homepage sections use the same `.novel-list .novel-item` markup.
 * - Covers are lazy-loaded: the real URL is in `data-src`, `src` is a
 *   placeholder image.
 * - Book page `/novel/<slug>.html`: chapter list is paginated via
 *   /e/extend/fy1.php?page=0&wjm=<slug> (first page) and
 *   /e/extend/fy.php?page=<n>&wjm=<slug> (later pages), each returning an
 *   HTML fragment of `li a` chapter links (`/novel/<slug>_<n>.html`).
 * - Search is a POST to /e/search/index.php with
 *   show=title, tempid=1, tbname=news, keyboard=<query> (the old `?s=`
 *   query is ignored by the site and just returned the homepage).
 * - Chapter page `/novel/<slug>_<n>.html`: `<title>` is
 *   "<Book> - Chapter N"; the old `.chapter-title, h1` selector returned
 *   the book title instead. Content is `.chapter-content`.
 */
class FanMTL(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "fanmtl"
    override val nameStrId = R.string.source_name_fanmtl
    override val baseUrl = "https://www.fanmtl.com/"
    override val catalogUrl = "https://www.fanmtl.com/list/all/all-newstime-0.html"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.startsWith("http")) url else URI(baseUrl).resolve(url).toString()

    private fun coverUrl(element: org.jsoup.nodes.Element): String {
        val img = element.selectFirst("img") ?: return ""
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return if (src.isBlank()) "" else resolve(src)
    }

    private fun parseNovelList(doc: Document): List<BookResult> =
        doc.select(".novel-list .novel-item").mapNotNull {
            val link = it.selectFirst("a[href]") ?: return@mapNotNull null
            val title = it.selectFirst(".novel-title")?.text()?.trim()
                ?: link.attr("title").trim()
            if (title.isBlank()) return@mapNotNull null
            BookResult(
                title = title,
                url = resolve(link.attr("href")),
                coverImageUrl = coverUrl(it)
            )
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // "<Book title> - Chapter 1"; .chapter-title/h1 hold the book title.
            doc.title().substringAfter(" - ", "").ifBlank { null }
                ?: doc.selectFirst(".chapter-title")?.text()
        }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        (doc.selectFirst("#chapter-article .chapter-content")
            ?: doc.selectFirst(".chapter-content"))?.let { element ->
            element.select("script").remove()
            element.select("div[align=\"center\"]").remove()
            element.select(".ads").remove()
            TextExtractor.get(element)
        } ?: ""
    }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst(".novel-header figure.cover img[src]")
                ?.let { coverUrl(it.parent() ?: it) }
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst(".novel-info .description, .novel-synopsis")
                ?.let { TextExtractor.get(it) }
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val firstDoc = networkClient.get(bookUrl).toDocument()
            val slug = Regex("/novel/([^/.]+)").find(bookUrl)?.groupValues?.get(1)
                ?: throw Exception("Invalid FanMTL book URL: $bookUrl")

            val lastPage = firstDoc.select(".pagination a[data-ajax-update]")
                .mapNotNull {
                    Regex("page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                }.maxOrNull()

            if (lastPage == null) {
                return@tryConnect firstDoc.select("ul.chapter-list li a[href]")
                    .map { parseChapterLink(it) }
            }

            val chapters = mutableListOf<ChapterResult>()
            for (page in 0..lastPage) {
                val ajaxPath = if (page == 0) "e/extend/fy1.php" else "e/extend/fy.php"
                val pageUrl = "${baseUrl}$ajaxPath?page=$page&wjm=$slug"
                val pageDoc = networkClient.get(pageUrl).toDocument()
                pageDoc.select("li a[href]").mapTo(chapters) { parseChapterLink(it) }
            }
            chapters
        }
    }

    private fun parseChapterLink(element: org.jsoup.nodes.Element): ChapterResult {
        val title = element.selectFirst(".chapter-title")?.text()?.trim()
            ?: element.attr("title").trim().ifBlank { element.text().trim() }
        return ChapterResult(title = title, url = resolve(element.attr("href")))
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            // Site pages are 0-based: index 0 -> all-newstime-0.html.
            val url = "${baseUrl}list/all/all-newstime-$index.html"
            val doc = networkClient.get(url).toDocument()
            val books = parseNovelList(doc)
            PagedList(
                list = books,
                index = index,
                isLastPage = books.isEmpty()
            )
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)
            val request = postRequest("${baseUrl}e/search/index.php")
                .postPayload {
                    add("show", "title")
                    add("tempid", "1")
                    add("tbname", "news")
                    add("keyboard", input)
                }
            val doc = networkClient.call(request).toDocument()
            val books = parseNovelList(doc)
            PagedList(list = books, index = index, isLastPage = true)
        }
    }
}
