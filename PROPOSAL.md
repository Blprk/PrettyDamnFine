# PrettyDamnFine — Transformation Proposal (v2)

Status: **AWAITING APPROVAL — no code has been changed for this proposal.**

> The v1 document was a maintenance checklist. This version is a product vision:
> what it would take to make this a genuinely great PDF reader rather than a
> functional one. It is deliberately ambitious. You approve the scope; I execute.

---

## 0. First, fix the bug you reported (non-negotiable, small)

**Problem:** There is no `BackHandler` anywhere in the project — only toolbar arrow
buttons. The system back gesture therefore finishes the Activity, and none of the
sheets/dialogs can be dismissed with back.

**Fix:** Hierarchical back stack via `androidx.activity.compose.BackHandler`:

- **Top level (`MainActivity`)**: Settings → Library; Viewer → `closeDocument()` → Library;
  Library → default exit.
- **Viewer overlays (top-first)**: AI dialog → search mode → page jump → bookmarks →
  theme selector → TOC → page overview → text selection → close document.

This also enables Android's **predictive back animations**.

**Open question:** does "previous page" mean (A) previous *screen* = Library *(recommended)*,
(B) a history of pages visited *inside* the PDF before returning to Library, or (C) both
with a setting?

---

## 1. The honest diagnosis: why the current app is only "okay"

| Claim in README/rules | Reality in code |
|---|---|
| PDFium rendering | Uses `android.graphics.pdf.PdfRenderer` — no tiling, no annotations, no forms, no encryption support |
| "Instantaneous zooming" | `graphicsLayer { scaleX }` on the whole page → blurry bitmap scaling. `renderTile()` exists but is never called |
| "AI Semantic Search" | Keyword overlap ranking + one LLM prompt. No embeddings, no semantic understanding, no citations to text positions |
| "Smart Library ... bookmarking exact page" | Bookmarks were in-memory only (fixed in my earlier pass); library is JSON in `SharedPreferences` |
| Clean modular architecture | Everything lives in one `app` module; UI directly owns rendering state |
| "High-performance, memory-safe for >100MB" | Fixed caps, no `onTrimMemory`, no viewport-aware rendering, no bitmap lifecycle tied to Compose |

So "much, much better" means replacing the **foundation**, not adding more surface features
on top of it. Here is that foundation.

---

## 2. The vision: four strategic pillars

### Pillar A — A real rendering engine (the single biggest leap)

Replace `PdfRenderer` + PDFBox with one native engine: **evaluate PdfiumAndroid vs MuPDF
(`fitz`)** during implementation and pick the better-maintained one.

What this unlocks:
- True **tiled, level-of-detail rendering**: only visible tiles rendered, at the right
  resolution for the current zoom → crisp text at 400% without OOM.
- **Encrypted/password-protected PDFs** (PDFBox can decrypt but `PdfRenderer` cannot render).
- **Hyperlinks, form fields, annotations** in one engine instead of three hacks.
- **Text extraction with positions** in the same engine → faster, consistent highlights
  and selection (today PDFBox is re-opened per page).
- **Removes ~3.5 MB of PDFBox CMap assets** from the 9.8 MB APK.

Engineering work:
- New `:core:pdf` module with an engine abstraction (`PdfDocument`, `PdfPageSource`,
  `PageTile`, `TextLayer`) so the rest of the app never talks to a concrete engine.
- Tile grid computed from viewport + scale; render jobs on a dedicated dispatcher with
  priority for visible tiles; bitmap pools sized by `onTrimMemory` / device class.
- Compose `Image` consumers own bitmaps via `dispose`/`DisposableEffect` — no global recycling.

**Effort: XL. Risk: highest — but this is where "much better" actually lives.**

---

### Pillar B — Genuine, offline-first document intelligence

Turn "AI search" into a real semantic layer, mostly on-device (protects the privacy promise).

1. **On-device embeddings** (LiteRT/TFLite `TextEmbedder`, ~20–40 MB quantized model):
   chunk every page into passages, embed locally, store vectors in Room.
2. **Hybrid retrieval**: vector similarity + lexical (FTS5) + page/heading metadata,
   then rerank top candidates. This finds *meaning*, not just matching words.
3. **Grounded answers with citations**: the LLM receives only retrieved passages, must
   return `{page, quote, answer}`; UI highlights the cited rect and jumps there.
