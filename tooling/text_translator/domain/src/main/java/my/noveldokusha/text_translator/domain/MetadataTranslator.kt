package my.noveldokusha.text_translator.domain

import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.feature.local_database.DAOs.MetadataTranslationDao
import my.noveldokusha.feature.local_database.tables.MetadataTranslation
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates book metadata (titles, descriptions) from Chinese to English.
 *
 * Translations are cached persistently in the [MetadataTranslation] table,
 * so a title that was already translated correctly is never re-translated:
 * not on the next library refresh, not when the library screen is
 * recreated, and not after an app restart. Previously the cache was only
 * an in-memory map in this class (lost on process death) plus a
 * per-ViewModel map in the library screen (lost on navigation), so every
 * library title was re-sent to Google Translate / Gemini over and over —
 * and titles that translate to themselves (already English) were not
 * cached anywhere at all, so they were re-translated on every refresh.
 *
 * Only successful translations are stored. If the translation service is
 * unavailable or the call fails, nothing is cached and the original text
 * is returned, so a later attempt can still succeed.
 */
@Singleton
class MetadataTranslator @Inject constructor(
    private val translationManager: TranslationManager,
    private val appPreferences: AppPreferences,
    private val metadataTranslationDao: MetadataTranslationDao,
) {
    private val cache = mutableMapOf<String, String>()

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        if (!appPreferences.METADATA_TRANSLATION_ENABLED.value) return text

        cache[text]?.let { return it }

        metadataTranslationDao.getTranslation(text, SOURCE_LANG, TARGET_LANG)
            ?.translatedText?.let { cached ->
                cache[text] = cached
                return cached
            }

        if (!translationManager.available) return text

        return try {
            val translator = translationManager.getTranslator(SOURCE_LANG, TARGET_LANG)
            val result = translator.translate(text)
            cache[text] = result
            metadataTranslationDao.insertReplace(
                MetadataTranslation(
                    originalText = text,
                    sourceLang = SOURCE_LANG,
                    targetLang = TARGET_LANG,
                    translatedText = result
                )
            )
            result
        } catch (_: Exception) {
            text
        }
    }

    suspend fun isEnabled(): Boolean = appPreferences.METADATA_TRANSLATION_ENABLED.value

    fun clearCache() {
        cache.clear()
    }

    suspend fun clearPersistentCache() {
        cache.clear()
        metadataTranslationDao.deleteAllTranslations()
    }

    companion object {
        private const val SOURCE_LANG = "zh"
        private const val TARGET_LANG = "en"
    }
}
