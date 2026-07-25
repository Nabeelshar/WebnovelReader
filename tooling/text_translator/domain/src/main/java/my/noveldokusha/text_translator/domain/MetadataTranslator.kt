package my.noveldokusha.text_translator.domain

import my.noveldokusha.core.appPreferences.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataTranslator @Inject constructor(
    private val translationManager: TranslationManager,
    private val appPreferences: AppPreferences,
) {
    private val cache = mutableMapOf<String, String>()

    suspend fun translate(text: String): String {
        if (text.isBlank()) return text
        if (!appPreferences.METADATA_TRANSLATION_ENABLED.value) return text

        cache[text]?.let { return it }

        if (!translationManager.available) return text

        try {
            val translator = translationManager.getTranslator("zh", "en")
            val result = translator.translate(text)
            cache[text] = result
            return result
        } catch (_: Exception) {
            return text
        }
    }

    suspend fun isEnabled(): Boolean = appPreferences.METADATA_TRANSLATION_ENABLED.value

    fun clearCache() {
        cache.clear()
    }
}
