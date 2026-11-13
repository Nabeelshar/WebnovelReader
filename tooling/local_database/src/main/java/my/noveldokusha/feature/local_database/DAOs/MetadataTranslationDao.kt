package my.noveldokusha.feature.local_database.DAOs

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import my.noveldokusha.feature.local_database.tables.MetadataTranslation

@Dao
interface MetadataTranslationDao {

    @Query("""
        SELECT * FROM MetadataTranslation
        WHERE originalText = :originalText
        AND sourceLang = :sourceLang
        AND targetLang = :targetLang
        LIMIT 1
    """)
    suspend fun getTranslation(
        originalText: String,
        sourceLang: String,
        targetLang: String
    ): MetadataTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplace(translation: MetadataTranslation)

    @Query("DELETE FROM MetadataTranslation")
    suspend fun deleteAllTranslations(): Int
}
