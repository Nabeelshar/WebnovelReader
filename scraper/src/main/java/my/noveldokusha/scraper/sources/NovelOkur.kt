package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
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
import okhttp3.FormBody
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Novel Okur — https://novelokur.com.tr
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * catalog, search, book and chapter pages). The site runs a custom
 * WordPress theme ("Novel-Okur-V3"); it matches neither the Madara
 * nor the NovelFull template, and lightnovel-crawler has no source
 * for it, so everything below was derived from the live pages:
 * - Catalog: /novel/ renders the first 24 books server-side as
 *   a.novel-card-horizontal cards (title in h3.title, cover in
 *   .cover img — src is the real cover URL). The classic WP route
 *   /novel/page/<n>/ is ignored by the theme (it always renders
 *   page 1); further pages load through the site's infinite-scroll
 *   AJAX: POST /wp-admin/admin-ajax.php with
 *   action=load_more_novels, page=<n>, orderby=name and the nonce
 *   embedded in the catalog page's load-more script (the endpoint
 *   rejects requests without it). The JSON reply is
 *   {success, data:{html, is_last_page}} and its html holds the
 *   same card markup. The first page's grid (#novel-grid) carries
 *   a data-max-pages attribute with the total page count.
 * - Search: the header form is GET /novel/?search=<query>; results
 *   reuse the card markup and paginate through the same AJAX with
 *   the search field set.
 * - Book page /novel/<slug>/: title in h1.novel-title, cover in
 *   og:image, synopsis in div.novel-synopsis. The full chapter
 *   list is server-rendered as a.chapter-item anchors inside
 *   .chapter-list (title in the .chapter-title span), listed
 *   NEWEST first — reversed here so the app gets chapters oldest
 *   to newest.
 * - Chapter page /chapter/<slug>/: the title is h1.chapter-title
 *   and the story is .chapter-content, a clean run of <p> plus an
 *   <h3> repeating the title (removed on a clone). Premium
 *   chapters are locked behind the site's diamond currency: their
 *   pages only serve a truncated preview inside
 *   .paywall-content-preview plus a login notice, so they yield
 *   empty text here, like locked chapters in other sources.
 */
