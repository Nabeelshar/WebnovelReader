package my.noveldoksuha.databaseexplorer.databaseBookInfo

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import my.noveldoksuha.coreui.BaseViewModel
import my.noveldokusha.core.utils.StateExtra_String
import my.noveldokusha.scraper.DatabaseInterface
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.text_translator.domain.MetadataTranslator
import timber.log.Timber
import javax.inject.Inject

interface DatabaseBookInfoStateBundle {
    val bookMetadata
        get() = BookMetadata(
            title = bookTitle,
            url = bookUrl
        )

    var databaseUrlBase: String
    var bookUrl: String
    var bookTitle: String
}


@HiltViewModel
class DatabaseBookInfoViewModel @Inject constructor(
    stateHandle: SavedStateHandle,
    scraper: Scraper,
    private val metadataTranslator: MetadataTranslator,
) : BaseViewModel(), DatabaseBookInfoStateBundle {
    override var databaseUrlBase: String by StateExtra_String(stateHandle)
    override var bookUrl: String by StateExtra_String(stateHandle)
    override var bookTitle: String by StateExtra_String(stateHandle)

    val database = scraper.getCompatibleDatabase(databaseUrlBase)!!

    internal val state = DatabaseBookInfoState(
        databaseNameStrId = mutableIntStateOf(database.nameStrId),
        book = mutableStateOf(
            DatabaseInterface.BookData(
                title = bookTitle,
                description = "",
                coverImageUrl = null,
                alternativeTitles = listOf(),
                authors = listOf(),
                tags = listOf(),
                genres = listOf(),
                bookType = "",
                relatedBooks = listOf(),
                similarRecommended = listOf()
            )
        )

    )

    val translatedTitle = mutableStateOf("")
    val translatedDescription = mutableStateOf("")

    init {
        viewModelScope.launch {
            database.getBookData(bookMetadata.url)
                .onSuccess {
                    state.book.value = it
                    translatedTitle.value = metadataTranslator.translate(it.title)
                    translatedDescription.value = metadataTranslator.translate(it.description)
                }
                .onError { Timber.d(it.exception) }
        }
    }
}