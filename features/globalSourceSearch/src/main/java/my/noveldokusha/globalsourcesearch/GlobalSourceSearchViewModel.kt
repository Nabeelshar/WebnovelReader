package my.noveldokusha.globalsourcesearch

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import my.noveldoksuha.coreui.BaseViewModel
import my.noveldoksuha.coreui.states.PagedListIteratorState
import my.noveldoksuha.data.CatalogItem
import my.noveldoksuha.data.ScraperRepository
import my.noveldokusha.core.utils.StateExtra_String
import my.noveldokusha.core.utils.asMutableStateOf
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.text_translator.domain.MetadataTranslator
import javax.inject.Inject

internal interface GlobalSourceSearchStateBundle {
    val initialInput: String
}

@HiltViewModel
internal class GlobalSourceSearchViewModel @Inject constructor(
    state: SavedStateHandle,
    private val scraperRepository: ScraperRepository,
    private val metadataTranslator: MetadataTranslator,
) : BaseViewModel(), GlobalSourceSearchStateBundle {
    override val initialInput by StateExtra_String(state)

    @Volatile
    private var searchJob: Job? = null

    val searchInput = state.asMutableStateOf("searchInput") { initialInput }
    val sourcesResults = mutableStateListOf<SourceResults>()
    val translatedTitles = mutableStateOf(mapOf<String, String>())

    init {
        search(text = searchInput.value)
    }

    fun search(text: String) {
        if (text.isBlank()) return

        searchJob?.cancel()
        translatedTitles.value = emptyMap()
        searchJob = viewModelScope.launch {
            sourcesResults.clear()
            scraperRepository.sourcesCatalogListFlow()
                .take(1)
                .collect { sources ->
                    val results = sources.map { source ->
                        SourceResults(
                            source = source,
                            searchInput = text,
                            coroutineScope = this@launch
                        )
                    }.also(sourcesResults::addAll)

                    results.forEach { sr ->
                        launch {
                            while (isActive) {
                                val books = sr.fetchIterator.list.toList()
                                translateBookTitles(books)
                                if (sr.fetchIterator.hasFinished) break
                                delay(500)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun translateBookTitles(books: List<BookResult>) {
        val toTranslate = books.filter { it.url !in translatedTitles.value }
        if (toTranslate.isEmpty()) return
        toTranslate.forEach { book ->
            val translated = metadataTranslator.translate(book.title)
            if (translated != book.title) {
                translatedTitles.value = translatedTitles.value + (book.url to translated)
            }
        }
    }

}

internal data class SourceResults(
    val source: CatalogItem,
    val searchInput: String,
    val coroutineScope: CoroutineScope
) {
    val fetchIterator = PagedListIteratorState(coroutineScope) {
        source.catalog.getCatalogSearch(it, searchInput)
    }

    init {
        fetchIterator.fetchNext()
    }
}