4. **Streaming responses**, answer cache per (document, query), multi-turn conversation
   with context, "Ask about this page", "Summarize selection", "Compare these two pages".
5. **Cross-library search**: one query across every indexed document, offline.
6. **Auto-generated outline** for PDFs with no TOC (detect heading font sizes/spacing),
   reading-time estimate, key-term extraction, link/table/figure detection.
7. **Privacy dashboard**: shows exactly what was sent to Groq (or nothing), with a
   hard "offline only" switch. Model download is optional/on-demand to keep base APK small.

**Effort: L–XL. Risk: medium (model size, index build time on large PDFs — mitigated by
background indexing with progress and cancellation).**

---

### Pillar C — Reading experience that feels premium

- **Focus Mode**: dim everything except the current line/paragraph; optional line guide.
- **Read aloud (TTS)**: Android `TextToSpeech` with sentence-level highlighting using the
  text layer's character rects — Kindle-style word/sentence tracking.
- **Gesture language**: focal-point pinch, snap-to-width, double-tap smart zoom on text
  blocks, edge-swipe page turn, volume-key page turn, configurable long-press actions.
- **Adaptive comfort**: warm/blue-light overlay, per-document brightness, auto theme by
  time of day, dyslexia-friendly font option for UI text, reduced-motion support.
- **Continuity**: exact scroll offset + zoom + theme restored per document; recently-read
  carousel with real thumbnails; reading stats (pages/min, session time, streak, goals)
  with simple charts.
- **Adaptive layouts**: phone, tablet/foldable two-pane (library + reader side by side),
  ChromeOS; predictive back; widgets and home-screen shortcuts.

**Effort: L. Risk: low–medium — mostly UI, but depends on Pillar A's text layer for TTS.**

---

### Pillar D — Document management & engineering quality

- **Library 2.0** (Room DB): tags, collections, smart folders, favourites, full-text library
  search, duplicate detection, storage/cache stats, cover selection, backup/export/import,
  and re-link UI for files whose URI permission expired.
- **Annotations**: highlight, underline, freehand, sticky notes, saved per document,
  exportable as Markdown/PDF.
- **Modularization**: `:app`, `:core:model`, `:core:data`, `:core:pdf`, `:feature:library`,
  `:feature:reader`, `:feature:ai`, `:feature:settings`; Hilt DI; Room; Kotlinx Serialization.
- **Quality gates**: unit + Compose + instrumented tests, Macrobenchmark startup/scroll
  benchmarks, GitHub Actions CI, optional privacy-preserving crash reporting, real release
  signing config (today it signs with the **debug key**), versioning + changelog + F-Droid
  metadata updates.

**Effort: L. Risk: low, but it is the work that makes everything else sustainable.**

---

## 3. Suggested sequence (waves, not a big-bang rewrite)

| Wave | Contents | Why this order |
|---|---|---|
| **Wave 0 — Fix & foundation** | Back fix (§0), module split, Room DB, Hilt, engine abstraction, baseline tests + CI | Everything after this becomes safer and faster to build |
| **Wave 1 — Engine swap** | Pdfium/MuPDF integration, tiled rendering, text layer, encryption, link support, trim PDFBox assets | Highest risk, so do it before piling on features |
| **Wave 2 — Intelligence** | On-device embeddings, hybrid retrieval, grounded streaming answers, citations, cross-library search, privacy dashboard | Requires the engine's text layer |
| **Wave 3 — Delight** | Focus mode, TTS read-aloud, gesture language, comfort settings, stats, adaptive layouts | Visible payoff users actually feel |
| **Wave 4 — Management & release** | Library 2.0, annotations, backup/restore, accessibility pass, release signing, benchmarks, store metadata | Polish and distribution |

I can parallelize items within a wave. Each wave ends with a build + on-device verification
through wireless ADB on your A17 (install, exercise flows, check logcat, screenshot).

---

## 4. Trade-offs you should decide

1. **Engine library:** PdfiumAndroid vs MuPDF — I'll evaluate and recommend during Wave 0/1,
   but tell me if you have a licence/FOSS preference (both are open source; worth checking
   F-Droid policy).
