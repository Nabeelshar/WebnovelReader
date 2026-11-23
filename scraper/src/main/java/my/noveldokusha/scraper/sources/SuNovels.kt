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
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI

/**
 * SuNovels — https://sunovels.com (Arabic, "شمس الروايات")
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * library, search, book and chapter pages). The site is a custom
 * Next.js app; it matches neither the Madara nor the NovelFull
 * template. There is no lightnovel-crawler source for it.
 * - Catalog: /library, paginated with a ZERO-based ?page=<n>
 *   (page 0 is the first page; the parameter-less URL is page 0).
 *   24 books per page; a page past the end renders an empty grid.
 *   Items are ul.grid-list cards: the card anchor links to
 *   /novel/<slug> and its <h4> carries the (Arabic) title. The
 *   card <img> is lazy-loaded (src is /placeholder.gif); the real
 *   cover URL only exists in the page's RSC payload
 *   (self.__next_f), where one /uploads/... image per book appears
 *   in the same order as the cards — the parser zips the two
 *   ordered lists, and only when their sizes match.
 * - Search: /search/?title=<query> (the param the site's own
 *   search page uses; ?q= / ?keyword= return nothing). Results
 *   reuse the catalog grid, 12 per page, and the site ignores any
 *   page parameter — a single page of results.
 * - Book page /novel/<slug>: cover in og:image, synopsis in
 *   div.description.
 * - Chapter list: the book page's chapters tab,
 *   <bookUrl>?activeTab=chapters&page=<n>, again ZERO-based,
 *   50 chapters per page, listed oldest to newest. Pages are
 *   slices of the site's chapter rows, so chapter numbers can
 *   skip (deleted chapters) and a number can repeat (the app
 *   dedupes by URL); the walk stops at the first page whose
 *   ul.chaptersList is empty. Items are the ul's direct <a>
 *   children; the title is the .chapter-title text (often just
 *   the chapter number — that is how the site titles most
 *   chapters — but titled chapters carry their full title).
 * - Chapter page /novel/<slug>/<num>: the chapter title is the
 *   <h2> inside div.titles (the <h1> next to it is the book
 *   title). The story is div.chapter-content, whose direct <p>
 *   children alternate between real paragraphs and hidden
 *   watermark paragraphs (class "d-none": a hex hash plus the
 *   site name) — the hidden ones are stripped. Many later
 *   chapters are locked on the web ("the requested chapter is
 *   locked..."): their chapter-content holds only a lock notice
 *   in nested divs and no direct story paragraphs, so they yield
 *   empty text instead of the notice.
 */
class SuNovels(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "sunovels"
    override val nameStrId = R.string.source_name_sunovels
    override val baseUrl = "https://sunovels.com/"
    override val catalogUrl = "https://sunovels.com/library"
    override val language = LanguageCode.ARABIC

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Catalog and search results share one item markup:
     * ul.grid-list cards whose anchor links to /novel/<slug> with
     * the title in its <h4>. Covers are not in the DOM (the <img>
     * is a lazy placeholder); they are recovered from the page's
     * RSC payload, where exactly one /uploads/... image per book
     * appears in card order — zipped only when the counts match,
     * otherwise covers are left empty (the book page's og:image
     * still provides one via getBookCoverImageUrl).
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val cards = doc.select("ul.grid-list a[href]").toList()
            .filter { anchor ->
                val href = anchor.attr("href")
                href.startsWith("/novel/") && href.removePrefix("/novel/")
                    .let { it.isNotEmpty() && !it.contains("/") }
            }
        val covers = Regex("/uploads/[^\"\\\\ ]+")
            .findAll(doc.html())
            .map { it.value }
            .toList()
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        cards.forEachIndexed { i, anchor ->
            val url = resolve(anchor.attr("href"))
            if (!seen.add(url)) return@forEachIndexed
            val title = anchor.selectFirst("h4")?.text()?.trim()
                ?.ifBlank { null }
                ?: anchor.text().trim()
            if (title.isBlank()) return@forEachIndexed
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = if (covers.size == cards.size)
                    resolve(covers[i])
                else ""
            )
        }
        return books
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            // div.titles holds the book title in <h1> and the
            // chapter title in <h2>.
            doc.selectFirst(".titles h2")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val container = doc.selectFirst("div.chapter-content")
                ?: return@withContext ""
            // The story lives in the container's direct <p>
            // children. Locked chapters have none (only a lock
            // notice nested in divs) and yield empty text.
            val storyParas = container.children().toList()
                .filter { it.tagName() == "p" && !it.hasClass("d-none") }
            if (storyParas.isEmpty()) return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            // Drop every non-paragraph child (notices, badges) and
            // the hidden watermark paragraphs interleaved in the
            // text (class "d-none": hex hash + site name).
            body.children().toList()
                .filter { it.tagName() != "p" || it.hasClass("d-none") }
                .forEach { it.remove() }
            body.select("script, iframe, ins").remove()
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?.let { resolve(it) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("div.description")
                    ?: doc.selectFirst("meta[name=description]")
                el?.let {
                    if (it.tagName() == "meta") it.attr("content")
                    else TextExtractor.get(it)
                }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            // The chapters tab paginates 50 chapters per page with
            // a zero-based page parameter; the walk stops at the
            // first page with an empty list. The generous cap only
            // guards against a site change looping forever.
            var page = 0
            while (page < 400) {
                val url = bookUrl.toUrlBuilderSafe()
                    .add("activeTab", "chapters")
                    .add("page", page.toString())
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val items = doc.select("ul.chaptersList > a[href]").toList()
                if (items.isEmpty()) break
                var added = 0
                for (anchor in items) {
                    val chapterUrl = resolve(anchor.attr("href"))
                    if (!seen.add(chapterUrl)) continue
                    val title = anchor.selectFirst(".chapter-title")
                        ?.text()?.trim()?.ifBlank { null }
                        ?: anchor.attr("title").ifBlank { null }
                        ?: anchor.text().trim()
                    if (title.isBlank()) continue
                    chapters += ChapterResult(title = title, url = chapterUrl)
                    added++
                }
                if (added == 0) break
                page++
            }
            // The site lists chapters oldest to newest already.
            chapters
        }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                // The library paginates with a zero-based ?page=
                // parameter, matching the app's page index.
                val url = catalogUrl.toUrlBuilderSafe()
                    .add("page", index.toString())
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    isLastPage = books.isEmpty() || books.size < 24
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
                // The site's own search page queries
                // /search/?title=<query>; results come back as a
                // single page (the site ignores pagination here).
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .add("title", input)
                    .toString()
                val doc = networkClient.get(url).toDocument()
                val books = parseBookList(doc)

                PagedList(list = books, index = index, isLastPage = true)
            }
        }
}
