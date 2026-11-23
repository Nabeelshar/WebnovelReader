package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
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
 * Author.Today — https://author.today
 *
 * Russian author platform. Site structure verified live 2026-11-23
 * (curl fetches of the catalog, search, book and reader endpoints,
 * cross-checked against the lightnovel-crawler project's AuthorToday
 * source):
 * - Catalog: the all-genres ebook listing /work/genre/all/ebook,
 *   25 books per page, paginated with ?page=<n> (past the end the
 *   site answers 404, which parses as an empty page). Items are
 *   .book-row blocks: title link in .book-title a (/work/<id>),
 *   cover in .book-cover img (real URL in src).
 * - Search: GET /search?q=<query>&category=works reuses the same
 *   .book-row markup (30 results per page, ?page=<n> paginates).
 *   Matched terms are wrapped in <em class="searched-item"> inside
 *   the title link; the plain text is unaffected.
 * - Book data and chapter lists come from the JSON API:
 *   GET https://api.author.today/v1/work/<id>/details with the
 *   "Authorization: Bearer guest" header the site's own apps use.
 *   The payload carries title, coverUrl, annotation (light HTML)
 *   and the full chapters array (id, workId, title, sortOrder,
 *   isAvailable). Unavailable (paid/draft) chapters are skipped,
 *   like the reference crawler does.
 * - Chapter URLs are the reader endpoints
 *   /reader/<workId>/chapter?id=<chapterId>. Requesting one returns
 *   JSON {data: {text}} where text is obfuscated: it is the chapter
 *   HTML XORed per UTF-16 code unit with the key
 *   reverse(Reader-Secret response header) + "@_@". The download
 *   step fetches the reader URL itself (the JSON parses as a
 *   document), so [getChapterText] re-requests the URL from the
 *   document location to read the Reader-Secret header, decodes the
 *   text and extracts the paragraphs. The chapter JSON carries no
 *   title, so [getChapterTitle] stays null and the app keeps the
 *   title from the chapter list.
 */
class AuthorToday(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "authortoday"
    override val nameStrId = R.string.source_name_authortoday
    override val baseUrl = "https://author.today/"
    override val catalogUrl = "https://author.today/work/genre/all/ebook"
    override val language = LanguageCode.RUSSIAN

    private val apiBaseUrl = "https://api.author.today/v1"

    private val apiHeaders: Headers = Headers.Builder()
        .add("Authorization", "Bearer guest")
        .add("Cookie", "AdultUser=true")
        .add("Accept", "application/json")
        .build()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun workIdFromUrl(url: String): String? =
        Regex("/work/(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun getWorkDetails(workId: String): JsonObject =
        networkClient.call(
            getRequest("$apiBaseUrl/work/$workId/details", headers = apiHeaders)
        ).toJson().asJsonObject

    /**
     * Catalog and works-search results share the .book-row markup:
     * title link in .book-title a, cover in .book-cover img (the
     * search page lazy-loads covers into data-src and leaves a data:
     * placeholder in src, the catalog uses src directly).
     */
    private fun parseBookRows(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (row in doc.select(".book-row")) {
            val link = row.selectFirst(".book-title a[href]") ?: continue
            val href = link.attr("href")
            if (workIdFromUrl(href) == null) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val img = row.selectFirst(".book-cover img")
            var cover = img?.attr("data-src").orEmpty()
            if (cover.isBlank() || cover.startsWith("data:")) {
                cover = img?.attr("src").orEmpty()
            }
            if (cover.startsWith("data:")) cover = ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    override suspend fun getChapterTitle(doc: Document): String? = null

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val location = doc.location()
            // The document is the reader endpoint's JSON answer; the
            // Reader-Secret header needed to decode the text is not
            // part of it, so re-request the same URL with headers.
            val decodedHtml = runCatching {
                val response = networkClient.call(
                    getRequest(location, headers = apiHeaders)
                )
                val secret = response.header("Reader-Secret")
                    ?: return@runCatching null
                val obfuscated = response.toJson().asJsonObject
                    .getAsJsonObject("data")?.str("text")
                    .orEmpty()
                if (obfuscated.isBlank()) null
                else decodeChapterText(obfuscated, secret)
            }.getOrNull() ?: return@withContext ""
            val body = Jsoup.parseBodyFragment(decodedHtml, baseUrl).body()
            TextExtractor.get(body)
        }

    /**
     * Reverses the reader obfuscation: the text is the chapter HTML
     * read as UTF-16LE code units, each XORed with the repeating key
     * reverse(secret) + "@_@" (verified against the live endpoint,
     * mirroring the reference crawler's decoder).
     */
    private fun decodeChapterText(obfuscated: String, secret: String): String {
        val cipher = secret.reversed() + "@_@"
        val bytes = obfuscated.toByteArray(Charsets.UTF_16LE)
        val unitCount = bytes.size / 2
        val chars = CharArray(unitCount)
        for (i in 0 until unitCount) {
            val unit = (bytes[2 * i].toInt() and 0xFF) or
                ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)
            chars[i] = (unit xor cipher[i % cipher.length].code).toChar()
        }
        return String(chars)
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val workId = workIdFromUrl(bookUrl) ?: return@tryConnect null
                getWorkDetails(workId).str("coverUrl").ifBlank { null }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val workId = workIdFromUrl(bookUrl) ?: return@tryConnect null
                val annotation = getWorkDetails(workId).str("annotation")
                if (annotation.isBlank()) null
                else TextExtractor.get(
                    Jsoup.parseBodyFragment(annotation, baseUrl).body()
                ).ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val workId = workIdFromUrl(bookUrl)
                ?: throw Exception("Invalid Author.Today book URL: $bookUrl")
            val details = getWorkDetails(workId)
            val chaptersJson = details.getAsJsonArray("chapters")
                ?: return@tryConnect emptyList()
            // The API lists chapters in reading order; sort by
            // sortOrder anyway and skip unavailable (paid/draft)
            // chapters like the reference crawler does.
            chaptersJson
                .map { it.asJsonObject }
                .filter { it.get("isAvailable")?.asBoolean == true }
                .sortedBy { it.get("sortOrder")?.asInt ?: 0 }
                .map { chapter ->
                    val chapterWorkId = chapter.get("workId")?.asLong?.toString()
                        ?: workId
                    ChapterResult(
                        title = chapter.str("title"),
                        url = "https://author.today/reader/$chapterWorkId" +
                            "/chapter?id=${chapter.get("id")?.asLong}"
                    )
                }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl?page=$page"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookRows(doc)

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
    ): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                if (input.isBlank())
                    return@tryConnect PagedList.createEmpty(index = index)
                val page = index + 1
                val builder = baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .add("q", input)
                    .add("category", "works")
                if (page > 1) builder.add("page", page)
                val doc = networkClient.get(builder.toString()).toDocument()
                val books = parseBookRows(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }
}
