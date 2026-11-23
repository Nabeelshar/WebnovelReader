package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toJson
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Xiaowaz — https://xiaowaz.fr (French fan translations)
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * book and chapter pages plus the WordPress REST API, cross-checked
 * against the lightnovel-crawler project's Xiaowaz source):
 * - WordPress site. Each book is a WP page under /series-en-cours/,
 *   /series-abandonnees/ or /oeuvres-originales/; chapters are posts
 *   under /articles/<slug>/. The parent listing pages
 *   (/series-en-cours/ etc.) are unusable — WordPress answers them
 *   with a broken 301 to "#". The catalog therefore uses the public
 *   WP REST API: GET /wp-json/wp/v2/pages?per_page=100&page=<n>
 *   (39 pages total on the whole site at verification time, 20 of
 *   them books). A book's cover is the first <img> of its page
 *   content — the same image the book page shows.
 * - Search uses the same endpoint's ?search= parameter (it matches
 *   page titles and content), filtered to book pages, so chapter
 *   posts never pollute the results.
 * - Book page: title in h1.card_title, cover is the first
 *   .entry-content img, and the synopsis is the <p> text of
 *   .entry-content before the "Sommaire" chapter list. The chapter
 *   list itself is .entry-content a[href*='/articles/'] in document
 *   order — the site lists chapters oldest first already (997 links
 *   for Overgeared, all unique, all inside the sommaire list).
 * - Chapter page: title in h1.card_title (identical to the list
 *   title), body in .entry-content minus the author box
 *   (.abh_box_business), the footnote container and the prev/next
 *   navigation (.wp-post-navigation) blocks.
 */
class Xiaowaz(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "xiaowaz"
    override val nameStrId = R.string.source_name_xiaowaz
    override val baseUrl = "https://xiaowaz.fr/"
    override val catalogUrl = "https://xiaowaz.fr/"
    override val iconUrl = "https://xiaowaz.fr/favicon.ico"
    override val language = LanguageCode.FRENCH

    private val apiUrl = "https://xiaowaz.fr/wp-json/wp/v2/pages"
    private val catalogPageSize = 100
    private val searchPageSize = 50

    /** Book pages: /<section>/<slug>/ with a single-segment slug. */
    private val bookLinkRegex =
        Regex("^https://xiaowaz\\.fr/(series-en-cours|series-abandonnees|oeuvres-originales)/[^/]+/$")

    /** Chapter posts: /articles/<slug>/ with a single-segment slug. */
    private val chapterLinkRegex =
        Regex("^/articles/[^/]+/$")

    private val coverRegex = Regex("<img[^>]+src=\"([^\"]+)\"")

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    private fun cleanTitle(rendered: String): String =
        Jsoup.parse(rendered).text()
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.card_title")?.text()?.trim()?.ifBlank { null }
                ?: doc.title().substringBefore(" | ").trim().ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val content = doc.selectFirst(".entry-content")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards, so nothing in the original
            // may be removed.
            val body = content.clone()
            body.select(
                ".abh_box_business, .footnote_container_prepare, " +
                    ".wp-post-navigation, script, iframe, style, ins"
            ).remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".entry-content img")
                val fromImg = img?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }
                fromImg?.takeIf { it.isNotBlank() }?.let { resolve(it) }
                    ?: doc.selectFirst("meta[property=og:image]")
                        ?.attr("content")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val content = doc.selectFirst(".entry-content")
                    ?: return@tryConnect null
                // The synopsis is the <p> text before the "Sommaire"
                // chapter list; headings (book titles, section
                // labels) and the list itself are skipped.
                val parts = mutableListOf<String>()
                for (child in content.children()) {
                    if (child.selectFirst("a[href*=/articles/]") != null) break
                    if (child.tagName() == "p") {
                        val text = child.text().trim()
                        if (text.isNotBlank()) parts += text
                    }
                }
                parts.joinToString("\n\n").ifBlank { null }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // The book page's sommaire links every chapter post in
            // reading order (oldest first). Only single-segment
            // /articles/<slug>/ links are chapters — author and
            // category archives live one level deeper.
            for (anchor in doc.select(".entry-content a[href*=/articles/]")) {
                val href = anchor.attr("href")
                val path = runCatching { URI(href).path }.getOrNull().orEmpty()
                if (!chapterLinkRegex.matches(path)) continue
                val url = resolve(href)
                if (!seen.add(url)) continue
                val title = anchor.text().trim()
                if (title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
            chapters
        }
    }

    private fun parseBookPage(page: JsonObject): BookResult? {
        val url = page.str("link")
        if (!bookLinkRegex.matches(url)) return null
        val rendered = page.getAsJsonObject("title")?.str("rendered").orEmpty()
        val title = cleanTitle(rendered)
        if (title.isBlank()) return null
        val content = page.getAsJsonObject("content")?.str("rendered").orEmpty()
        val cover = coverRegex.find(content)?.groupValues?.get(1).orEmpty()
        return BookResult(
            title = title,
            url = url,
            coverImageUrl = resolve(cover)
        )
    }

    private suspend fun fetchBooks(
        pageIndex: Int,
        pageSize: Int,
        search: String?
    ): PagedList<BookResult> {
        val builder = apiUrl.toUrlBuilderSafe()
            .add("per_page", pageSize)
            .add("page", pageIndex + 1)
            .add("_fields", "id,link,title,content")
        if (!search.isNullOrBlank()) builder.add("search", search)
        val array = networkClient.get(builder.toString()).toJson().asJsonArray
        val books = array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { parseBookPage(it) }
        }
        return PagedList(
            list = books,
            index = pageIndex,
            isLastPage = array.size() < pageSize
        )
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                fetchBooks(index, catalogPageSize, search = null)
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
                fetchBooks(index, searchPageSize, search = input)
            }
        }
}
