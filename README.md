 

# NovelDokusha
Android web novel reader. Reader focused on simplicity, improving reading immersion.
Search from a large catalog of content, open your pick and just enjoy.

> **Note**: This is an actively maintained fork by [@Nabeelshar](https://github.com/Nabeelshar). The [original repository](https://github.com/nanihadesuka/NovelDokusha) is no longer actively maintained.

**Latest release: [v2.5.0](https://github.com/Nabeelshar/WebnovelReader/releases/tag/v2.5.0)** — 33 new sources (71 total, 9 languages), Cloudflare verification no longer interrupts reading, bundled Open Sans reader font. Signed with the same key as v2.4.x, so it installs over your existing version and keeps your library.

# License
Copyright © 2023, [nani](https://github.com/nanihadesuka), Released under [GPL-3](LICENSE) FOSS

## Features
  - **Live translation** - Google Translate with optional Gemini AI
  - **Auto-detect source language** when translating chapters
  - **Bring your own Google Translate key** (free; see [docs/GOOGLE_TRANSLATE_API_KEY.md](docs/GOOGLE_TRANSLATE_API_KEY.md))
  - **71 sources in 9 languages** from where to read novels:
    - **English (33)**: AllNovel, BedNovel, BlackBox TL, Booknet, FanMTL, FreeWebNovel, Gravity Tales, Infinite Novel Translations, KiteNovel, LNMTL, LibRead, Light Novels Translations, NewNovel, NoBadNovel, Novel Archive, Novel Updates, NovelBin, NovelBuddy, NovelCool, NovelFull, NovelHall, NovelHi, Novelight, PandaNovel, Re:Library, Read Novel Full, Royal Road, ScribbleHub, SonicMTL, The Wandering Inn, WoopRead, WtrLab, Wuxia World
    - **Chinese (9)**: Powanjuan, QQ阅读 (book.qq.com), 69书吧 (69shuba.com, with GBK encoding support), Ttkan, Twkan, UU看书 (uukanshu.net), 乐阅读 (27k.net), 微風小說 (wfxs.tw), 顶点小说 (ddxss.cc)
    - **Russian (9)**: Author.Today, IFreedom, Jaomix, LabNovel, Litnet, Ranobe Novels, RanobeLib, Ranobes, WuxiaWorld.ru
    - **Indonesian (8)**: Baca Lightnovel, Dark Translation, Indonesia Webnovel, MeioNovel, MoreNovel, NovelKu, Sakura Novel, WbNovel
    - **French (4)**: Chireads, Novel France, Novel de Glace, Xiaowaz
    - **Portuguese (4)**: Novel Mania, Phoenix Novels, Saikai, Tsundoku
    - **Arabic (2)**: Kol Novel, Su Novels
    - **Turkish (2)**: Fenrir Scans, Novel Okur
  - **Multiple databases** to search for novels
  - **Local source** to read local EPUBs
  - **Easy backup and restore**
  - **Light and dark themes**
  - Follows modern **Material 3** guidelines
  - **Advanced Reader Features**:
    - Infinite scroll
    - Custom font, font size (includes bundled **Open Sans**)
    - **Live translation** with Gemini AI
    - **Text to speech**:
      - Background playback
      - Adjust voice, pitch, speed
      - Save your preferred voices
  - **Automatic Cloudflare bypass** - Seamless access to protected sources; verification only appears for what you open yourself, never over the chapter you are reading (background updates and downloads no longer trigger it)

  
## Screenshots
 
|              Library               |                Finder                |
|:----------------------------------:|:------------------------------------:|
|    ![](screenshots/library.png)    |     ![](screenshots/finder.png)      |
|             Book info              |            Book chapters             |
|   ![](screenshots/book_info.png)   |  ![](screenshots/book_chapers.png)   |
|               Reader               |           Database search            |
|    ![](screenshots/reader.png)     | ![](screenshots/database_search.png) |
|           Global search            |                                      |
| ![](screenshots/global_search.png) |                                      |

## Tech stack
  - Kotlin
  - XML views
  - Jetpack compose
  - Material 3
  - Coroutines
  - LiveData
  - Room (SQLite) for storage
  - Jsoup
  - Okhttp
  - Coil, glide
  - Gson, Moshi
  - Google MLKit for translation
  - Android TTS
  - Android media (TTS playback notification controls)
