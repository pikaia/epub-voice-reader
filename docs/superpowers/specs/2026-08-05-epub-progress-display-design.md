# EPUB Progress Display — Design

## Summary

EPUB books currently always show `0:00` as their remaining-time text in the library list, and no
percentage or progress bar renders at all (both derive from `Book.duration`/`Book.position`, which
are summed from `Chapter.duration` — always `0` for the synthetic chapters EPUBs use). This was a
known, deliberately-deferred gap from the Library EPUB Unification plan (2026-08-03): that plan
fixed *categorization* (Not Started / Current / Finished) but explicitly left the numeric progress
display for later.

This plan gives EPUBs the same progress display as audiobooks, in both places audiobooks show it:

1. **Library card** — total book time remaining/elapsed + percentage, using the existing
   `BookRemainingProgressRow`/`BookProgressIndicator` composables audiobooks already render through.
2. **Reader screen** — a chapter-scoped, interactive scrubber (elapsed/remaining time within the
   *current* chapter, draggable, seeking on release), mirroring the audiobook player screen's
   per-chapter scrubber.

Since EPUB narration is synthesized on demand (no fixed-duration audio files exist for text that
hasn't been read yet), none of this is measured — it's *estimated* from character count and an
assumed narration pace, the same technique e-readers use for "12 min left in this chapter."

## Decisions

- **Time is estimated from character count, not sentence count.** Sentence length varies too much
  for a flat per-sentence rate to be stable; character count × an assumed reading pace (~150
  words/minute ≈ 15 characters/second) is the standard technique for this kind of estimate and is
  cheap to compute from data already in the `epubSentence` table.
- **Fixed pace constant, not self-calibrating from real synthesized-clip durations.** A single
  reasonable rate used for every book, matching how e-reader reading-time estimates work — simpler,
  predictable, no running-average state to persist or handle the early no-data case for.
  `CHARS_PER_SECOND = 15` (≈150 words/minute at ~6 characters/word including the trailing space, a
  typical audiobook/TTS narration pace) — a single top-level constant, one place to tune later.
- **Library-card progress is chapter-granular, not character-precise.** Total duration is
  character-accurate (`epubTotalCharacterCount / rate`), but *elapsed* is computed as that total ×
  `currentEpubChapterIndex / epubChapterCount` — a chapter-fraction, not a true character offset
  into the book. This keeps `Book.toItemViewState()` a pure function with no new repo dependency,
  which matters because it runs for every visible card on every render. The tradeoff: progress
  advances in chunky per-chapter steps at the library level rather than continuously. The
  reader-screen scrubber (below) is the precise view; the library card is the glanceable one.
- **Reader-screen scrubber is sentence-precise, computed live.** Only the current chapter is on
  screen, so `EpubBookRepo.sentences(bookId, currentChapterIndex)` (already used by
  `EpubPlaylistController.loadWindow()`) is queried directly — no new caching needed for this part.
- **Seeking reuses `EpubPlaylistController.start()`'s existing arbitrary-position capability.**
  `start(bookId, voiceId, bookTitle, chapterIndex, sentenceIndex)` already supports beginning
  playback from any chapter/sentence (this is how resume-from-persisted-position already works) —
  dragging the scrubber and releasing converts the drop position to a target sentence index and
  calls `start()` again with it, rather than inventing a new seek primitive. Seeking stays within
  the current chapter, matching the audiobook player's own scrubber (cross-chapter navigation stays
  on the existing chapter dropdown/prev-next controls).
- **One new cached field, one new query — nothing else changes about EPUB import/parsing.**
  `BookContent.epubTotalCharacterCount: Int` (Room migration v64→v65, default `0`), populated once
  at first parse (same place/pattern as `epubChapterCount` from the unification plan:
  `EpubBookOpener.open()`'s `if (chapters.isEmpty())` branch, plus the same "backfill on next open"
  branch added for legacy books whose `epubChapterCount` was already `0`). Backed by a new
  `EpubSentenceDao.totalCharacterCount(bookId): Int` query
  (`SELECT COALESCE(SUM(LENGTH(text)), 0) FROM epubSentence WHERE bookId = :bookId`) — a single
  aggregate query, not a full sentence-object load.

