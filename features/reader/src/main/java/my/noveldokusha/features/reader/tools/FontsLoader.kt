package my.noveldokusha.features.reader.tools

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

internal class FontsLoader(
    private val assets: AssetManager? = null
) {
    companion object {
        const val OPEN_SANS = "Open Sans"

        val availableFonts = listOf(
            OPEN_SANS,
            "casual",
            "cursive",
            "monospace",
            "sans-serif",
            "sans-serif-black",
            "sans-serif-condensed",
            "sans-serif-condensed-light",
            "sans-serif-light",
            "sans-serif-medium",
            "sans-serif-smallcaps",
            "sans-serif-thin",
            "serif",
            "serif-monospace"
        )

        /**
         * Fonts bundled in assets/fonts, mapped to
         * (regular, bold) file names. Everything not listed here is
         * an Android system font family resolved by name.
         */
        private val bundledFonts = mapOf(
            OPEN_SANS to ("OpenSans-Regular.ttf" to "OpenSans-Bold.ttf")
        )
    }

    private val typeFaceNORMALCache = mutableMapOf<String, Typeface>()
    private val typeFaceBOLDCache = mutableMapOf<String, Typeface>()
    private val fontFamilyCache = mutableMapOf<String, FontFamily>()

    private fun bundledTypeface(name: String, bold: Boolean): Typeface? {
        val files = bundledFonts[name] ?: return null
        val assetManager = assets ?: return null
        return try {
            Typeface.createFromAsset(
                assetManager,
                "fonts/" + if (bold) files.second else files.first
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getTypeFaceNORMAL(name: String) = typeFaceNORMALCache.getOrPut(name) {
        bundledTypeface(name, bold = false) ?: Typeface.create(name, Typeface.NORMAL)
    }

    fun getTypeFaceBOLD(name: String) = typeFaceBOLDCache.getOrPut(name) {
        bundledTypeface(name, bold = true) ?: Typeface.create(name, Typeface.BOLD)
    }

    fun getFontFamily(name: String) = fontFamilyCache.getOrPut(name) {
        FontFamily(getTypeFaceNORMAL(name))
    }
}
