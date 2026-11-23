package my.noveldokusha.scraper.sources

import android.net.Uri
import com.google.gson.JsonParser
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
 * Ranobes — https://ranobes.com (Russian)
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * catalog, search, book, chapter-list and chapter pages, cross-checked
 * against the lightnovel-crawler project's Ranobes source, which
 * targets the ranobes.top / ranobes.net mirrors of the same site):
 * - Catalog: /ranobe/, paginated as /ranobe/page/<n>/, 10 books per
 *   page. Search: /search/<query>/, paginated the same way with
 *   /search/<query>/page/<n>/. Both reuse one item markup:
 *   article.shortstory with the title link in .short-cont .title a
 *   and the cover as a figure.cover whose style attribute carries
 *   "background-image: url(...)" (list items have no <img> cover).
 * - Book page /ranobe/<id>-<slug>.html: cover in
 *   .r-fullstory-poster .poster a img (data-src preferred, src is a
 *   root-relative path), description in .r-desription — the full
 *   text lives in .moreless__full with a "collapse" toggle link
 *   inside it; .moreless__short is a truncated duplicate.
 * - Chapter list: the numeric book id (the "/<id>-" segment of the
 *   book URL) has a table of contents at /chapters/<id>/, paginated
 *   as /chapters/<id>/page/<n>. Each page embeds the data as JSON in
 *   a window.__DATA__ script: {pages_count, chapters: [{title,
 *   link}]}. Pages and the chapters inside them are newest first, so
 *   the merged list is reversed to get oldest to newest (Against the
 *   Gods: 89 pages, 2,225 chapters).
 * - Chapter page /<book-slug>-<id>/<chapter-id>.html: the chapter
 *   title is h1.title in the form "<chapter title> | <book title>",
 *   and the story text is div#arrticle (the site's own spelling),
 *   minus the .free-support block and adfox ad slots the crawler's
 *   cleaner also removes.
 */
class Ranobes(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "ranobes"
    override val nameStrId = R.string.source_name_ranobes
    override val baseUrl = "https://ranobes.com/"
    override val catalogUrl = "https://ranobes.com/ranobe/"
    override val language = LanguageCode.RUSSIAN

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun coverFromStyle(style: String): String =
        Regex("""url\(\s*['"]?([^'\")]+)['"]?\s*\)""").find(style)
            ?.groupValues?.get(1)?.trim().orEmpty()

    /**
     * Catalog and search results share one item markup: an
     * article.shortstory whose .title anchor links to
     * /ranobe/<id>-<slug>.html. The cover is not an <img>: it is the
     * background-image of figure.cover (an <img> fallback is kept
     * for items that carry one).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val scoped = doc.select("article.shortstory")
        val items = if (scoped.isNotEmpty()) scoped else doc.select(".short-cont")
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (item in items) {
            val link = item.selectFirst(".title a[href]") ?: continue
            val href = link.attr("href")
            if (!href.contains("/ranobe/") || !href.endsWith(".html")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val cover = item.selectFirst("figure.cover")
                ?.let { coverFromStyle(it.attr("style")) }
                ?.ifBlank { null }
                ?: item.selectFirst(".poster img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
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
            // h1.title is "<chapter title> | <book title>".
            val raw = doc.selectFirst("h1.title")?.text()
                ?: doc.selectFirst("h1")?.text()
                ?: return@withContext null
            raw.substringBefore(" | ").trim().ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("div#arrticle")
                ?: return@withContext ""
            // Work on a clone so the original Document stays intact
            // for getChapterTitle (the title lives outside #arrticle,
            // but the pattern is kept consistent with other sources).
            val body = container.clone()
            body.select(
                ".free-support, div[id^=adfox_], script, iframe, ins"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".r-fullstory-poster .poster a img")
                    ?: doc.selectFirst(".poster img")
                val fromImg = img?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
                val cover = fromImg?.ifBlank { null }
                    ?: doc.selectFirst(".r-fullstory-poster figure.cover")
                        ?.let { coverFromStyle(it.attr("style")) }
                    ?: ""
                cover.ifBlank { null }?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                // .moreless__full holds the complete description;
                // .moreless__short is its truncated duplicate and
                // .r-desription contains both, so prefer the parts
                // over the whole block. The toggle anchors ("show
                // full" / "collapse") are UI junk, not text.
                val el = doc.selectFirst(".r-desription .moreless__full")
                    ?: doc.selectFirst(".r-desription .moreless__short")
                    ?: doc.selectFirst(".r-desription")
                    ?: return@tryConnect null
                val clean = el.clone()
                clean.select(".moreless__toggle").remove()
                TextExtractor.get(clean).ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val bookId = Regex("""/(\d+)-""").find(bookUrl)
                ?.groupValues?.get(1)
                ?: return@tryConnect emptyList()
            val tocUrl = baseUrl + "chapters/$bookId/"

            val firstDoc = networkClient.get(tocUrl).toDocument()
            val (firstChapters, pagesCount) = parseTocPage(firstDoc)
            val newestFirst = firstChapters.toMutableList()
            for (page in 2..pagesCount) {
                val doc = networkClient.get(tocUrl + "page/$page").toDocument()
                newestFirst += parseTocPage(doc).first
            }
            // Pages are newest first and so are the chapters inside
            // each page; reversing the merged list gives the site's
            // reading order, oldest to newest.
            newestFirst.asReversed()
                .distinctBy { it.url }
        }
    }

    /**
     * Parses one table-of-contents page: the chapter data is JSON in
     * a window.__DATA__ script — {pages_count, chapters: [{title,
     * link}]}. Returns the chapters (newest first) and the total
     * page count (1 when the data block is missing).
     */
    private fun parseTocPage(doc: Document): Pair<List<ChapterResult>, Int> {
        val script = doc.select("script").firstOrNull {
            it.data().contains("window.__DATA__")
        }?.data() ?: return emptyList<ChapterResult>() to 1
        val jsonText = script.substringAfter("window.__DATA__")
            .substringAfter("=")
            .trim()
            .trimEnd(';')
        val data = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (e: Exception) {
            return emptyList<ChapterResult>() to 1
        }
        val pagesCount = data.get("pages_count")?.takeIf { it.isJsonPrimitive }
            ?.asInt ?: 1
        val chapters = mutableListOf<ChapterResult>()
        val array = data.getAsJsonArray("chapters") ?: return chapters to pagesCount
        for (entry in array) {
            val obj = entry.asJsonObject
            val title = obj.get("title")?.asString?.trim().orEmpty()
            val link = obj.get("link")?.asString.orEmpty()
            if (title.isBlank() || link.isBlank()) continue
            chapters += ChapterResult(title = title, url = resolve(link))
        }
        return chapters to maxOf(pagesCount, 1)
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}page/$page/"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = hasNextPage(doc, page)

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
            tryConnect("index=$index") {
                if (input.isBlank())
                    return@tryConnect PagedList.createEmpty(index = index)
                val page = index + 1
                val url = baseUrl + "search/" + Uri.encode(input) + "/" +
                    if (page == 1) "" else "page/$page/"

                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                val hasNext = hasNextPage(doc, page)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNext
                )
            }
        }

    /** The DLE pager links straight to /page/<next>/ when one exists. */
    private fun hasNextPage(doc: Document, page: Int): Boolean =
        doc.select("a[href]").toList().any {
            it.attr("href").endsWith("/page/${page + 1}/")
        }
}
