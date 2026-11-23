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
import java.net.URLDecoder

/**
 * WoopRead — https://woopread.com
 *
 * Site structure verified live 2026-11-23 (curl fetches of the
 * homepage, browse, search, book and chapter pages, cross-checked
 * against the lightnovel-crawler project's WoopRead source). The
 * site has been rebuilt since the crawler was written: the old
 * WordPress/Madara markup (.c-tabs-item__content, the
 * /ajax/chapters POST, .reading-content) is gone. It is now a
 * Next.js app, but every page is server-rendered, so plain HTML
 * parsing works throughout:
 * - Catalog: /browse?page=<n>, 20 book cards per page; pages past
 *   the end return an empty list. Search: /search?q=<query>&page=<n>
 *   reuses the same card markup (different card layout, same
 *   anchors). A card is an <a href="/series/<slug>"> holding a
 *   heading with the title (h3 in browse cards, h2 in search
 *   cards) and the cover <img>; the img src goes through the
 *   /_next/image proxy, whose "url" query parameter is the real
 *   CDN cover URL.
 * - Book page /series/<slug>: title in <h1>, cover in
 *   meta[property=og:image] (a direct imgcdn.woopread.com URL),
 *   synopsis in div.text-text-secondary.line-clamp-3.
 * - Chapter list: the rendered chapter grid only shows a window
 *   of the newest chapters (and almost none for some books), but
 *   the page's embedded RSC flight payload carries the book's
 *   full chapter array as JSON objects
 *   {"id":…,"title":…,"number":…,"slug":…} already ordered
 *   oldest to newest (verified: 481/481 chapters for one book,
 *   exactly 238 for another). Those are extracted from the
 *   <script> contents; chapter URLs are <book-url>/<slug>. If
 *   the payload ever disappears, the rendered grid anchors are
 *   used as a (partial, newest-first) fallback.
 * - Chapter page /series/<slug>/<chapter-slug>: the chapter
 *   title is h2.notranslate; the story is div[id^="chapter-"]
 *   (one per chapter), paragraphs wrapped in divs with a TTS
 *   <button> each (buttons carry no text). Credit paragraphs
 *   ("Translator:" / "Editor:") and a leading paragraph that
 *   repeats the chapter title are removed, on a clone.
 *   Locked premium chapters contain "This chapter is locked"
 *   and yield empty text, as in the old crawler.
 */