class NovelOkur(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelokur"
    override val nameStrId = R.string.source_name_novelokur
    override val baseUrl = "https://novelokur.com.tr/"
    override val catalogUrl = "https://novelokur.com.tr/novel/"
    override val iconUrl = "https://novelokur.com.tr/favicon.ico"
    override val language = LanguageCode.TURKISH

    private val ajaxUrl = "https://novelokur.com.tr/wp-admin/admin-ajax.php"

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search share one card markup: an
     * a.novel-card-horizontal whose href is the book page, with
     * the title in h3.title and the cover in .cover img (its src
     * is the real cover; there is no lazy placeholder swap).
     */
    private fun parseBookList(root: Element): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in root.select("a.novel-card-horizontal[href]").toList()) {
            val url = resolve(card.attr("href"))
            if (!url.contains("/novel/") || !seen.add(url)) continue
            val title = card.selectFirst("h3.title")?.text()?.trim()
                ?.ifBlank { null }
                ?: card.text().trim()
            if (title.isBlank()) continue
            val img = card.selectFirst(".cover img") ?: card.selectFirst("img")
            val cover = img?.attr("src")?.ifBlank { null }
                ?: img?.attr("data-src")
                ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    /** Total catalog pages, from the grid's data-max-pages. */
    private fun maxPages(doc: Document): Int =
        doc.selectFirst("#novel-grid")?.attr("data-max-pages")
            ?.toIntOrNull() ?: 1

    /**
     * The load-more AJAX is protected by a nonce that the catalog
     * page embeds in its infinite-scroll script, right after the
     * load_more_novels action in the same FormData block.
     */
    private fun extractNonce(doc: Document): String? {
        val script = doc.select("script").toList()
            .firstOrNull { it.data().contains("load_more_novels") }
            ?.data() ?: return null
        val tail = script.substringAfter("load_more_novels")
        return Regex("""formData\.append\('nonce',\s*'([0-9a-fA-F]+)'\)""")
            .find(tail)?.groupValues?.get(1)
    }

    /**
     * One page of books from the site's load-more AJAX (used for
     * every page after the server-rendered first one, for both
     * the catalog and search). Returns the books and whether the
     * site reports this as the last page.
     */
    private suspend fun fetchAjaxPage(
        page: Int,
        search: String,
        nonce: String
    ): Pair<List<BookResult>, Boolean> {
        val body = FormBody.Builder()
            .add("action", "load_more_novels")
            .add("page", page.toString())
            .add("status", "")
            .add("language", "")
            .add("orderby", "name")
            .add("genre", "")
            .add("search", search)
            .add("nonce", nonce)
            .build()
        val headers = Headers.Builder()
            .add("Referer", catalogUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val json = networkClient.call(
            postRequest(ajaxUrl, headers = headers, body = body)
        ).toJson().asJsonObject
        val data = json.getAsJsonObject("data") ?: return emptyList<BookResult>() to true
        val html = data.get("html")?.asString.orEmpty()
        if (html.isBlank()) return emptyList<BookResult>() to true
        val isLast = data.get("is_last_page")?.asBoolean ?: false
        val fragment = Jsoup.parseBodyFragment(html, baseUrl)
        return parseBookList(fragment) to isLast
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.chapter-title")?.text()?.trim()
                // Locked chapter pages prefix the title with a lock.
                ?.removePrefix("🔒")?.trim()
                ?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // Premium chapters only serve a truncated preview plus
            // a login notice — treat them as locked (empty text).
            if (doc.selectFirst(".paywall-content-preview") != null)
                return@withContext ""
            val container = doc.selectFirst(".chapter-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards. The h1 title lives
            // outside this container, but an <h3> inside repeats
            // it and is dropped here.
            val body = container.clone()
            body.select("h3, script, iframe").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val og = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")?.ifBlank { null }
                og ?: doc.selectFirst("img.wp-post-image")?.let {
                    resolve(it.attr("src").ifBlank { it.attr("data-src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".novel-synopsis")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val scoped = doc.select(".chapter-list a.chapter-item[href]")
            val anchors = if (scoped.isNotEmpty()) scoped
            else doc.select("a.chapter-item[href]")
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            for (anchor in anchors.toList()) {
                val url = resolve(anchor.attr("href"))
                if (!url.contains("/chapter/") || !seen.add(url)) continue
                // The .chapter-title span holds the title; the
                // anchor's full text would also pull in the date
                // span. Locked chapters carry a lock-icon <img>
                // inside the span, which contributes no text.
                val title = anchor.selectFirst(".chapter-title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: anchor.ownText().trim()
                if (title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
            // The site lists chapters newest first; the app expects
            // them oldest to newest.
            chapters.reverse()
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                if (page == 1) {
                    val doc = networkClient.get(catalogUrl).toDocument()
                    val books = parseBookList(doc)
                    return@tryConnect PagedList(
                        list = books,
                        index = index,
                        isLastPage = books.isEmpty() || page >= maxPages(doc)
                    )
                }
                // Pages past the first only exist through the
                // load-more AJAX, which needs a fresh nonce from
                // the catalog page.
                val nonceDoc = networkClient.get(catalogUrl).toDocument()
                val nonce = extractNonce(nonceDoc)
                    ?: return@tryConnect PagedList.createEmpty(index = index)
                val (books, isLast) = fetchAjaxPage(page, "", nonce)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = isLast || books.isEmpty()
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
                val searchUrl = baseUrl.toUrlBuilderSafe()
                    .addPath("novel")
                    .add("search", input)
                    .toString()
                if (page == 1) {
                    val doc = networkClient.get(searchUrl).toDocument()
                    val books = parseBookList(doc)
                    return@tryConnect PagedList(
                        list = books,
                        index = index,
                        isLastPage = books.isEmpty() || page >= maxPages(doc)
                    )
                }
                val nonceDoc = networkClient.get(searchUrl).toDocument()
                val nonce = extractNonce(nonceDoc)
                    ?: return@tryConnect PagedList.createEmpty(index = index)
                val (books, isLast) = fetchAjaxPage(page, input, nonce)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = isLast || books.isEmpty()
                )
            }
        }
}
