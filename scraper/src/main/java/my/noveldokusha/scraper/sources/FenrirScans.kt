package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toJson
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
 * Fenrir Scans — https://fenrirscans.com (Turkish)
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * catalog, book and chapter pages plus the theme's own main.js,
 * cross-checked against the lightnovel-crawler project's FenrirScans
 * source — the site has since been rebuilt on the "StarLab" theme,
 * so the crawler's MangaStream selectors and its -bolum-<n> chapter
 * URL pattern no longer apply; it is a novels-only site now, books
 * live under /novel/<slug>/ and chapters under /chapter/<slug>/):
 * - Catalog: /novel/, paginated /novel/page/<n>/, 24 books per page
 *   (17 pages, ~407 books). Items are .novel-card-dark cards: title
 *   link in .novel-card-title-dark a, cover in img.wp-post-image
 *   (src is the real cover). A page past the end returns an empty
 *   grid, which marks the last page.
 * - Search: there is no server-rendered search page (the ?s= query
 *   is ignored); the site's own search is the WordPress AJAX
 *   autocomplete POST /wp-admin/admin-ajax.php with
 *   action=live_search_novels, search_term=<q>, post_type=novel and
 *   the starlab_ajax nonce — it answers at most 5 results with
 *   {title, link, thumbnail}. The search is Turkish-character
 *   sensitive ("tanri" does not match "tanrı"); the query is passed
 *   through unchanged, exactly as the site's own search box does.
 * - Book page /novel/<slug>/: title in h1, cover in
 *   img.wp-post-image, synopsis in .description-full (fallback
 *   .description-short). The chapter list is NOT fully rendered
 *   server-side (only the newest few chapters are); the full list
 *   loads through the same admin-ajax endpoint with
 *   action=load_all_chapters, novel_id=<id>, page=<n>,
 *   per_page=50, sort_order=oldest — the novel id, total count and
 *   page size are carried by the book page's
 *   .chapters-pagination-nav data attributes, and each response
 *   page carries a structured all_chapters array ({title, url})
 *   already oldest first.
 * - Both AJAX actions reject requests without the nonce (HTTP 403),
 *   so the nonce is scraped from the starlab_ajax script block of a
 *   fetched page (the book page itself for chapter lists, the home
 *   page for searches).
 * - Chapter page: title in h1.chapter-title (outside the body); the
 *   story is div.chapter-content — a repeated <h1> followed by clean
 *   <p> paragraphs, no ads/scripts/links inside (verified on the
 *   first and last chapters of the test book).
 */
