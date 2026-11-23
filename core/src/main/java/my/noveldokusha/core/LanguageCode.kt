package my.noveldokusha.core

import androidx.annotation.StringRes

/**
 * ISO 639-1 codes
 * https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes
 */
enum class LanguageCode(
    @Suppress("PropertyName") val iso639_1: String,
    @StringRes val nameResId: Int
) {
    ENGLISH(iso639_1 = "en", nameResId = R.string.language_english),
    PORTUGUESE(iso639_1 = "pt", nameResId = R.string.language_portuguese),
    @Suppress("unused")
    SPANISH(iso639_1 = "es", nameResId = R.string.language_spanish),
    @Suppress("unused")
    FRENCH(iso639_1 = "fr", nameResId = R.string.language_french),
    INDONESIAN(iso639_1 = "id", nameResId = R.string.language_indonesian),
    CHINESE(iso639_1 = "zh", nameResId = R.string.language_chinese),
    RUSSIAN(iso639_1 = "ru", nameResId = R.string.language_russian),
    ARABIC(iso639_1 = "ar", nameResId = R.string.language_arabic),
    TURKISH(iso639_1 = "tr", nameResId = R.string.language_turkish)
}