class WoopRead(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "woopread"
    override val nameStrId = R.string.source_name_woopread
    override val baseUrl = "https://woopread.com/"
    override val catalogUrl = "https://woopread.com/browse"
    override val language = LanguageCode.ENGLISH

    private fun resolve(url: String): String =
        if (url.isBlank()) ""
        else if (url.startsWith("http")) url
        else URI(baseUrl).resolve(url).toString()

    /**
     * Cover <img> srcs are Next.js image-proxy URLs
     * (/_next/image?url=<encoded>&w=…&q=…); the real cover is the
     * decoded "url" parameter. Non-proxy srcs are just resolved.
     */
    private fun decodeCover(src: String): String {
        if (src.isBlank()) return ""
        if (src.contains("/_next/image?")) {
            val query = src.substringAfter("?", "")
            for (param in query.split("&")) {
                if (param.startsWith("url=")) {
                    return URLDecoder.decode(param.removePrefix("url="), "UTF-8")
                }
            }
        }
        return resolve(src)
    }

    /**
     * Catalog and search share the card anchor: an
     * <a href="/series/<slug>"> (exactly one path segment after
     * /series/ — chapter links have a second segment). The title
     * is the card's heading; the img alt and the aria-label are
     * fallbacks ("Cover image for <title>" / "View details for
     * <title>").
     */
    private fun parseBookList(doc: Document): List<BookResult> {
        val books = mutableListOf<BookResult>()
        val seen = mutableSetOf<String>()
        for (link in doc.select("a[href]").toList()) {
            val url = resolve(link.attr("href"))
            val segments = try {
                URI(url).path.split("/").filter { it.isNotEmpty() }
            } catch (e: Exception) {
                continue
            }
            if (segments.size != 2 || segments[0] != "series") continue
            if (!seen.add(url)) continue
            val img = link.selectFirst("img")
            val title = link.selectFirst("h2, h3, h4")?.text()?.trim()
                ?.ifBlank { null }
                ?: img?.attr("alt")?.removePrefix("Cover image for ")?.trim()
                    ?.ifBlank { null }
                ?: link.attr("aria-label")
                    .removePrefix("View details for ").trim()
            if (title.isBlank()) continue
            books += BookResult(
                title = title,
                url = url,
                coverImageUrl = decodeCover(img?.attr("src") ?: "")
            )
        }
        return books
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h2.notranslate")?.text()?.trim()?.ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            // Locked premium chapters carry no story text.
            if (doc.text().contains("This chapter is locked")) return@withContext ""
            val container = doc.selectFirst("div[id^=\"chapter-\"]")
                ?: return@withContext ""
            // Work on a clone: the same Document is reused for
            // getChapterTitle afterwards.
            val body = container.clone()
            body.select("script, iframe, button").remove()
            // Translator/editor credit paragraphs are not story.
            body.select("p").toList()
                .filter {
                    it.text().contains("Translator:") || it.text().contains("Editor:")
                }
                .forEach { it.remove() }
            // A leading paragraph that just repeats the chapter
            // title (the h2 heading) is dropped.
            val title = doc.selectFirst("h2.notranslate")?.text()?.trim()
            val firstParagraph = body.select("p").firstOrNull()
            if (title != null && firstParagraph != null) {
                val first = firstParagraph.text().trim()
                val repeatsTitle = first.length < 150 &&
                    (first.equals(title, ignoreCase = true) ||
                        first.startsWith("$title:", ignoreCase = true) ||
                        first.startsWith("$title -", ignoreCase = true))
                if (repeatsTitle) firstParagraph.remove()
            }
            TextExtractor.get(body)
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.ifBlank { null }
                    ?: doc.select("img").toList()
                        .firstOrNull { it.attr("src").contains("/wp-content/uploads/") }
                        ?.let { decodeCover(it.attr("src")) }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val el = doc.selectFirst("div.text-text-secondary.line-clamp-3")
                el?.let { TextExtractor.get(it) }
            }
        }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val fromPayload = parsePayloadChapters(doc, bookUrl)
            if (fromPayload.isNotEmpty()) return@tryConnect fromPayload
            // Fallback: the rendered chapter grid (newest first,
            // only a window of the newest chapters) — better than
            // nothing if the payload format ever changes.
            val chapters = mutableListOf<ChapterResult>()
            val seen = mutableSetOf<String>()
            for (anchor in doc.select("a[href]").toList()) {
                val url = resolve(anchor.attr("href"))
                if (!url.contains("/chapter-")) continue
                if (!seen.add(url)) continue
                val title = anchor.selectFirst("div")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: anchor.text().trim()
                if (title.isBlank()) continue
                chapters += ChapterResult(title = title, url = url)
            }
            chapters.reverse()
            chapters
        }
    }

    /**
     * The book page's <script> flight payload embeds the full
     * chapter array as escaped JSON objects:
     * {\"id\":\"…\",\"title\":\"…\",\"number\":N,\"slug\":\"…\"},
     * already ordered oldest to newest. Extract, unescape titles,
     * dedupe by slug and (stably) sort by number as a safety net.
     */
    private fun parsePayloadChapters(
        doc: Document,
        bookUrl: String
    ): List<ChapterResult> {
        val scripts = StringBuilder()
        for (script in doc.select("script")) {
            scripts.append(script.data()).append('\n')
        }
        val found = mutableListOf<Pair<Double, ChapterResult>>()
        val seen = mutableSetOf<String>()
        for (match in chapterObjectRegex.findAll(scripts.toString())) {
            val slug = match.groupValues[4]
            if (!seen.add(slug)) continue
            val number = match.groupValues[3].toDoubleOrNull() ?: continue
            val title = match.groupValues[2]
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
                .trim()
            if (title.isBlank()) continue
            found += number to ChapterResult(
                title = title,
                url = bookUrl.removeSuffix("/") + "/" + slug
            )
        }
        found.sortBy { it.first }
        return found.map { it.second }
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect("index=$index") {
                val page = index + 1
                val doc = networkClient.get("$catalogUrl?page=$page").toDocument()
                val books = parseBookList(doc)

                PagedList(
                    list = books,
                    index = index,
                    // Pages past the end return an empty list.
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
                val url = baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .add("q", input)
                    .add("page", (index + 1).toString())
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

    private companion object {
        val chapterObjectRegex = Regex(
            """\\"id\\":\\"([^\\"]+)\\",\\"title\\":\\"((?:[^\\]|\\.)*?)\\",""" +
                """\\"number\\":(\d+(?:\.\d+)?),\\"slug\\":\\"([^\\"]+)\\""""
        )
    }
}
