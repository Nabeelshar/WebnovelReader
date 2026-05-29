package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.text_translator.domain.TranslationModelState
import my.noveldokusha.text_translator.domain.TranslatorState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Translation manager using Google Translate widget translateHtml API (no user API key).
 * Matches browser requests from translate-pa.googleapis.com (twkan.com HAR).
 */
class TranslationManagerGoogleFree(
    private val coroutineScope: AppCoroutineScope,
    private val appPreferences: AppPreferences,
) : TranslationManager {

    private val apiKey: String
        get() = appPreferences.TRANSLATION_GOOGLE_API_KEY.value.trim()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override val available = true
    override val isUsingOnlineTranslation = true

    // Cache for translations
    private val translationCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    // Google Translate supports many languages
    override val models = mutableStateListOf<TranslationModelState>().apply {
        val supportedLanguages = listOf(
            "auto", // API detect-language (translateHtml); pass-through in normalizeLanguageCode
            "en", "zh", "ja", "ko", "es", "fr", "de", "it", "pt", "ru",
            "ar", "hi", "th", "vi", "id", "tr", "pl", "nl", "sv", "da",
            "fi", "no", "cs", "el", "he", "ro", "hu", "uk", "bg", "hr"
        )

        addAll(supportedLanguages.map { lang ->
            TranslationModelState(
                language = lang,
                available = true, // Always available via API
                downloading = false,
                downloadingFailed = false
            )
        })
    }

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? {
        return models.firstOrNull { it.language == language }
    }

    override fun getTranslator(source: String, target: String): TranslatorState {
        Log.d(TAG, "getTranslator: source=$source, target=$target")
        return TranslatorState(
            source = source,
            target = target,
            translate = { input -> translateWithGoogleFree(input, source, target) }
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * translateHtml as used by the Google Translate widget (captured from twkan.com HAR).
     * Payload: [[[content, ...], source_lang, target_lang], "te_lib"]
     */
    private suspend fun translateWithGoogleFree(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        retryCount: Int = 2
    ): String = withContext(Dispatchers.IO) {
        val cacheKey = "$sourceLanguage-$targetLanguage:$text"
        translationCache[cacheKey]?.let {
            Log.d(TAG, "translateWithGoogleFree: using cached translation")
            return@withContext it
        }

        Log.d(TAG, "translateWithGoogleFree: starting translation (length=${text.length})")

        val html = buildTaggedHtml(listOf(text))
        if (html.length > MAX_TAGGED_HTML_CHARS) {
            Log.d(TAG, "translateWithGoogleFree: payload too long (${html.length} > $MAX_TAGGED_HTML_CHARS), splitting...")
            return@withContext translateLongText(text, sourceLanguage, targetLanguage)
        }

        var lastException: Exception? = null
        repeat(retryCount) { attempt ->
            try {
                Log.d(TAG, "translateWithGoogleFree: attempt ${attempt + 1}/$retryCount")

                val responseBody = postTranslateHtml(listOf(html), sourceLanguage, targetLanguage)
                val translatedHtml = parseFirstTranslatedString(responseBody)

                val result = extractTextFromTaggedHtml(translatedHtml, 0) ?: translatedHtml.trim()

                if (result.isNotEmpty() && !result.startsWith("[Translation")) {
                    Log.d(TAG, "translateWithGoogleFree: success, result length=${result.length}")
                    translationCache[cacheKey] = result
                    return@withContext result
                }

                Log.w(TAG, "translateWithGoogleFree: empty or failed response")

            } catch (e: Exception) {
                Log.e(TAG, "translateWithGoogleFree: error on attempt ${attempt + 1} - ${e.message}", e)
                lastException = e
            }

            if (attempt < retryCount - 1) {
                kotlinx.coroutines.delay(200L * (attempt + 1))
            }
        }

        return@withContext "[Translation error: ${lastException?.message?.take(50) ?: "unknown"}]"
    }

    /** Unescape common HTML entities returned by translateHtml */
    private fun unescapeHtml(text: String): String {
        return text
            .replace("&#39;", "'")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private suspend fun translateLongText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "translateLongText: splitting text (${text.length} chars) into ~$MAX_RAW_TEXT_CHARS-char batches")

        val (firstPart, secondPart) = splitAtLimit(text, MAX_RAW_TEXT_CHARS)
        Log.d(TAG, "translateLongText: part1=${firstPart.length} chars, part2=${secondPart.length} chars")

        if (firstPart.isEmpty() && secondPart.isEmpty()) {
            return@withContext ""
        }

        val (translatedFirst, translatedSecond) = coroutineScope {
            val deferredFirst = async {
                if (firstPart.isNotEmpty()) {
                    translateWithGoogleFree(firstPart, sourceLanguage, targetLanguage)
                } else ""
            }

            val deferredSecond = async {
                if (secondPart.isNotEmpty()) {
                    translateWithGoogleFree(secondPart, sourceLanguage, targetLanguage)
                } else ""
            }

            Pair(deferredFirst.await(), deferredSecond.await())
        }
        return@withContext if (translatedFirst.isNotEmpty() && translatedSecond.isNotEmpty()) {
            "$translatedFirst $translatedSecond"
        } else {
            (translatedFirst + translatedSecond).trim()
        }
    }


    /**
     * Split text into (first ~limit chars, remaining).
     * Tries to break on sentence boundary or whitespace near the limit to preserve context.
     */
    private fun splitAtLimit(text: String, limit: Int): Pair<String, String> {
        if (text.length <= limit) return Pair(text, "")

        // Try to find a sentence-ending punctuation at or before limit
        val sentenceEnd = text.substring(0, limit).lastIndexOfAny(charArrayOf('.', '!', '?', '。', '！', '？', '\n'))
        val splitIndex = when {
            sentenceEnd > limit / 2 -> sentenceEnd + 1
            else -> {
                // Fall back to a whitespace near the limit
                val ws = text.substring(0, limit).lastIndexOf(' ')
                if (ws > limit / 2) ws else limit
            }
        }

        val first = text.substring(0, splitIndex).trim()
        val second = text.substring(splitIndex).trim()
        return Pair(first, second)
    }

    /**
     * Translate all paragraphs using <a i=N> tags for paragraph-level mapping.
     * This mirrors the Google Translate website widget's approach from the HAR capture,
     * ensuring each paragraph maps correctly in the response.
     */
    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "translateBatch: translating ${texts.size} texts with <a i=N> tags")

        val translations = mutableMapOf<String, String>()

        val taggedHtml = buildTaggedHtml(texts)
        val taggedLength = taggedHtml.length

        if (taggedLength > MAX_TAGGED_HTML_CHARS) {
            Log.w(TAG, "translateBatch: tagged HTML too long ($taggedLength chars), splitting chunks")
            val chunks = mutableListOf<List<String>>()
            var currentChunk = mutableListOf<String>()

            texts.forEach { text ->
                val candidate = currentChunk + text
                if (currentChunk.isNotEmpty() && buildTaggedHtml(candidate).length > MAX_TAGGED_HTML_CHARS) {
                    chunks.add(currentChunk.toList())
                    currentChunk = mutableListOf(text)
                } else {
                    currentChunk.add(text)
                }
            }
            if (currentChunk.isNotEmpty()) {
                chunks.add(currentChunk)
            }

            Log.d(TAG, "translateBatch: split into ${chunks.size} chunks, translating in parallel")
            val results = coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        try {
                            Pair(chunk, translateBatchWithTags(chunk, sourceLanguage, targetLanguage))
                        } catch (e: Exception) {
                            Log.e(TAG, "translateBatch: chunk failed - ${e.message}")
                            Pair(chunk, null)
                        }
                    }
                }.awaitAll()
            }

            results.forEach { (originalChunkTexts, translatedMap) ->
                if (translatedMap != null) {
                    translations.putAll(translatedMap)
                } else {
                    originalChunkTexts.forEach { translations[it] = it }
                }
            }
        } else {
            try {
                translations.putAll(translateBatchWithTags(texts, sourceLanguage, targetLanguage))
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: failed - ${e.message}", e)
                texts.forEach { translations[it] = it }
            }
        }

        Log.d(TAG, "translateBatch: completed, ${translations.size}/${texts.size} entries")
        return@withContext translations
    }

    /**
     * Translate a list of texts using <a i=N> tag wrapping for precise paragraph mapping.
     */
    private suspend fun translateBatchWithTags(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val htmlContent = buildTaggedHtml(texts)
        val responseBody = postTranslateHtml(listOf(htmlContent), sourceLanguage, targetLanguage)
        val translatedHtml = parseFirstTranslatedString(responseBody)

        val translations = mutableMapOf<String, String>()

        if (translatedHtml.isNotEmpty()) {
            val tagRegex = Regex("""<a\s+i=(\d+)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val matches = tagRegex.findAll(unescapeHtml(translatedHtml))

            for (match in matches) {
                val index = match.groupValues[1].toIntOrNull() ?: continue
                val translatedText = match.groupValues[2].trim()
                if (index < texts.size) {
                    translations[texts[index]] = translatedText
                }
            }
        }

        texts.forEach { text ->
            if (text !in translations) {
                translations[text] = text
            }
        }

        return@withContext translations
    }

    /** Widget-style paragraph tags (twkan.com HAR). */
    private fun buildTaggedHtml(texts: List<String>): String {
        return texts.mapIndexed { index, text ->
            "<a i=$index>\n    $text</a>"
        }.joinToString("")
    }

    private fun buildTranslateHtmlPayload(
        contentParts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val source = normalizeLanguageCode(sourceLanguage)
        val target = normalizeLanguageCode(targetLanguage)
        return buildJsonArray {
            add(
                buildJsonArray {
                    add(
                        buildJsonArray {
                            contentParts.forEach { add(JsonPrimitive(it)) }
                        }
                    )
                    add(JsonPrimitive(source))
                    add(JsonPrimitive(target))
                }
            )
            add(JsonPrimitive("te_lib"))
        }.toString()
    }

    private fun normalizeLanguageCode(language: String): String {
        return when (language.lowercase()) {
            "auto" -> "auto"
            "zh-cn", "zh-hans" -> "zh-CN"
            "zh-tw", "zh-hant" -> "zh-TW"
            else -> language
        }
    }

    private fun requireApiKey(): String {
        val key = apiKey
        if (key.isBlank()) {
            throw IllegalStateException(
                "Google Translate API key not set. Open Settings → Translation and add your key " +
                    "(see docs/GOOGLE_TRANSLATE_API_KEY.md)."
            )
        }
        return key
    }

    private fun postTranslateHtml(
        contentParts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): String {
        val payload = buildTranslateHtmlPayload(contentParts, sourceLanguage, targetLanguage)
        val requestBody = payload.toRequestBody(CONTENT_TYPE_JSON_PROTOBUF.toMediaType())
        val request = okhttp3.Request.Builder()
            .url(TRANSLATE_HTML_URL)
            .post(requestBody)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Content-Type", "application/json+protobuf")
            .header("Origin", TRANSLATE_ORIGIN)
            .header("Pragma", "no-cache")
            .header("Referer", "$TRANSLATE_ORIGIN/")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "cross-site")
            .header("User-Agent", USER_AGENT)
            .header("X-Browser-Channel", "stable")
            .header("X-Browser-Copyright", "Copyright 2026 Google LLC. All Rights Reserved.")
            .header("X-Browser-Validation", "+f/6R40gd6znZQYfwfSnAdnLwLk=")
            .header("X-Browser-Year", "2026")
            .header("X-Client-Data", "CKmdygEIlKHLAQiFoM0BCJHLlDA=")
            .header("X-Goog-Api-Key", requireApiKey())
            .build()

        val startTime = System.currentTimeMillis()
        val response = client.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - startTime
        val responseBody = response.body?.string() ?: ""

        Log.d(
            TAG,
            "postTranslateHtml: code=${response.code}, elapsed=${elapsed}ms, bodyLength=${responseBody.length}"
        )

        if (!response.isSuccessful) {
            throw IllegalStateException("translateHtml HTTP ${response.code}")
        }
        if (responseBody.isEmpty()) {
            throw IllegalStateException("translateHtml empty response")
        }
        return responseBody
    }

    private fun parseFirstTranslatedString(responseBody: String): String {
        val jsonElement = json.parseToJsonElement(responseBody)
        return jsonElement.jsonArray
            .getOrNull(0)?.jsonArray
            ?.getOrNull(0)?.jsonPrimitive?.contentOrNull
            ?.let { unescapeHtml(it) }
            ?: ""
    }

    private fun extractTextFromTaggedHtml(html: String, index: Int): String? {
        val tagRegex = Regex("""<a\s+i=$index>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        return tagRegex.find(html)?.groupValues?.getOrNull(1)?.trim()
    }

    override fun downloadModel(language: String) {
        // No-op for online translation
    }

    override fun removeModel(language: String) {
        // No-op for online translation
    }

    /**
     * Invalidate cached translation(s)
     */
    fun invalidateCacheFor(sourceLanguage: String, targetLanguage: String, text: String? = null) {
        Log.d(TAG, "invalidateCacheFor: source=$sourceLanguage, target=$targetLanguage")
        if (text == null) {
            val prefix = "$sourceLanguage-$targetLanguage:"
            val keysToRemove = translationCache.keys.filter { it.startsWith(prefix) }
            Log.d(TAG, "invalidateCacheFor: clearing ${keysToRemove.size} cached entries")
            keysToRemove.forEach { translationCache.remove(it) }
        } else {
            val key = "$sourceLanguage-$targetLanguage:$text"
            val removed = translationCache.remove(key)
            Log.d(TAG, "invalidateCacheFor: ${if (removed != null) "cleared" else "no entry found for"} specific key")
        }
    }

    companion object {
        private const val TAG = "TranslationGoogleFree"
        private const val TRANSLATE_HTML_URL = "https://translate-pa.googleapis.com/v1/translateHtml"
        private const val TRANSLATE_ORIGIN = "https://twkan.com"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private val CONTENT_TYPE_JSON_PROTOBUF = "application/json+protobuf"
        /** Max tagged HTML per translateHtml request (~35KB payload tested OK for full chapters). */
        private const val MAX_TAGGED_HTML_CHARS = 16_000
        /** Raw text split size when a single tagged block would exceed [MAX_TAGGED_HTML_CHARS]. */
        private const val MAX_RAW_TEXT_CHARS = 12_000
    }
}
