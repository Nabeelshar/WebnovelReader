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
 * The Wandering Inn — https://wanderinginn.com
 *
 * Single-work site: pirateaba's web serial, hosted on its own
 * WordPress (Elementor) site. Site structure verified live
 * 2026-11-23 (curl fetches of the home page, the table of
 * contents, the about page and several chapter pages,
 * cross-checked against the lightnovel-crawler project's
 * WanderingInn source):
 * - There is no catalogue of books: the site hosts exactly one
 *   work, "The Wandering Inn" (og:site_name). The catalogue is
 *   that single book, whose URL is the table-of-contents page
 *   (https://wanderinginn.com/table-of-contents/). The author's
 *   other serials (The Last Tide, Gravesong) live on other
 *   sites and have no TOC here. Catalog search filters the one
 *   book by title (same approach as the ReLibrary source).
 * - The TOC page holds div#table-of-contents with one
 *   div[id*='vol'] per volume (vol-1 .. vol-10) and one
 *   .chapter-entry a[href] per chapter inside them — 843
 *   chapters, all on wanderinginn.com, no duplicates, in
 *   document order = reading order (oldest first). The anchor
 *   text is the site's own chapter label ("1.00", "10.80 KT").
 * - Chapter pages: the chapter label is the page's og:title
 *   ("1.00", "3.24", "10.80 KT"). The story lives in
 *   #reader-content (an Elementor theme-post-content widget);
 *   the crawler's div.entry-content selector is the pre-redesign
 *   markup and is kept only as a fallback. Inside the container
 *   the only junk is the "Previous Chapter" / "Next Chapter"
 *   navigation anchors (in their own paragraphs at the start /
 *   end), removed on a clone before extraction — the original
 *   document is never mutated, so getChapterTitle can still
 *   read og:title from it afterwards.
 * - Some chapters (part of the rewritten Volume 1) are
 *   Patreon-locked: #reader-content then holds only a "Support
 *   us on Patreon to access this content." notice. Those yield
 *   empty text, like locked chapters in other sources.
 * - The book cover is the TOC page's og:image (a wide banner —
 *   the same image the reference crawler used). The site has
 *   no synopsis page; the description is the site's own
 *   "Welcome to the Innverse, ..." blurb paragraph from the
 *   home page, located by its text anchor because the
 *   Elementor markup around it has no stable selector.
 */
class WanderingInn(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "wanderinginn"
    override val nameStrId = R.string.source_name_wanderinginn
    override val baseUrl = "https://wanderinginn.com/"
    override val catalogUrl = "https://wanderinginn.com/table-of-contents/"
    override val iconUrl = "https://wanderinginn.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun cleanUrl(url: String): String = resolve(url).substringBefore('#')

    /**
     * The whole "catalogue" is the one book: its title is the
     * site's og:site_name, its URL is the table-of-contents
     * page (which getChapterList parses), its cover the TOC
     * page's og:image.
     */
    private fun parseBook(doc: Document): BookResult {
        val title = doc.selectFirst("meta[property=og:site_name]")
            ?.attr("content")?.trim()?.ifBlank { null }
            ?: "The Wandering Inn"
        val cover = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")?.trim().orEmpty()
        return BookResult(
            title = title,
            url = catalogUrl,
            coverImageUrl = resolve(cover)
        )
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // Chapter pages carry the chapter label as og:title
            // ("1.00", "10.80 KT"). It lives in <head>, outside
            // the content container getChapterText cleans.
            val ogTitle = doc.selectFirst("meta[property=og:title]")
                ?.attr("content")?.trim()
            if (!ogTitle.isNullOrBlank() && ogTitle != "Table of Contents")
                return@withContext ogTitle
            doc.title().trim()
                .removeSuffix(" - The Wandering Inn")
                .trim()
                .ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("#reader-content")
                ?: doc.selectFirst("div.entry-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select("script, iframe, ins, form").remove()
            // Chapter navigation: anchors labelled "Previous
            // Chapter" / "Next Chapter" / "Table of Contents"
            // sit inside the content (first/last paragraphs).
            // Remove the anchors, then any paragraph they
            // emptied or that is itself just a nav label.
            for (anchor in body.select("a[href]").toList()) {
                if (anchor.text().trim() in navLabels) anchor.remove()
            }
            for (paragraph in body.select("p").toList()) {
                val text = paragraph.text().trim()
                if (text.isBlank() || text in navLabels) paragraph.remove()
            }
            val text = TextExtractor.get(body)
            // Patreon-locked chapters serve only a support
            // notice instead of the story.
            if (text.contains("Support us on Patreon to access this content"))
                return@withContext ""
            text
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val ogImage = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")?.trim().orEmpty()
                ogImage.ifEmpty { null }?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                // No synopsis page exists; the site's own blurb
                // is a paragraph on the home page. The Elementor
                // markup has no stable selector, so the paragraph
                // is located by its opening words.
                val doc = networkClient.get(baseUrl).toDocument()
                val blurb = doc.select("p").toList()
                    .map { it.text().trim() }
                    .firstOrNull { it.startsWith("Welcome to the Innverse") }
                blurb?.ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val toc = doc.selectFirst("div#table-of-contents")
                ?: return@tryConnect emptyList()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            fun addChapter(anchor: org.jsoup.nodes.Element) {
                val href = anchor.attr("href")
                // The crawler's guard: external links are not
                // chapters of this book.
                if (!href.contains("wanderinginn.com")) return
                val url = cleanUrl(href)
                if (!seen.add(url)) return
                val title = anchor.text().trim()
                    .ifBlank { "Chapter ${chapters.size + 1}" }
                chapters += ChapterResult(title = title, url = url)
            }
            // Volumes in document order, chapters in reading
            // order inside each volume (oldest first).
            val volumes = toc.select("div[id*='vol']").toList()
            if (volumes.isNotEmpty()) {
                for (volume in volumes) {
                    for (anchor in volume.select(".chapter-entry a[href]").toList())
                        addChapter(anchor)
                }
            } else {
                for (anchor in toc.select(".chapter-entry a[href]").toList())
                    addChapter(anchor)
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                if (index > 0) return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                PagedList(
                    list = listOf(parseBook(doc)),
                    index = index,
                    isLastPage = true
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
                // Single-book site: search matches the query
                // against the book's title — every query token
                // must appear in it (same approach as ReLibrary).
                val tokens = normalize(input).split(' ').filter { it.isNotBlank() }
                if (tokens.isEmpty())
                    return@tryConnect PagedList.createEmpty(index = index)
                val doc = networkClient.get(catalogUrl).toDocument()
                val book = parseBook(doc)
                val title = normalize(book.title)
                val books = if (tokens.all { title.contains(it) })
                    listOf(book) else emptyList()
                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ")

    private companion object {
        val navLabels = setOf("Previous Chapter", "Next Chapter", "Table of Contents")
    }
}
