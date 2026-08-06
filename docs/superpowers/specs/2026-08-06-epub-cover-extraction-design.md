# EPUB Cover Extraction — Design

## Summary

EPUB covers currently never display. `core/epub` doesn't parse the OPF manifest's cover
reference at all, and the existing audiobook `CoverScanner` is structurally unable to help:
it either treats `book.id` as a folder (EPUBs are a single-file URI, so this fails) or tries
to pull embedded ID3/FLAC picture frames out of the file via ExoPlayer's `MetadataRetriever`
(which can't read anything meaningful out of a zip). Net effect: `CoverScanner` runs against
every EPUB on every library scan and silently fails every time.

This plan adds real cover extraction: `core/epub`'s existing OPF-parsing pass locates and
reads the cover image's raw bytes, and the first-open import flow saves it through the same
`CoverSaver` path audiobook covers already use. The rest of the app — list/grid views,
`Book.cover`, the null-cover placeholder — needs zero changes; they already work generically
across source types.

## Decisions

- **Lazy, at first-open — not at scan time.** EPUBs get a lightweight filename-only stub at
  folder-scan time (`EpubBookParser`); the zip itself isn't opened until the user opens the
  book for the first time (`EpubImporter.import()`, triggered from `EpubBookOpener.open()`).
  Cover extraction piggybacks on that existing first-open zip read rather than opening every
  EPUB's zip at scan time just to grab a cover — matches the app's established "parsing is
  lazy" principle. Consequence: an EPUB shows without a cover in the library list until it's
  opened once.
- **Detect covers via both EPUB conventions, then fall back to the first manifest image.**
  Check EPUB3's `<item properties="cover-image">` first, then EPUB2's
  `<meta name="cover" content="ID">` (resolving `ID` against the manifest), then — if neither
  is present — fall back to the first `image/*`-typed item in the manifest. Real-world EPUBs
  (especially converted/ripped ones) frequently have a cover image without proper metadata
  declaring it; the fallback catches most of those. If nothing matches any of the three,
  `coverBytes` is `null` and the book falls through to the existing null-cover UI placeholder
  — the same one audiobooks with no embedded art already use.
- **Single parse pass, not two.** `DefaultEpubParser` already opens the zip, parses the OPF
  manifest, and resolves entry hrefs to build the chapter list — the same work needed to find
  and read a cover entry. Cover detection and extraction happen in that same pass and return
  as part of the existing `ParsedBook` result (`coverBytes: ByteArray?`), rather than parsing
  just an href and having the caller re-open the zip separately. `core/epub` stays what it's
  always been — a pure, Android-free parser returning plain data (`ByteArray`, not `Bitmap`).
- **Backfill already-imported EPUBs.** An EPUB imported before this feature existed has
  chapters/sentences already persisted, so `EpubBookOpener.open()`'s existing
  `chapters.isEmpty()` trigger for `EpubImporter.import()` won't fire for it again. A second
  condition — chapters present but `content.cover == null` — re-runs the SAF-copy-and-parse
  step purely to pick up a cover, once, the next time that book is opened. This costs one
  extra file copy + zip parse per already-imported book, matching the backfill pattern already
  used for reading-progress fields (`EpubBookOpener`'s `withBackfilledProgressFields`).
- **Reuse `CoverSaver` and its existing storage format.** Extracted cover bytes are decoded via
  `BitmapFactory` and saved through `CoverSaver.save(bookId, Bitmap)`, the same method the
  audiobook cover path already calls — same `bookCovers/<uuid>.png` file location, same PNG
  storage format, so nothing downstream needs to know or care that the cover came from an EPUB
  rather than an audio file's embedded tag.
- **A bad or oversized cover never blocks import.** Undecodable image bytes, or a cover entry
  larger than the cap (5 MB — generous for a cover image, well under the existing 20 MB
  per-entry cap used for chapter text), are treated exactly like "no cover found": `coverBytes`
  is `null`, the book still imports normally, nothing is logged as an import failure.

## Architecture

```
DefaultEpubParser.parseZip()
  parseSpineHrefs(opfXml)          [existing — chapters/spine, unchanged]
  findCoverHref(opfXml, manifest)  [new — checks properties="cover-image", then
                                     <meta name="cover"> + id lookup, then first
                                     image/* item in the manifest, in that order]
  readEntryBytesOrNull(coverHref)  [new — binary sibling to the existing text-only
                                     readEntryOrNull; same href resolution against
                                     opfDir as chapter hrefs already use; own 5MB cap]
        │
        ▼
ParsedBook(chapters: List<ParsedChapter>, coverBytes: ByteArray?)
        │
        ▼
EpubImporter.persist()  [first-open import path — already the trigger point]
  on EpubParseResult.Success with coverBytes != null:
    BitmapFactory.decodeByteArray(coverBytes) — null result treated as no-cover
    coverSaver.save(bookId, bitmap)   [existing method, existing bookCovers/<uuid>.png path]
        │
        ▼
BookContent.cover  [existing field — ListBooks/GridBooks already render it and
                     already have a null-cover placeholder; no UI changes needed]
```

**Backfill path:** `EpubBookOpener.open(bookId)` currently does:

```kotlin
if (chapters.isEmpty()) {
  // ...import, backfill progress fields...
} else if (content.epubChapterCount == 0 || content.epubTotalCharacterCount == 0) {
  // ...backfill progress fields only...
}
```

This gains a cover-backfill branch alongside the existing progress-field backfill: when
chapters are already present but `content.cover == null`, re-run `epubImporter.import(...)`
(which re-copies the SAF file and re-parses — cheap relative to a one-time cost, and the
simplest way to reuse the exact same cover-extraction code path rather than duplicating it)
and save the resulting cover if one is found.

## Error handling

| Condition | Result |
|---|---|
| No cover metadata and no images in manifest | `coverBytes = null` → existing null-cover placeholder |
| Cover entry bytes present but `BitmapFactory` can't decode them | Treated as no-cover; not a parse failure |
| Cover entry larger than 5 MB | Skipped, treated as no-cover; not a parse failure |
| Cover extraction fails for any other reason during import | Book import still succeeds — a broken cover must never block a book from being usable |

## Testing

- `core/epub`: extend `EpubTestFixtures.buildTestEpub` to optionally include a cover image
  entry plus its manifest declaration, in either convention. New `DefaultEpubParserTest`
  cases: EPUB3 `properties="cover-image"`, EPUB2 `<meta name="cover">`, fallback-to-first-image
  when neither convention is declared, no-cover-anywhere (`coverBytes == null`), and an
  oversized cover entry (capped, not a parse failure).
- `core/scanner`: `EpubImporterTest` case asserting a parsed cover is saved via `CoverSaver`
  and ends up on `BookContent.cover`; a case asserting a `null` `coverBytes` leaves
  `BookContent.cover` untouched (no crash, no placeholder file).
- `features/epubReader`: `EpubBookOpenerTest` case for the backfill path — chapters already
  present, `content.cover == null` — confirming `import()` is re-triggered and the cover ends
  up saved.

## Out of scope

- Re-syncing a cover if the user replaces the EPUB file in place with a different edition —
  same pre-existing staleness gap already noted for chapters/sentences (Plan 2's deferred
  items), not new to or made worse by this feature.
- Any manual cover editing/replacement UI — this only wires up automatic extraction, matching
  how audiobook covers are auto-extracted today with no manual override flow.
