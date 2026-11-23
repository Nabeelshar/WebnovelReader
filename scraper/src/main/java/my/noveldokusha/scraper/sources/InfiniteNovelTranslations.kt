package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Infinite Novel Translations — https://infinitenoveltranslations.net
 *
 * WordPress translation-group site (Blogus/Blogza theme). Site
 * structure verified live 2026-11-23 (curl fetches of the home page,
 * the category pages, six book pages and chapter pages,
 * cross-checked against the lightnovel-crawler project's
 * InfiniteNovelTranslations source):
 * - There is no paginated book listing. The full project index is
 *   the site header menu (ul#menu-primary, present on every page):
 *   one text anchor per book, grouped under the Light Novels /
 *   Web Novels / Completed / Dropped headings. Single-segment
 *   paths are books, plus the teaser books under /teasers/<slug>/;
 *   the headings themselves and the "Novel Illustrations"
 *   subpages are not books. The menu is text-only, so covers are
 *   enriched from the category showcase pages (/light-novels/,
 *   /web-novels/, /completed/, /dropped/), where each book also
 *   has an image anchor inside <main>; the remaining books get
 *   their cover per book from getBookCoverImageUrl.
 * - The site's own search returns chapter posts, which are useless
 *   as book results, so catalog search filters the menu list by
 *   title instead (same approach as the DarkTranslation source).
 * - Book page: title in h1.entry-title (outside the content), the
 *   content root is div.hentry (the old .entry-content class the
 *   crawler used no longer exists). The chapter list is the set of
 *   same-site text anchors inside the content, in document order —
 *   that is the site's reading order (volumes in order, chapters
 *   ascending inside each volume; "Idle Talk" side chapters sit
 *   between numbered chapters). Glossary / novel-illustrations
 *   subpages, upload links and the book page itself are filtered
 *   out. Some older volumes of a few books are hosted on sister
 *   sites (e.g. Atelier Tanaka vols. 1-7 on rhextranslations.com)
 *   and are therefore not part of this source's lists, exactly as
 *   the site presents them. Merged pages like "Chapters 1 - 4"
 *   are single pages holding several chapters and are kept as one
 *   entry, as the site lists them.
 * - Book-page hrefs are messy: mixed http/https and even glued
 *   hosts ("infinitenoveltranslations.net<slug>/..."). All URLs
 *   are normalized (host repaired, https forced, trailing slash
 *   canonicalized) before use.
 * - Description: the book page opens with synopsis paragraphs
 *   followed by the table of contents, so the description is the
 *   text of the paragraphs before the first paragraph that
 *   carries a chapter link.
 * - Cover: the first /wp-content/uploads/ image inside the book
 *   page content (og:image is a generic site banner, not used).
 * - Chapter page: title in h1.entry-title (outside .hentry, so
 *   getChapterText never touches it); the story is the .hentry
 *   content, with the "<- Previous Chapter | Table of Contents |
 *   Next Chapter ->" navigation paragraphs (top and bottom)
 *   removed on a clone. Translator notes are kept, as the
 *   reference crawler keeps them.
 * - Note: the site intermittently serves a "One moment, please..."
 *   bot-check page to plain HTTP clients; requests usually succeed
 *   again on a later attempt.
 */
