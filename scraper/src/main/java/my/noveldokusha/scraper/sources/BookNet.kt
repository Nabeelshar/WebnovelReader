package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.postRequest
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
 * BookNet — https://booknet.com (English section)
 *
 * Same publishing platform as Litnet (the app's Litnet source);
 * the lightnovel-crawler BookNet source targets the same flows
 * but its catalog/search selectors (.book-item) predate the
 * current redesign. Site structure verified live 2026-11-23.
 * Note: this VM's egress IP is geo-pinned by the site to its
 * Spanish section, so the live markup was verified on the /es/
 * pages — the platform serves identical markup per language
 * section, and the /en/ routes below are the site's own English
 * paths (the crawler's search/book/reader URLs use them too).
 *
 * - Catalog: /en/top/all, paginated as ?page=<n>, 20 books per
 *   page. Items are .bn_book-genre cards: title link in
 *   a.bn_book-genre__title-link, cover in .bn_book-genre__image
 *   img, annotation in .bn_book-genre__annotation.
 * - Search: GET /en/search?q=<query>&type=book&page=<n> renders
 *   48 .bn_book-row__vert-item cards per page (the bare /search
 *   page renders no book results server-side; the type=book tab
 *   is the book list). Title link in
 *   .bn_book-row__vert-item-title a, cover in
 *   .bn_book-row__vert-item-image img.
 * - Book page /en/book/<alias>: cover in .bn_book__header-image
 *   img (og:image is a share banner, used only as fallback),
 *   description in .bn_book__about-content.
 * - Chapter list: the book page itself carries the full list as
 *   .bn_book__chapters-item entries in reading order (verified
 *   complete against the page's own "N chapters" counter, up to
 *   106 chapters): the (empty) anchor
 *   .bn_book__chapters-item-link points at the reader URL
 *   /en/reader/<alias>?c=<chapterId> and the title sits in the
 *   sibling .bn_book__chapters-item-title span. The legacy
 *   reader-page <select name="chapter"> the crawler used no
 *   longer exists — reader pages now show an access notice to
 *   anonymous visitors.
 * - Chapter body: POST https://booknet.com/reader/get-page with
 *   form fields chapterId + page + the CSRF field returns JSON
 *   {status, chapterTitle, data, totalPages}; data is the
 *   chapter HTML for that page and the pages are concatenated.
 *   Unlike litnet.com, booknet.com enforces the CSRF token: the
 *   token (meta[name=csrf-token] / csrf-param) is read from the
 *   chapter page the app already downloaded and sent back as
 *   the X-CSRF-Token header and form field; the shared
 *   NetworkClient cookie jar carries the session cookie the
 *   token is bound to (a token sent without its session cookie
 *   is rejected with HTTP 400). Paid chapters answer
 *   {"status":0,...} and yield empty text.
 *
 * [getChapterText] / [getChapterTitle] only read the csrf meta
 * and the chapter id (the ?c= parameter) from the fetched
 * reader page and re-request the body from the get-page
 * endpoint (the Litnet pattern), so neither mutates the shared
 * document.
 */
class BookNet(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "booknet"
    override val nameStrId = R.string.source_name_booknet
    override val baseUrl = "https://booknet.com/"
    override val catalogUrl = "https://booknet.com/en/top/all"
    override val iconUrl = "https://booknet.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val getPageUrl = "https://booknet.com/reader/get-page"

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog cards (.bn_book-genre) and search cards
     * (.bn_book-row__vert-item) use different markup; a listing
     * page carries only one of the two kinds.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val catalogCards = doc.select(".bn_book-genre")
        if (catalogCards.isNotEmpty()) {
            return catalogCards.toList().mapNotNull { card ->
                val titleLink = card.selectFirst(".bn_book-genre__title-link")
                    ?: return@mapNotNull null
                val title = titleLink.text().trim()
                val url = resolve(titleLink.attr("href"))
                if (title.isBlank() || url.isBlank()) return@mapNotNull null
                val cover = card.selectFirst(".bn_book-genre__image img")
                    ?.attr("src").orEmpty()
                val description = card.selectFirst(".bn_book-genre__annotation")
                    ?.let { TextExtractor.get(it) }.orEmpty()
                BookResult(
                    title = title,
                    url = url,
                    coverImageUrl = resolve(cover),
                    description = description
                )
            }
        }
        return doc.select(".bn_book-row__vert-item").toList().mapNotNull { card ->
            val titleLink = card.selectFirst(".bn_book-row__vert-item-title a")
                ?: return@mapNotNull null
            val title = titleLink.text().trim()
            val url = resolve(titleLink.attr("href"))
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            val cover = card.selectFirst(".bn_book-row__vert-item-image img")
                ?.attr("src").orEmpty()
            BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl?page=$page"
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
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
                val url = "https://booknet.com/en/search".toUrlBuilderSafe()
                    .add("q", input)
                    .add("type", "book")
                    .add("page", (index + 1).toString())
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst(".bn_book__header-image img")?.attr("src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { resolve(it) }
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                        ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst(".bn_book__about-content")
                    ?.let { TextExtractor.get(it) }
                    ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // The book page lists every chapter in reading order;
            // the anchor itself is empty and the title lives in a
            // sibling span inside the same item.
            for (item in doc.select(".bn_book__chapters-item")) {
                val link = item.selectFirst(".bn_book__chapters-item-link")
                    ?: continue
                val url = resolve(link.attr("href"))
                if (url.isBlank() || !seen.add(url)) continue
                val title = item.selectFirst(".bn_book__chapters-item-title")
                    ?.text()?.trim().orEmpty()
                if (title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
            chapters
        }
    }

    private fun chapterIdFromUrl(url: String): String? =
        Regex("[?&]c=(\\d+)").find(url)?.groupValues?.get(1)

    private suspend fun getChapterPageJson(
        doc: Document,
        chapterId: String,
        page: Int
    ): JsonObject? {
        // booknet.com (unlike litnet.com) rejects the get-page
        // POST without the session's CSRF token; the reader page
        // the app downloaded carries it in a meta tag, and the
        // shared cookie jar supplies the session cookie it is
        // bound to.
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")
            ?.attr("content")?.takeIf { it.isNotBlank() } ?: return null
        val csrfParam = doc.selectFirst("meta[name=csrf-param]")
            ?.attr("content")?.takeIf { it.isNotBlank() } ?: "_csrf"
        val headers = Headers.Builder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("X-CSRF-Token", csrfToken)
            .add("Referer", doc.location())
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val request = postRequest(getPageUrl, headers = headers)
            .postPayload {
                add("chapterId", chapterId)
                add("page", page.toString())
                add(csrfParam, csrfToken)
            }
        return runCatching {
            networkClient.call(request).toJson().asJsonObject
        }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.int(key: String, default: Int): Int =
        if (has(key) && !get(key).isJsonNull) get(key).asInt else default

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val chapterId = chapterIdFromUrl(doc.location())
                ?: return@withContext null
            val json = getChapterPageJson(doc, chapterId, page = 1)
                ?: return@withContext null
            if (json.int("status", 0) != 1) return@withContext null
            json.str("chapterTitle").takeIf { it.isNotBlank() }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val chapterId = chapterIdFromUrl(doc.location())
                ?: return@withContext ""
            val firstPage = getChapterPageJson(doc, chapterId, page = 1)
                ?: return@withContext ""
            // Paid chapters answer status 0 with an availability
            // message in data; they yield empty text, not an error.
            if (firstPage.int("status", 0) != 1) return@withContext ""
            val totalPages = maxOf(firstPage.int("totalPages", 1), 1)
            val html = StringBuilder(firstPage.str("data"))
            for (page in 2..totalPages) {
                val json = getChapterPageJson(doc, chapterId, page) ?: continue
                if (json.int("status", 0) == 1) html.append(json.str("data"))
            }
            if (html.isBlank()) return@withContext ""
            val fragment = Jsoup.parseBodyFragment(html.toString(), baseUrl)
            TextExtractor.get(fragment.body())
        }
}
