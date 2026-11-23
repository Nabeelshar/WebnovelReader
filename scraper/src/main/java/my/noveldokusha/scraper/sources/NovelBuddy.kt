package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * NovelBuddy — https://novelbuddy.me
 *
 * No lightnovel-crawler source exists for this site; everything
 * below was derived and verified live on 2026-11-23. The site is a
 * Next.js app (the "flux" theme, shared with mangabuddy.com) backed
 * by a public JSON API at https://api.novelbuddy.me. The bare
 * homepage is a static SEO landing page — the real app lives under
 * /home, /latest, /search, /<book-slug>, …
 *
 * - Catalog: GET /latest?page=<n> (server-rendered), 24 book cards
 *   per page in <article> elements, pages verified disjoint, pager
 *   links onward as ?page=<n+1>. In each card the first anchor is
 *   the cover link to /<book-slug> and carries the cover <img>
 *   (src on rs.novelbuddy.me/covers/…, alt = full book title).
 * - Search: the /search page renders results client-side, so the
 *   API is used instead: GET api.novelbuddy.me/titles/search
 *   ?q=<query>&page=<n> -> {data: {items: [{name, url, cover}],
 *   pagination: {has_next, total_pages, …}}}, 20 items per page.
 *   (The "keyword" parameter is ignored by this endpoint; "q" is
 *   the real one.)
 * - Book page /<book-slug>: title in <h1>, cover in og:image, and
 *   the full record (name / cover / summary) in the embedded
 *   __NEXT_DATA__ JSON at props.pageProps.initialManga. The meta
 *   description is the summary truncated by the site, used only as
 *   a fallback.
 * - Chapter list: the book page itself only embeds the newest 50
 *   chapters. The full list comes from the API in two calls:
 *   GET /titles/by-slug/<slug> -> {data: {new_url:
 *   "/titles/<hsid>-<slug>"}} resolves the slug to the title's
 *   alphanumeric id, then GET /titles/<hsid>/chapters returns
 *   EVERY chapter in one response ({data: {chapters: [{name, url,
 *   number}]}}), newest first — reversed here (2,727 chapters
 *   verified for the test book, numbers strictly descending in the
 *   response).
 * - Chapter page /<book-slug>/<chapter-slug>: the story is
 *   .novel-tts-content (clean <p> text, no ads/scripts inside; the
 *   text matches the page's initialChapter.content exactly). It
 *   opens with an <h3> repeating the chapter title — removed on a
 *   clone so getChapterTitle can still read the original document.
 *   The chapter title itself is read from the __NEXT_DATA__ JSON
 *   (props.pageProps.initialChapter.name), with that <h3> as
 *   fallback.
 */
class NovelBuddy(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelbuddy"
    override val nameStrId = R.string.source_name_novelbuddy
    override val baseUrl = "https://novelbuddy.me/"
    override val catalogUrl = "https://novelbuddy.me/latest"
    override val language = LanguageCode.ENGLISH

    private val apiBaseUrl = "https://api.novelbuddy.me"

    private val apiHeaders: Headers = Headers.Builder()
        .add("Accept", "application/json, text/plain, */*")
        .add("Referer", baseUrl)
        .build()

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private suspend fun getApiJson(url: String): JsonObject =
        networkClient.call(getRequest(url, headers = apiHeaders)).toJson().asJsonObject

    /** The book slug is the single path segment of the book URL. */
    private fun slugFromUrl(bookUrl: String): String =
        bookUrl.removePrefix(baseUrl).substringBefore("/").substringBefore("?")

    /**
     * Parses the page's embedded Next.js JSON (__NEXT_DATA__) and
     * returns its pageProps object, or null when absent/broken.
     */
    private fun pageProps(doc: Document): JsonObject? {
        val raw = doc.selectFirst("script#__NEXT_DATA__")?.data()?.trim()
            ?: doc.select("script").firstOrNull { it.data().contains("\"pageProps\"") }?.data()
            ?: return null
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonObject("props")
                ?.getAsJsonObject("pageProps")
        }.getOrNull()
    }

    /**
     * Catalog cards: one <article> per book. The first anchor is the
     * cover link to /<book-slug> and wraps the cover <img>, whose
     * alt text is the full book title (the visible title anchor can
     * be CSS-truncated, so the alt / aria-label wins).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("article")) {
            val link = item.select("a[href]").toList()
                .firstOrNull { isBookHref(it.attr("href")) } ?: continue
            val url = resolve(link.attr("href"))
            if (!seen.add(url)) continue
            val img = link.selectFirst("img") ?: item.selectFirst("img")
            val title = img?.attr("alt")?.trim().orEmpty()
                .ifBlank { link.attr("aria-label").trim() }
                .ifBlank {
                    item.select("a[href]").toList()
                        .firstOrNull { it.attr("href") == link.attr("href") && it.text().isNotBlank() }
                        ?.text()?.trim().orEmpty()
                }
            if (title.isBlank()) continue
            val cover = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    /** Book links are a single path segment (/<slug>); chapter
     *  links add a second segment and static pages are excluded. */
    private fun isBookHref(href: String): Boolean {
        if (!href.startsWith("/") || href.startsWith("//")) return false
        val segment = href.removePrefix("/").substringBefore("?")
        if (segment.isEmpty() || segment.contains("/")) return false
        return segment !in staticPages
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val fromJson = pageProps(doc)
                ?.getAsJsonObject("initialChapter")
                ?.str("name")
                ?.takeIf { it.isNotBlank() }
            fromJson
                ?: doc.selectFirst(".novel-tts-content h3")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content = doc.selectFirst(".novel-tts-content")
                ?: doc.selectFirst("article.novel-reader-content")
                ?: return@withContext ""
            // Clean a clone: the <h3> inside repeats the chapter
            // title, which getChapterTitle still needs from this
            // same document afterwards.
            val clone = content.clone()
            clone.select("script, iframe, ins, .ads, h3").remove()
            TextExtractor.get(clone)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
                    ?: pageProps(doc)?.getAsJsonObject("initialManga")
                        ?.str("cover")?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                pageProps(doc)?.getAsJsonObject("initialManga")
                    ?.str("summary")?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")
                        ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val slug = slugFromUrl(bookUrl)
            // Resolve the slug to the title's alphanumeric id.
            val bySlug = getApiJson("$apiBaseUrl/titles/by-slug/$slug")
            val newUrl = bySlug.getAsJsonObject("data")?.str("new_url").orEmpty()
            val hsid = newUrl.removePrefix("/titles/").substringBefore("-")
                .takeIf { it.isNotBlank() }
                ?: return@tryConnect emptyList()
            // One call returns every chapter, newest first.
            val json = getApiJson("$apiBaseUrl/titles/$hsid/chapters")
            val array = json.getAsJsonObject("data")
                ?.getAsJsonArray("chapters") ?: return@tryConnect emptyList()
            array.map { it.asJsonObject }
                .map {
                    ChapterResult(
                        title = it.str("name"),
                        url = resolve(it.str("url"))
                    )
                }
                .reversed()
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "$catalogUrl?page=$page"
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").toList().any {
                    it.attr("href").contains("?page=${page + 1}")
                }
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNext
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
                val url = "$apiBaseUrl/titles/search".toUrlBuilderSafe()
                    .add("q", input)
                    .add("page", (index + 1).toString())
                    .toString()
                val data = getApiJson(url).getAsJsonObject("data")
                    ?: return@tryConnect PagedList.createEmpty(index = index)
                val books = data.getAsJsonArray("items")?.map { it.asJsonObject }
                    ?.map {
                        BookResult(
                            title = it.str("name"),
                            url = resolve(it.str("url")),
                            coverImageUrl = resolve(it.str("cover"))
                        )
                    } ?: emptyList()
                val hasNext = data.getAsJsonObject("pagination")
                    ?.takeIf { it.has("has_next") && !it.get("has_next").isJsonNull }
                    ?.get("has_next")?.asBoolean ?: false
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = !hasNext
                )
            }
        }

    private companion object {
        val staticPages = setOf(
            "home", "latest", "ranking", "browse", "search", "genres",
            "genre", "status", "auth", "lists", "mtl-novels", "mtl",
            "az-list", "newest", "popular", "schedule", "community",
            "discussions", "faq", "filters", "me", "about", "dmca",
            "contact", "terms", "privacy-policy", "hall-of-fame",
            "trending"
        )
    }
}
