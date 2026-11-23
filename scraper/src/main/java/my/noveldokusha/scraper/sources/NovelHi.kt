package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
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

/**
 * NovelHi — https://novelhi.com
 *
 * JSON API + token-loaded chapter content, verified live 2026-11-23:
 * - Catalog and search share one endpoint:
 *   GET /book/searchByPageInShelf?curr=<page>&limit=20[&keyword=<q>]
 *   -> {code:"200", data:{pageNum, pageSize, total, list:[{bookName,
 *      simpleName, picUrl, bookDesc, ...}]}}. Without a keyword it
 *      returns the whole catalog (2,319 books); past the end the list
 *      is empty. Book URLs are /s/<simpleName>, which 301-redirect to
 *      the canonical /novel/<genre>/<slug> book page.
 * - Book page: hidden input#bookId and input#canonicalNovelPath,
 *   cover in a.book_cover img.cover, synopsis in p.detail-desc,
 *   title in h1.
 * - Chapter list: GET /book/queryIndexList?bookId=<id>&curr=1&
 *   limit=50000 returns every chapter NEWEST first; reversed here
 *   to oldest -> newest. Chapter URLs are <book path>/<indexNum>.
 * - Chapter pages carry NO story text in their HTML: the text is
 *   fetched by the site's own JS from a per-chapter content URL.
 *   The page holds it in hidden inputs #chapterContentPath and
 *   #chapterContentToken; GET <base><path>?token=<token> with an
 *   X-Requested-With header answers {code:"200", data:{content}}
 *   where content is HTML of <sent> sentence elements whose text
 *   is ROT13-obfuscated (the site renders it with a scrambled
 *   font). ROT13-decoding yields the clean English text.
 *   [getChapterText] only reads the document (title inputs stay
 *   untouched for [getChapterTitle]) and makes that one extra call.
 * - Chapters the content endpoint refuses (non-"200" code, e.g.
 *   locked/VIP) yield empty text instead of an error.
 */
