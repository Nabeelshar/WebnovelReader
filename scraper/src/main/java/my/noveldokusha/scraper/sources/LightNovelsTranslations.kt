package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
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
 * Light Novels Translations — https://lightnovelstranslations.com
 *
 * WordPress novel site (custom "light_novel" theme with a members /
 * early-access system). Site structure verified live 2026-11-23
 * (curl fetches of the catalogue, book pages and chapter pages,
 * cross-checked against the lightnovel-crawler project's
 * lightnovetrans source):
 * - Catalogue: /browse/, paginated as ?paged=<n> (6 books per
 *   page, ~18 pages; a past-the-end page returns zero items).
 *   Items are article.read_list-story-item: title link in h3 a
 *   (to /novel/<slug>/), cover in .item_thumb img. Books without
 *   a cover get a placeholder banner image (class
 *   lnt-current-placeholder) — reported as no cover.
 * - Search is the catalogue's own filter form: GET
 *   /browse/?field-search=<q>, same item markup, paginated with
 *   &paged=<n>. (The header search box at /?search=<q> renders
 *   no book results server-side.)
 * - Book page /novel/<slug>/: title in .novel_title, cover in
 *   .novel-image img (same placeholder rule), synopsis in
 *   .novel_text (its trailing .alternate_titles block is not
 *   part of the synopsis).
 * - The chapter list lives on the book page's table-of-contents
 *   tab (?tab=table_contents) and is grouped into volumes:
 *   section.lnt-volume[data-volume]. Only the first volume's
 *   first 50 chapters are server-rendered on the plain book
 *   page; each volume renders on demand at
 *   ?tab=table_contents&lnt_volume=<id>, paginated inside the
 *   volume at 50 chapters per page via &lnt_page=<n> ("Next
 *   chapters" links). Rows are li.chapter-item a in reading
 *   order — volumes in order, chapters oldest first inside each
 *   volume (479 chapters verified for one book, matching the
 *   count the page itself states). Locked early-access chapters
 *   carry the li.lock class; they are listed like the site lists
 *   them and yield empty text when read (see below).
 * - Chapter page: the story is .text_story — one <h2> repeating
 *   the chapter title followed by clean <p> paragraphs. A
 *   locked chapter's page instead carries only a short preview
 *   in .text_story plus a .unlock_chapter paywall block, so
 *   getChapterText returns empty for those rather than passing
 *   the truncated preview off as the chapter.
 */
class LightNovelsTranslations(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "lightnovelstranslations"
    override val nameStrId = R.string.source_name_lightnovelstranslations
    override val baseUrl = "https://lightnovelstranslations.com/"
    override val catalogUrl = "https://lightnovelstranslations.com/browse/"
    override val iconUrl = "https://lightnovelstranslations.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** The site serves a banner placeholder instead of a cover. */
    private fun coverOf(img: Element?): String {
        if (img == null) return ""
        if (img.hasClass("lnt-current-placeholder")) return ""
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        if (src.contains("lnt-banner-wordmark")) return ""
        return resolve(src)
    }

    /**
     * Catalogue and search results share one item markup:
     * article.read_list-story-item with the title link in h3 a
     * and the cover in .item_thumb img.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in doc.select("article.read_list-story-item")) {
            val link = item.selectFirst("h3 a[href]")
                ?: item.selectFirst(".item_thumb a[href]")
                ?: continue
            val url = resolve(link.attr("href")).substringBefore('#')
            if (!url.startsWith(baseUrl)) continue
            if (!seen.add(url)) continue
            val title = link.text().trim()
                .ifBlank { link.attr("aria-label") }
                .trim()
            if (title.isBlank()) continue
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = coverOf(item.selectFirst(".item_thumb img"))
            )
        }
        return books
    }

    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[href]").toList().any {
            it.attr("href").contains("paged=${page + 1}")
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // The chapter title is the <h2> that opens .text_story;
            // it matches the chapter-list title exactly.
            doc.selectFirst(".text_story h2")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // Locked (early-access) chapters only render a short
            // preview plus a paywall block; report them as empty
            // instead of serving the truncated preview.
            if (doc.selectFirst(".unlock_chapter") != null) return@withContext ""
            val container = doc.selectFirst(".text_story")
                ?: return@withContext ""
            // Work on a clone: getChapterTitle still needs the
            // <h2> from this same Document afterwards.
            val body = container.clone()
            body.select("h2, script, iframe, ins").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                coverOf(doc.selectFirst(".novel-image img")).ifEmpty { null }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val synopsis = doc.selectFirst(".novel_text")
                    ?: return@tryConnect null
                // The synopsis block ends with an alternate-titles
                // div that is not part of the synopsis.
                val body = synopsis.clone()
                body.select(".alternate_titles").remove()
                TextExtractor.get(body).trim().ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val tocUrl = bookUrl.toUrlBuilderSafe()
                .add("tab", "table_contents")
                .toString()
            val doc = networkClient.get(tocUrl).toDocument()
            val volumeIds = doc.select("section.lnt-volume").toList()
                .map { it.attr("data-volume") }
                .filter { it.isNotBlank() }
                .distinct()
            if (volumeIds.isEmpty()) {
                // No volume grouping: the whole list is on the page.
                return@tryConnect parseChapterRows(doc)
            }
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            for (volumeId in volumeIds) {
                var pageUrl: String? = bookUrl.toUrlBuilderSafe()
                    .add("tab", "table_contents")
                    .add("lnt_volume", volumeId)
                    .toString()
                var page = 1
                // 50 chapters per page; the cap only guards against
                // a pager loop on a site change.
                while (pageUrl != null && page <= 200) {
                    val volumeDoc = networkClient.get(pageUrl).toDocument()
                    val section = volumeDoc
                        .selectFirst("section.lnt-volume[data-volume='$volumeId']")
                        ?: break
                    for (chapter in parseChapterRows(section)) {
                        if (seen.add(chapter.url)) chapters += chapter
                    }
                    pageUrl = nextPageUrl(section, page)
                    page++
                }
            }
            chapters
        }
    }

    /** The "Next chapters" link of a volume panel, if any. */
    private fun nextPageUrl(section: Element, currentPage: Int): String? {
        for (link in section.select("a.lnt-page-link[href]").toList()) {
            val href = link.attr("href")
            val target = Regex("lnt_page=(\\d+)").find(href)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (target == currentPage + 1) return resolve(href)
        }
        return null
    }

    private fun parseChapterRows(root: Element): List<ChapterResult> {
        val chapters = mutableListOf<ChapterResult>()
        val seen = mutableSetOf<String>()
        val rows = root.select(".novel_list_chapter_content li.chapter-item a[href]")
            .ifEmpty { root.select("li.chapter-item a[href]") }
        for (anchor in rows.toList()) {
            val url = resolve(anchor.attr("href")).substringBefore('#')
            if (!url.startsWith(baseUrl)) continue
            if (!seen.add(url)) continue
            val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
            if (title.isBlank()) continue
            chapters += ChapterResult(title = title, url = url)
        }
        return chapters
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = catalogUrl.toUrlBuilderSafe()
                    .add("paged", page)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNextPage(doc, page)
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
                val url = catalogUrl.toUrlBuilderSafe()
                    .add("field-search", input)
                    .add("paged", page)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNextPage(doc, page)
                )
            }
        }
}