2. **On-device model size:** bundle ~20–40 MB in the APK, or download on first use (keeps the
   base APK small but needs network and storage). F-Droid cannot do dynamic feature delivery,
   so a runtime download is the compatible option.
3. **Scope:** approve all four waves, or start with Wave 0+1 (foundation + engine) and review
   before continuing?
4. **Back behaviour:** option A, B, or C from §0.

## 5. Validation after approval

For every wave: `./gradlew assembleDebug assembleRelease` + `testDebugUnitTest` + on-device
install and flow verification over wireless ADB, with screenshots/logcat evidence before I
report it done.

**Nothing will be executed until you approve the scope and answer the decisions above.**

---

## Appendix A — PDF engine debate: PDFium Android vs MuPDF (resolved recommendation)

Two independent research agents argued each side (sources below). They **agree** on the
following:

- The status quo (`PdfRenderer` + `pdfbox-android`) is the worst option and must go.
- Neither engine ships a Compose tiled viewer; we must build the tile grid/LOD scheduler
  ourselves. The difference is only the primitives each exposes.
- Removing `pdfbox-android` deletes ~3.5 MB of CMap assets and the per-page `PDDocument.load`
  re-open.

### The real trade-off

| Dimension | PDFium (`io.legere:pdfiumandroid:2.0.3`) | MuPDF (`com.artifex.mupdf:fitz:1.28.5`) |
|---|---|---|
| Licence | BSD-3/Apache-2.0 — app stays Apache-2.0, no copyleft | **AGPL-3.0-or-later — whole app must be relicensed AGPL**; F-Droid accepts it, but all future components must be AGPL-compatible |
| F-Droid build | On Maven Central → allowed prebuilt FLOSS binary | Hosted on `maven.ghostscript.com` (not trusted) → F-Droid needs a build-from-source recipe with NDK |
| Upstream maintenance | Chrome/PDFium is huge, but the *binding* is ~1 maintainer / 105 stars (bus factor) | Artifex ships monthly, official Android SDK + viewer, strong upstream |
| Features | Render, text+char boxes, links, TOC, password, metadata. **No annotation authoring, no form fill** | Tiles + cancellable display lists, per-char quads, annotations, forms, encryption, links, structured text, outlines |
| 16 KB pages | Verified 2\*\*14 on arm64/x86_64 (stale `barteksc:1.9.0` is 4 KB only — do not use it) | Verified 0x4000 |
| APK impact | ~2.6 MB compressed arm64 + Guava (R8-shrinkable), net smaller | ~9–10 MB compressed native after ABI filtering; F-Droid viewer precedent ~16 MiB |
| Integration | Keep `PdfEngine`'s public surface; replace per-page PDFBox load with one `PdfTextPage` | Cleaner Java API but manual native lifetime (`destroy()`) and a bigger conceptual jump |

### Recommendation

**Default choice: PDFium via `io.legere:pdfiumandroid:2.0.3`.** For an Apache-2.0,
F-Droid-intent, privacy-first *reader*, it preserves our licence, avoids a source-build
recipe, is 16 KB-ready, and is the smaller/faster-to-integrate change. Pin the stable
2.0.3 release; avoid the 3.0.x alphas and the stale `barteksc` artifact.

**Choose MuPDF instead if** annotation authoring / form filling / redaction become hard
roadmap requirements — it is the only engine here that covers them. That choice must be
made deliberately: relicense to AGPL-3.0-or-later, retain Apache notices, update LICENSE /
README / F-Droid metadata, and forbid proprietary components thereafter.

**Risk to verify with a short spike before committing either:** 16 KB emulator boot, real
APK delta after R8, TTS reading order (PDFium returns content-stream order), and a
device-level render benchmark — no trustworthy Android head-to-head exists.

### Key sources
- PDFium licence: https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE
- `io.legere` binding + releases: https://github.com/johngray1965/PdfiumAndroidKt
- MuPDF licence / AGPL guidance: https://mupdf.readthedocs.io/en/latest/license.html , https://artifex.com/licensing/agpl
- MuPDF releases: https://mupdf.com/releases/history
- F-Droid inclusion policy / MuPDF viewer listing: https://f-droid.org/docs/Inclusion_Policy/ , https://f-droid.org/packages/com.artifex.mupdf.viewer.app/
- Android 16 KB page requirement: https://developer.android.com/guide/practices/page-sizes
