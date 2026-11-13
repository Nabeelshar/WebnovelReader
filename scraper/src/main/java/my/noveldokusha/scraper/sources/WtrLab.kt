package my.noveldokusha.scraper.sources

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toJson
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * WTR-LAB — https://wtr-lab.com/
 *
 * Next.js site; the catalog / book pages are server-rendered shells, the
 * real data lives in `#__NEXT_DATA__` JSON. Verified live 2026-11-13:
 * - Catalog: GET /en/novel-list?page=N -> `pageProps.series[]`
 *   (10 per page, `pageProps.count` total). Each item: `{raw_id, slug,
 *   chapter_count, data: {title, author, description, image}}`.
 * - Book: GET /en/serie-<raw_id>/<slug> -> `pageProps.serie.serie_data`
 *   (same shape, `chapter_count` chapters).
 * - Search: POST /api/search with JSON `{"text": "<query>"}` ->
 *   `{data: [<series items>]}`.
 * - Chapter text: POST /api/reader/get with JSON
 *   `{"language": "en", "raw_id": "<id>", "chapter_no": "<n>"}` ->
 *   `{chapter: {...}, content_url: "/api/v2/reader/content/..."}`.
 *   GET the content URL -> `{data: {data: {body: "arr:<iv>:<tag>:<ct>"}}}`.
 *
 * The chapter body is AES-256-GCM encrypted (key hard-coded in the site's
 * own reader JS chunk, `IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru`): the body is
 * `arr:<base64 iv>:<base64 tag>:<base64 ciphertext>`, decrypted plaintext
 * is a JSON array of paragraph strings. The decrypted text is the RAW
 * (Chinese) text — the site machine-translates it client-side with
 * Google Translate, so the app's own live chapter translation does the
 * same job here.
 *
 * Previous version of this source was broken in every step: the catalog
 * parsed `.novel-item` selectors that no longer exist on the page, search
 * sent a form body instead of JSON (and built `/serie-<id>/f<slug>` URLs),
 * and chapter text expected the old `{data: {data: {body: [...]}}}`
 * response shape the API no longer returns.
 */
class WtrLab(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "wtrlab"
    override val nameStrId = R.string.source_name_wtrlab
    override val baseUrl = "https://wtr-lab.com/"
    override val catalogUrl = "https://wtr-lab.com/en/novel-list"
    override val language = LanguageCode.ENGLISH

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun nextData(doc: Document): JsonObject? = runCatching {
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        JsonParser.parseString(raw).asJsonObject
    }.getOrNull()

    private fun pageProps(doc: Document): JsonObject? =
        nextData(doc)?.getAsJsonObject("props")?.getAsJsonObject("pageProps")

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun parseSeriesItem(item: JsonObject): BookResult? {
        val rawId = item.str("raw_id").ifBlank { return null }
        val slug = item.str("slug").ifBlank { return null }
        val data = item.getAsJsonObject("data") ?: return null
        val title = data.str("title").ifBlank { return null }
        return BookResult(
            title = title,
            url = "${baseUrl}en/serie-$rawId/$slug",
            coverImageUrl = data.str("image"),
            description = "Author: ${data.str("author")}"
        )
    }

    // AES-256-GCM, key from the site's own reader bundle.
    private fun decryptBody(body: String): String? {
        val payload = when {
            body.startsWith("arr:") || body.startsWith("str:") -> body.substring(4)
            else -> return null
        }
        val parts = payload.split(":")
        if (parts.size != 3) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.DEFAULT)
            val tag = Base64.decode(parts[1], Base64.DEFAULT)
            val ciphertext = Base64.decode(parts[2], Base64.DEFAULT)
            val key = SecretKeySpec(
                "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru".toByteArray(Charsets.UTF_8),
                "AES"
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext + tag), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun paragraphsToText(decrypted: String): String {
        val trimmed = decrypted.trim()
        if (!trimmed.startsWith("[")) return trimmed
        return runCatching {
            JsonParser.parseString(trimmed).asJsonArray
                .map { it.asString }
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
        }.getOrDefault(trimmed)
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.title().substringBefore(" | ").ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val location = doc.location()
            val rawId = Regex("/serie-(\\d+)").find(location)?.groupValues?.get(1)
                ?: return@withContext ""
            val chapterNo = Regex("/chapter-(\\d+)").find(location)?.groupValues?.get(1)
                ?: return@withContext ""
            runCatching {
                val payload = JsonObject().apply {
                    addProperty("language", "en")
                    addProperty("raw_id", rawId)
                    addProperty("chapter_no", chapterNo)
                }.toString().toRequestBody(jsonMediaType)
                val request = postRequest("$baseUrl/api/reader/get")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Referer", location)
                    .post(payload)
                val readerJson = networkClient.call(request).toJson().asJsonObject
                if (readerJson.get("success")?.asBoolean != true) return@runCatching ""
                val contentUrl = readerJson.get("content_url")?.asString
                    ?: return@runCatching ""
                val absoluteUrl =
                    if (contentUrl.startsWith("http")) contentUrl else baseUrl.trimEnd('/') + contentUrl
                val contentJson = networkClient.get(absoluteUrl).toJson().asJsonObject
                val body = contentJson.getAsJsonObject("data")
                    ?.getAsJsonObject("data")
                    ?.get("body")?.asString ?: return@runCatching ""
                val decrypted = decryptBody(body) ?: body
                paragraphsToText(decrypted)
            }.getOrDefault("")
        }

    private fun serieData(doc: Document): JsonObject? =
        pageProps(doc)?.getAsJsonObject("serie")?.getAsJsonObject("serie_data")

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            serieData(networkClient.get(bookUrl).toDocument())
                ?.getAsJsonObject("data")?.str("image")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            serieData(networkClient.get(bookUrl).toDocument())
                ?.getAsJsonObject("data")?.str("description")?.ifBlank { null }
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val serie = serieData(doc) ?: throw Exception("Serie data not found")
            val rawId = serie.str("raw_id").ifBlank { serie.str("id") }
            val slug = serie.str("slug")
            val chapterCount = serie.get("chapter_count")?.asInt ?: 0
            (1..chapterCount).map { chapterNo ->
                ChapterResult(
                    title = "Chapter $chapterNo",
                    url = "${baseUrl}en/serie-$rawId/$slug/chapter-$chapterNo"
                )
            }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val page = index + 1
            val url = baseUrl.toUrlBuilderSafe()
                .addPath("en", "novel-list")
                .add("page", page)
                .toString()
            val props = pageProps(networkClient.get(url).toDocument())
            val books = props?.getAsJsonArray("series")
                ?.mapNotNull { parseSeriesItem(it.asJsonObject) } ?: emptyList()
            val total = props?.get("count")?.asInt ?: 0
            PagedList(
                list = books,
                index = index,
                isLastPage = books.isEmpty() || (total > 0 && page * books.size >= total)
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
            val payload = JsonObject().apply {
                addProperty("text", input)
            }.toString().toRequestBody(jsonMediaType)
            val request = postRequest("$baseUrl/api/search")
                .addHeader("Content-Type", "application/json")
                .addHeader("Referer", catalogUrl)
                .post(payload)
            val json = networkClient.call(request).toJson().asJsonObject
            val books = json.getAsJsonArray("data")
                ?.mapNotNull { parseSeriesItem(it.asJsonObject) } ?: emptyList()
            PagedList(list = books, index = index, isLastPage = true)
        }
    }
}
