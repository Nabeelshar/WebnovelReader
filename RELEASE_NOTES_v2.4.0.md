## WebnovelReader v2.4.0

### Translation (Google Translate free)

- Updated **translateHtml** client to match the Google Translate website widget (twkan.com HAR capture)
- Browser-style request headers (`Origin`, `Referer`, `X-Browser-*`, etc.)
- Paragraph batching with `<a i=N>` tags for accurate per-paragraph mapping
- **`auto` source language** — API detects the chapter language automatically
- Larger chapters supported per request (~16k tagged HTML; full chapters tested)
- Longer network timeouts for big chapter translations

### Other

- Version **2.4.0** (versionCode 29)
- Removed broken **Jaomix** source from catalog

### Setup (required)

1. **Settings → Translation → Google Translate API key** — paste your key ([free guide](docs/GOOGLE_TRANSLATE_API_KEY.md))
2. Reader → Source: **auto** (or zh-TW) → Target: **en** → Enable translation
3. Optional: **Gemini API key** for higher quality (uses Google Translate as fallback)

### Install

Download `WebnovelReader_v2.4.0-release.apk` below.  
APK is signed with a debug certificate for sideloading (enable “Install unknown apps” if prompted).
