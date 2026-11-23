package my.noveldokusha.scraper.sources

import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
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
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Gravity Tales — https://gravitytales.com
 *
 * The site was rebuilt (WordPress + Fictioneer theme) and no longer
 * uses the old /novel/<id> URLs the previous crawlers knew. Structure
 * verified live 2026-11-23 against the real pages:
 * - Books live at /story/<slug>/, chapters at
 *   /story/<slug>/<chapter-slug>/. There is no paginated HTML catalog:
 *   the front page only shows the 20 latest story cards. The catalog
 *   therefore uses the site's public WordPress REST API:
 *   GET /wp-json/wp/v2/fcn_story?per_page=100&page=<n>&_embed=1
 *   returns a JSON array of {title.rendered, link, _embedded
 *   ["wp:featuredmedia"][0].source_url} (355 stories at verification
 *   time, X-WP-Total header). The REST collection's own ?search=
 *   parameter returns nothing for this post type, so search uses the
 *   site's HTML search instead.
 * - Search is the WordPress search form: GET /?s=<q>&post_type=any,
 *   paginated as /page/<n>/?s=<q>&post_type=any (15 cards per page).
 *   Results mix story cards and chapter cards; both carry a link to
 *   the book page (the story title link on story cards, a story link
 *   in the meta row on chapter cards) and the book's cover <img>, so
 *   chapter hits are mapped back to their book and de-duplicated.
 * - Book page: title h1.story__identity-title, cover
 *   .story__thumbnail img, description .story__summary. The full
 *   chapter list is rendered on the book page in reading order
 *   (oldest first): li.chapter-group__list-item._publish holds one
 *   a.chapter-group__list-item-link per published chapter. Scheduled
 *   / locked entries (_future) link to ?post_type=fcn_chapter&p=<id>
 *   placeholders instead and are excluded.
 * - Chapter page: title h1.chapter__title (matches the chapter-list
 *   titles, subtitles included), story text in
 *   section#chapter-content (.chapter__content) as <p> paragraphs.
 */
class GravityTales(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "gravitytales"
    override val nameStrId = R.string.source_name_gravitytales
    override val baseUrl = "https://gravitytales.com/"
    override val catalogUrl = "https://gravitytales.com/"
    override val iconUrl = "https://gravitytales.com/favicon.ico"
    override val language = LanguageCode.ENGLISH

    private val apiUrl = "https://gravitytales.com/wp-json/wp/v2/fcn_story"
    private val catalogPageSize = 100
    private val bookUrlRegex =
        Regex("^https://gravitytales\\.com/story/[^/]+/$")

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter__title")?.text()
                ?: doc.selectFirst("h1")?.text()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val body = doc.selectFirst("#chapter-content")
                ?: doc.selectFirst(".chapter__content")
                ?: return@withContext ""
            body.select("script, iframe").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".story__thumbnail img")
                val fromImg = img?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
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
                doc.selectFirst(".story__summary")
                    ?.let { TextExtractor.get(it) }
                    ?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val prefix = bookUrl.trimEnd('/') + "/"
            doc.select(".chapter-group__list-item._publish a[href]")
                .toList()
                .map {
                    ChapterResult(
                        title = it.text().trim(),
                        url = resolve(it.attr("href"))
                    )
                }
                // Only real chapter pages of this book: scheduled /
                // locked entries point at ?post_type=fcn_chapter
                // placeholders, not at /story/<slug>/<chapter>/.
                .filter { it.url.startsWith(prefix) && it.title.isNotBlank() }
                .distinctBy { it.url }
        }
    }

    private fun parseStory(story: JsonObject): BookResult? {
        val url = story.str("link")
        val rendered = story.getAsJsonObject("title")?.str("rendered").orEmpty()
        val title = Jsoup.parse(rendered).text().trim()
        if (url.isBlank() || title.isBlank()) return null
        val cover = story.getAsJsonObject("_embedded")
            ?.getAsJsonArray("wp:featuredmedia")
            ?.firstOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.str("source_url")
            .orEmpty()
        return BookResult(
            title = title,
            url = url,
            coverImageUrl = cover
        )
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val url = apiUrl.toUrlBuilderSafe()
                    .add("per_page", catalogPageSize)
                    .add("page", index + 1)
                    .add("_embed", 1)
                    .toString()
                val books = networkClient.get(url).toJson().asJsonArray
                    .mapNotNull { element ->
                        element.takeIf { it.isJsonObject }
                            ?.asJsonObject
                            ?.let { parseStory(it) }
                    }
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.size < catalogPageSize
                )
            }
        }

    /**
     * Search results are Fictioneer cards (.card). Story cards link
     * their title to the book page; chapter cards carry the book's
     * title and URL in a meta-row link instead. Both carry the book's
     * cover as the card <img>. Collect the book-level link of every
     * card, de-duplicated, in result order.
     */
    private fun parseCards(root: Element): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in root.select(".card")) {
            val bookLinks = card.select("a[href]").toList()
                .filter { bookUrlRegex.matches(resolve(it.attr("href"))) }
            val titleLink = bookLinks.firstOrNull { it.text().isNotBlank() }
                ?: continue
            val url = resolve(titleLink.attr("href"))
            if (!seen.add(url)) continue
            val img = card.selectFirst("img")
            val cover = img?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }.orEmpty()
            books += BookResult(
                title = titleLink.text().trim(),
                url = url,
                coverImageUrl = resolve(cover)
            )
        }
        return books
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
                if (page > 1) builder.addPath("page", page.toString())
                val url = builder
                    .add("s", input)
                    .add("post_type", "any")
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseCards(doc)
                val hasNext = doc.select("a[href]").any {
                    it.attr("href").contains("/page/${page + 1}/")
                }
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || !hasNext
                )
            }
        }
}
