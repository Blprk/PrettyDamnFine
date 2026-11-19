# Changelog

All notable changes to PrettyDamnFine are documented here. This project follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and uses semantic versioning.

---

## [2.0.1] — 2026-11-20

### Fixed
- **Groq "model not found"**: retired model IDs (`llama-3.1-8b-instant`, `llama-3.3-70b-versatile`, `qwen/qwen3-32b`, `llama-4-scout`, `groq/compound*`) were still hard-coded. Default is now `openai/gpt-oss-20b`, the random pool contains only active models, and gpt-oss requests use `max_completion_tokens`.
- Added **Refresh models** in Settings and in the AI model picker: fetches the live model list from `GET /openai/v1/models` with your key, so future Groq changes no longer break the app.
- 404 now explains that the model was retired and tells you to refresh.

### Added
- **Share** button in the PDF viewer and in each library document's menu. Uses a FileProvider content URI so WhatsApp, Email, Drive and any other PDF handler can open the file.
- FileProvider configuration (`@xml/file_paths`) for the app-private PDF working copy.

### Changed
- Page indicator now uses `derivedStateOf`, eliminating whole-screen recomposition on every scroll frame.

---

## [2.0.0] — 2026-11-20

**The MuPDF release.** A major rewrite of the document engine and interaction layer.
This is a breaking change in licensing (Apache-2.0 → AGPL-3.0-or-later) and in the
PDF parsing stack. See [PROPOSAL.md](PROPOSAL.md) for the reasoning and roadmap.

### ⚠️ Upgrade notes

- The app is now licensed under **AGPL-3.0-or-later** because it embeds MuPDF (`fitz`).
  Previously Apache-2.0 code remains under those terms in `LICENSE-APACHE.txt`.
- The APK is larger (~21 MB vs ~9.8 MB) because it bundles MuPDF native libraries
  for `arm64-v8a` and `armeabi-v7a`. PDFBox's 3.5 MB of font/CMap assets are gone.
- If you had a Groq API key saved under the old encrypted store, **re-enter it once**:
  the old Android Keystore key was invalid, so the corrupt store is now detected and
  rebuilt automatically.
- PDFs you had open are copied into app-private storage on first open. Annotation
  edits are saved to that working copy, so original files on your device are never
  modified.

### Added

- **MuPDF engine** (`com.artifex.mupdf:fitz:1.28.5`) replaces
  `android.graphics.pdf.PdfRenderer` + PDFBox for rendering, text extraction,
  search positions, outlines and encryption support.
- **Real PDF annotations**: select text → **Highlight** creates a native MuPDF
  highlight annotation; a **save** action writes it back to the working copy.
- **Password-protected PDFs** are detectable (`needsPassword` / `authenticate`)
  at the engine layer.
- **Table of contents** is now extracted from the PDF outline (`Document.loadOutline`)
  and rendered with all nested levels, replacing a hard-coded empty list.
- **Cancellable tile rendering** primitives using MuPDF `DisplayList`, `Cookie` and
  `AndroidDrawDevice` (foundation for sharp zoom).
- **Text-selection toolbar** with explicit Copy / Highlight / Close actions (plus
  Call and Email for phone numbers and addresses). The toolbar appears only after
  the selection gesture settles, preventing accidental copies.
- **Hierarchical system Back handling**: back dismisses the topmost layer, then
  returns from the viewer to the library, then exits. Participates in Android
  predictive-back animations.
- **Reading Theme "Auto"** now resolves to the system light/dark setting.
- **Double-tap zoom** with focal-point handling (was declared but empty).
- **Read Mode** now actually locks horizontal panning (was icon-only).
- **Fast scrollbar** is now used for documents longer than three pages (was dead code).
- **Dark-mode system bars** via `values-night` theme resources.
- `LICENSE-APACHE.txt`, `CHANGELOG.md` and this expanded documentation.

### Changed

- `DocumentRepository` now queries `OpenableColumns.DISPLAY_NAME` with an explicit
  projection, repairs titles previously stored as opaque provider IDs, and prunes
  entries whose URI permission has expired — fixing "random string names in history
  that won't open".
- `DocumentRepository` validates stored URIs before showing them; dead entries are
  removed automatically and a clear message is shown when a file cannot be opened.
- `GroqSearchService` now ranks **every page** locally by query-term overlap and
  sends the most relevant excerpts instead of only the first ~8,000 characters.
  Prompts require grounded answers with page citations. In-flight searches are
  cancelled when a document changes.
- Text extraction now uses one long-lived MuPDF document instead of re-opening
  and re-parsing the PDF with PDFBox for every page. Search updates are no longer
  O(n²).
- Bookmarks, reading progress, theme and session data are persisted per document.
- `EncryptedSharedPreferences` failures are recovered automatically (corrupt prefs
  and stale master key are discarded and recreated). The fallback store is now
  shared process-wide so all screens see the same API key.
- Page thumbnails in the overview use real per-page aspect ratios and the active
  reading theme.
- Bitmap pooling/recycling that could corrupt a bitmap still displayed by Compose
  was removed in favour of an LRU cache plus GC.
- Version bumped to **2.0.0 (code 3)**.

### Fixed

- System Back gesture no longer exits the app from the PDF viewer or Settings.
- Selecting text no longer produces an unsolicited "Copied to clipboard" toast.
- The AI search no longer reports "set your API key" when it was saved on another
  screen.
- "Auto" theme, Read Mode, double-tap zoom, fast scrollbar, TOC and bookmark
  persistence all work instead of being no-ops.
- Potential `Bitmap` recycled/reused while still on screen.
- Release builds crashed on launch because R8 stripped MuPDF JNI classes; added keep rules for `com.artifex.mupdf.**`.

### Known limitations

- Zoom currently still uses a scaled page bitmap; the tile primitives exist but the
  Compose viewport tile scheduler is future work.
- Annotation editing is limited to highlights on selected text; freehand, notes,
  forms and redaction are not yet exposed in the UI.
- Password prompts are not yet surfaced in the UI (engine-level support exists).
- Release builds are currently signed with the debug key; a production signing
  configuration is still required before Play Store distribution.
- No automated UI tests yet; unit tests only cover the template stubs.

---

## [1.0.1] — 2026-03-25

- Initial published release.
- Jetpack Compose reader with light/dark/sepia themes, library, local text search,
  bookmarks, page overview and optional Groq-powered semantic search.
- Built on `PdfRenderer` + `pdfbox-android`.

---

## Roadmap

See [PROPOSAL.md](PROPOSAL.md) for the full phased plan: tiled zoom, on-device
embeddings for genuine semantic search, read-aloud with sentence highlighting,
Library 2.0, annotations beyond highlights, accessibility and CI.