class InfiniteNovelTranslations(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "infinitenoveltranslations"
    override val nameStrId = R.string.source_name_infinitenoveltranslations
    override val baseUrl = "https://infinitenoveltranslations.net/"
    override val catalogUrl = "https://infinitenoveltranslations.net/"
    override val iconUrl = "https://infinitenoveltranslations.net/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val host = "infinitenoveltranslations.net"

    /** Category showcase pages whose <main> carries cover anchors. */
    private val categoryPages = listOf(
        "light-novels/", "web-novels/", "completed/", "dropped/"
    )

    /** Single-segment paths that are site index pages, not books. */
    private val indexSlugs = setOf(
        "", "light-novels", "web-novels", "completed", "dropped",
        "teasers", "active-projects"
    )

    /** Book subpages that are never chapters. */
    private val utilityLastSegments = setOf("glossary", "novel-illustrations")

    /**
     * Normalizes a site URL: resolves relative hrefs, repairs the
     * glued-host hrefs the site contains (the book slug glued onto
     * the host with no slash), forces https, drops query/fragment
     * and canonicalizes the trailing slash. Returns "" for
     * external or unparseable URLs.
     */
    private fun normalizeSiteUrl(href: String): String {
        if (href.isBlank()) return ""
        val absolute = if (href.startsWith("http")) href
        else try {
            URI(baseUrl).resolve(href).toString()
        } catch (e: Exception) {
            return ""
        }
        val uri = try {
            URI(absolute)
        } catch (e: Exception) {
            return ""
        }
        val urlHost = uri.host?.lowercase() ?: return ""
        if (urlHost.startsWith(host) && urlHost != host) {
            // Glued host: "infinitenoveltranslations.net<slug>/..."
            val glued = urlHost.removePrefix(host) + (uri.rawPath ?: "")
            val fixed = baseUrl + glued.removePrefix("/")
            return if (fixed.endsWith("/")) fixed else "$fixed/"
        }
        if (urlHost != host && urlHost != "www.$host") return ""
        val path = uri.rawPath ?: ""
        var url = "https://$host$path"
        if (!url.endsWith("/")) {
            val lastSegment = path.substringAfterLast('/')
            if (!lastSegment.contains('.')) url += "/"
        }
        return url
    }

    private fun pathSegments(url: String): List<String> {
        val path = try {
            URI(url).rawPath ?: ""
        } catch (e: Exception) {
            ""
        }
        return path.split('/').filter { it.isNotEmpty() }
    }

    /** A menu URL is a book: /<slug>/, or a teaser under /teasers/<slug>/. */
    private fun isBookUrl(url: String): Boolean {
        val segments = pathSegments(url)
        return when {
            segments.size == 1 -> segments[0] !in indexSlugs
            segments.size == 2 && segments[0] == "teasers" -> true
            else -> false
        }
    }

    private fun contentRoot(doc: Document): Element? =
        doc.selectFirst(".hentry")
            ?: doc.selectFirst(".bs-card-box")
            ?: doc.selectFirst("main")

    /**
     * The book index is the header menu (ul#menu-primary): one
     * text anchor per book. Covers are left empty here and filled
     * in by [loadCatalog] from the category showcase pages.
     */
    private fun parseMenuBooks(doc: Document): MutableMap<String, BookResult> {
        val books = linkedMapOf<String, BookResult>()
        val menu = doc.selectFirst("ul#menu-primary") ?: return books
        for (anchor in menu.select("a[href]")) {
            val title = anchor.text().trim()
            if (title.isBlank()) continue
            val url = normalizeSiteUrl(anchor.attr("href"))
            if (url.isEmpty() || !isBookUrl(url)) continue
            if (url !in books) {
                books[url] = BookResult(title = title, url = url, coverImageUrl = "")
            }
        }
        return books
    }

    private suspend fun loadCatalog(): List<BookResult> {
        val doc = networkClient.get(catalogUrl).toDocument()
        val books = parseMenuBooks(doc)
        // Cover enrichment: on the category showcase pages each
        // book also appears as an image anchor inside <main>.
        for (category in categoryPages) {
            try {
                val categoryDoc = networkClient.get(baseUrl + category).toDocument()
                val main = categoryDoc.selectFirst("main") ?: categoryDoc
                for (anchor in main.select("a[href]")) {
                    val url = normalizeSiteUrl(anchor.attr("href"))
                    val book = books[url] ?: continue
                    if (book.coverImageUrl.isNotEmpty()) continue
                    val img = anchor.selectFirst("img") ?: continue
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (src.isNotBlank()) {
                        books[url] = book.copy(coverImageUrl = src)
                    }
                }
            } catch (e: Exception) {
                // A missing showcase page only costs some covers.
            }
        }
        return books.values.toList()
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
        }

    private fun isNavParagraph(text: String): Boolean =
        text.contains("Next Chapter") || text.contains("Previous Chapter") ||
            (text.contains("Table of Contents") && text.contains("|"))

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = contentRoot(doc) ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards (the title element lives
            // outside .hentry, but nothing here may mutate the
            // original document regardless).
            val body = container.clone()
            body.select("script, iframe, ins, form, select, button, table").remove()
            for (p in body.select("p").toList()) {
                if (isNavParagraph(p.text())) p.remove()
            }
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val root = contentRoot(doc) ?: return@tryConnect null
                for (img in root.select("img")) {
                    val src = img.attr("data-src").ifBlank { img.attr("src") }
                    if (!src.contains("/wp-content/uploads/")) continue
                    if (src.contains("gravatar")) continue
                    val normalized = normalizeSiteUrl(src)
                    return@tryConnect normalized.ifEmpty { src }
                }
                null
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val root = contentRoot(doc) ?: return@tryConnect null
                val cleanBookUrl = normalizeSiteUrl(bookUrl)
                // The synopsis paragraphs come first; the table of
                // contents starts at the first paragraph carrying
                // a chapter link.
                val parts = mutableListOf<String>()
                for (p in root.select("p")) {
                    val hasChapterLink = p.select("a[href]").toList().any {
                        isChapterUrl(normalizeSiteUrl(it.attr("href")), cleanBookUrl)
                    }
                    if (hasChapterLink) break
                    val text = p.text().trim()
                    if (text.isNotBlank()) parts += text
                }
                parts.joinToString("\n\n").ifBlank { null }
            }
        }

    private fun isChapterUrl(url: String, bookUrl: String): Boolean {
        if (url.isEmpty() || url == bookUrl) return false
        if (url.contains("/wp-content/") || url.contains("/category/")) return false
        val segments = pathSegments(url)
        if (segments.isEmpty()) return false
        if (segments.last() in utilityLastSegments) return false
        if (segments.size == 1 && segments[0] in indexSlugs) return false
        return true
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val root = contentRoot(doc) ?: return@tryConnect emptyList()
            val cleanBookUrl = normalizeSiteUrl(bookUrl)
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // Document order is the site's own reading order
            // (oldest first); no reversal needed.
            for (anchor in root.select("a[href]")) {
                val title = anchor.text().trim()
                if (title.isBlank()) continue
                val url = normalizeSiteUrl(anchor.attr("href"))
                if (!isChapterUrl(url, cleanBookUrl)) continue
                if (!seen.add(url)) continue
                chapters += ChapterResult(title = title, url = url)
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                if (index > 0) return@tryConnect PagedList.createEmpty(index = index)
                val books = loadCatalog()
                PagedList(list = books, index = index, isLastPage = true)
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
                // The site's own search returns chapter posts, not
                // books, so search the (small) catalogue by title:
                // every query token must appear in the book title.
                val tokens = normalize(input).split(' ').filter { it.isNotBlank() }
                if (tokens.isEmpty())
                    return@tryConnect PagedList.createEmpty(index = index)
                val books = loadCatalog().toList().filter { book ->
                    val title = normalize(book.title)
                    tokens.all { title.contains(it) }
                }
                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ")
}
