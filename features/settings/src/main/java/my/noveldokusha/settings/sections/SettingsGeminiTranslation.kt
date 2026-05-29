package my.noveldokusha.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.noveldoksuha.coreui.theme.ColorAccent
import my.noveldoksuha.coreui.theme.textPadding

@Composable
internal fun SettingsGeminiTranslation(
    googleTranslateApiKey: String,
    geminiApiKey: String,
    geminiModel: String,
    preferOnlineTranslation: Boolean,
    onGoogleTranslateApiKeyChange: (String) -> Unit,
    onGeminiApiKeyChange: (String) -> Unit,
    onGeminiModelChange: (String) -> Unit,
    onPreferOnlineChange: (Boolean) -> Unit,
) {
    var googleKeyText by remember(googleTranslateApiKey) { mutableStateOf(googleTranslateApiKey) }
    var apiKeyText by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var modelText by remember(geminiModel) { mutableStateOf(geminiModel) }

    Column {
        Text(
            text = "Translation Services",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = ColorAccent
        )

        Text(
            text = when {
                geminiApiKey.isNotBlank() && preferOnlineTranslation ->
                    "Active: Google Gemini API"
                googleTranslateApiKey.isNotBlank() ->
                    "Active: Google Translate"
                else ->
                    "Not configured — add Google Translate API key below"
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.textPadding(),
            color = when {
                googleTranslateApiKey.isBlank() && !(geminiApiKey.isNotBlank() && preferOnlineTranslation) ->
                    MaterialTheme.colorScheme.error
                geminiApiKey.isNotBlank() && preferOnlineTranslation ->
                    MaterialTheme.colorScheme.primary
                else ->
                    MaterialTheme.colorScheme.secondary
            }
        )

        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google Translate API key",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = googleKeyText,
                        onValueChange = {
                            googleKeyText = it
                            onGoogleTranslateApiKeyChange(it)
                        },
                        label = { Text("Required for free translation") },
                        placeholder = { Text("AIzaSy...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "How to get a free key (no billing):\n" +
                            "1. Open translate.google.com in Chrome\n" +
                            "2. Press F12 → Network tab → filter: translateHtml\n" +
                            "3. Translate any word on the page\n" +
                            "4. Click the translateHtml request\n" +
                            "5. Copy x-goog-api-key from Request Headers\n" +
                            "6. Paste here\n\n" +
                            "Full guide: docs/GOOGLE_TRANSLATE_API_KEY.md in the project repo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Gemini API Key (optional)",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = {
                            apiKeyText = it
                            onGeminiApiKeyChange(it)
                        },
                        label = { Text("Enter your Gemini API key(s)") },
                        placeholder = { Text("AIzaSy...\nAIzaSy...") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 5
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Get a free key at: ai.google.dev\n" +
                            "Tip: Multiple keys (one per line) help avoid rate limits.\n" +
                            "When enabled below, Gemini is used first; Google Translate is the fallback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ListItem(
            headlineContent = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Gemini Model",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = modelText,
                        onValueChange = {
                            modelText = it
                            onGeminiModelChange(it)
                        },
                        label = { Text("Model name") },
                        placeholder = { Text("gemini-2.5-flash-lite") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Default: gemini-2.5-flash-lite\n" +
                            "Examples: gemini-flash-lite-latest, gemini-2.5-flash-lite\n" +
                            "Models: ai.google.dev/gemini-api/docs/models",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ListItem(
            headlineContent = {
                Text(text = "Use Gemini API")
            },
            supportingContent = {
                Text(
                    text = if (apiKeyText.isNotBlank()) {
                        "When enabled, uses Gemini first, then Google Translate if Gemini fails. " +
                            "Google Translate key above is still required as fallback."
                    } else {
                        "Add a Gemini API key to enable. Otherwise only Google Translate is used."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            },
            trailingContent = {
                Switch(
                    checked = preferOnlineTranslation,
                    onCheckedChange = onPreferOnlineChange,
                    enabled = apiKeyText.isNotBlank()
                )
            }
        )
    }
}
