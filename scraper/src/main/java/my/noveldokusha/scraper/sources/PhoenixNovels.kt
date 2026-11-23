package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.BookTextMapper
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLDecoder

/**
 * Phoenix Novels — https://phoenixnovels.com.br
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * catalog, search, book and chapter pages, cross-checked against
 * the lightnovel-crawler project's PhoenixNovels source — the site
 * has since been redesigned and none of the crawler's Madara
 * selectors survive):
 * - Catalog: /novels/, paginated ?page=<n>, 15 books per page
 *   (33 books total at verification time). Items are
 *   .novels-catalog article.novel-card: title link in
 *   .novel-card__titles h2 a to /manga/<slug>, cover in
 *   .novel-card__media img. The pager links to ?page=<n+1> while
 *   more pages exist; pages past the end return an empty list.
 * - Search: the header form is GET /novels?q=<query>, results
 *   reuse the catalog markup and paginate as ?q=<query>&page=<n>.
 * - Book page /manga/<slug>: cover in .asura-cover img, synopsis
 *   in .asura-synopsis. The full chapter list is server-rendered
 *   in .vol-panel blocks (volumes in order), each holding
 *   a.ch-item anchors whose .ch-title is the chapter title. The
 *   panels are already ordered oldest to newest, so document
 *   order is reading order — nothing is reversed.
 * - Chapter page: title in h1.reader-title (matches the list
 *   title). The body (.reader-content) is rendered as page
 *   "images": each img.reader-text-page-image is an SVG data URI
 *   whose story text sits in <tspan> elements of the <text> that
 *   is a direct child of <svg> (a sibling <g> holds a repeated
 *   "VISITANTE" watermark, which is never read). A tspan with an
 *   x attribute starts a new visual line (word tspans carry their
 *   leading space except at line starts), blank tspans mark
 *   paragraph breaks, and the text is laced with zero-width
 *   characters that are stripped. Real image files interleaved
 *   in the flow (chapter illustrations) are kept as image
 *   entries. A guest watermark grid overlays the article in HTML
 *   but lives outside .reader-content, so it is never extracted.
 */
class PhoenixNovels(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "phoenixnovels"
    override val nameStrId = R.string.source_name_phoenixnovels
    override val baseUrl = "https://phoenixnovels.com.br/"
    override val catalogUrl = "https://phoenixnovels.com.br/novels/"
    override val language = LanguageCode.PORTUGUESE

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup: an
     * article.novel-card whose title anchor links to /manga/<slug>
     * and whose media img carries the cover.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in doc.select(".novels-catalog article.novel-card")) {
            val link = card.selectFirst(".novel-card__titles h2 a")
                ?: card.selectFirst("a.novel-cover")
                ?: continue
            val url = resolve(link.attr("href"))
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val img = card.selectFirst(".novel-card__media img")
                ?: card.selectFirst("img")
            val cover = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")
                ?: ""
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
    }

    private fun isLastPage(doc: Document, books: List<BookResult>, page: Int): Boolean {
        if (books.isEmpty()) return true
        val next = Regex("(?:^|[?&])page=${page + 1}(?:&|$)")
        return doc.select("a[href]").toList().none {
            next.containsMatchIn(it.attr("href"))
        }
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.reader-title")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst(".reader-content")
                ?: return@withContext ""
            val blocks = mutableListOf<String>()
            for (img in container.select("img")) {
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                when {
                    src.startsWith("data:image/svg") ->
                        blocks += decodeSvgPageText(src)
                    src.isNotBlank() && !src.startsWith("data:") ->
                        blocks += BookTextMapper.ImgEntry(
                            path = resolve(src),
                            yRel = 1.45f
                        ).toXMLString()
                }
            }
            if (blocks.none { it.isNotBlank() }) {
                // Fallback for any plain-HTML chapter: extract the
                // container's text on a clone, minus the guest
                // watermark grid and other non-story elements. The
                // original document is left untouched for
                // getChapterTitle.
                val body = container.clone()
                body.select(
                    ".guest-watermark-grid, script, iframe, style, ins"
                ).remove()
                return@withContext TextExtractor.get(body)
            }
            blocks.filter { it.isNotBlank() }.joinToString("\n\n").trim()
        }

    /**
     * Decodes one SVG page image and rebuilds its story paragraphs.
     * The story lives in the <text> element that is a direct child
     * of the root <svg>; the watermark <text>s are nested in a <g>
     * and are ignored. Word tspans carry their leading space except
     * at visual line starts (tspan with an x attribute), where a
     * separating space is restored; blank tspans are paragraph
     * breaks. Zero-width characters the site sprinkles inside
     * words are stripped.
     */
    private fun decodeSvgPageText(src: String): List<String> {
        val svg = try {
            val payload = src.substringAfter(",")
            if (src.substringBefore(",").contains("base64")) {
                String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
            } else {
                URLDecoder.decode(payload, "UTF-8")
            }
        } catch (e: Exception) {
            return emptyList()
        }
        val svgDoc = try {
            Jsoup.parse(svg, "", Parser.xmlParser())
        } catch (e: Exception) {
            return emptyList()
        }
        val root = svgDoc.firstElementChild() ?: return emptyList()
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) paragraphs += text
            current.clear()
        }
        for (child in root.children().toList()) {
            if (child.tagName() != "text") continue
            for (tspan in child.select("tspan").toList()) {
                val text = tspan.wholeText()
                    .replace("\u200B", "")
                    .replace("\u200C", "")
                    .replace("\u200D", "")
                    .replace("\uFEFF", "")
                if (tspan.hasAttr("x")) {
                    if (text.isBlank()) {
                        flush()
                        continue
                    }
                    if (current.isNotEmpty() &&
                        current[current.length - 1] != ' ' &&
                        !text.startsWith(" ")
                    ) {
                        current.append(" ")
                    }
                }
                current.append(text)
            }
        }
        flush()
        return paragraphs
    }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".asura-cover img")
                img?.let {
                    resolve(it.attr("data-src").ifBlank { it.attr("src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst(".asura-synopsis")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // Volume panels and their chapters are server-rendered
            // in reading order (oldest first) — document order is
            // kept as-is.
            for (anchor in doc.select(".vol-panel a.ch-item")) {
                val url = resolve(anchor.attr("href"))
                if (!seen.add(url)) continue
                val title = anchor.selectFirst(".ch-title")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: anchor.attr("data-title").ifBlank { null }
                    ?: continue
                chapters += ChapterResult(title = title, url = url)
            }
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val builder = baseUrl.toUrlBuilderSafe().addPath("novels")
                if (page > 1) builder.add("page", page)
                val doc = networkClient.get(builder.toString()).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = isLastPage(doc, books, page)
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
                val builder = baseUrl.toUrlBuilderSafe()
                    .addPath("novels")
                    .add("q", input)
                if (page > 1) builder.add("page", page)
                val doc = networkClient.get(builder.toString()).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = isLastPage(doc, books, page)
                )
            }
        }
}
