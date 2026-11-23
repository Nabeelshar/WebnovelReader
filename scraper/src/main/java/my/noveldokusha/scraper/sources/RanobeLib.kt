package my.noveldokusha.scraper.sources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toJson
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.BookTextMapper
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * RanobeLib — https://ranobelib.me (Russian, LibSocial network)
 *
 * JSON API, not HTML scraping. Verified live 2026-11-23:
 * - The site was rebuilt since the lightnovel-crawler source was
 *   written: book pages now live at /ru/book/<id>--<slug>, and the
 *   API moved to https://api.cdnlibs.org/api (the api.mangalib.me
 *   host the new frontend also references sits behind a DDoS-Guard
 *   block page from here). Every API call REQUIRES the header
 *   `Site-Id: 3` (RanobeLib's id in the network) — without it the
 *   API answers 404 {"data":{"toast":...}}.
 * - GET /api/manga?page=N[&q=<query>]
 *   -> {data: [{id, name, rus_name, eng_name, slug, slug_url,
 *       cover: {default, thumbnail}, ...}], meta: {has_next_page}}
 *   60 items per page; used for both the catalog and search.
 * - GET /api/manga/<slug>?fields[]=summary&fields[]=...
 *   -> {data: {rus_name, eng_name, cover: {default}, summary, ...}}
 *   `summary` is a ProseMirror-style doc ({type:"doc", content:[...]}),
 *   not a plain string. Both the plain slug and the <id>--<slug>
 *   slug_url form are accepted as <slug>.
 * - GET /api/manga/<slug>/chapters
 *   -> {data: [{volume, number, name, branches: [{branch_id}]}]}
 *   The full list in one response, already ordered oldest -> newest.
 *   A chapter can have several translation branches (teams); like
 *   the reference crawler, the branch used for the whole book is the
 *   one covering the most chapters, falling back per chapter to its
 *   first branch when it lacks the dominant one.
 * - GET /api/manga/<slug>/chapter?volume=<v>&number=<n>&branch_id=<b>
 *   -> {data: {name, number, content, attachments}}
 *   `number` is the book-global chapter number (volume 8's last
 *   chapter is number 1432, not its in-volume 8). `content` is the
 *   same ProseMirror doc format as the summary (paragraph / heading /
 *   image / horizontalRule nodes); image nodes reference the
 *   chapter's `attachments` by file name. Older content may be a
 *   plain HTML string instead.
 *
 * Book URLs are the site's book URLs (`/ru/book/<slug_url>`);
 * chapter URLs are the site's reader URLs
 * (`/ru/book/<slug_url>/read/v<volume>/c<number>?bid=<branch_id>` —
 * `bid` is the query param the site's own router uses).
 * [transformChapterUrl] rewrites a chapter URL to its API URL before
 * the download step fetches it, and [getChapterText] /
 * [getChapterTitle] re-request the API (with the Site-Id header the
 * plain fetch lacks) from the document location.
 */
class RanobeLib(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "ranobelib"
    override val nameStrId = R.string.source_name_ranobelib
    override val baseUrl = "https://ranobelib.me/"
    override val catalogUrl = "https://ranobelib.me/ru/catalog"
    override val iconUrl = "https://ranobelib.me/static/images/logo/rl/favicon.ico"
    override val language = LanguageCode.RUSSIAN

    private val apiBaseUrl = "https://api.cdnlibs.org/api"

    private val apiHeaders: Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Referer", baseUrl)
        .add("Site-Id", "3")
        .build()

    private suspend fun getApiJson(url: String): JsonObject =
        networkClient.call(getRequest(url, headers = apiHeaders)).toJson().asJsonObject

    private fun JsonObject.obj(key: String): JsonObject? =
        if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else null

    private fun JsonObject.arr(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

    private fun JsonObject.str(key: String): String =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString else ""

    /** String value of a field that may be a JSON string or number. */
    private fun JsonObject.strOrNum(key: String): String = str(key)

    private fun JsonObject.longOrNull(key: String): Long? =
        if (has(key) && get(key).isJsonPrimitive) get(key).asString.toLongOrNull()
        else null

    private fun bookTitle(item: JsonObject): String =
        item.str("rus_name")
            .ifBlank { item.str("eng_name") }
            .ifBlank { item.str("name") }

    private fun parseBook(item: JsonObject): BookResult {
        val slugUrl = item.str("slug_url").ifBlank { item.str("slug") }
        val cover = item.obj("cover")
        return BookResult(
            title = bookTitle(item),
            url = "${baseUrl}ru/book/$slugUrl",
            coverImageUrl = cover?.str("default")?.ifBlank { null }
                ?: cover?.str("thumbnail")
                ?: ""
        )
    }

    private fun slugFromBookUrl(url: String): String? =
        Regex("/book/([^/?#]+)").find(url)?.groupValues?.get(1)

    private fun chapterDisplayTitle(name: String, number: String): String =
        name.ifBlank { if (number.isNotBlank()) "Глава $number" else "" }

    // ----------------------------------------------------------------
    // ProseMirror doc extraction (summary + chapter bodies)
    // ----------------------------------------------------------------

    /**
     * Flattens a ProseMirror doc into the app's chapter text format:
     * blocks separated by blank lines, illustrations declared with
     * [BookTextMapper.ImgEntry] like [TextExtractor] does for HTML.
     */
    private fun extractDocText(
        doc: JsonObject,
        attachments: Map<String, String>
    ): String {
        val blocks = mutableListOf<String>()
        collectBlocks(doc, attachments, blocks)
        return blocks.joinToString("\n\n").trim()
    }

    private fun collectBlocks(
        node: JsonElement,
        attachments: Map<String, String>,
        out: MutableList<String>
    ) {
        if (node is JsonArray) {
            node.forEach { collectBlocks(it, attachments, out) }
            return
        }
        if (node !is JsonObject) return
        when (node.str("type")) {
            "paragraph", "heading" -> {
                val text = inlineText(node).trim()
                if (text.isNotEmpty()) out += text
            }
            "image" -> {
                node.obj("attrs")?.arr("images")?.forEach { img ->
                    val stem = (img as? JsonObject)?.str("image").orEmpty()
                    val url = attachments[stem]
                    if (!url.isNullOrBlank()) {
                        out += BookTextMapper.ImgEntry(
                            path = url,
                            yRel = 1.45f
                        ).toXMLString()
                    }
                }
            }
            "horizontalRule" -> Unit // paragraph break only
            else -> node.arr("content")?.let { collectBlocks(it, attachments, out) }
        }
    }

    private fun inlineText(node: JsonObject): String {
        val sb = StringBuilder()
        fun walk(element: JsonElement) {
            if (element is JsonArray) {
                element.forEach { walk(it) }
                return
            }
            if (element !is JsonObject) return
            when (element.str("type")) {
                "text" -> sb.append(element.str("text"))
                "hardBreak" -> sb.append("\n")
                else -> element.arr("content")?.let { walk(it) }
            }
        }
        node.arr("content")?.let { walk(it) }
        return sb.toString()
    }

    /** Attachment file name -> absolute URL, from a chapter's data. */
    private fun attachmentsMap(data: JsonObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        data.arr("attachments")?.forEach { element ->
            val att = element as? JsonObject ?: return@forEach
            val name = att.str("name")
            val url = att.str("url")
            if (name.isNotBlank() && url.isNotBlank()) {
                map[name] = when {
                    url.startsWith("http") -> url
                    url.startsWith("/") -> "https://ranobelib.me$url"
                    else -> "https://ranobelib.me/$url"
                }
            }
        }
        return map
    }

    // ----------------------------------------------------------------
    // Book detail (cover / description)
    // ----------------------------------------------------------------

    private suspend fun getBookDetail(bookUrl: String): JsonObject? {
        val slug = slugFromBookUrl(bookUrl) ?: return null
        val builder = "$apiBaseUrl/manga/$slug".toUrlBuilderSafe()
        BOOK_FIELDS.forEach { builder.add("fields[]", it) }
        return runCatching { getApiJson(builder.toString()).obj("data") }.getOrNull()
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val cover = getBookDetail(bookUrl)?.obj("cover")
                cover?.str("default")?.ifBlank { null }
                    ?: cover?.str("thumbnail")?.ifBlank { null }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val detail = getBookDetail(bookUrl) ?: return@tryConnect null
                val summary = detail.get("summary")
                when {
                    summary == null || summary.isJsonNull -> null
                    summary.isJsonObject ->
                        extractDocText(summary.asJsonObject, emptyMap())
                            .ifBlank { null }
                    summary.isJsonPrimitive && summary.asJsonPrimitive.isString ->
                        summary.asString.ifBlank { null }
                    else -> null
                }
            }
        }

    // ----------------------------------------------------------------
    // Chapter list
    // ----------------------------------------------------------------

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val slugUrl = slugFromBookUrl(bookUrl)
                ?: throw Exception("Invalid RanobeLib book URL: $bookUrl")
            val json = getApiJson("$apiBaseUrl/manga/$slugUrl/chapters")
            val chapters = json.arr("data")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()

            // The branch (translation team) covering the most
            // chapters of this book, as the reference crawler picks.
            val branchCounts = mutableMapOf<Long, Int>()
            for (chapter in chapters) {
                chapter.arr("branches")?.forEach { branch ->
                    val branchId = (branch as? JsonObject)
                        ?.longOrNull("branch_id") ?: return@forEach
                    branchCounts[branchId] = (branchCounts[branchId] ?: 0) + 1
                }
            }
            val dominantBranch = branchCounts.maxByOrNull { it.value }?.key

            // The API already returns the list oldest -> newest.
            chapters.mapNotNull { chapter ->
                val branches = chapter.arr("branches")
                    ?.mapNotNull { it as? JsonObject }
                    .orEmpty()
                if (branches.isEmpty()) return@mapNotNull null
                val branchId = branches
                    .firstOrNull { it.longOrNull("branch_id") == dominantBranch }
                    ?.longOrNull("branch_id")
                    ?: branches.first().longOrNull("branch_id")
                    ?: return@mapNotNull null
                val volume = chapter.strOrNum("volume")
                val number = chapter.strOrNum("number")
                ChapterResult(
                    title = chapterDisplayTitle(chapter.str("name"), number),
                    url = "${baseUrl}ru/book/$slugUrl/read/v$volume/c$number" +
                            "?bid=$branchId"
                )
            }
        }
    }

    // ----------------------------------------------------------------
    // Chapter body
    // ----------------------------------------------------------------

    private data class ChapterRef(
        val slug: String,
        val volume: String,
        val number: String,
        val branchId: String?
    )

    private fun queryParam(url: String, key: String): String? =
        Regex("[?&]$key=([^&#]+)").find(url)?.groupValues?.get(1)

    private fun chapterRefFromLocation(location: String): ChapterRef? {
        // Transformed (API) URL:
        // /api/manga/<slug>/chapter?volume=..&number=..&branch_id=..
        Regex("/manga/([^/]+)/chapter").find(location)?.let { match ->
            return ChapterRef(
                slug = match.groupValues[1],
                volume = queryParam(location, "volume") ?: return null,
                number = queryParam(location, "number") ?: return null,
                branchId = queryParam(location, "branch_id")
            )
        }
        // Original reader URL: /book/<slug>/read/v<vol>/c<num>?bid=..
        Regex("/book/([^/]+)/read/v([\\d.]+)/c([\\d.]+)").find(location)?.let { match ->
            return ChapterRef(
                slug = match.groupValues[1],
                volume = match.groupValues[2],
                number = match.groupValues[3],
                branchId = queryParam(location, "bid")
            )
        }
        return null
    }

    override suspend fun transformChapterUrl(url: String): String {
        val ref = chapterRefFromLocation(url) ?: return url
        return buildString {
            append("$apiBaseUrl/manga/${ref.slug}/chapter")
            append("?volume=${ref.volume}&number=${ref.number}")
            if (!ref.branchId.isNullOrBlank()) {
                append("&branch_id=${ref.branchId}")
            }
        }
    }

    private suspend fun getChapterJson(doc: Document): JsonObject? {
        val ref = chapterRefFromLocation(doc.location()) ?: return null
        val url = buildString {
            append("$apiBaseUrl/manga/${ref.slug}/chapter")
            append("?volume=${ref.volume}&number=${ref.number}")
            if (!ref.branchId.isNullOrBlank()) {
                append("&branch_id=${ref.branchId}")
            }
        }
        return runCatching { getApiJson(url).obj("data") }.getOrNull()
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val data = getChapterJson(doc) ?: return@withContext null
            chapterDisplayTitle(data.str("name"), data.strOrNum("number"))
                .ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val data = getChapterJson(doc) ?: return@withContext ""
            val content = data.get("content") ?: return@withContext ""
            when {
                content.isJsonObject -> extractDocText(
                    content.asJsonObject,
                    attachmentsMap(data)
                )
                // Legacy chapters store the body as an HTML string.
                content.isJsonPrimitive && content.asJsonPrimitive.isString -> {
                    val fragment = Jsoup.parseBodyFragment(content.asString)
                    TextExtractor.get(fragment.body())
                }
                else -> ""
            }
        }

    // ----------------------------------------------------------------
    // Catalog + search
    // ----------------------------------------------------------------

    private suspend fun getBooksPage(
        index: Int,
        search: String?
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val builder = "$apiBaseUrl/manga".toUrlBuilderSafe()
                .add("page", index + 1)
            if (!search.isNullOrBlank()) builder.add("q", search)
            val json = getApiJson(builder.toString())
            val books = json.arr("data")
                ?.mapNotNull { (it as? JsonObject)?.let { item -> parseBook(item) } }
                .orEmpty()
            val hasNext = json.obj("meta")?.let { meta ->
                if (meta.has("has_next_page") && meta.get("has_next_page").isJsonPrimitive) {
                    meta.get("has_next_page").asBoolean
                } else {
                    null
                }
            } ?: (books.size >= PAGE_SIZE)
            PagedList(list = books, index = index, isLastPage = !hasNext)
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

    private companion object {
        const val PAGE_SIZE = 60

        val BOOK_FIELDS = listOf(
            "background",
            "eng_name",
            "otherNames",
            "summary",
            "genres",
            "chap_count",
            "status_id",
            "authors",
            "format"
        )
    }
}
