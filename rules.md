You are a senior Android engineer working inside an EXISTING Android Studio project.

CRITICAL CONSTRAINTS:
- DO NOT recreate the project.
- DO NOT overwrite or regenerate Gradle files completely.
- ONLY modify Gradle files minimally when adding required dependencies.
- PRESERVE existing project structure unless explicitly extending it.
- ALL code must be production-quality, modular, and performance-optimized.

PROJECT CONTEXT:
We are building a high-performance, minimal, distraction-free PDF workspace app for Android.

CORE PHILOSOPHY:
- Speed is the primary feature.
- UI must be minimal and gesture-driven.
- Advanced features must not clutter the default interface.
- The app must handle large PDFs efficiently without lag or crashes.

TECH STACK REQUIREMENTS:
- Language: Kotlin
- UI: Jetpack Compose
- Concurrency: Kotlin Coroutines + Flow
- PDF Rendering: PDFium (Android binding preferred; fallback only if necessary)
- Architecture: Clean modular structure (UI / domain / data separation where reasonable)

----------------------------------------
PHASE 1 GOAL:
Implement a high-performance PDF VIEWING ENGINE with:
- Smooth vertical scrolling
- Pinch-to-zoom (center-aware)
- Double-tap zoom
- Lazy page loading
- Memory-safe rendering for large PDFs

----------------------------------------
FEATURE REQUIREMENTS (PHASE 1):

1. PDF RENDERING ENGINE
- Use PDFium for rendering pages
- Render pages as bitmaps efficiently
- Implement tile-based rendering for zoom (if feasible)
- Load only visible pages (lazy loading)
- Use background threads for rendering (coroutines)

2. VIEWER UI (Compose)
- Fullscreen document view
- Vertical scroll by default
- Minimal UI chrome
- Display current page indicator (subtle overlay)

3. ZOOM SYSTEM
- Pinch-to-zoom with proper focal point handling
- Double tap to zoom in/out
- Maintain zoom state per session

4. READ MODE (IMPORTANT DIFFERENTIATOR)
- User can toggle "Read Mode"
- Locks horizontal movement
- Allows only vertical scrolling
- Maintains chosen zoom level
- Optional: fit-to-width behavior

5. PERFORMANCE REQUIREMENTS
- No UI thread blocking
- Efficient bitmap reuse or caching
- Avoid OOM crashes on large PDFs (>100MB)
- Use paging strategy for document

6. FILE HANDLING
- Load PDF from local storage URI
- Basic file picker integration (can be simple for now)

7. STATE MANAGEMENT
- Persist:
  - Last opened page
  - Zoom level
  - Scroll position

----------------------------------------
ARCHITECTURE REQUIREMENTS:

- Separate:
  - PDF rendering layer
  - ViewModel (state management)
  - UI composables
- Use unidirectional data flow
- Avoid tightly coupled UI and rendering logic

----------------------------------------
DELIVERABLE FORMAT:

Provide:

1. FILE STRUCTURE (clear and organized)
2. ALL REQUIRED KOTLIN FILES (complete, not snippets)
3. DEPENDENCY ADDITIONS (only minimal changes to Gradle)
4. CLEAR INSTRUCTIONS on:
   - Where to place each file
   - What to modify in existing project

----------------------------------------
IMPORTANT:

- Do NOT implement OCR, annotation, or editing yet.
- Do NOT over-engineer UI.
- Focus on performance and correctness.

----------------------------------------
AFTER IMPLEMENTATION:

Also include:
- Known limitations
- Suggested next steps (Phase 2: search, grid view, annotations)

----------------------------------------

Start with Phase 1 implementation only.
Do not skip architecture explanation.
Do not give vague descriptions—provide concrete, working code.