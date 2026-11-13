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
 * FreeWebNovel — https://freewebnovel.com
 *
 * Fixes (2026-11-13):
 * - [getChapterList] read the title/URL from `selectFirst("a")` on the
 *   `<a>` element itself, which always returns null: every chapter came
 *   back with an empty title and a `...null` URL. Read the attributes
 *   from the element directly.
 * - [getCatalogSearch] used a raw `Jsoup.connect(...).post()`, bypassing
 *   the app's NetworkClient (no Cloudflare bypass, no shared cookies or
 *   user agent) — the site 403s bare requests. It now posts through
 *   NetworkClient like every other source.
 * - `baseUrl` had no trailing slash (the unit test requires one) and
 *   cover/book URLs were built with plain string concatenation, producing
 *   `https://freewebnovel.comhttps://...` / doubled slashes when the
 *   attribute was already absolute. URLs are now resolved properly.
 */
class FreeWebNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "freewebnovel"
    override val nameStrId = R.string.source_name_freewebnovel
    override val baseUrl = "https://freewebnovel.com/"
    override val catalogUrl = "https://freewebnovel.com/completed-novel/"
    override val iconUrl = "https://freewebnovel.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun parseBookList(doc: Document): List<BookResult> =
        doc.select(".ul-list1 .li-row").mapNotNull { element ->
            val link = element.selectFirst(".tit a") ?: return@mapNotNull null
            val coverUrl = element.selectFirst(".pic img")
                ?.let { it.attr("data-src").ifEmpty { it.attr("src") } } ?: ""
            BookResult(
                title = link.text(),
                url = resolve(link.attr("href")),
                coverImageUrl = resolve(coverUrl)
            )
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1, .chapter-title")?.text()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".txt")?.let { element ->
                element.select("script").remove()
                element.select(".ads").remove()
                element.select("h4").remove()
                element.select("sub").remove()
                TextExtractor.get(element)
            } ?: ""
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl)
                    .toDocument()
                    .selectFirst(".pic img")?.attr("src")
                    ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                networkClient.get(bookUrl)
                    .toDocument()
                    .selectFirst(".m-desc .txt")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl)
                .toDocument()
                .select("#idData li a")
                .map {
                    ChapterResult(
                        title = it.attr("title").ifBlank { it.text() },
                        url = resolve(it.attr("href"))
                    )
                }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = "$baseUrl/completed-novel/$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || doc.selectFirst("a:nth-child(13)") == null
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
                val request = postRequest("${baseUrl}search")
                    .postPayload { add("searchkey", input) }
                val doc = networkClient.call(request).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = books.isEmpty())
            }
        }
}