class NovelHi(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelhi"
    override val nameStrId = R.string.source_name_novelhi
    override val baseUrl = "https://novelhi.com/"
    override val catalogUrl = "https://novelhi.com/"
    override val iconUrl = "https://novelhi.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val pageSize = 20

    private fun xhrHeaders(referer: String): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .add("Accept", "application/json, text/plain, */*")
        .build()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private suspend fun getApiJson(url: String, referer: String = baseUrl): JsonObject =
        networkClient.call(getRequest(url, headers = xhrHeaders(referer)))
            .toJson().asJsonObject

    private fun bookUrl(simpleName: String): String = "${baseUrl}s/$simpleName"

    private fun cleanDescription(raw: String): String {
        if (raw.isBlank()) return ""
        val withBreaks = raw.replace(Regex("<br\\s*/?>"), " ")
        return Jsoup.parseBodyFragment(withBreaks).text().trim()
    }

    private fun parseBook(item: JsonObject): BookResult = BookResult(
        title = item.str("bookName"),
        url = bookUrl(item.str("simpleName")),
        coverImageUrl = item.str("picUrl"),
        description = cleanDescription(item.str("bookDesc"))
    )

    private suspend fun getBooksPage(
        index: Int,
        search: String?
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val builder = "${baseUrl}book/searchByPageInShelf".toUrlBuilderSafe()
                .add("curr", index + 1)
                .add("limit", pageSize)
            if (!search.isNullOrBlank()) builder.add("keyword", search)
            val data = getApiJson(builder.toString())
                .getAsJsonObject("data")
            val list = data?.getAsJsonArray("list")
                ?.map { parseBook(it.asJsonObject) }
                ?.filter { it.title.isNotBlank() && it.url != bookUrl("") }
                ?: emptyList()
            val total = data?.str("total")?.toIntOrNull() ?: 0
            val isLastPage = list.isEmpty() || (index + 1) * pageSize >= total
            PagedList(list = list, index = index, isLastPage = isLastPage)
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = getBooksPage(index, search = null)

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> =
        if (input.isBlank()) withContext(Dispatchers.Default) {
            Response.Success(PagedList.createEmpty(index = index))
        } else {
            getBooksPage(index, search = input)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("a.book_cover img.cover")?.attr("src")
                    ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("p.detail-desc")?.let { TextExtractor.get(it) }
                    ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val bookId = doc.selectFirst("input#bookId")?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: throw Exception("NovelHi book id not found: $bookUrl")
            // After the /s/<name> redirect the document location is
            // the canonical book URL; the page also carries it in a
            // hidden input. Chapter URLs hang off that path.
            val bookPath = doc.selectFirst("input#canonicalNovelPath")?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: doc.location().removePrefix(baseUrl.trimEnd('/'))
            val url = "${baseUrl}book/queryIndexList".toUrlBuilderSafe()
                .add("bookId", bookId)
                .add("curr", 1)
                .add("limit", 50000)
                .toString()
            val list = getApiJson(url, referer = bookUrl)
                .getAsJsonObject("data")
                ?.getAsJsonArray("list")
                ?: return@tryConnect emptyList()
            // The API returns the chapters newest first.
            list.map { it.asJsonObject }
                .reversed()
                .map { item ->
                    ChapterResult(
                        title = item.str("indexName"),
                        url = baseUrl.trimEnd('/') + bookPath.trimEnd('/') +
                            "/" + item.str("indexNum")
                    )
                }
                .distinctBy { it.url }
        }
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("input#currentIndexName")?.attr("value")
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val path = doc.selectFirst("input#chapterContentPath")?.attr("value")
                ?.takeIf { it.isNotBlank() } ?: return@withContext ""
            val token = doc.selectFirst("input#chapterContentToken")?.attr("value")
                .orEmpty()
            val url = (baseUrl.trimEnd('/') + path).toUrlBuilderSafe()
                .add("token", token)
                .toString()
            val json = runCatching {
                getApiJson(url, referer = doc.location())
            }.getOrNull() ?: return@withContext ""
            if (json.str("code") != "200") return@withContext ""
            val data = json.get("data") ?: return@withContext ""
            val contentHtml = when {
                data.isJsonObject -> data.asJsonObject.str("content")
                data.isJsonPrimitive -> data.asString
                else -> ""
            }
            if (contentHtml.isBlank()) return@withContext ""
            extractChapterText(contentHtml)
        }

    /**
     * The content payload is HTML of <sent> sentence elements in
     * paragraph groups separated by <br><br>, all ROT13-obfuscated.
     * Decode each paragraph's sentences back to plain text.
     */
    private fun extractChapterText(contentHtml: String): String {
        val bodyHtml = Jsoup.parseBodyFragment(contentHtml, baseUrl).body().html()
        val chunks = bodyHtml.split(Regex("(<br\\s*/?>\\s*){2,}"))
        val paragraphs = chunks.mapNotNull { chunk ->
            val sents = Jsoup.parseBodyFragment(chunk, baseUrl).select("sent")
            val text = if (sents.isNotEmpty()) {
                sents.toList().joinToString(" ") { it.text() }
            } else {
                Jsoup.parseBodyFragment(chunk, baseUrl).text()
            }
            rot13(text).trim().takeIf { it.isNotBlank() }
        }
        if (paragraphs.isNotEmpty()) return paragraphs.joinToString("\n\n")
        // Fallback: no paragraph structure survived, decode it all.
        val all = Jsoup.parseBodyFragment(contentHtml, baseUrl).select("sent")
        return if (all.isNotEmpty()) {
            rot13(all.toList().joinToString(" ") { it.text() }).trim()
        } else {
            rot13(Jsoup.parseBodyFragment(contentHtml, baseUrl).text()).trim()
        }
    }

    private fun rot13(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    in 'a'..'m', in 'A'..'M' -> c + 13
                    in 'n'..'z', in 'N'..'Z' -> c - 13
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
