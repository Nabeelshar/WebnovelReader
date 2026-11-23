package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * Powanjuan （破万卷） — https://powanjuan.cc
 *
 * Site structure verified live 2026-11-23 (curl inspection of the live
 * pages; the lightnovel-crawler source for this site only covers the
 * book/chapter pages and was used as a hint for those selectors):
 * - The whole site is GB2312-encoded (declared in the meta tag); every
 *   fetch is decoded with [charset] ("GBK", the superset), and the app
 *   parses chapter pages with the same charset.
 * - Catalog is the site-wide "latest updates" list at /newest/:
 *   20 books per page, paginated as /newest/index_<n>.html (5 pages at
 *   verification time; past-the-end pages return the site 404 with no
 *   items). Items are `ul.clearfix li` blocks whose book link is
 *   `.info .title strong a` — the same <strong> also holds a category
 *   label link (e.g. /khjj/), so book links are filtered by the
 *   /<category>/<id> href shape. Some hrefs lack the ".html" suffix
 *   (e.g. /dsyq/20818); those URLs 301 to a different form, so ".html"
 *   is appended — the .html form serves the book page directly.
 * - Book titles on the site carry a chapter-range suffix, e.g.
 *   "祥子修仙记(1-377)" or "…(全本）"; the suffix is stripped everywhere
 *   (same cleanup the crawler applies to the book page <h1>).
 * - Search is the EmpireCMS form: POST /e/search/index.php with
 *   keyboard=<query GBK-percent-encoded>&show=title&classid=0. The
 *   endpoint answers 302 to /e/search/result/?searchid=<id>, whose
 *   page reuses the catalog item markup. A UTF-8-encoded keyboard
 *   finds nothing on this GBK site, so the body is built manually
 *   (OkHttp's FormBody would encode in UTF-8).
 * - Book page /<category>/<id>.html: title in .desc h1, description
 *   in .descInfo p. The full chapter list is server-rendered on the
 *   same page under .catalog ul.clearfix li a, already ordered
 *   oldest → newest (verified 1..596), with hrefs of the form
 *   /view/<classid>-<bookid>-<n>.html and generic titles ("第N节").
 * - Chapter page: the real chapter heading ("第5章 …") is the first
 *   <p> of #mycontent on most sections; the <h1> is "<book> 第N节".
 *   #mycontent contains only <p> elements (no scripts, links or ads),
 *   so the text needs no junk removal. The first section additionally
 *   carries the book's TXT header block (title/author/intro) before
 *   chapter 1 — that is the site's own content, kept as-is.
 * - Covers: the site has effectively none. Book pages contain no
 *   cover <img>, and the cover.js/covers.js scripts that would inject
 *   one are served as 0-byte files by the site itself. A handful of
 *   homepage-featured books have /d/file/<cat>/<timestamp>.jpg covers
 *   whose filenames cannot be derived from a book URL, so
 *   getBookCoverImageUrl is left at the default (null).
 */
class Powanjuan(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "powanjuan"
    override val nameStrId = R.string.source_name_powanjuan
    override val baseUrl = "https://powanjuan.cc/"
    override val catalogUrl = "https://powanjuan.cc/newest/"
    override val language = LanguageCode.CHINESE
    override val charset = "GBK"

    private val bookHrefRegex = Regex("^/[^/]+/\\d+(\\.html)?$")

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** Book hrefs without the ".html" suffix 301 elsewhere; the .html form is canonical. */
    private fun normalizeBookUrl(href: String): String {
        val resolved = resolve(href)
        return if (resolved.matches(Regex(".*/\\d+$"))) "$resolved.html" else resolved
    }

    /** Strips the site's chapter-range suffix: "祥子修仙记(1-377)" → "祥子修仙记". */
    private fun cleanTitle(raw: String): String {
        val cleaned = raw.substringBefore('(').substringBefore('（').trim()
        return cleaned.ifBlank { raw.trim() }
    }

    /**
     * Catalog, category and search-result pages share one item markup:
     * the book link is `.info .title strong a`. The <strong> can also
     * contain a category label link, so only /<category>/<id> hrefs
     * count as books. Items carry no cover image on this site.
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        val links = doc.select(".info .title strong a[href]").toList()
            .filter { bookHrefRegex.matches(it.attr("href")) }
        for (link in links) {
            val url = normalizeBookUrl(link.attr("href"))
            if (!seen.add(url)) continue
            val title = cleanTitle(link.text())
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
            // Most sections open with the real chapter heading as the
            // first paragraph of #mycontent (e.g. "第5章 …"). Sections
            // that start mid-chapter (and the first section, which
            // opens with the book's TXT header) fall back to the <h1>,
            // which reads "<book title> 第N节".
            val firstParagraph = doc.selectFirst("#mycontent p")
                ?.text()?.trim().orEmpty()
            if (firstParagraph.length in 3..60 &&
                chapterHeadingRegex.containsMatchIn(firstParagraph)
            ) {
                return@withContext firstParagraph
            }
            doc.selectFirst("h1")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content = doc.selectFirst("#mycontent")
                ?: doc.selectFirst(".content")
                ?: return@withContext ""
            // #mycontent holds only <p> elements on this site, but
            // clean a clone defensively so the shared document keeps
            // everything getChapterTitle needs (it runs after this).
            val clone = content.clone()
            clone.select("script, iframe, ins").remove()
            TextExtractor.get(clone)
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument(charset)
                val el = doc.selectFirst(".descInfo p")
                    ?: doc.selectFirst(".descInfo")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument(charset)
            // The full list is server-rendered on the book page under
            // .catalog, already ordered oldest → newest.
            val scoped = doc.select(".catalog ul.clearfix li a[href]")
            val candidates = if (scoped.isNotEmpty()) scoped
            else doc.select("a[href]")
            candidates
                .toList()
                .filter { it.attr("href").contains("/view/") }
                .map {
                    ChapterResult(
                        title = it.text().trim(),
                        url = resolve(it.attr("href"))
                    )
                }
                .filter { it.title.isNotBlank() }
                .distinctBy { it.url }
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}index_$page.html"

                val doc = networkClient.get(url).toDocument(charset)
                val books = parseBookList(doc)
                val hasNext = doc.select("a[href]").any {
                    it.attr("href") == "/newest/index_${page + 1}.html"
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
                if (input.isBlank() || index > 0)
                    return@tryConnect PagedList.createEmpty(index = index)
                // EmpireCMS search: the keyboard must be GBK-encoded —
                // a UTF-8 query silently returns "no results" here.
                val encoded = URLEncoder.encode(input, "GBK")
                val body = "keyboard=$encoded&show=title&classid=0"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val request = postRequest(
                    url = "${baseUrl}e/search/index.php",
                    headers = Headers.Builder().add("Referer", baseUrl).build(),
                    body = body
                )
                // The endpoint 302-redirects to the results page.
                val doc = networkClient.call(request, followRedirects = true)
                    .toDocument(charset)
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }

    private companion object {
        val chapterHeadingRegex =
            Regex("^第[0-9零一二三四五六七八九十百千万两]+[章节回卷部集]")
    }
}
