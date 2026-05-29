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

### Install

Download `WebnovelReader_v2.4.0-release.apk` below.  
APK is signed with a debug certificate for sideloading (enable “Install unknown apps” if prompted).

### Usage

Reader → Translation settings → Source: **auto** (or e.g. zh-TW) → Target: **en** → Enable translation.

Optional: add a **Gemini API key** in Settings for higher-quality translation (falls back to free Google Translate).
