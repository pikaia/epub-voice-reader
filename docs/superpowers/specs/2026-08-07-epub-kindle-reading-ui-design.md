# Kindle-Style EPUB Reading UI — Design

## Summary

The EPUB reader currently renders one `Text` composable per sentence in a `LazyColumn`, and
snaps (`animateScrollToItem`) the highlighted sentence to the very top of the screen on every
advance — on a short chapter this looks fine, on a longer one it looks broken: the highlight
appears stuck at the top while text scrolls up underneath it. There's no paragraph structure
(the parser flattens each chapter's whole body to one blob before sentence-splitting), no way
to tap a sentence to seek there, and the screen's chrome (top bar, chapter picker, scrubber
position, playback controls) doesn't match the audiobook player screen at all — no cover, a
dropdown chapter picker in a plain top bar, scrubber pinned under the top bar instead of near
the bottom, and none of the audiobook screen's sleep timer / bookmark / speed / skip-silence /
volume-boost controls.

This redesign does two things together, since both touch the same screen and the same
top-bar/scrubber/controls chrome:

1. Replace the one-sentence-per-row list with continuous, paragraph-flowing text, a soft
   theme-aware highlight (not a hard block-background), gentle keep-in-view scrolling instead
   of snap-to-top, and tap-a-sentence-to-seek.
