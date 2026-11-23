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
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Novel France — https://novelfrance.fr
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * browse, search, book and chapter pages, plus the site's own JS
 * bundles; lightnovel-crawler has no crawler for this domain):
 * - The site is a Next.js app backed by a JSON API proxied under
 *   /api. Books live at /novel/<slug>, chapters at
 *   /novel/<slug>/chapter-<number> (numbers may be decimal, e.g.
 *   chapter-3215.1, and most books start at a chapter-0 prologue).
 * - Catalog: /browse renders 24 book cards per page and paginates
 *   as /browse?page=<n> (855 books at verification time; the last
 *   page is the short/empty one). Each card is the book anchor
 *   itself: cover in its <img src> (/uploads/covers/...), title in
 *   the .line-clamp-2 span (the img alt carries the same title).
 * - Search: GET /search?q=<query> server-renders the same cards in
 *   a single page.
 * - Book page: title in <h1>, cover in the og:image meta, synopsis
 *   in the single <p class="whitespace-pre-line ..."> (visually
 *   line-clamped in the browser, full text in the HTML).
 * - Chapter list: the book page only embeds the newest 50 chapters
 *   and paginates the rest client-side through
 *   GET /api/chapters/<novel-slug>?skip=<n>&take=<m>&order=asc|desc,
 *   which answers {chapters: [{chapterNumber, title, slug, ...}],
 *   total, skip, take, hasMore}. take is capped at 100 server-side.
 *   order=asc returns the prologue (chapterNumber 0) first, i.e.
 *   oldest to newest as the app expects. Premium chapters are
 *   listed like the others; their pages may show a lock notice
 *   instead of the text until they become free.
 * - Chapter page: title in <h1>; the story is div.chapter-content,
 *   whose children are div.para-row wrappers each holding one <p>
 *   plus a .para-action comment button (stripped here on a clone,
 *   so the document stays intact for getChapterTitle).
 */
class NovelFrance(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelfrance"
    override val nameStrId = R.string.source_name_novelfrance
    override val baseUrl = "https://novelfrance.fr/"
    override val catalogUrl = "https://novelfrance.fr/browse"
    override val language = LanguageCode.FRENCH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** True for book links (/novel/<slug>), false for chapter links. */
    private fun isBookHref(href: String): Boolean {
        val path = href.substringBefore("?").substringBefore("#")
            .removePrefix("https://novelfrance.fr")
            .removePrefix("http://novelfrance.fr")
        if (!path.startsWith("/novel/")) return false
        val slug = path.removePrefix("/novel/").trim('/')
        return slug.isNotEmpty() && !slug.contains("/")
    }

    /**
     * Catalog and search results share one card markup: the book
     * anchor itself, with the cover in its <img> and the title in
     * the .line-clamp-2 span (img alt as fallback).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (link in doc.select("a[href]")) {
            val href = link.attr("href")
            if (!isBookHref(href)) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val img = link.selectFirst("img")
            val title = link.selectFirst(".line-clamp-2")?.text()?.trim()
                ?.ifBlank { null }
                ?: img?.attr("alt")?.trim()?.ifBlank { null }
                ?: link.text().trim()
            if (title.isBlank()) continue
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
            doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".chapter-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select(".para-action, button, script, iframe, ins, style").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.ifBlank { null }
                    ?: doc.selectFirst("img[src*=/uploads/covers/]")?.attr("src")
                        ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("p.whitespace-pre-line")
                if (el != null) TextExtractor.get(el)
                else doc.selectFirst("meta[property=og:description]")
                    ?.attr("content")?.ifBlank { null }
            }
        }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val novelSlug = bookUrl.substringBefore("?").trimEnd('/')
                .substringAfter("/novel/").substringBefore("/")
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            var skip = 0
            // take is capped at 100 by the API.
            while (true) {
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("api", "chapters", novelSlug)
                    .add("skip", skip)
                    .add("take", CHAPTER_PAGE_SIZE)
                    .add("order", "asc")
                    .toString()
                val json = networkClient.call(getRequest(url))
                    .toJson().asJsonObject
                val items = json.getAsJsonArray("chapters") ?: break
                for (item in items) {
                    val obj = item.asJsonObject
                    val chapterSlug = obj.str("slug")
                    if (chapterSlug.isBlank()) continue
                    val chapterUrl = "https://novelfrance.fr/novel/" +
                        "$novelSlug/$chapterSlug"
                    if (!seen.add(chapterUrl)) continue
                    val title = obj.str("title").ifBlank {
                        "Chapitre " + chapterSlug.removePrefix("chapter-")
                    }
                    chapters += ChapterResult(title = title, url = chapterUrl)
                }
                val hasMore = json.get("hasMore")
                    ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                if (!hasMore || items.size() == 0) break
                skip += items.size()
            }
            chapters
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
                    // 24 cards per full page; a short or empty page
                    // is the end of the catalog.
                    isLastPage = books.size < CATALOG_PAGE_SIZE
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
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .add("q", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private companion object {
        const val CATALOG_PAGE_SIZE = 24
        const val CHAPTER_PAGE_SIZE = 100
    }
}
