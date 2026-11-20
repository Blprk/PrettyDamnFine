# Feature audit — what a proper PDF reader/editor needs

This checklist was produced by an independent codebase audit against mainstream PDF reader
conventions (Adobe Acrobat, Foxit, MuPDF Viewer, Google PDF Viewer, PDF.js) and Android
platform norms. Status reflects version 2.0.2 (after the import/storage fixes).

Legend: **Done** · **Partial** · **Missing**

> Latest pass: a full UI/UX modernization (one Material 3 token system, reusable
> components, accessible icon actions) and an explicit-state-machine refactor are in
> place. 23 implicit modes were inventoried; 14 were promoted to sealed types under
> `com.blprkfgre.prettydamnfine.state` while every legacy ViewModel flow remains
> available. See [CHANGELOG.md](CHANGELOG.md) → Unreleased.

## A. Core viewing / rendering
| Feature | Status |
|---|---|
| Open local files and `content://` streams from other apps reliably | Done |
| Password-protected PDFs with a password prompt | Done (was broken: encrypted files were treated as stale) |
| Crisp high-zoom rendering (tiled/LOD instead of scaled bitmap) | Missing — `renderTile()` exists but is unused |
| Fit-width / fit-page and zoom below 100% | Missing — zoom clamped to 1f–5f |
| Single-page / two-page / landscape layouts | Missing |
| Document properties / metadata screen | Missing |
| Tappable internal and web links | Missing |
| Damaged-file / unsupported-format error state | Partial |

## B. Import & storage management
| Feature | Status |
|---|---|
| `ACTION_VIEW` **and** `ACTION_SEND` from WhatsApp, Gmail, Files, etc. | Done (singleTop + `onNewIntent` + SEND filter) |
| Durable app-owned copy so volatile provider URIs never break the library | Done (import into `filesDir/pdf`, stable `file://` library entry) |
| Recent documents survive restart, including files from other apps | Done |
| **Save a copy / Download to device storage** | Done (SAF `CreateDocument`, no permission needed) |
| Library "Remove" never deletes the user's file | Done |
| Local storage browser / Downloads shortcut / re-link lost files | Missing |
| Storage management (cache size, cleanup of stale working copies) | Partial — private copy removed on library removal |

## C. Navigation & search
| Feature | Status |
|---|---|
| Page indicator, go-to-page, fast scrollbar, thumbnail overview | Done |
| TOC / outline navigation | Done |
| Reading position + zoom restored per document | Done |
| Full-text search with next/previous and on-page highlights | Done (partial: results depend on extraction progress) |
| Debounced search, case/whole-word options, distinct current-match color | Missing |
| Swipe / volume-key page turns | Missing |
| Bookmarks (persisted but UI intentionally removed per request) | Disabled by request |

## D. Annotation & light editing
| Feature | Status |
|---|---|
| Real PDF highlight + remove highlight | Done |
| Save edits back to a private working copy | Done |
| Unsaved-changes indicator / auto-save on close | Missing |
| Underline, strikethrough, sticky note, ink, text box, shapes | Missing |
| Annotation list, color/opacity picker, undo/redo | Missing |
| Form fill, signatures, redaction | Missing |

## E. Text selection actions
| Feature | Status |
|---|---|
| First long-press selects immediately (prefetched char positions) | Done |
| Copy, Call, Email, Highlight, Remove highlight, Close | Done |
| Share selection, Search selection, Look up / Define, Add note | Missing |

## F. Sharing / export
| Feature | Status |
|---|---|
| Share PDF via FileProvider (library + viewer) | Done (flushes unsaved annotations first) |
| Save a copy to any storage provider | Done |
| Open with… / Print / export annotation summary | Missing |

## G. Settings / UX
| Feature | Status |
|---|---|
| App light/dark toggle + page themes (light/dark/sepia/auto) | Done |
| Proper hierarchical Back handling | Done |
| Real settings screen (gestures, default zoom, storage, about) | Missing |
| Accessibility pass (labels, large text, TalkBack) | Missing |
| Tablet / foldable two-pane layouts | Missing |

## H. Privacy / offline
| Feature | Status |
|---|---|
| No `INTERNET` permission, no analytics, no accounts | Done (verified in built APK) |
| Private PDFs and library metadata excluded from backup/device transfer | Done |
| App lock / biometric gate | Missing |

## Next recommended wave (after the three trust-breaking fixes)
1. Restore bookmarks + TOC into a unified navigation sheet; add clickable PDF links.
2. Share selected text / search selection; debounce search.
3. Generalize annotations: underline, strikethrough, note, color picker, undo.
4. Tile-based zoom rendering and fit-width/fit-page.
5. Real settings screen, storage cleanup, accessibility pass, production signing key.
