package my.noveldokusha.globalsourcesearch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import my.noveldoksuha.coreui.BaseActivity
import my.noveldoksuha.coreui.theme.Theme
import my.noveldoksuha.data.ScraperRepository
import my.noveldokusha.core.Response
import my.noveldokusha.core.utils.Extra_String
import my.noveldokusha.feature.local_database.BookMetadata
import my.noveldokusha.navigation.NavigationRoutes
import javax.inject.Inject

@AndroidEntryPoint
class GlobalSourceSearchActivity : BaseActivity() {
    class IntentData : Intent, GlobalSourceSearchStateBundle {
        override var initialInput by Extra_String()

        constructor(intent: Intent) : super(intent)
        constructor(ctx: Context, input: String) : super(
            ctx,
            GlobalSourceSearchActivity::class.java
        ) {
            this.initialInput = input
        }
    }

    @Inject
    internal lateinit var navigationRoutes: NavigationRoutes

    @Inject
    internal lateinit var scraperRepository: ScraperRepository

    private val viewModel by viewModels<GlobalSourceSearchViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Theme(themeProvider = themeProvider) {
                GlobalSourceSearchScreen(
                    searchInput = viewModel.searchInput.value,
                    listSources = viewModel.sourcesResults,
                    translatedTitles = viewModel.translatedTitles.value,
                    onBookClick = { navigationRoutes.chapters(this, it).let(::startActivity) },
                    onPressBack = ::onBackPressed,
                    onSearchInputChange = viewModel.searchInput::value::set,
                    onSearchInputSubmit = { text ->
                        if (text.startsWith("https://") || text.startsWith("http://")) {
                            val source = scraperRepository.getCompatibleSource(text)
                            if (source != null) {
                                val catalog = scraperRepository.getCompatibleSourceCatalog(text)
                                if (catalog != null) {
                                    lifecycleScope.launch {
                                        val title = try {
                                            when (val result = catalog.getCatalogSearch(0, text)) {
                                                is Response.Success -> result.data.list.firstOrNull()?.title ?: text
                                                else -> text
                                            }
                                        } catch (_: Exception) { text }
                                        startActivity(
                                            navigationRoutes.chapters(
                                                this@GlobalSourceSearchActivity,
                                                BookMetadata(title = title, url = text)
                                            )
                                        )
                                    }
                                } else {
                                    startActivity(
                                        navigationRoutes.chapters(
                                            this@GlobalSourceSearchActivity,
                                            BookMetadata(title = text, url = text)
                                        )
                                    )
                                }
                                return@GlobalSourceSearchScreen
                            }
                        }
                        viewModel.search(text)
                    },
                )
            }
        }
    }
}
