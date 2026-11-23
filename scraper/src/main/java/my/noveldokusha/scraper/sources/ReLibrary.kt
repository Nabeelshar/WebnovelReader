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
 * Re:Library — https://re-library.com
 *
 * WordPress gender-bender translation site. Site structure verified
 * live 2026-11-23 (curl fetches of the catalogue page, several book
 * pages and chapter pages, cross-checked against the
 * lightnovel-crawler project's ReLibrary source):
 * - The catalogue is the single page /translations/: one
 *   article.repi-item per book (79 books), title/link in
 *   h4.repi-title a, cover in img.repi-thumb. There is no
 *   pagination, so the whole catalogue is served as one page.
 * - The site's own search (/?s=<q>) returns chapter blog posts,
 *   which are useless as book results, so catalog search filters
 *   the catalogue list by title instead (same approach as the
 *   DarkTranslation source).
 * - Book page /translations/<slug>/: title in .entry-title, cover
 *   in .entry-content table img (the info table's image, same URL
 *   as og:image). The .entry-content holds the info table
 *   (title/author/translator/language/categories), a synopsis box
 *   (.su-box .su-box-content) and the chapter list itself, which
 *   lives in volume spoiler accordions: ul.rl-subpages li > a, in
 *   document order — volumes in order, chapters in reading order
 *   inside each volume, i.e. oldest first (Demon Sword Maiden:
 *   1,462 chapters verified, no duplicates; Alchemist Startover:
 *   306). The crawler's .page_item selector no longer exists on
 *   the site.
 * - Chapter page (under the book URL, grouped by volume): title
 *   in .entry-title (outside .entry-content, so getChapterText
 *   never touches it); the story is .entry-content, wrapped in
 *   junk: a "Leave a comment" .su-button, .nextPageLink /
 *   .prevPageLink / .PageLink navigation, a credits table, ad
 *   slots (.ad-slot, .code-block), a Jetpack share block
 *   (.sharedaddy) and a "Support Us" block (an h1 plus a Ko-fi
 *   table/select inside a centred div). All of it is removed on a
 *   clone before extraction; real story paragraphs and
 *   illustration images are kept.
 */
class ReLibrary(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "relibrary"
    override val nameStrId = R.string.source_name_relibrary
    override val baseUrl = "https://re-library.com/"
    override val catalogUrl = "https://re-library.com/translations/"
    override val iconUrl = "https://re-library.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun cleanUrl(url: String): String = resolve(url).substringBefore('#')

    /**
     * The whole catalogue lives on the /translations/ page: one
     * article.repi-item per book, with the title link in
     * h4.repi-title and the cover in img.repi-thumb.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("article.repi-item")) {
            val link = item.selectFirst("h4.repi-title a[href]") ?: continue
            val url = cleanUrl(link.attr("href"))
            if (!url.startsWith(baseUrl)) continue
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val cover = item.selectFirst("img.repi-thumb")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }.orEmpty()
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
            doc.selectFirst(".entry-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".entry-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards (the title element lives
            // outside .entry-content, but nothing here may mutate
            // the original document regardless).
            val body = container.clone()
            body.select(
                "script, iframe, ins, form, select, button, table, " +
                    ".su-button, .nextPageLink, .prevPageLink, .PageLink, " +
                    ".ad-slot, .code-block, .sharedaddy, .sd-content"
            ).remove()
            // The "Support Us" block is a centred div holding an
            // h1, a Ko-fi button image and a project <select>
            // (the table/select are already removed above; drop
            // the whole block so the button image and heading do
            // not leak into the text). A "References" footnote
            // heading is dropped the same way the reference
            // crawler drops it.
            for (heading in body.select("h1, h2").toList()) {
                when (heading.text().trim()) {
                    "Support Us" -> {
                        val block = heading.parents()
                            .firstOrNull { it.tagName() == "div" }
                        (block ?: heading).remove()
                    }
                    "References" -> heading.remove()
                }
            }
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".entry-content table img")
                val fromTable = img?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }.orEmpty()
                if (fromTable.isNotEmpty()) return@tryConnect resolve(fromTable)
                val ogImage = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.trim()
                    .orEmpty()
                ogImage.ifEmpty { null }?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                // The synopsis lives in a Shortcodes Ultimate box
                // inside .entry-content (its .su-box-title heading
                // just says "Synopsis:").
                val synopsis = doc.selectFirst(".entry-content .su-box-content")
                if (synopsis != null) {
                    val text = TextExtractor.get(synopsis).trim()
                    if (text.isNotBlank()) return@tryConnect text
                }
                // Fallback: the info table text (title / author /
                // translator / language / categories). Scope to
                // the table itself — the rest of .entry-content
                // is the chapter list, which must not leak in.
                val table = doc.selectFirst(".entry-content table")
                    ?: return@tryConnect null
                val body = table.clone()
                body.select("img").remove()
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
            // The chapter list is the book page's subpage widget:
            // one ul.rl-subpages per volume, in document order —
            // volumes in order, chapters in reading order inside
            // each volume (oldest first). No reversal needed.
            for (anchor in doc.select("ul.rl-subpages li > a[href]")) {
                val url = cleanUrl(anchor.attr("href"))
                if (!url.startsWith(baseUrl)) continue
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
                // The site's own search returns chapter posts, not
                // books, so search the (small) catalogue by title:
                // every query token must appear in the book title.
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
