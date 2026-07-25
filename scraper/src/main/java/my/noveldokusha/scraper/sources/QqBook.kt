package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class QqBook(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "qqbook"
    override val nameStrId = R.string.source_name_qqbook
    override val baseUrl = "https://book.qq.com/"
    override val catalogUrl = "https://book.qq.com/book-rank"
    override val language = LanguageCode.CHINESE

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.chapter-title")?.text()
        }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        val content = doc.selectFirst("div.chapter-content")
        content?.let { TextExtractor.get(it) }?.trim() ?: ""
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("div.book-cover img.ypc-book-cover")
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.let { if (it.startsWith("http")) it else URI(baseUrl).resolve(it).toString() }
        }
    }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("div.book-intro")
                ?.let { TextExtractor.get(it) }
        }
    }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()

            val listContainer = doc.select("ul.book-dir").let { lists ->
                if (lists.size >= 2) lists[1] else lists.first()
            }

            listContainer.select("li.list a.list-a")
                .mapNotNull { element ->
                    val href = element.attr("href")
                    if (href.isBlank()) return@mapNotNull null

                    ChapterResult(
                        title = element.selectFirst("span.name")?.text()?.trim() ?: element.text().trim(),
                        url = URI(baseUrl).resolve(href).toString()
                    )
                }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = if (page == 1) {
                catalogUrl
            } else {
                "https://book.qq.com/book-rank/male-sell/cycle-1-$page"
            }

            val doc = networkClient.get(url).toDocument()
            val items = doc.select("div.book-large.rank-book")
                .mapNotNull { element ->
                    val link = element.selectFirst("a.wrap") ?: return@mapNotNull null
                    val href = link.attr("href")
                    val img = element.selectFirst("img.ypc-book-cover")
                    val imgSrc = img?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
                    val titleEl = element.selectFirst("h4.title")
                    val title = titleEl?.text()?.trim() ?: link.attr("title").trim()
                    if (title.isBlank()) return@mapNotNull null

                    BookResult(
                        title = title,
                        url = URI(baseUrl).resolve(href).toString(),
                        coverImageUrl = if (imgSrc.startsWith("http")) imgSrc else URI(baseUrl).resolve(imgSrc).toString()
                    )
                }

            PagedList(list = items, index = index, isLastPage = items.isEmpty())
        }
    }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            if (input.startsWith(baseUrl) && input.contains("book-detail") && index == 0) {
                val doc = networkClient.get(input).toDocument()
                val rawTitle = doc.title()?.trim() ?: ""
                val title = rawTitle
                    .replace(Regex("_[^_]+$"), "")
                    .replace("-QQ阅读", "")
                    .trim()
                    .ifEmpty { input }
                val coverUrl = doc.selectFirst("div.book-cover img.ypc-book-cover")
                    ?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
                val coverImageUrl = if (coverUrl.startsWith("http")) coverUrl
                    else if (coverUrl.isNotBlank()) URI(baseUrl).resolve(coverUrl).toString()
                    else ""

                return@tryConnect PagedList(
                    list = listOf(BookResult(title = title, url = input, coverImageUrl = coverImageUrl)),
                    index = index,
                    isLastPage = true
                )
            }

            val encodedQuery = URLEncoder.encode(input, "UTF-8")
            val url = if (index == 0) {
                "https://book.qq.com/so/$encodedQuery"
            } else {
                "https://book.qq.com/so/$encodedQuery?pageNo=${index + 1}"
            }

            val doc = networkClient.get(url).toDocument()
            val items = doc.select("div.book-large.result-item")
                .mapNotNull { element ->
                    val link = element.selectFirst("a.wrap") ?: return@mapNotNull null
                    val href = link.attr("href")
                    val img = element.selectFirst("img.ypc-book-cover")
                    val imgSrc = img?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: ""
                    val titleEl = element.selectFirst("h4.title")
                    val title = titleEl?.text()?.trim() ?: link.attr("title").trim()
                    if (title.isBlank()) return@mapNotNull null

                    BookResult(
                        title = title,
                        url = URI(baseUrl).resolve(href).toString(),
                        coverImageUrl = if (imgSrc.startsWith("http")) imgSrc else URI(baseUrl).resolve(imgSrc).toString()
                    )
                }

            val hasNextPage = doc.selectFirst("div.pagination .pagination-next.pagination-disabled") == null
                && doc.selectFirst("div.pagination") != null
            PagedList(list = items, index = index, isLastPage = !hasNextPage || items.isEmpty())
        }
    }
}
