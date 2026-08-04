# Library EPUB Unification (Plan 5) — Design

## Summary

Treat EPUB books as a first-class media source in the library screen, not a bolted-on special case. Three
concrete, independent gaps are fixed, all stemming from the same root cause: `BookOverview`'s "what book, what
progress, what's currently playing" concepts are entirely audiobook-shaped, and EPUB playback (Plan 4) never
plugged into them.

1. The library list's progress categorization (`Book.category`: Not Started / Current / Finished) reads
   audiobook-only `position`/`duration` fields, which an EPUB never populates — so every EPUB shows "Not Started"
   forever regardless of real reading progress.
2. The library screen's global play/pause FAB does nothing (silently, with no error shown) when nothing is loaded
   into the player yet and the last thing read was an EPUB, because it only knows how to resolve the audiobook-only
   `currentBookStoreId`.
3. The folder-add flow (`SelectFolderTypeViewModel`) has zero EPUB awareness: file counting is audio-format-only,
   and the folder-type auto-detection heuristic never recognizes a folder of `.epub` files as "list each book
   individually" — it collapses the whole folder into one bogus "book."

This elaborates the "library epub-awareness" scope flagged across several rounds of on-device testing after Plan
4 merged (see `docs/superpowers/plans/2026-08-02-epub-reader-playback.md` and the project's ongoing on-device
testing notes). Voice picker UI, EPUB cover extraction, and the Kindle-style highlighting/reading redesign are
explicitly out of scope — they're a separate, later plan.

## Decisions

- **Do not extend `currentBookStoreId`'s role, and do not touch `VoicePlayer`/`MediaItemProvider`/
  `LibrarySessionCallback`.** Those classes resolve `currentBookStoreId` into real, playable audio `MediaItem`s
  unconditionally, and this session already found and fixed two separate bugs where they hijacked an active EPUB
  session (`onPlaybackResumption`, `onSetMediaItemsForSingleItem`). Writing an EPUB's `BookId` into that same
  DataStore would reintroduce the same class of bug at every one of those consumers, trading a "minimal" library
  fix for a second pass through code that was just hardened. The library screen gets its own, separate way to
  find "the most recently active book of either type" instead (see Architecture).
- **Cache two new Int fields on `BookContent` for EPUB progress: `epubChapterCount` and
  `epubLastChapterSentenceCount`.** Mirrors how audiobook `duration` is a cached value, not computed live from
  chapter files on every read. Computed exactly once, inside `EpubBookOpener.open()`'s existing
  `if (chapters.isEmpty())` fresh-import branch, right after `chapters` is reloaded post-import — this is the
  *only* point where a book's chapters/sentences go from empty to populated (Plan 2's `MediaScanner` stub-entry
  creation deliberately never persists real `EpubChapter`/`EpubSentence` rows), so it never needs recomputing on
  later opens. Uses the class's already-injected `bookContentRepo`/`epubBookRepo` — no new dependency. Avoids
  needing a full flattened "sentence ordinal"
  position system (which would ripple through `EpubPlaylistController`/`EpubReaderViewModel`'s existing
  chapter+sentence-index model) — Finished only needs to know "am I at/near the end of the last chapter,"  which
  these two Ints answer directly against the existing `currentEpubChapterIndex`/`currentEpubSentenceIndex` fields.
- **EPUB "Finished" uses a small trailing-sentence buffer**, mirroring audiobooks' 5-second-before-the-end buffer,
  rather than requiring an exact match on the last sentence. Exact semantics: Finished when
  `currentEpubChapterIndex >= epubChapterCount - 1 && currentEpubSentenceIndex >= epubLastChapterSentenceCount - 2`
  (within the last 2 sentences of the last chapter). Not Started stays exact:
  `currentEpubChapterIndex == 0 && currentEpubSentenceIndex == 0`.
- **EPUB reading now also updates `lastPlayedAt`**, mirroring `VoicePlayer.updateLastPlayedAt()`'s audiobook
  behavior. This is the one new signal the library FAB needs to compare "most recently active audiobook" against
  "most recently active EPUB" without touching `currentBookStoreId`.
