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
import java.net.URI

/**
 * Dark Translation — https://darktranslation.com
 *
 * Indonesian fan-translation blog (WordPress, brands itself
 * "Dark L[E]giun"). Site structure verified live 2026-11-23 (curl
 * fetches of the novel list, several book pages and chapter pages,
 * cross-checked against the lightnovel-crawler project's
 * DarkTranslation source):
 * - There is no paginated book listing: the site's own novel index
 *   is the single page /daftar-novel/, whose div.entry-content
 *   carries one <h2><a> per book (9 books; two further <h2> headings
 *   have no link and are skipped). The page carries no cover images
 *   at all, so catalog covers stay empty — the real cover is served
 *   per book by getBookCoverImageUrl.
 * - The site's own search (/?s=<q>) returns mostly chapter posts,
 *   which are useless as book results, so catalog search filters
 *   the daftar-novel list by title instead.
 * - Book page (a WP post/page under /novel/<slug>/ or the root):
 *   title in h1.entry-title, cover in the og:image meta (fallback:
 *   first image inside div.entry-content). The chapter list is the
 *   set of same-site links inside div.entry-content p a, in
 *   document order — that is the order the site itself presents
 *   (prologue first, newest last; verified on Re:Zero with 187
 *   unique chapters and on Eighty-Six with 73). External credit
 *   links (NovelUpdates, source blogs) and the site's utility
 *   pages are filtered out. One listed book (Danmachi) has an
 *   index page with no chapter links at all on the site, so its
 *   chapter list is legitimately empty. The daftar page itself is
 *   partly stale on the site's side: three of its nine links are
 *   dead there (Seirei Gensouki's index page is gone; Jack of All
 *   Trades and Tale of Dark Knight moved to new URLs the daftar
 *   never updated) — the list is mirrored as the site presents it.
 * - There is no synopsis block: getBookDescription returns the
 *   book page's info block (source/author/translator lines) — the
 *   entry-content text with the table-of-contents paragraphs
 *   removed.
 * - Chapter page: title in h1.entry-title; the story is
 *   div.entry-content (clean <p> prose; the only junk is a row of
 *   social-share anchors with empty text, removed on a clone).
 */
class DarkTranslation(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "darktranslation"
    override val nameStrId = R.string.source_name_darktranslation
    override val baseUrl = "https://darktranslation.com/"
    override val catalogUrl = "https://darktranslation.com/daftar-novel/"
    override val language = LanguageCode.INDONESIAN

    /** Site pages that are never chapters, by last URL segment. */
    private val utilitySlugs = setOf(
        "daftar-novel",
        "donasi",
        "fanspage",
        "admin",
        "rekan-seperjuangan",
        "laman-contoh"
    )

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun cleanUrl(url: String): String = resolve(url).substringBefore('#')

    /**
     * A chapter link is a same-site link on the book page that is
     * neither the book page itself nor one of the site's utility
     * pages. External links (translation sources, NovelUpdates)
     * fail the same-site check.
     */
    private fun isChapterHref(href: String, bookUrl: String): Boolean {
        if (href.isBlank()) return false
        val url = cleanUrl(href)
        if (!url.startsWith(baseUrl)) return false
        if (url == cleanUrl(bookUrl)) return false
        val slug = url.removePrefix(baseUrl).trim('/').substringAfterLast('/')
        return slug.isNotEmpty() && slug !in utilitySlugs
    }

    /**
     * The whole catalogue lives on the daftar-novel page: one
     * <h2><a> per book inside div.entry-content. The page has no
     * cover images, so covers are left empty here and resolved
     * from the book page when a book is opened.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (link in doc.select("div.entry-content h2 a[href]")) {
            val url = cleanUrl(link.attr("href"))
            if (!url.startsWith(baseUrl)) continue
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = ""
            )
        }
        return books
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.entry-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("div.entry-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select("script, iframe, ins, .post-share").remove()
            body.select(
                "a[href*=\"facebook.com/sharer\"], " +
                    "a[href*=\"twitter.com/intent\"], " +
                    "a[href*=\"pinterest.com/pin\"], " +
                    "a[href*=\"linkedin.com/shareArticle\"]"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val ogImage = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
                    .orEmpty()
                if (ogImage.isNotEmpty()) return@tryConnect resolve(ogImage)
                val img = doc.select("div.entry-content img")
                    .firstOrNull { !it.attr("src").contains("gravatar") }
                img?.let { resolve(it.attr("src")) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val container = doc.selectFirst("div.entry-content")
                    ?: return@tryConnect null
                // The book page is an info block followed by the
                // chapter table of contents: drop every paragraph
                // that carries a chapter link (and the share row),
                // then extract what remains.
                val body = container.clone()
                body.select(".post-share").remove()
                for (p in body.select("p")) {
                    val hasChapterLink = p.select("a[href]").any {
                        isChapterHref(it.attr("href"), bookUrl)
                    }
                    if (hasChapterLink) p.remove()
                }
                TextExtractor.get(body).trim().ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            for (anchor in doc.select("div.entry-content p a[href]")) {
                val href = anchor.attr("href")
                if (!isChapterHref(href, bookUrl)) continue
                val url = cleanUrl(href)
                if (!seen.add(url)) continue
                val title = anchor.text().trim()
                    .ifBlank { "Chapter ${chapters.size + 1}" }
                chapters += ChapterResult(title = title, url = url)
            }
            // Document order is the site's own reading order
            // (oldest first); no reversal needed.
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
                // The site's own search returns chapter posts, not
                // books, so search the (small) catalogue by title:
                // every query token must appear in the book title.
                val tokens = normalize(input).split(' ').filter { it.isNotBlank() }
                if (tokens.isEmpty())
                    return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                val books = parseBookList(doc).filter { book ->
                    val title = normalize(book.title)
                    tokens.all { title.contains(it) }
                }
                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ")
}
