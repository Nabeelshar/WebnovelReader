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

class WfxsTw(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "wfxstw"
    override val nameStrId = R.string.source_name_wfxstw
    override val baseUrl = "https://www.wfxs.tw/"
    override val catalogUrl = "https://www.wfxs.tw/topallvisit/1.html"
    override val language = LanguageCode.CHINESE

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("div.chapter-content h1")?.text()
        }

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        doc.selectFirst("div.content")?.let { content ->
            content.select("script, .txtad, ins").remove()
            TextExtractor.get(content)
        } ?: ""
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.ifBlank { null }
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst("div#bookimg img.lazyload")
                    ?.attr("_src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { src ->
                        if (src.startsWith("http")) src
                        else URI(baseUrl).resolve(src).toString()
                    }
        }
    }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.ifBlank { null }
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst("div#bookintro p")
                    ?.let { TextExtractor.get(it) }
        }
    }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val bookId = bookUrl.trimEnd('/').substringAfterLast("/")
            val listUrl = "https://www.wfxs.tw/booklist/$bookId.html"

            networkClient.get(listUrl).toDocument()
                .select("div#readerlists ul li a")
                .mapNotNull { element ->
                    val href = element.attr("href")
                    if (!href.contains("/xiaoshuo/$bookId/")) return@mapNotNull null

                    ChapterResult(
                        title = element.attr("title").ifBlank { element.text().trim() },
                        url = URI(baseUrl).resolve(href).toString()
                    )
                }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = "https://www.wfxs.tw/topallvisit/$page.html"

            val doc = networkClient.get(url).toDocument()
            val items = doc.select("div.rec_rullist ul")
                .mapNotNull { ul ->
                    val link = ul.selectFirst("li.three a[href^=/xiaoshuo/]")
                        ?: return@mapNotNull null

                    BookResult(
                        title = link.text().trim(),
                        url = URI(baseUrl).resolve(link.attr("href")).toString(),
                    )
                }

            PagedList(list = items, index = index, isLastPage = items.isEmpty())
        }
    }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            if (input.startsWith("http") && input.contains("wfxs.tw")) {
                val doc = networkClient.get(input).toDocument()
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                    ?: doc.selectFirst("div.booktitle h1")?.text()?.trim()
                    ?: doc.selectFirst("title")?.text()?.trim()
                    ?: "Unknown"

                val coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                    ?: doc.selectFirst("div#bookimg img.lazyload")?.attr("_src")?.takeIf { it.isNotBlank() }
                        ?.let { if (it.startsWith("http")) it else URI(baseUrl).resolve(it).toString() }
                    ?: ""

                val bookUrl = doc.selectFirst("meta[property=og:url]")?.attr("content")
                    ?.replace("https:/", "https://")
                    ?.trimEnd('/')
                    ?.plus("/")
                    ?: input

                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = title,
                            url = bookUrl,
                            coverImageUrl = coverUrl,
                            description = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim() ?: ""
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            val encodedQuery = java.net.URLEncoder.encode(input, "UTF-8")
            val searchUrl = "https://www.wfxs.tw/s.html?type=articlename&s=$encodedQuery"

            val doc = networkClient.get(searchUrl).toDocument()
            val items = doc.select("div.result-card")
                .mapNotNull { card ->
                    val link = card.selectFirst("div.book-info a.book-title")
                        ?: return@mapNotNull null
                    val coverImg = card.selectFirst("div.book-cover img")?.attr("src") ?: ""

                    BookResult(
                        title = link.text().trim(),
                        url = URI(baseUrl).resolve(link.attr("href")).toString(),
                        coverImageUrl = if (coverImg.startsWith("http")) coverImg
                        else if (coverImg.startsWith("/")) URI(baseUrl).resolve(coverImg).toString()
                        else ""
                    )
                }

            PagedList(list = items, index = index, isLastPage = true)
        }
    }
}
