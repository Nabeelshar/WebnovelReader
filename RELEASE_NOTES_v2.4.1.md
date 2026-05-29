## WebnovelReader v2.4.1

### Security & setup

- **Removed hardcoded Google Translate API key** from the app source
- **You must add your own key** in Settings → Translation (free; takes ~2 minutes)
- In-app guide + full doc: [GOOGLE_TRANSLATE_API_KEY.md](docs/GOOGLE_TRANSLATE_API_KEY.md)

### How to get a free key

1. Open [translate.google.com](https://translate.google.com) in Chrome  
2. F12 → Network → filter `translateHtml`  
3. Translate any word → copy `x-goog-api-key` from request headers  
4. Paste in **Settings → Translation → Google Translate API key**

### Includes v2.4.0 translation improvements

- translateHtml widget API (browser-style headers, `<a i=N>` paragraphs)
- **auto** source language detection
- Larger chapters per request

### Optional

- **Gemini API key** in Settings for higher-quality translation (Google key still used as fallback)

### Install

Download `WebnovelReader_v2.4.1-release.apk` below.