## Architecture

**Data flow, library card:**

```
BookContent.epubTotalCharacterCount (cached, populated once at parse time)
        │
        ▼
Book.toItemViewState() [pure function, no repo access]
  totalDuration = epubTotalCharacterCount / CHARS_PER_SECOND
  elapsedFraction = currentEpubChapterIndex / epubChapterCount   (0 if epubChapterCount == 0)
  elapsed = totalDuration * elapsedFraction
  remainingTime = formatTime(totalDuration - elapsed)
  progress = elapsedFraction
        │
        ▼
BookRemainingProgressRow / BookProgressIndicator  [existing composables, unchanged]
```

**Data flow, reader-screen scrubber:**

```
EpubReaderViewModel (already has epubBookRepo, currentSentence position from
EpubPlaylistController.currentSentenceFlow())
        │
        ▼
On chapter/sentence change: query epubBookRepo.sentences(bookId, currentChapterIndex)
  chapterTotalChars = sum of text.length for all sentences in this chapter
  chapterElapsedChars = sum of text.length for sentences before currentSentenceIndex
  chapterDuration = chapterTotalChars / CHARS_PER_SECOND
  chapterPosition = chapterElapsedChars / CHARS_PER_SECOND
        │
        ▼
New scrubber composable in EpubReaderView: position/duration text (MM:SS format,
reusing voice.core.ui.formatTime) + draggable Slider
        │
        ▼ (on drag release)
targetFraction = slider value / chapterDuration
Scan cumulative character offsets of this chapter's sentences to find the sentence
index whose offset range contains targetFraction * chapterTotalChars
        │
        ▼
EpubPlaylistController.start(bookId, voiceId, bookTitle, currentChapterIndex, targetSentenceIndex)
```

## Data Model

Room migration v64 → v65, one additive column, matching the existing `epubChapterCount` pattern:

```kotlin
@ColumnInfo(defaultValue = "0")
val epubTotalCharacterCount: Int = 0,
```

`EpubSentenceDao` gains:

```kotlin
@Query("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM epubSentence WHERE bookId = :bookId")
public suspend fun totalCharacterCount(bookId: BookId): Int
```

`EpubBookRepo` gains a matching `totalCharacterCount(bookId: BookId): Int` method, implemented by
delegating to the DAO (same shape as the existing `chapters`/`sentences` methods).

`EpubBookOpener.open()`'s existing chapter/progress-backfill logic (from the unification plan) is
extended to also populate `epubTotalCharacterCount` alongside `epubChapterCount`/
`epubLastChapterSentenceCount`, in both the fresh-import branch and the legacy-backfill branch.

## Testing

- Unit tests for the character-count-to-time estimation math (pure functions): total duration,
  elapsed/remaining split, edge cases (`epubChapterCount == 0`, `epubTotalCharacterCount == 0`).
- Unit test for the new `EpubSentenceDao.totalCharacterCount` query (or `EpubBookRepoImpl`-level,
  matching how `chapters`/`sentences` are already tested).
- Unit tests for the reader-screen chapter-duration/position computation given a fake
  `EpubBookRepo.sentences()` response.
- Unit test for the seek-fraction-to-sentence-index conversion (given a set of sentences with known
  lengths, dragging to various fractions should resolve to the expected sentence index, including
  boundary cases: drag to 0%, drag to 100%, drag to a fraction landing exactly on a sentence
  boundary).
- ViewModel test asserting a drag-release calls `EpubPlaylistController.start()` with the expected
  `chapterIndex`/`sentenceIndex`, not any other playback primitive.

## Out of Scope

- Real (measured, not estimated) time — would require synthesizing the whole book upfront, which
  contradicts the deliberate rolling-window streaming-synthesis design from the original EPUB
  parsing plan.
- Self-calibrating the pace constant from actual synthesized-clip durations.
- Cross-chapter seeking via the scrubber (stays on the existing chapter dropdown/prev-next).
- Any other progress-display precision improvement (e.g. per-chapter character counts cached for
  library-card-level precision) — the chapter-granular library card is an accepted tradeoff, not a
  placeholder for a future refinement in *this* plan.
