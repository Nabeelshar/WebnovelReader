package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores translated book metadata (titles, descriptions) so a title that
 * was already translated correctly is never re-translated.
 *
 * Previously [my.noveldokusha.text_translator.domain.MetadataTranslator]
 * kept translations only in an in-memory map, and the library screen kept
 * its own per-ViewModel map, so every app restart (and every time the
 * library screen was recreated) re-sent every library title to Google
 * Translate / Gemini, even ones translated long ago. Titles that
 * translated to themselves (already English) were not even kept in the
 * ViewModel map, so they were re-translated on every library refresh.
 */
@Entity(
    indices = [
        Index(value = ["originalText", "sourceLang", "targetLang"], unique = true)
    ]
)
data class MetadataTranslation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalText: String,
    val sourceLang: String,
    val targetLang: String,
    val translatedText: String,
    val timestamp: Long = System.currentTimeMillis()
)