class FenrirScans(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "fenrirscans"
    override val nameStrId = R.string.source_name_fenrirscans
    override val baseUrl = "https://fenrirscans.com/"
    override val catalogUrl = "https://fenrirscans.com/novel/"
    override val iconUrl = "https://fenrirscans.com/favicon.ico"
    override val language = LanguageCode.TURKISH

    private val ajaxUrl = "https://fenrirscans.com/wp-admin/admin-ajax.php"

    private fun xhrHeaders(referer: String): Headers = Headers.Builder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", referer)
        .add("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun JsonObject.int(key: String, default: Int): Int =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asInt }
            .getOrDefault(default) else default

    /**
     * The StarLab theme localizes a starlab_ajax script block with a
     * WordPress nonce on every page; both AJAX actions below reject
     * requests that omit it (403), so it is scraped from a page the
     * source already fetched. The regex is anchored to the
     * starlab_ajax block: other widgets on the page carry their own
     * unrelated nonces earlier in the markup.
     */
    private fun extractNonce(doc: Document): String? {
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val anchored = Regex(
            "starlab_ajax\\s*=\\s*\\{[^}]*?\"nonce\"\\s*:\\s*\"([a-f0-9]+)\""
        )
        return anchored.find(scripts)?.groupValues?.get(1)
            ?: anchored.find(doc.html())?.groupValues?.get(1)
    }

    private suspend fun ajaxAction(
        referer: String,
        fields: Map<String, String>
    ): JsonObject? {
        val request = postRequest(ajaxUrl, headers = xhrHeaders(referer))
            .postPayload {
                for ((key, value) in fields) add(key, value)
            }
        return runCatching {
            networkClient.call(request).toJson().asJsonObject
        }.getOrNull()
    }

    /**
     * Catalog and (potentially) other listing pages share the card
     * markup: a .novel-card-dark whose .novel-card-title-dark anchor
     * links to /novel/<slug>/ and whose img.wp-post-image carries
     * the cover in src.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in doc.select(".novel-card-dark").toList()) {
            val link = card.selectFirst(".novel-card-title-dark a[href]")
                ?: card.selectFirst("a[href*=/novel/]")
                ?: continue
            val href = link.attr("href")
            if (!href.contains("/novel/") || href.endsWith("/novel/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val img = card.selectFirst("img.wp-post-image") ?: card.selectFirst("img")
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

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.chapter-title")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst(".chapter-content h1")?.text()?.trim()
                    ?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".chapter-content-wrapper")
                ?: doc.selectFirst("#readerarea")
                ?: return@withContext ""
            // Work on a clone: drop the repeated chapter-title <h1>
            // and any non-story elements without touching the
            // document getChapterTitle reads afterwards.
            val body = container.clone()
            body.select("h1, script, style, ins, iframe, .ad-zone").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst("img.wp-post-image")
                    ?: doc.selectFirst("meta[property=og:image]")
                img?.let {
                    if (it.tagName() == "meta") resolve(it.attr("content"))
                    else resolve(it.attr("src").ifBlank { it.attr("data-src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".description-full")
                    ?: doc.selectFirst(".description-short")
                    ?: doc.selectFirst(".novel-description")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val nav = doc.selectFirst(".chapters-pagination-nav")
            val novelId = nav?.attr("data-novel-id")?.takeIf { it.isNotBlank() }
            val nonce = extractNonce(doc)
            if (novelId != null && nonce != null) {
                val perPage = nav.attr("data-per-page").toIntOrNull() ?: 50
                val total = nav.attr("data-total").toIntOrNull() ?: 0
                val totalPages = if (total > 0)
                    (total + perPage - 1) / perPage else 1
                val chapters = mutableListOf<ChapterResult>()
                val seen = mutableSetOf<String>()
                for (page in 1..totalPages) {
                    val json = ajaxAction(bookUrl, mapOf(
                        "action" to "load_all_chapters",
                        "novel_id" to novelId,
                        "page" to page.toString(),
                        "per_page" to perPage.toString(),
                        "sort_order" to "oldest",
                        "nonce" to nonce
                    )) ?: continue
                    val data = json.getAsJsonObject("data") ?: continue
                    val pageChapters = parseAjaxChapters(data)
                    if (pageChapters.isEmpty()) break
                    for (chapter in pageChapters) {
                        if (seen.add(chapter.url)) chapters += chapter
                    }
                }
                if (chapters.isNotEmpty()) return@tryConnect chapters
            }
            // Fallback: whatever the book page rendered server-side
            // (only the newest few chapters, listed newest first).
            parseRenderedChapters(doc).reversed()
        }
    }

    /**
     * One load_all_chapters response page: the structured
     * all_chapters array ({title, url}) is authoritative; the
     * chapters HTML fragment is parsed the same way if the array is
     * ever dropped.
     */
    private fun parseAjaxChapters(data: JsonObject): List<ChapterResult> {
        val chapters = mutableListOf<ChapterResult>()
        val array = data.getAsJsonArray("all_chapters")
        if (array != null) {
            for (item in array) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val url = resolve(obj.str("url"))
                val title = obj.str("title").trim()
                if (url.isBlank() || title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
        }
        if (chapters.isNotEmpty()) return chapters
        val html = data.str("chapters")
        if (html.isBlank()) return chapters
        val fragment = Jsoup.parseBodyFragment(html, baseUrl)
        return parseRenderedChapters(fragment)
    }

    private fun parseRenderedChapters(root: org.jsoup.nodes.Element): List<ChapterResult> {
        val items = root.select(".chapters-list .chapter-item")
        val scoped = if (items.isNotEmpty()) items else root.select(".chapter-item")
        return scoped.toList().mapNotNull { item ->
            val link = item.selectFirst("a.chapter-title-link[href]")
                ?: item.selectFirst("a[href*=/chapter/]")
                ?: return@mapNotNull null
            val title = link.text().trim()
            if (title.isBlank()) return@mapNotNull null
            ChapterResult(title = title, url = resolve(link.attr("href")))
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}page/$page/"

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
                if (input.isBlank() || index > 0)
                    return@tryConnect PagedList.createEmpty(index = index)
                // The AJAX search needs the starlab_ajax nonce; the
                // home page carries it like every other page.
                val homeDoc = networkClient.get(baseUrl).toDocument()
                val nonce = extractNonce(homeDoc)
                    ?: return@tryConnect PagedList.createEmpty(index = index)
                val json = ajaxAction(baseUrl, mapOf(
                    "action" to "live_search_novels",
                    "search_term" to input,
                    "post_type" to "novel",
                    "nonce" to nonce
                )) ?: return@tryConnect PagedList.createEmpty(index = index)
                val results = json.getAsJsonObject("data")
                    ?.getAsJsonArray("results")
                val books = mutableListOf<BookResult>()
                val seen = mutableSetOf<String>()
                if (results != null) {
                    for (item in results) {
                        if (!item.isJsonObject) continue
                        val obj = item.asJsonObject
                        val url = resolve(obj.str("link"))
                        val title = obj.str("title").trim()
                        if (url.isBlank() || title.isBlank()) continue
                        if (!seen.add(url)) continue
                        books += BookResult(
                            title = title,
                            url = url,
                            coverImageUrl = resolve(obj.str("thumbnail"))
                        )
                    }
                }

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
