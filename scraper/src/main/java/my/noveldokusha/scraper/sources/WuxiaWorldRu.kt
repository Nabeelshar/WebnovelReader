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
 * WuxiaWorld.ru — https://wuxiaworld.ru (Russian ranobe, WordPress).
 * Not to be confused with [WuxiaWorld], which targets the
 * wuxiaworld.site mirror.
 *
 * Site structure verified live 2026-11-23:
 * - Each book is a WordPress category: the book page lives at
 *   /category/<slug>/ and chapters are posts at /<slug>/<post-name>/.
 * - Catalog: /chitat-onlajn-knigi-ranobe-manga-novelly/, paginated
 *   with /page/<n>/ (an out-of-range page returns 200 with no cards).
 *   Items are article.js-bookcard cards: title link h3 a ->
 *   /category/<slug>/, cover in img[itemprop=image].
 * - Search: the /poisk/ page server-renders the same cards for
 *   /poisk/?search=<q>, paginated as /poisk/page/<n>/?search=<q>
 *   (10 results per page).
 * - Book page: cover in .category-card img[itemprop=image],
 *   description in the (hidden) .description block, and a
 *   .js-cat-data element carrying data-category (category id),
 *   data-slug and data-count used by the chapter list.
 * - Chapter list: the book page only renders the newest ~30
 *   chapters; the full list comes from the theme's own endpoint
 *   POST /wp-content/themes/Wuxia/template-parts/category/chapters-query.php
 *   with form fields cat_id / limit / offset, returning a JSON array
 *   of {post_name, post_title} newest-first. Chapter titles on the
 *   site are the post titles with the book (category) name stripped.
 * - Chapter page: the story text is #js-full-content (inside
 *   .entry-content) as plain <p> paragraphs, with div.code-block ad
 *   slots interleaved and the first <p> repeating the chapter title.
 *   The <h1> is "<book title> <chapter title> Ранобэ Новелла".
 */
class WuxiaWorldRu(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "wuxiaworld_ru"
    override val nameStrId = R.string.source_name_wuxiaworld_ru
    override val baseUrl = "https://wuxiaworld.ru/"
    override val catalogUrl =
        "https://wuxiaworld.ru/chitat-onlajn-knigi-ranobe-manga-novelly/"
    override val language = LanguageCode.RUSSIAN

    private val chaptersEndpoint =
        "https://wuxiaworld.ru/wp-content/themes/Wuxia/template-parts/category/chapters-query.php"

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
            if (!href.contains("/category/")) continue
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
     * the <h1> wraps it as "<book> <chapter> Ранобэ Новелла".
     */
    private fun chapterTitleOf(doc: Document): String? {
        val h1 = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val content = doc.selectFirst("#js-full-content")
            ?: doc.selectFirst(".entry-content")
        val firstParagraph = content?.selectFirst("p")?.text()?.trim()
        if (!firstParagraph.isNullOrBlank() && firstParagraph.length <= 150 &&
            (h1.isBlank() || h1.contains(firstParagraph))
        ) {
            return firstParagraph
        }
        return h1.removeSuffix("Ранобэ Новелла").trim().ifBlank { null }
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
                doc.selectFirst(".description")?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val catData = doc.selectFirst(".js-cat-data")
            val catId = catData?.attr("data-category").orEmpty()
            val catSlug = catData?.attr("data-slug").orEmpty()
                .ifBlank { bookSlug(bookUrl) }
            val catName = catData?.attr("data-catname").orEmpty()
            if (catId.isBlank() || catSlug.isBlank()) {
                return@tryConnect parseRenderedChapters(doc)
            }
            // The endpoint returns chapters newest-first in pages;
            // fetch them all, then reverse into reading order.
            val newestFirst = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            val pageSize = 1000
            var offset = 0
            while (offset / pageSize < 10) {
                val request = postRequest(chaptersEndpoint).postPayload {
                    add("cat_id", catId)
                    add("limit", pageSize.toString())
                    add("offset", offset.toString())
                }
                val array = try {
                    networkClient.call(request).toJson().asJsonArray
                } catch (e: Exception) {
                    null
                } ?: break
                for (item in array) {
                    val obj = item.asJsonObject
                    val postName = obj.get("post_name")?.asString.orEmpty()
                    if (postName.isBlank() || postName == catSlug) continue
                    val url = "$baseUrl$catSlug/$postName/"
                    if (!seen.add(url)) continue
                    val rawTitle = obj.get("post_title")?.asString.orEmpty()
                    newestFirst += ChapterResult(
                        title = stripBookName(rawTitle, catName),
                        url = url
                    )
                }
                if (array.size() < pageSize) break
                offset += pageSize
            }
            if (newestFirst.isEmpty()) parseRenderedChapters(doc)
            else newestFirst.reversed()
        }
    }

    /** The site strips the book name off chapter post titles. */
    private fun stripBookName(postTitle: String, catName: String): String {
        val title = postTitle.trim()
        if (catName.isBlank()) return title
        return title.removePrefix(catName).trim().ifBlank { title }
    }

    private fun bookSlug(bookUrl: String): String =
        Regex("/category/([^/]+)").find(bookUrl)?.groupValues?.get(1).orEmpty()

    /**
     * Fallback for when the book page lacks its .js-cat-data block:
     * the server-rendered archive cards (newest ~30 chapters only).
     */
    private fun parseRenderedChapters(doc: Document): List<ChapterResult> {
        val chapters = mutableListOf<ChapterResult>()
        val seen = mutableSetOf<String>()
        for (link in doc.select("h3.arh-title a[href]")) {
            val url = resolve(link.attr("href"))
            if (!seen.add(url)) continue
            val title = link.text().trim()
            if (title.isBlank()) continue
            chapters += ChapterResult(title = title, url = url)
        }
        return chapters.reversed()
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val url = if (page == 1) catalogUrl else "${catalogUrl}page/$page/"
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