2. Align the reader's chrome with the audiobook player screen: icon bar (sleep timer,
   bookmark, speed, overflow menu with skip-silence/volume-boost), scrolling title, a
   cover-forward header that scrolls away into the text, a chapter row (matching
   `ChapterRow`'s tap-to-open-dialog + prev/next-chapter chevrons), and a bottom-pinned
   scrubber + playback row (matching `SliderRow`/`PlaybackRow`, with prev/next-*sentence*
   taking the place of audiobook's rewind/fast-forward).

## Decisions

- **Paragraph boundaries are captured at parse time, not guessed later.** Real paragraph
  fidelity (matching the source EPUB's actual structure) was chosen over a single unbroken
  flow or heuristic guessing — most EPUBs have real, meaningful paragraph structure worth
  preserving, and heuristic-guessing from flattened text would be unreliable. This means
  `DefaultEpubParser` changes (see Architecture) and a Room migration.
- **Paragraph = one "leaf" block-level element**, walked in document order: `p`, `div`, `li`,
  `blockquote`, `h1`–`h6` that contain no nested block-level children. This handles the
  common cases (plain `<p>`-per-paragraph, list items, headers) without double-counting text
  from a wrapper `<div>` around several `<p>`s. A chapter with no block elements at all (rare)
  falls back to treating the whole body as one paragraph — today's behavior, unchanged, so
  nothing regresses.
- **Scroll behavior: keep-in-view, not centered or absent.** Scrolls only when the active
  sentence would go off-screen (past roughly the bottom third), and animates smoothly rather
  than snapping — closest to how a real e-reader's read-along tracking feels. Most sentence
  advances cause no scroll at all; the text just fills in beneath the highlight until it
  nears the bottom.
- **Highlight: soft yellow background, theme-aware.** A muted warm-yellow background in light
  mode, a dimmer amber/gold tint in dark mode. Applied as a `SpanStyle` background over the
  active sentence's character range within its paragraph's `AnnotatedString` — not a full-row
  background like today.
- **Tap-to-seek.** Tapping a sentence's text jumps narration there and resumes playing —
  resolved via `TextLayoutResult.getOffsetForPosition` (tap point → character offset →
  sentence via stored per-sentence ranges within the paragraph's `AnnotatedString`).
- **Typography is out of scope.** Font/size/line-spacing customization is a natural follow-up
  once the core reading experience is right; this redesign keeps `MaterialTheme` typography
  as-is.
- **Cover-forward layout, matching the audiobook screen almost exactly.** Icon bar → scrolling
  title → large cover (same proportions as the audiobook screen) → chapter row, all as the
  first item(s) of the same scrollable list the flowing text lives in — so scrolling into the
  book naturally scrolls the cover away, while the bottom scrubber/controls bar stays pinned
  regardless of scroll position. Opening a book shows the cover first, matching the audiobook
  screen's first impression; scrolling down reveals text.
- **Reuse existing controls, don't reimplement them.** Sleep timer, playback speed, skip
  silence, and volume boost all bottom out in `PlayerController`/`SleepTimer` methods that
  operate on the underlying `MediaController`/`BookContent` fields with no audiobook-specific
  assumptions (confirmed by reading `BookPlayViewModel`, `PlayerController`, `SleepTimerImpl`,
  and `CurrentBookResolver.book()` — the last of these is just `BookRepository.get(bookId)`,
  source-type-agnostic). These get wired into `EpubReaderViewModel` as new methods that call
  the same collaborators, not reimplemented. The three dialog composables
  (`SpeedDialog`, `VolumeGainDialog`, a sleep-timer dialog) move from `features:playbackScreen`
  into `core:ui` so both screens render the same UI without a cross-feature dependency
  (`features:epubReader` already depends on `core:ui`, not on `features:playbackScreen`).
- **Bookmarks are the one real gap, and stay out of scope here.** The `Bookmark` entity is
  shaped as `chapterId: ChapterId, time: Long` (a millisecond offset into an audiobook
  chapter) — there's no way to represent an EPUB position (chapter index + sentence index) in
  it today. The bookmark icon renders in the icon bar for visual/layout parity but is
  disabled/no-op for EPUB books; the sleep timer's "drop a bookmark when it fires" side effect
  is skipped for EPUB sessions. Real EPUB bookmark support is a separate future plan.
- **Chapter picker becomes a dialog, not a dropdown.** Tapping the chapter row opens a dialog
  matching `SelectChapterDialog`'s visual pattern (simpler here, since EPUB chapters have no
  marks — just a flat list), replacing today's top-bar `DropdownMenu`.
- **Prev/next-sentence buttons take the audiobook screen's rewind/fast-forward slot.** EPUB
  has no continuous-audio position to skip through by seconds, but sentence-level skip is the
  natural equivalent and pairs with the scrubber and tap-to-seek already in this design. They
  clamp at the current chapter's first/last sentence rather than crossing chapter boundaries —
  consistent with how seeking already works today, and chapter navigation already has its own
  prev/next-chapter chevrons in the chapter row.
- **The paragraph-data backfill reuses the existing cover-backfill reimport path, merged into
  one condition from the start.** `EpubBookOpener.open()` already re-imports an
  already-parsed book when `content.cover == null` (Plan 7's backfill). Rather than adding a
  second, separate reimport trigger, the paragraph-data check (`!content.hasParagraphData`,
  a new cached `BookContent` field — not a live query, matching how `epubChapterCount` already
  avoids scanning) is OR'd into that same condition, so a legacy book gets both backfills in
  one reimport, not two. This preempts the exact class of bug Plan 7's final review had to
  catch after the fact (progress fields computed before an unrelated reimport went stale) by
  not splitting the condition in the first place. `currentEpubChapterIndex`/
  `currentEpubSentenceIndex` are unaffected by any reimport — confirmed on-device during Plan
  7's verification, since `replaceChapters` only touches the chapter/sentence tables, never
  `BookContent`'s resume-position fields.

## Architecture

### Parser and data model

```
DefaultEpubParser.parseChapter(html)
  document = Jsoup.parse(html)
  leafBlocks = walk(document.body())   [new — depth-first, collects elements matching
                                         {p, div, li, blockquote, h1..h6} that have no
                                         block-level descendant, in document order;
                                         falls back to [document.body()] itself if none found]
  paragraphs = leafBlocks.mapIndexed { paragraphIndex, block ->
    splitSentences(block.text()).map { ParsedSentence(it, paragraphIndex) }
  }
  ParsedChapter(title, sentences = paragraphs.flatten())
```

`ParsedChapter.sentences` changes from `List<String>` to `List<ParsedSentence>`
(`text: String, paragraphIndex: Int`). `EpubSentence` (Room entity) gains
`paragraphIndex: Int` (migration v65→v66, additive column, `DEFAULT -1` for existing rows —
an unambiguous "not yet computed" sentinel, since real paragraph indices are always ≥0).
`EpubImporter.persist()` threads `paragraphIndex` through unchanged otherwise. `BookContent`
gains `hasParagraphData: Boolean` (migration v65→v66 also — one migration, two additive
columns across two tables — `DEFAULT false`), set to `true` alongside the other backfilled
fields in `EpubBookOpener.withBackfilledProgressFields`.

`EpubBookOpener.open()`'s existing branch:

```kotlin
if (content.cover == null) { /* reimport */ }
```

becomes:

```kotlin
if (content.cover == null || !content.hasParagraphData) { /* reimport, once */ }
```

### Reader view state and interaction

`EpubReaderViewState.Content.sentences: List<String>` becomes
`paragraphs: List<List<String>>` (each inner list is one paragraph's sentences, in order) —
computed in `EpubReaderViewModel` by run-length grouping the flat, already paragraph-ordered
sentence list from `EpubBookRepo.sentences()` on `paragraphIndex` (cheap linear pass, done
once per chapter load, not per recomposition). `activeSentenceIndex` stays a flat index into
the chapter as it does today; the view derives per-paragraph start offsets once per state
emission so each paragraph's `LazyColumn` item can independently determine whether it
contains the active sentence — this keeps recomposition scoped to the one or two paragraphs
whose highlight actually changed, not the whole visible list.

Each paragraph renders as one `Text` using an `AnnotatedString` built from its sentences, with
a `SpanStyle(background = highlightColor)` applied over the active sentence's character range
when applicable. Tap handling: `Modifier.pointerInput { detectTapGestures { offset -> ... } }`
+ `TextLayoutResult.getOffsetForPosition(offset)` resolves the tap to a character index, then
a binary search over the paragraph's stored per-sentence character ranges resolves the
sentence — reused by tap-to-seek.

Scroll: track which paragraph item contains the active sentence; on change, check
`LazyListState.layoutInfo.visibleItemsInfo` for that item's bounds. If it's already within a
comfortable viewport zone (top ~10% to bottom ~70%), don't scroll. Otherwise animate-scroll it
into that zone rather than snapping it to the very top.

Three new `EpubReaderViewModel` entry points — `skipToPreviousSentence()`,
`skipToNextSentence()`, `onSentenceTapped(chapterRelativeIndex: Int)` — all funnel into the
same underlying jump used by `seekTo()` today:

```kotlin
private fun jumpToSentence(index: Int) {
  // clamped to [0, currentChapterSentences.lastIndex]
  epubPlaylistController.start(bookId, voiceId, bookTitle, activeChapterIndex, index)
}
```

### Chrome

```
Scaffold
  topBar: icon bar (close, sleep timer, bookmark [disabled for EPUB], speed, overflow
          [skip silence, volume boost]) — mirrors BookPlayAppBar, EPUB-appropriate subset
  content: single LazyColumn
    item: scrolling title (matches audiobook's horizontally-scrolling title)
    item: cover (same proportions as CoverRow)
    item: chapter row (tap → chapter dialog; prev/next-chapter chevrons)
    items: one per paragraph (flowing text, highlighting, tap-to-seek — see above)
  bottomBar (pinned, outside the LazyColumn): scrubber row (existing ChapterScrubberRow,
    unchanged) + playback row (prev-sentence / play-pause / next-sentence, mirrors
    PlaybackRow's layout)
```

`EpubReaderViewModel` gains the sleep-timer/speed/volume-gain/skip-silence methods described
under Decisions, mirroring `BookPlayViewModel`'s existing ones and calling the same
collaborators (`SleepTimer`, `PlayerController`, `VolumeGainFormatter`). `SpeedDialog`,
`VolumeGainDialog`, and a new shared sleep-timer dialog composable move from
`features:playbackScreen` to `core:ui`; `features:playbackScreen` is updated to reference
them from the new location so nothing is duplicated.

## Error handling

| Condition | Result |
|---|---|
| Chapter HTML has no block-level elements at all | Whole body treated as one paragraph — today's behavior, unchanged |
| Legacy sentence rows with `paragraphIndex = -1` | `EpubBookOpener.open()` detects `!hasParagraphData` and triggers the merged reimport once; resume position (`currentEpubChapterIndex`/`currentEpubSentenceIndex`) is untouched, per the same guarantee already verified on-device for the cover backfill |
| Tap lands in inter-sentence whitespace or resolves out of range | Clamped to the nearest valid sentence in the tapped paragraph |
| Skip-to-previous/next-sentence at the first/last sentence of a chapter | Clamped — no-op at the boundary; chapter-level navigation stays available via the chapter row's chevrons |
| Bookmark action on an EPUB book | No-op; icon renders visibly disabled |
| Sleep timer fires during EPUB playback | Pauses playback exactly as it does for audiobooks (fully player-level); the auto-bookmark side effect is skipped for EPUB sessions |

## Testing

- `core/epub`: new `DefaultEpubParserTest` cases — paragraph boundaries from plain `<p>` tags,
  from a wrapper `<div>` containing multiple `<p>`s (must not double-count the wrapper's own
  text), from list items and headers, and the no-block-elements fallback (single paragraph,
  matching today's behavior).
- Room migration (v65→v66): verified via generated schema JSON diff, per this project's
  established practice around the Windows `MigrationTestHelper` gap — not a real
  migration-test run.
- `EpubBookOpenerTest`: extend for the merged backfill condition — cover missing only,
  paragraph data missing only, both missing (still exactly one reimport), and neither missing
  (no reimport).
- `EpubReaderViewModelTest`: paragraph grouping from a flat sentence list; tap-to-seek
  character-offset-to-sentence resolution; skip-to-previous/next-sentence, including
  clamping at chapter boundaries; sleep timer / speed / volume gain / skip silence method
  wiring (state passthrough, using fakes for `SleepTimer`/`PlayerController` matching this
  project's existing test patterns).
- No Compose UI test infrastructure exists anywhere in this project today (confirmed — no
  `createComposeRule` usage in the codebase). Consistent with every other plan here, visual
  correctness (highlight color in both themes, scroll feel, cover-forward layout) is confirmed
  by on-device manual verification, not new UI test scaffolding.

## Out of scope

- Real EPUB bookmark support (extending the `Bookmark` model to represent a chapter+sentence
  position) — the bookmark icon is visually present but disabled; this is a separate future
  plan.
- Typography customization (serif/sans toggle, font size, line spacing) — a natural follow-up
  once the core reading experience (this plan) is in place.
- Cross-chapter skip-by-sentence (rolling into the next/previous chapter when skipping past a
  chapter boundary) — clamped instead; chapter navigation already covers this via the chapter
  row's chevrons.
- Re-deriving paragraph structure for EPUBs that already have `hasParagraphData = true` but
  whose underlying file changes in place — same pre-existing staleness gap already noted for
  chapters/sentences/covers in earlier plans, not new to or made worse by this one.
