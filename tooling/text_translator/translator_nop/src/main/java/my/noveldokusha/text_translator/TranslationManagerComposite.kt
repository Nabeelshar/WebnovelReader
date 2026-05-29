package my.noveldokusha.text_translator

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.text_translator.domain.TranslationModelState
import my.noveldokusha.text_translator.domain.TranslatorState

/**
 * Composite translation manager that switches between Gemini and Google Translate Free
 * Uses Gemini if API key is configured, otherwise falls back to Google Translate Free
 */
class TranslationManagerComposite(
    private val coroutineScope: AppCoroutineScope,
    private val geminiManager: TranslationManagerGemini,
    private val googleFreeManager: TranslationManagerGoogleFree,
    private val appPreferences: AppPreferences
) : TranslationManager {

    override val available: Boolean = true

    override val isUsingOnlineTranslation: Boolean = true

    override val models = mutableStateListOf<TranslationModelState>()

    init {
        // Merge models from both providers
        val allLanguages = mutableSetOf<String>()
        allLanguages.addAll(geminiManager.models.map { it.language })
        allLanguages.addAll(googleFreeManager.models.map { it.language })

        models.addAll(allLanguages.map { lang ->
            TranslationModelState(
                language = lang,
                available = true,
                downloading = false,
                downloadingFailed = false
            )
        })
    }

    override suspend fun hasModelDownloaded(language: String): TranslationModelState? {
        return models.firstOrNull { it.language == language }
    }

    private fun hasGeminiApiKey(): Boolean {
        val apiKey = appPreferences.TRANSLATION_GEMINI_API_KEY.value
        return apiKey.isNotBlank()
    }

    override fun getTranslator(source: String, target: String): TranslatorState {
        val hasApiKey = hasGeminiApiKey()
        val preferOnline = appPreferences.TRANSLATION_PREFER_ONLINE.value

        Log.d(TAG, "getTranslator: source=$source, target=$target")
        Log.d(TAG, "  hasGeminiApiKey=$hasApiKey, preferOnline=$preferOnline")

        return when {
            // Use Gemini if API key is configured and online translation is preferred
            hasApiKey && preferOnline -> {
                Log.d(TAG, "getTranslator: using Gemini with Google Free fallback")
                val geminiTranslator = geminiManager.getTranslator(source, target)
                val googleFreeTranslator = googleFreeManager.getTranslator(source, target)

                TranslatorState(
                    source = source,
                    target = target,
                    translate = { input ->
                        var lastException: Exception? = null
                        // Try Gemini first with 2 retries
                        repeat(2) { attempt ->
                            try {
                                Log.d(TAG, "Gemini attempt ${attempt + 1}/2")
                                val result = geminiTranslator.translate(input)

                                // Check if result is an error message
                                if (!result.startsWith("[Translation") && !result.startsWith("[API")) {
                                    Log.d(TAG, "Gemini translation succeeded")
                                    return@TranslatorState result
                                } else {
                                    Log.w(TAG, "Gemini returned error: $result")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Gemini attempt ${attempt + 1} failed: ${e.message}", e)
                                lastException = e
                                if (attempt < 1) {
                                    kotlinx.coroutines.delay(1000L)
                                }
                            }
                        }

                        // Fallback to Google Free
                        Log.w(TAG, "Gemini failed, falling back to Google Translate Free")
                        try {
                            val result = googleFreeTranslator.translate(input)
                            Log.d(TAG, "Google Free fallback succeeded")
                            result
                        } catch (e: Exception) {
                            Log.e(TAG, "Google Free fallback also failed: ${e.message}", e)
                            throw lastException ?: e
                        }
                    }
                )
            }

            // Use Google Translate Free if no API key or online not preferred
            else -> {
                Log.d(TAG, "getTranslator: using Google Translate Free (${if (!hasApiKey) "no API key" else "offline preferred"})")
                googleFreeManager.getTranslator(source, target)
            }
        }
    }

    override fun downloadModel(language: String) {
        // No-op for online translation
    }

    override fun removeModel(language: String) {
        // No-op for online translation
    }

    /**
     * Invalidate cache in both managers
     */
    fun invalidateCacheFor(sourceLanguage: String, targetLanguage: String, text: String? = null) {
        Log.d(TAG, "invalidateCacheFor: delegating to both managers")
        geminiManager.invalidateCacheFor(sourceLanguage, targetLanguage, text)
        googleFreeManager.invalidateCacheFor(sourceLanguage, targetLanguage, text)
    }

    /**
     * Get current active translator name for UI display
     */
    fun getActiveTranslatorName(): String {
        return when {
            hasGeminiApiKey() && appPreferences.TRANSLATION_PREFER_ONLINE.value -> "Google Gemini API"
            appPreferences.TRANSLATION_GOOGLE_API_KEY.value.isNotBlank() -> "Google Translate"
            else -> "Not configured"
        }
    }

    /**
     * Batch translation - delegates to active manager
     */
    override suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val hasApiKey = hasGeminiApiKey()
        val preferOnline = appPreferences.TRANSLATION_PREFER_ONLINE.value

        if (hasApiKey && preferOnline) {
            Log.d(TAG, "translateBatch: using Gemini")
            try {
                return@withContext geminiManager.translateBatch(texts, sourceLanguage, targetLanguage)
            } catch (e: Exception) {
                Log.e(TAG, "translateBatch: Gemini failed, falling back to Google Free", e)
            }
        }

        Log.d(TAG, "translateBatch: using Google Translate Free")
        return@withContext googleFreeManager.translateBatch(texts, sourceLanguage, targetLanguage)
    }

    companion object {
        private const val TAG = "TranslationComposite"
    }
}
