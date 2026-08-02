# Reader UI & Playback (Plan 4) — Design

## Summary

Build `features:epubReader`, the read-along screen that ties together everything Plans 1–3 built: it parses an
EPUB on first open (`core:scanner`'s `EpubImporter`, deliberately left unwired since Plan 2), synthesizes and
plays its sentences via `core:tts` (Plan 3's `SentenceClipCache`/`VoiceManager`), and highlights the sentence
currently narrated, staying in sync by construction (current sentence = current media item). Voice selection is
auto-assigned on first open rather than picked by the user — the full voice picker UI is Plan 5's job.

This design elaborates the `features:epubReader` bullet in the parent spec
(`docs/superpowers/specs/2026-07-30-epub-ai-voice-reader-design.md`), which specified the module's role but not
its concrete implementation.

## Decisions

- **Reuse Voice's existing MediaSession/PlaybackService for playback**, not a separate player. Sentence clips
  become `MediaItem`s fed through the same `PlaybackService` audiobooks already use — background playback,
  lock-screen controls, and the widget work for EPUB narration the same way they do for audiobooks, for free.
- **Windowing logic lives entirely in `features:epubReader`, not in `core:playback`.** `core:playback`'s existing
  `VoicePlayer`/`MediaItemProvider`/`LibrarySessionCallback` are built around a fundamentally different shape:
  `VoicePlayer.setBook()` resolves a book into its *entire* chapter-marks list and loads it into ExoPlayer in one
  shot, because audiobook chapter files always already exist on disk in full before playback starts. EPUB
  narration can't do that — clips are synthesized just ahead of playback (Plan 3/parent-spec's rolling-buffer
  decision), so the playlist has to grow as it plays. Rather than teaching that shared, already-shipped,
  audiobook-only code a second, structurally different resolution mode, `features:epubReader` talks to the same
  `MediaController`/`PlaybackService`/`MediaSession` directly and owns all EPUB-specific playlist/windowing logic
  itself. `core:playback` is not modified. Tradeoff: some duplication of plumbing `VoicePlayer` provides for
  audiobooks for free (though most of it — speed/gain/skip-silence persistence — isn't book-type-specific and
  should still work unmodified for EPUB playback, since it hooks the underlying player regardless of media type).
- **Window reload is batched, not incremental.** Rather than fine-grained per-item `addMediaItem`/`removeMediaItem`
  calls tracking exact ExoPlayer indices, the playlist controller reloads in batches: when playback nears the end
  of the currently-loaded ~30-sentence window, it synthesizes the next batch and does one atomic
  `setMediaItems(items, index, position)` call preserving the current item's relative position. Simpler to get
  correct than true incremental streaming, and Media3's `setMediaItems` with an explicit index+position is
  designed exactly for this kind of atomic swap.
- **No explicit "buffering" pause state for synthesis-falls-behind-playback.** The window-ahead margin (reloading
  at window-position N-5 of ~30, not N) covers the normal case. If synthesis is genuinely too slow, ExoPlayer just
  reaches the end of the currently-loaded window and stops — which reads to the user as buffering/pausing without
  needing new player-state machinery.
- **Voice is auto-assigned on first open, not picked by the user.** If a book's `voiceId` is unset, the reader
  silently defaults to `VoiceCatalog.entries.first()`, installing it via `VoiceManager.install` if needed, and
  persists the choice. No picker UI ships in this plan — voice selection/switching UI is entirely Plan 5's scope.
  This unblocks playback without building UI that Plan 5 would immediately extend or replace.
- **A permanently-failing sentence is skipped, not replaced with literal silence.** The parent spec's Error
  Handling section says a failing sentence "plays silence and is visually marked." This design skips adding a
  `MediaItem` for it entirely (rather than synthesizing an actual silent WAV placeholder) and marks it visually in
  the reader UI — same practical effect (narration doesn't stop, the gap is visible) for less machinery.
- **New `BookContent` fields for EPUB reading position:** `currentEpubChapterIndex: Int` and
  `currentEpubSentenceIndex: Int` (new Room migration). The existing `currentChapter: ChapterId` /
  `positionInChapter: Long` fields are audiobook-shaped (a real per-file `ChapterId` + millisecond offset) and
  can't carry per-sentence position — an EPUB book only ever has one synthetic audiobook-`Chapter` (Plan 2), so
  `currentChapter` for an EPUB book is always that same single `ChapterId` regardless of reading progress.
- **Chapter navigation includes a simple jump-to-chapter list**, not just linear next/previous, reusing
  `EpubBookRepo.chapters(bookId)` (already returns title + index) styled after the existing `SelectChapterDialog`
  pattern. Cheap given the data's already there, and meaningfully better than being stuck linear.

## Architecture

New module: **`features:epubReader`**, following the existing feature-module pattern (`AGENTS.md`: features depend
only on core + infrastructure, no feature-to-feature deps). Dependencies: `core:tts` (`VoiceManager`,
`SentenceClipCache`, `VoiceCatalog`), `core:scanner` (`EpubImporter`), `core:data` (`EpubBookRepo`,
`BookRepository`, `BookContentRepo`), `core:documentfile` (`CachedDocumentFileFactory`), `core:playback`
(`PlayerController`-level access to the shared `MediaController`). `core:epub` is not a direct dependency — only
`core:scanner`'s `EpubImporter` is touched, matching Plan 2's existing boundary.

- **`EpubReaderViewModel`** — the screen's state holder, created via the same per-screen Metro subgraph +
  assisted-injection pattern as `BookPlayViewModel` (`rootGraphAs<EpubReaderGraph>().epubReaderViewModelFactory
  .create(bookId)`). Owns: ensure-parsed / ensure-voice-assigned on open, the current chapter's sentence list
  (one chapter in memory at a time), the currently-highlighted sentence index, play/pause/chapter-nav intents, and
  periodic position persistence.
- **`EpubPlaylistController`** — the windowing scheduler. On (re)start, determines the starting
  `(chapterIndex, sentenceIndex)`, synthesizes an initial batch of upcoming sentences via
  `SentenceClipCache.getOrSynthesize` (blocking only on the first clip or two so playback starts promptly, the
  rest synthesizing in the background), builds `MediaItem`s pointing at the resulting WAV files, and calls
  `controller.setMediaItems(items, startIndex, 0)`. Watches the controller's current-media-item-index; near the
  end of the loaded window, synthesizes the next batch and does one `setMediaItems` reload. Talks to the
  `MediaController` through a thin interface exposing only the surface this class needs (`setMediaItems`,
  current-index observation) — not the whole `MediaController` API — so it's fake-able in tests without a real
  ExoPlayer/MediaSession.
- **`EpubReaderScreen`** (Compose) — renders the current chapter's sentences (from
  `EpubBookRepo.sentences(bookId, chapterIndex)`) in a `LazyColumn`, highlighting the active sentence and
  auto-scrolling it into view. Playback controls (play/pause, skip forward/back by sentence, chapter nav) are new,
  sentence-scoped components — not a reuse of `BookPlayView`'s time-based scrubbing UI.

**Routing:** `BookOverviewViewModel.onBookClick`/`onSearchBookClick` gain a `content.sourceType` check (via the
already-injected `contentRepo`) and route to a new `Destination.EpubReader(bookId)` instead of
`Destination.Playback(bookId)` when the source is `Epub`. `features:epubReader` registers its own
`NavEntryProvider<Destination.EpubReader>`, mirroring how `features:playbackScreen` registers
`bookPlayNavEntryProvider()` today. This closes the gap flagged since Plan 2: currently every tapped book routes
to the audiobook player regardless of type, which would just fail on a non-audio file.

**First-open handling:** an EPUB's `BookId` is literally `BookId(file.uri)` (Plan 2), so the reader ViewModel can
always reconstruct the SAF `Uri` via `bookId.toUri()` — no separately-stored file reference is needed.

## Data Flow

**Opening a book (first time or resume)**
1. `BookOverviewViewModel` routes to `Destination.EpubReader(bookId)` when `sourceType == Epub`.
2. `EpubReaderViewModel` checks `EpubBookRepo.chapters(bookId)`; if empty, resolves the file via
   `CachedDocumentFileFactory.create(bookId.toUri())` and calls `EpubImporter.import(bookId, file)`, persisting
   chapters/sentences. A `Malformed`/`DrmProtected` result shows an error state instead of the reading UI.
3. If `content.voiceId == null`, auto-assigns and installs the first catalog voice (installing blocks the screen
   with a loading state — an unavoidable one-time ~65MB download before any synthesis can happen).
4. `EpubReaderViewModel` loads the current chapter's sentences (from `currentEpubChapterIndex`, default 0) and
   hands `(chapterIndex, sentenceIndex)` to `EpubPlaylistController` to start.

**Playback start**
5. `EpubPlaylistController` synthesizes the first ~30 sentences from the starting position, builds `MediaItem`s,
   and calls `controller.setMediaItems(items, startIndex, 0)` then `play()`.
6. As the current media item index advances, `EpubReaderViewModel` maps it back to
   `(chapterIndex, sentenceIndex)` (the window controller tracks this mapping since it built the list) and
   updates the highlighted sentence.

**Window sliding**
7. When playback nears the end of the loaded window (window-position N-5 of ~30), `EpubPlaylistController`
   synthesizes the next batch and does one `setMediaItems` reload, preserving the current item's relative
   position — no audible interruption.
8. Chapter boundaries within a window are transparent to the controller — it keeps requesting
   `(chapterIndex, sentenceIndex + 1)`, rolling into the next chapter's sentence 0 when the current chapter is
   exhausted. The ViewModel separately reloads the chapter's sentence text for display when the *displayed*
   chapter changes.

**Position persistence**
9. `EpubReaderViewModel` periodically (on each sentence change, debounced) persists
   `(currentEpubChapterIndex, currentEpubSentenceIndex)` via `BookRepository.updateBook`, mirroring how audiobook
   position persistence already works but at sentence granularity instead of a millisecond offset.

**Resume**
10. Reopening the book skips straight to step 4 with the persisted `(chapterIndex, sentenceIndex)` as the
    starting point — no re-parse (chapters/sentences already exist), no re-voice-assignment (already set).

**Mid-book voice change** (mechanism only — the picker UI itself is Plan 5's job): changing `voiceId` invalidates
the *forward* window only; `EpubPlaylistController` restarts from the current position with the new voice.
Already-synthesized clips for the old voice are left alone — still cached per Plan 3's cache semantics, nothing
here actively evicts them.

## Error Handling & Edge Cases

- **Malformed/DRM-protected EPUB:** `EpubImporter.import()` returns a typed `EpubParseResult`
  (`Malformed`/`DrmProtected`, Plan 1/2). On either, the reader screen shows an error state instead of the
  reading UI — no crash, no partial state.
- **Synthesis failure for a sentence:** `SentenceClipCache.getOrSynthesize` returns a typed `Failure`.
  `EpubPlaylistController` retries once or twice with a short backoff; if still failing, skips that sentence from
  the media timeline (no `MediaItem` added) and the ViewModel marks it visually in the text.
- **Voice install failure (first-open auto-assign):** `VoiceManager.install` returns a typed `Failure`. The reader
  screen shows a clear error with a retry action rather than silently failing to start.
- **Storage pressure:** handled at the `SentenceClipCache` layer already (typed `Failure` when eviction can't free
  enough space) — surfaces through the same synthesis-failure path above, no special casing needed here.
- **Synthesis falling behind playback:** handled by construction (see Decisions) — no explicit pause state.
- **Very large books:** one chapter's sentences in memory at a time (Architecture), not the whole book — EPUB
  chapters are naturally bounded (a few hundred sentences at most), so this needs no additional intra-chapter
  paging.

## Testing

Following the parent spec's stated approach for `features:epubReader` (Molecule + Turbine, in-memory fakes):

- **`EpubReaderViewModel`**: Molecule/Turbine tests covering sentence-highlight-follows-playback,
  resume-from-persisted-position, first-open parse+voice-assign flow, and the malformed/DRM error states — using
  fakes for `EpubBookRepo`, `SentenceClipCache`/`VoiceManager` (building on Plan 3's `FakeSynthesisEngine`
  precedent), and a fake `MediaController`-surface test double.
- **`EpubPlaylistController`**: unit tests for the windowing logic itself — initial window synthesis, reload-at-N-5
  triggering, position-preserving reload, and skip-and-mark on synthesis failure — decoupled from real
  ExoPlayer/MediaSession via the thin controller-facing interface from Architecture.
- **Routing:** a focused test on `BookOverviewViewModel.onBookClick`/`onSearchBookClick` confirming the
  `sourceType` branch picks the right destination.
- Real device verification (matching how Plans 1–2 were manually verified): open a real EPUB on-device, confirm
  audio plays, highlighting tracks it, the app can be backgrounded with lock-screen controls, and resuming picks
  up where it left off.

## Non-Goals

- The full voice picker/download-management UI — Plan 5's job. This plan only auto-assigns a voice on first open.
- Any settings for the clip-cache size cap — Plan 5's job (per Plan 3's design).
- Per-word (as opposed to per-sentence) highlighting — already a non-goal in the parent spec.
- Scrubbing to an arbitrary time position — sentence-granularity seeking only.
- Literal silent-audio generation for permanently-failed sentences — skip-and-mark instead (see Decisions).
- Modifying `core:playback`'s existing audiobook playback code in any way.
