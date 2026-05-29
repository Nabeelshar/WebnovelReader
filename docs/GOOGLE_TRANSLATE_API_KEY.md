# Google Translate API key (free)

Live translation uses Google’s **translateHtml** endpoint (same as the Translate website widget).  
The app does **not** ship an API key — you add your own in **Settings → Translation**.

No paid Google Cloud account is required for the method below.

## How to get a key (free, ~2 minutes)

1. On a computer, open **Chrome** (or Edge).
2. Go to [https://translate.google.com](https://translate.google.com).
3. Press **F12** to open Developer Tools.
4. Open the **Network** tab.
5. In the filter box, type: `translateHtml`
6. On the Translate page, translate any short phrase (e.g. `hello` → any language).
7. In the network list, click the request named **`translateHtml`** (host: `translate-pa.googleapis.com`).
8. Under **Request Headers**, find **`x-goog-api-key`**.
9. Copy the value (starts with `AIzaSy...`).
10. In the app: **Settings → Translation → Google Translate API key** → paste → leave the field.

That key is the public widget key your browser already uses. It is **not** a Cloud Translation billing key.

## Tips

- If translation stops working, repeat the steps — Google sometimes rotates keys.
- Do **not** share your key publicly or commit it to git.
- For higher quality (optional), use a **Gemini API key** in the same settings screen ([ai.google.dev](https://ai.google.dev)).

## Command-line test (optional)

```bash
export GOOGLE_TRANSLATE_API_KEY="AIzaSy..."
python test_translate_html_api.py --test-auto
```

## Troubleshooting

| Problem | What to try |
|--------|-------------|
| `API key not set` | Paste key in Settings → Translation |
| HTTP 403 / 400 | Copy a fresh key from translate.google.com Network tab |
| Wrong language | Set source to **auto** or pick **zh-TW** / **zh-CN** explicitly |
