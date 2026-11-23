package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.postPayload
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toJson
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Ranobe Novels — https://ranobe-novels.ru (Russian ranobe, WordPress).
 * Runs the same "ranobe-novels" theme family as [WuxiaWorldRu], but
 * with its own URL layout.
 *
 * Site structure verified live 2026-11-23 (curl fetches of the home,
 * catalog, search, book and chapter pages plus the theme's AJAX
 * endpoints, cross-checked against the lightnovel-crawler project's
 * RanobeNovel source):
 * - Each book is a WordPress category: the book page lives at
 *   /ranobe/<slug>/ and chapters are posts at /<post-name>/ (no book
 *   slug prefix, unlike wuxiaworld.ru).
 * - Catalog: the /poisk/ page with no query lists every book,
 *   paginated as /poisk/page/<n> (249 pages of 10 at verification
 *   time; pages are disjoint). Search is the same page with
 *   /poisk/?search=<q>, paginated as /poisk/page/<n>/?search=<q>.
 *   Both reuse one item markup: article.js-bookcard cards with the
 *   title link h3 a -> /ranobe/<slug>/ and the cover in
 *   img[itemprop=image]. (The theme also exposes a POST search
 *   endpoint, get-search-results.php, but the server-rendered page
 *   makes it unnecessary.)
 * - Book page: title in h2.category-title a, cover in
 *   .category-card img[itemprop=image] (the crawler's
 *   picture.category-img selector is dead — the <img> itself carries
 *   the category-img class now), description in
 *   div.category-exerpt.description, and a hidden .js-cat-data
 *   element carrying data-category (the WP category id), data-slug
 *   and data-count.
 * - Chapter list: the book page only renders the newest chapters;
 *   the full list comes from the theme's own endpoint
 *   POST /wp-content/themes/ranobe-novels/template-parts/category/chapters-query.php
 *   with form fields cat_id / limit / offset / query, returning a
 *   JSON array of {post_name, post_title} newest-first (verified:
 *   254 chapters for one book, 11,743 for the largest). Post titles
 *   here are already bare chapter titles — no book-name stripping
 *   needed. If .js-cat-data is missing, the category id is recovered
 *   from the page's wp-json alternate link, as the crawler does.
 * - Chapter page: the story text is #js-full-content (inside
 *   .entry-content) as plain <p> paragraphs, with div.code-block ad
 *   slots interleaved, a promo paragraph linking to the
 *   "kak-chitat-ranobe" page, and the first <p> repeating the
 *   chapter title. The <h1> is "<book title> <chapter title>".
 */
class RanobeNovels(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "ranobenovels"
    override val nameStrId = R.string.source_name_ranobenovels
    override val baseUrl = "https://ranobe-novels.ru/"
    override val catalogUrl = "https://ranobe-novels.ru/poisk/"
    override val language = LanguageCode.RUSSIAN

    private val chaptersEndpoint =
        "https://ranobe-novels.ru/wp-content/themes/ranobe-novels/template-parts/category/chapters-query.php"

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /** Catalog and search results share the article.js-bookcard markup. */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (card in doc.select("article.js-bookcard")) {
            val link = card.selectFirst("h3 a[href]") ?: continue
            val href = link.attr("href")
            if (!href.contains("/ranobe/")) continue
            val url = resolve(href)
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            val img = card.selectFirst("img[itemprop=image]")
                ?: card.selectFirst("img")
            val cover = img?.let {
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
        withContext(Dispatchers.Default) { chapterTitleOf(doc) }

    /**
     * The chapter title as the site shows it in the chapter list:
     * the first <p> of the chapter body repeats it verbatim, while
     * the <h1> wraps it as "<book title> <chapter title>".
     */
    private fun chapterTitleOf(doc: Document): String? {
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val content = doc.selectFirst("#js-full-content")
            ?: doc.selectFirst(".entry-content")
        val firstParagraph = content?.selectFirst("p")?.text()?.trim()
        if (!firstParagraph.isNullOrBlank() && firstParagraph.length <= 200 &&
            (h1.isBlank() || h1.contains(firstParagraph))
        ) {
            return firstParagraph
        }
        return h1.ifBlank { null }
    }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("#js-full-content")
                ?: doc.selectFirst(".entry-content")
                ?: return@withContext ""
            // Work on a clone: getChapterTitle still needs the
            // original document (its first <p>) after this call.
            val clone = container.clone()
            clone.select(
                "script, iframe, ins, style, form, .code-block, .js-remove, .post__img"
            ).remove()
            // Drop the repeated title paragraph.
            val title = chapterTitleOf(doc)
            if (title != null) {
                clone.select("p").firstOrNull { it.text().trim() == title }
                    ?.remove()
            }
            // Drop site promo paragraphs (they link to the
            // "how to read without ads" page).
            clone.select("a[href*=kak-chitat-ranobe]").toList().forEach {
                it.closest("p")?.remove()
            }
            TextExtractor.get(clone)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val img = doc.selectFirst(".category-card img[itemprop=image]")
                    ?: doc.selectFirst("img[itemprop=image]")
                img?.let {
                    resolve(it.attr("data-src").ifBlank { it.attr("src") })
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("div.category-exerpt.description")
                    ?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val catId = doc.selectFirst(".js-cat-data")
                ?.attr("data-category").orEmpty()
                .ifBlank { categoryIdFromAlternate(doc) }
            if (catId.isBlank()) return@tryConnect emptyList()
            // The endpoint returns chapters newest-first in pages;
            // fetch them all, then reverse into reading order.
            val newestFirst = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            val pageSize = 1000
            var offset = 0
            while (offset / pageSize < 20) {
                val request = postRequest(chaptersEndpoint).postPayload {
                    add("cat_id", catId)
                    add("limit", pageSize.toString())
                    add("offset", offset.toString())
                    add("query", "")
                }
                val array = try {
                    networkClient.call(request).toJson().asJsonArray
                } catch (e: Exception) {
                    null
                } ?: break
                for (item in array) {
                    val obj = item.asJsonObject
                    val postName = obj.get("post_name")?.asString.orEmpty()
                    if (postName.isBlank()) continue
                    val url = "$baseUrl$postName/"
                    if (!seen.add(url)) continue
                    val title = obj.get("post_title")?.asString.orEmpty().trim()
                    if (title.isBlank()) continue
                    newestFirst += ChapterResult(title = title, url = url)
                }
                if (array.size() < pageSize) break
                offset += pageSize
            }
            newestFirst.reversed()
        }
    }

    /**
     * Fallback for the WP category id: the book page's
     * <link rel="alternate" type="application/json"> points at
     * /wp-json/wp/v2/categories/<id>.
     */
    private fun categoryIdFromAlternate(doc: Document): String {
        val href = doc.selectFirst(
            "link[rel=alternate][type=application/json][href*=/wp-json/wp/v2/categories/]"
        )?.attr("href").orEmpty()
        return href.trimEnd('/').substringAfterLast("/")
            .takeIf { it.all(Char::isDigit) }.orEmpty()
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl
                else "${baseUrl}poisk/page/$page/"
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
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
                val path = if (page == 1) "poisk" else "poisk/page/$page"
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath(*path.split("/").toTypedArray())
                    .add("search", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)
                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty()
                )
            }
        }

}
