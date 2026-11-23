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
 * Blackbox Translations — https://blackbox-tl.com
 *
 * WordPress danmei/BL translation blog. Site structure verified
 * live 2026-11-23 (curl fetches of the novels index, five book
 * pages and several chapter pages, cross-checked against the
 * lightnovel-crawler project's BlackboxTL source):
 * - The whole site is served with RELATIVE hrefs/srcs (a
 *   relative-URL plugin rewrites them, e.g. a chapter link on a
 *   book page is "../../novels/bttc/bttc-chapter-1/"). The app's
 *   toDocument() sets no base URI, so every URL is resolved
 *   against the site root — which is where all of the site's
 *   relative URLs climb to, so resolution is exact.
 * - There is no listing markup: the /novels/ page's own content
 *   is empty and the book list lives in the site menu, which
 *   links every book as /novels/<slug>/ (42 books). The menu is
 *   present on the /novels/ page itself, so the catalogue is
 *   parsed from there. The menu carries no covers; the real
 *   cover (for books that have one) is served per book by
 *   getBookCoverImageUrl. The WP REST API and the sitemap both
 *   redirect to the homepage, and the site search returns the
 *   latest blog posts regardless of the query, so catalog
 *   search filters the menu list by title instead (same
 *   approach as the DarkTranslation / ReLibrary sources).
 * - Book page /novels/<slug>/: title in h1.entry-title. The
 *   div.entry-content holds an info block (Title / Author /
 *   Translator / Status / Synopsis paragraphs), then a
 *   "Table of Contents:" marker paragraph and the chapter
 *   links, grouped several per paragraph, in reading order
 *   (oldest first). Chapter URLs take three shapes: pages
 *   under the book (/novels/<slug>/<chapter>/), occasional
 *   top-level pages (/tsog-chapter-1/) and occasional dated
 *   blog posts (/2021/06/30/bttc-chapter-32/), so the list is
 *   built from every same-host anchor in the content (author
 *   box removed) rather than a path pattern — external links
 *   (the original-novel source, other translations) are
 *   excluded by the host check, exactly like the crawler's
 *   "stops external links" rule. Verified counts: Breaking
 *   through the Clouds 55, Perfect Match 19, Winner Takes
 *   All 19, Creatures of Habit 3, The Sword of Galaxy 3.
 * - Some chapters are WordPress password-protected pages
 *   (Breaking through the Clouds ch. 32 onwards: the reader
 *   must prove they bought the original novel). They carry a
 *   password form instead of story text; getChapterText
 *   returns empty text for them, like locked chapters on
 *   other sources. One TOC quirk: BTTC chapter 32 links to
 *   the dated announcement post about the locking (which
 *   itself links to the protected chapter page) — the list
 *   mirrors the site's own TOC as presented.
 * - Chapter page: title in h1.entry-title ("BttC Chapter 1";
 *   protected pages prefix it with "Protected:", stripped
 *   here). The story is div.entry-content, wrapped in junk:
 *   a "Table of Contents | Next Chapter →" navigation
 *   paragraph at both ends, <hr> separators and the author
 *   box (.abh_box, which also injects "recent posts" links
 *   to other books). All of it is removed on a clone before
 *   extraction (the crawler's cleaning rules); the volume /
 *   chapter heading paragraphs are part of the text and kept.
 */
class BlackBoxTL(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "blackboxtl"
    override val nameStrId = R.string.source_name_blackboxtl
    override val baseUrl = "https://blackbox-tl.com/"
    override val catalogUrl = "https://blackbox-tl.com/novels/"
    override val iconUrl = "https://blackbox-tl.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun cleanUrl(url: String): String =
        resolve(url)
            .replace("://www.blackbox-tl.com", "://blackbox-tl.com")
            .substringBefore('#')

    /** The resolved URL if it belongs to this site, else null. */
    private fun siteUrl(url: String): String? {
        val cleaned = cleanUrl(url)
        return cleaned.takeIf { it.startsWith(baseUrl) }
    }

    /**
     * The catalogue is the site menu's book list: anchors whose
     * URL is exactly /novels/<slug>/ (deeper links are chapters,
     * other links are pages/categories). The menu has no covers.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        val bookPath = Regex("^/novels/[^/]+/$")
        for (anchor in doc.select(".menu a[href]")) {
            val url = siteUrl(anchor.attr("href")) ?: continue
            if (!bookPath.matches(URI(url).path)) continue
            if (!seen.add(url)) continue
            val title = anchor.text().trim()
            if (title.isBlank()) continue
            books += BookResult(title = title, url = url, coverImageUrl = "")
        }
        return books
    }

    /** The book page's div.entry-content with the author box removed. */
    private fun contentClone(doc: Document): Element? {
        val container = doc.selectFirst("div.entry-content") ?: return null
        val body = container.clone()
        body.select(".abh_box").remove()
        return body
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.entry-title")?.text()?.trim()
                ?.removePrefix("Protected:")?.trim()
                ?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("div.entry-content")
                ?: return@withContext ""
            // Password-protected chapters hold a password form
            // instead of story text: treat them as locked.
            if (container.selectFirst("input[type=password]") != null)
                return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select(".abh_box, hr, script, iframe, ins, form").remove()
            // The crawler's rule: drop the navigation paragraphs
            // ("Table of Contents | Next Chapter →" and friends).
            for (paragraph in body.select("p").toList()) {
                val text = paragraph.text()
                if (text.contains("Table of Contents") ||
                    text.contains("Next Chapter →") ||
                    text.contains("← Previous Chapter")
                ) paragraph.remove()
            }
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val body = contentClone(doc) ?: return@tryConnect null
                // Many books have no cover at all; the only other
                // images in the content are the Ko-fi button and
                // author avatars, which are never the cover.
                val img = body.select("img").toList().firstOrNull {
                    val src = it.attr("data-src").ifBlank { it.attr("src") }
                    src.isNotBlank() &&
                        !src.contains("avatar", ignoreCase = true) &&
                        !src.contains("kofi", ignoreCase = true)
                } ?: return@tryConnect null
                resolve(img.attr("data-src").ifBlank { img.attr("src") })
                    .ifBlank { null }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val body = contentClone(doc) ?: return@tryConnect null
                // The description is the info block before the
                // table of contents: cut everything from the
                // "Table(s) of Contents" marker paragraph on.
                // Books without the marker fall back to cutting
                // at the first paragraph that links a chapter.
                val paragraphs = body.select("p").toList()
                val marker = paragraphs.firstOrNull {
                    it.text().trim().matches(Regex("^Tables? of Contents.*"))
                }
                val cutStart = marker ?: paragraphs.firstOrNull { paragraph ->
                    paragraph.select("a[href]").toList().any { anchor ->
                        val url = siteUrl(anchor.attr("href"))
                        url != null && url != cleanUrl(bookUrl)
                    }
                }
                if (cutStart != null) {
                    var cutting = false
                    for (paragraph in paragraphs) {
                        if (paragraph == cutStart) cutting = true
                        if (cutting) paragraph.remove()
                    }
                }
                TextExtractor.get(body).trim().ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val body = contentClone(doc) ?: return@tryConnect emptyList()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // The TOC is every same-host anchor in the content,
            // in document order (= reading order, oldest first).
            // Chapter URLs live under the book, at the site root
            // and under dated post paths, so no path pattern is
            // applied; external links are excluded by the host
            // check and the book page itself is skipped.
            for (anchor in body.select("a[href]")) {
                val url = siteUrl(anchor.attr("href")) ?: continue
                if (url == cleanUrl(bookUrl)) continue
                if (!seen.add(url)) continue
                val title = anchor.text().trim()
                    .ifBlank { "Chapter ${chapters.size + 1}" }
                chapters += ChapterResult(title = title, url = url)
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                if (index > 0) return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                val books = parseBookList(doc)
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
                // The site's own search returns the latest blog
                // posts regardless of the query, so search the
                // (small) catalogue by title instead: every query
                // token must appear in the book title.
                val tokens = normalize(input).split(' ').filter { it.isNotBlank() }
                if (tokens.isEmpty())
                    return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                val books = parseBookList(doc).toList().filter { book ->
                    val title = normalize(book.title)
                    tokens.all { title.contains(it) }
                }
                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ")
}