- **The library FAB decides by comparing `lastPlayedAt` across both candidates, not by unifying them into one
  DataStore.** Read `currentBookStoreId` (audiobook candidate, if set) and separately query the most-recently-read
  EPUB (`BookContentRepo`, filtered by `sourceType == Epub`, max by `lastPlayedAt`). Whichever has the more recent
  `lastPlayedAt` wins. Audiobook wins (or no EPUB exists) → existing `playerController.playPause()` behavior,
  completely unchanged. EPUB wins → navigate to `Destination.EpubReader(bookId)` instead of trying to play inline;
  the reader screen already auto-resumes from the persisted position and auto-plays on open (existing behavior
  from Plan 4 + this session's resume-position fix), so no new playback-start logic is needed here.
- **Folder-add: generalize file counting rather than special-casing EPUB.** New `isEpubFile()`/`epubFileCount()`
  extensions in `core:data:api`, mirroring the existing `isAudioFile()`/`audioFileCount()` shape exactly (same
  file, same pattern — single-file check + recursive `walk()` count). Combine into `isBookFile()`/
  `bookFileCount()` (audio OR epub). `SelectFolderTypeViewModel` uses the generalized versions everywhere it
  currently uses the audio-only ones — this fixes "shows 0 files" for any EPUB entry with no separate branching
  logic needed in the view-state-building code.
- **Folder-type auto-detection gains one new case: multiple flat `.epub` files.** Audiobook folders use
  subdirectories (each subfolder = one book with multiple audio tracks inside) to trigger "list each item
  individually" (`FolderMode.Audiobooks`) detection. EPUB collections are structurally flat — each `.epub` file
  *is* a complete book on its own, no subfolder needed. Add `children.count { it.isEpubFile() } > 1 ->
  FolderMode.Audiobooks` as an additional detection case (reusing the existing mode name/list-each-item behavior;
  not introducing a new `FolderMode` — the existing `Audiobooks` mode's actual behavior, "list each child as a
  separate book," is exactly what's needed here too).

## Architecture

No new modules. Changes span `core:data:api`/`core:data:impl` (new `BookContent` fields + migration + epub-file
extensions), `features:epubReader` (writing the new fields + `lastPlayedAt`), and `features:bookOverview`/
`features:folderPicker` (the consuming logic: category display, FAB routing, folder-add detection).

- **`core/data/api/BookContent.kt`** — two new trailing constructor params, both `@ColumnInfo(defaultValue = "0")`:
  `epubChapterCount: Int = 0`, `epubLastChapterSentenceCount: Int = 0`.
- **`core/data/impl/AppDb.kt`** — `AutoMigration(from = 63, to = 64)`, `VERSION = 64`.
- **`features/bookOverview/overview/BookOverviewCategory.kt`**'s `Book.category` — branches on `content.sourceType`: `Audio` keeps
  the existing position/duration logic unchanged; `Epub` uses the new fields against
  `currentEpubChapterIndex`/`currentEpubSentenceIndex` per the Decisions section's exact thresholds.
- **`core/data/api/SupportedAudioFormats.kt`** (or a new sibling file) — adds `isEpubFile()`/`epubFileCount()`/
  `isBookFile()`/`bookFileCount()` alongside the existing audio-only functions.
- **`features/epubReader/EpubReaderViewModel.kt`** — the existing position-tracking `scope.launch` collector (which
  already calls `bookRepository.updateBook(bookId) { it.copy(currentEpubChapterIndex = ..., ...) }` on every
  sentence change) also sets `lastPlayedAt = Instant.now()` in that same `copy()` call. No new collector needed.
- **`features/epubReader/EpubBookOpener.kt`** — inside the existing `if (chapters.isEmpty())` branch, right after
  `chapters = epubBookRepo.chapters(bookId)` reloads them post-import: compute `epubChapterCount = chapters.size`
  and query the last chapter's sentence count (`epubBookRepo.sentences(bookId, chapters.size - 1).size`), then
  write both into `BookContent` via a `bookContentRepo.put(content.copy(...))` call (fetching `content` from
  `bookContentRepo.get(bookId)` a touch earlier than the existing code does, or restructuring slightly so this
  write and the existing `voiceId`-assignment write can share one `content` lookup — either is fine, this is an
  implementation detail for the plan, not a design decision). This is a *separate* write from the voiceId one
  further down, since voiceId assignment only fires conditionally (when unset) and the two are independent.
- **`features/bookOverview/overview/BookOverviewViewModel.kt`** — `playPause()` (currently a one-line delegation to
  `playerController.playPause()`) becomes: look up the audiobook candidate via `currentBookStoreDataStore` (already
  injected) and the most-recent EPUB via `contentRepo` (already injected), compare `lastPlayedAt`, branch per the
  Decisions section.
- **`features/folderPicker/selectType/SelectFolderTypeViewModel.kt`** — `defaultFolderMode()` gains the new
  multiple-flat-epub-files case; `viewState()`'s per-`FolderMode` book-listing blocks switch from `audioFileCount()`
  to `bookFileCount()`.

## Data Flow

**Progress display (library list rendering)**
1. `BookOverview`'s existing per-book UI code reads `Book.category` for sorting/section-placement, unchanged at
   the call site.
2. `Book.category` now checks `content.sourceType` first. For `Epub`, it compares
   `currentEpubChapterIndex`/`currentEpubSentenceIndex` against the cached `epubChapterCount`/
   `epubLastChapterSentenceCount` fields (populated at parse time, step 4 below) to pick Not Started / Current /
   Finished — no DB query needed at render time, matching the existing synchronous-property shape.

**Parsing (first open)**
3. `EpubBookOpener.open()`'s existing `if (chapters.isEmpty())` branch calls `EpubImporter.import(...)`, which
   persists the real chapters/sentences via `EpubBookRepo.replaceChapters(...)` (unchanged). Immediately after,
   still inside that same branch, `EpubBookOpener` reloads `chapters` (existing code) and now also computes and
   writes `epubChapterCount`/`epubLastChapterSentenceCount` into that book's `BookContent`.

**Reading progress + `lastPlayedAt`**
4. `EpubReaderViewModel`'s existing `currentSentenceFlow()` collector's `bookRepository.updateBook(...)` call adds
   `lastPlayedAt = Instant.now()` to the same `copy()`, alongside the existing
   `currentEpubChapterIndex`/`currentEpubSentenceIndex` update — no new collector or trigger point.

**Cold-start library FAB**
5. User taps the library's global play/pause FAB with nothing loaded into the player yet.
6. `BookOverviewViewModel.playPause()` reads `currentBookStoreDataStore.data.first()`; if non-null, looks up that
   book's `lastPlayedAt` via `bookRepository`/`contentRepo`.
7. Separately queries `contentRepo` for the most-recently-read `Epub`-sourced book (`all().filter { sourceType ==
   Epub }.maxByOrNull { lastPlayedAt }`).
8. Compares the two `lastPlayedAt` values (treating "no candidate" as absent, not as epoch/zero, so an absent
   audiobook candidate never wins by default). More recent wins: audiobook → unchanged
   `playerController.playPause()`; EPUB → `navigator.goTo(Destination.EpubReader(bookId))`, which auto-resumes and
   auto-plays via existing Plan 4 behavior.

**Folder add**
9. User picks a folder via the SAF picker; `SelectFolderTypeViewModel.defaultFolderMode()` runs its heuristic,
   now including the multiple-flat-`.epub`-files case, to pick the default `FolderMode`.
10. `viewState()` builds the book-preview list using `bookFileCount()` (audio OR epub) instead of the audio-only
    `audioFileCount()`, so EPUB entries show a real, non-zero file count and aren't visually indistinguishable
    from a broken/empty entry.

## Error Handling & Edge Cases

- **A book with no persisted `epubChapterCount`/`epubLastChapterSentenceCount` yet** (pre-existing library rows
  from before this migration, or a book that's never been opened so chapters were never parsed): both default to
  `0` via the migration's `DEFAULT 0`. `Book.category`'s Epub branch treats `epubChapterCount == 0` as "unparsed,
  can't determine progress" and falls back to Not Started (matching the pre-fix behavior for that specific edge
  case, rather than a division-by-zero-shaped Finished miscalculation).
- **No audiobook has ever been played** (`currentBookStoreId` is null): the FAB comparison in step 6-8 treats a
  null audiobook candidate as "always loses" to any EPUB candidate that exists; if neither exists, the FAB's
  behavior is unchanged from today (whatever `playerController.playPause()` already does with no current book —
  a no-op, pre-existing behavior, not addressed by this plan).
- **Both an audiobook and an EPUB were added to the library but neither has ever actually played**: both
  `BookParser.kt` and `EpubBookParser.kt` already initialize `lastPlayedAt = Instant.EPOCH` on import (confirmed in
  the existing code, not import time), so comparing two never-played books' `lastPlayedAt` is a safe, well-defined
  tie — the comparison in step 8 doesn't need special-casing for this; whichever "wins" the tie is inconsequential
  since neither has real progress to resume anyway.
- **Folder containing a mix of audio subfolders and flat `.epub` files**: the new auto-detection case
  (`children.count { it.isEpubFile() } > 1`) is additive to the existing audio-mixed-with-directories check — both
  can independently trigger `FolderMode.Audiobooks`, and the existing per-child `bookFileCount()` correctly counts
  whichever type each individual child actually is. A user can also always manually override the detected
  `FolderMode` via the existing UI regardless of auto-detection.

## Testing

- **`Book.category`** (`features/bookOverview/src/test/kotlin/voice/features/bookOverview/BookOverviewCategoryTest.kt`,
  which already exists): new test cases for `sourceType ==
  Epub` covering Not Started (0,0), Current (mid-book), Finished (at/near last chapter's last sentence, using the
  2-sentence buffer), and the "unparsed, epubChapterCount == 0" fallback edge case. Existing audiobook test cases
  must still pass unchanged.
- **`SelectFolderTypeViewModel`**: new test cases for a folder of only `.epub` files (auto-detects
  `FolderMode.Audiobooks`-equivalent listing, correct per-item file counts), a single loose `.epub` file
  (`FolderMode.SingleBook`, count reads 1 not 0), and a mixed audio-subfolder + flat-epub-files folder. Existing
  audio-only test cases must still pass unchanged.
- **`BookOverviewViewModel.playPause()`**: new test cases covering audiobook-more-recent-wins,
  epub-more-recent-wins (asserts `navigator.goTo(Destination.EpubReader(...))`, not
  `playerController.playPause()`), no-audiobook-candidate, and no-epub-candidate. Follows this file's existing
  `mockk<Navigator>(relaxed = true)` + `verify` pattern (already used for the `onBookClick` routing tests from
  Plan 4).
- **Migration test**: `migrate64` in `DataBaseMigratorTest.kt`, following the exact `migrate63` pattern from Plan
  4's Task 1 — verified via the generated schema JSON, per this Windows dev machine's documented `MigrationTestHelper`
  limitation.
- Manual on-device verification: confirm a previously-read EPUB shows Current (not Not Started) in the library
  list; confirm reading to the end of a short test EPUB shows Finished; confirm killing the app after reading an
  EPUB, then tapping the library FAB, opens and auto-plays that EPUB instead of silently failing.

## Non-Goals

- Per-book voice picker / voice download management UI — separate, later plan.
- EPUB cover extraction — separate, later plan.
- Kindle-style highlighting/reading UI redesign — separate, later plan.
- A visual "synthesizing/constructing cache" loading indicator — separate, later plan (noted from the same
  on-device testing round, but not a library-unification concern).
- Wiring `EpubReaderViewState.Content.failedSentenceIndices` end-to-end — separate, later plan.
- Any change to `VoicePlayer`, `MediaItemProvider`, or `LibrarySessionCallback` — explicitly ruled out (see
  Decisions) to avoid reopening the hijacking-bug class this session just fixed.
- A unified, single "current book of either type" DataStore — explicitly ruled out in favor of the
  `lastPlayedAt`-comparison approach (see Decisions).
- Percentage-complete display or any UI beyond the existing three-category (Not Started/Current/Finished)
  sectioning — this plan only fixes categorization accuracy, not new progress UI.
