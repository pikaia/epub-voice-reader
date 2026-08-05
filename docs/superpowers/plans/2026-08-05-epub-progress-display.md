# EPUB Progress Display Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give EPUB books the same progress display as audiobooks — library-card total time/percentage, and an interactive chapter-scoped scrubber on the reader screen — using character-count-based time estimates since EPUB narration is synthesized on demand.

**Architecture:** One new cached `Int` field (`BookContent.epubTotalCharacterCount`, Room v64→v65) backs a library-card estimate computed in the existing pure `Book.toItemViewState()`. The reader screen's chapter-scoped scrubber is computed live from sentence texts already loaded into `EpubReaderViewModel` — no new query needed there. Both share one small time-estimation utility. Seeking reuses `EpubPlaylistController.start()`'s existing arbitrary-position capability; no new playback primitive.

**Tech Stack:** Kotlin, Room (migration + DAO query), Jetpack Compose (Material3 `Slider`, mirroring the existing audiobook player's `SliderRow`), Molecule/Turbine for ViewModel state tests.

## Global Constraints

- `CHARS_PER_SECOND = 15` (≈150 words/minute, ~6 characters/word including trailing space) is the one assumed narration pace, defined once and reused everywhere a character count needs to become a duration or vice versa.
- The library card's elapsed/remaining is chapter-granular (`currentEpubChapterIndex / epubChapterCount` fraction × total estimated duration), not character-precise — this is a deliberate, accepted tradeoff (see design spec's "Out of Scope"), not something to "fix" mid-plan.
- The reader-screen scrubber only seeks within the current chapter. Cross-chapter navigation stays on the existing chapter dropdown.
- No new playback primitive: seeking calls `EpubPlaylistController.start(bookId, voiceId, bookTitle, chapterIndex, sentenceIndex)`, exactly like resuming a persisted position already does.
- Known Windows-environment test gaps (pre-existing, unrelated to this plan, do not attempt to fix): `DataBaseMigratorTest`'s `MigrationTestHelper`-based tests all fail locally with `IllegalArgumentException`; verify migrations via the generated schema JSON instead. `NaturalOrderComparatorTest.uriComparatorFiles` and `ConvertersTest.file` also fail locally, unrelated to any code here.
- Ksp-generated Room schema JSON sometimes doesn't regenerate despite Gradle reporting success — after adding the migration, run the `kspDebugKotlin` task (or `assembleFreeDebug`) with `--rerun-tasks` if `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/65.json` doesn't appear.

---

### Task 1: Cache `epubTotalCharacterCount` and add the shared time-estimation utility

**Files:**
- Modify: `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/EpubProgressEstimate.kt`
- Test: `core/data/api/src/test/kotlin/voice/core/data/EpubProgressEstimateTest.kt`
- Modify: `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt`
- Modify: `core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt`
- Test: `core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Test: `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt`

**Interfaces:**
- Produces: `BookContent.epubTotalCharacterCount: Int` (default `0`). `voice.core.data.estimatedEpubDurationMs(characterCount: Int): Long`. `voice.core.data.estimatedEpubCharacterCount(durationMs: Long): Int`. `EpubBookRepo.totalCharacterCount(bookId: BookId): Int`.

- [ ] **Step 1: Write the failing test for the time-estimation utility**

Create `core/data/api/src/test/kotlin/voice/core/data/EpubProgressEstimateTest.kt`:

```kotlin
package voice.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class EpubProgressEstimateTest {

  @Test
  fun `zero characters is zero duration`() {
    assertEquals(expected = 0L, actual = estimatedEpubDurationMs(0))
  }

  @Test
  fun `duration scales with character count at 15 chars per second`() {
    assertEquals(expected = 1_000L, actual = estimatedEpubDurationMs(15))
    assertEquals(expected = 10_000L, actual = estimatedEpubDurationMs(150))
  }

  @Test
  fun `character count is the inverse of duration`() {
    assertEquals(expected = 150, actual = estimatedEpubCharacterCount(10_000L))
    assertEquals(expected = 0, actual = estimatedEpubCharacterCount(0L))
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:data:api:testDebugUnitTest --tests "*EpubProgressEstimateTest*"`
Expected: FAIL — `estimatedEpubDurationMs`/`estimatedEpubCharacterCount` are unresolved references (the file doesn't exist yet).

- [ ] **Step 3: Create the time-estimation utility**

Create `core/data/api/src/main/kotlin/voice/core/data/EpubProgressEstimate.kt`:

```kotlin
package voice.core.data

private const val EPUB_CHARS_PER_SECOND = 15

/**
 * Estimated narration duration for the given character count, based on a fixed assumed narration
 * pace (~150 words/minute, ~6 characters/word). EPUB narration is synthesized on demand, so
 * there's no measured duration to use instead — this is the same technique e-readers use for
 * "12 min left in this chapter."
 */
public fun estimatedEpubDurationMs(characterCount: Int): Long =
  characterCount.toLong() * 1000L / EPUB_CHARS_PER_SECOND

/**
 * Inverse of [estimatedEpubDurationMs] — how many characters correspond to a given duration at
 * the same assumed pace. Used to resolve a scrubber seek target back to a sentence position.
 */
public fun estimatedEpubCharacterCount(durationMs: Long): Int =
  (durationMs * EPUB_CHARS_PER_SECOND / 1000L).toInt()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:data:api:testDebugUnitTest --tests "*EpubProgressEstimateTest*"`
Expected: PASS

- [ ] **Step 5: Add the cached field to `BookContent`**

In `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`, add the new column after
`epubLastChapterSentenceCount` (the last existing epub field) and extend the existing `init`
validation:

```kotlin
  @ColumnInfo(defaultValue = "0")
  val epubLastChapterSentenceCount: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubTotalCharacterCount: Int = 0,
) {
```

```kotlin
    require(epubChapterCount >= 0 && epubLastChapterSentenceCount >= 0 && epubTotalCharacterCount >= 0) {
      "invalid epub progress cache in $this"
    }
```

(This replaces the existing `require(epubChapterCount >= 0 && epubLastChapterSentenceCount >= 0)`
line — same check, one more field added to it.)

- [ ] **Step 6: Add the migration**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, bump the version and
add the auto-migration:

```kotlin
    AutoMigration(from = 63, to = 64),
    AutoMigration(from = 64, to = 65),
  ],
)
@TypeConverters(Converters::class)
public abstract class AppDb : RoomDatabase() {
```

```kotlin
  internal companion object {
    const val VERSION = 65
    const val DATABASE_NAME = "autoBookDB"
  }
```

- [ ] **Step 7: Regenerate the schema JSON**

Run: `./gradlew :core:data:impl:kspDebugKotlin --rerun-tasks`
Expected: a new `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/65.json` file appears,
containing `epubTotalCharacterCount` as an `INTEGER NOT NULL` column with default `0` on
`content2`. If it doesn't appear, the schema task is stale — re-run with `--rerun-tasks` again, or
run `./gradlew :app:assembleFreeDebug --rerun-tasks` instead (this has been the reliable fix in
this project before).

- [ ] **Step 8: Write the failing migration test**

In `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt`,
add (after the existing `migrate64` test):

```kotlin
  @Test
  fun migrate65() {
    val dbName = "testDb"
    val db = helper.createDatabase(dbName, 64)
    db.execSQL(
      "INSERT INTO `content2`(`id`,`playbackSpeed`,`skipSilence`,`isActive`,`lastPlayedAt`,`author`,`name`," +
        "`addedAt`,`chapters`,`currentChapter`,`positionInChapter`,`cover`,`gain`,`genre`,`narrator`,`series`," +
        "`part`,`sourceType`,`voiceId`,`currentEpubChapterIndex`,`currentEpubSentenceIndex`,`epubChapterCount`," +
        "`epubLastChapterSentenceCount`) " +
        "VALUES ('book1', 1.0, 0, 1, '1970-01-01T00:00:00Z', NULL, 'A Book', '1970-01-01T00:00:00Z', '[]', " +
        "'chapter1', 0, NULL, 0, NULL, NULL, NULL, NULL, 'Epub', NULL, 0, 0, 0, 0)",
    )
    db.close()

    val migratedDb = helper.runMigrationsAndValidate(
      dbName,
      65,
      true,
      *allMigrations(),
    )

    val cursor = migratedDb.query("SELECT * FROM content2 WHERE id = 'book1'")
    cursor.moveToFirst()
    assertEquals(expected = 0, actual = cursor.getInt("epubTotalCharacterCount"))
    cursor.close()
  }
```

- [ ] **Step 9: Run the migration test, and note the expected local failure**

Run: `./gradlew :core:data:impl:testDebugUnitTest --tests "*DataBaseMigratorTest*"`
Expected: `migrate65` FAILS locally with `IllegalArgumentException` from `MigrationTestHelper` —
this is the documented, pre-existing Windows environment gap that affects *every* test in this
class (confirmed by `migrate43`/`migrate44`/`migrate60`/`migrate63`/`migrate64`/
`emptyTableLeadsToCorrectSchema` all failing identically, unrelated to this change). Verify
correctness instead by inspecting `65.json` from Step 7: confirm `epubTotalCharacterCount` is
present on `content2` as `INTEGER NOT NULL` with `"defaultValue": "0"`, and that the schema's
identity hash changed from `64.json`'s.

- [ ] **Step 10: Add the DAO query**

In `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt`, add
after the existing `sentences` query:

```kotlin
  @Query("SELECT COALESCE(SUM(LENGTH(text)), 0) FROM epubSentence WHERE bookId = :bookId")
  public suspend fun totalCharacterCount(bookId: BookId): Int
```

- [ ] **Step 11: Add the repo method**

In `core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt`, add to the interface
(after `sentences`):

```kotlin
  public suspend fun totalCharacterCount(bookId: BookId): Int
```

In `core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt`, implement it
(after `sentences`):

```kotlin
  override suspend fun totalCharacterCount(bookId: BookId): Int = sentenceDao.totalCharacterCount(bookId)
```

- [ ] **Step 12: Write the failing repo test**

In `core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt`, add (after
`replaceChaptersStoresChaptersAndSentences`):

```kotlin
  @Test
  fun `totalCharacterCount sums every sentence across every chapter`() = runTest {
    val bookId = BookId("content://book1")
    repo.replaceChapters(
      bookId,
      chapters = listOf(
        EpubChapter(bookId = bookId, index = 0, title = "Chapter One"),
        EpubChapter(bookId = bookId, index = 1, title = "Chapter Two"),
      ),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "12345"),
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "1234567890"),
        EpubSentence(bookId = bookId, chapterIndex = 1, index = 0, text = "123"),
      ),
    )

    assertEquals(expected = 18, actual = repo.totalCharacterCount(bookId))
  }

  @Test
  fun `totalCharacterCount is zero for a book with no sentences`() = runTest {
    assertEquals(expected = 0, actual = repo.totalCharacterCount(BookId("content://unknown")))
  }
```

- [ ] **Step 13: Run tests to verify everything passes**

Run: `./gradlew :core:data:api:testDebugUnitTest :core:data:impl:testDebugUnitTest --tests "*EpubProgressEstimateTest*" --tests "*EpubBookRepoImplTest*"`
Expected: PASS (the `DataBaseMigratorTest` class is expected to still show its pre-existing
failures — that's fine, verified via schema JSON in Step 9).

- [ ] **Step 14: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/BookContent.kt \
  core/data/api/src/main/kotlin/voice/core/data/EpubProgressEstimate.kt \
  core/data/api/src/test/kotlin/voice/core/data/EpubProgressEstimateTest.kt \
  core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt \
  core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt \
  core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt \
  core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt \
  core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt \
  core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt \
  core/data/impl/schemas/voice.core.data.repo.internals.AppDb/65.json
git commit -m "Cache epubTotalCharacterCount and add the shared narration-pace estimator"
```

---

### Task 2: Populate `epubTotalCharacterCount` when a book is parsed

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`
- Test: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`

**Interfaces:**
- Consumes: `EpubBookRepo.totalCharacterCount(bookId: BookId): Int` (Task 1).
- Produces: `EpubBookOpener.open()` now also populates `BookContent.epubTotalCharacterCount`,
  everywhere it already populates `epubChapterCount`/`epubLastChapterSentenceCount`.

- [ ] **Step 1: Write the failing test — fresh parse populates the new field**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`, extend
the existing `parses on first open and auto-assigns the first catalog voice` test's assertions
(the minimal EPUB built by `buildMinimalEpub` has one chapter, "Hello there. This is chapter
one." split into 2 sentences by the real sentence splitter — the existing test already asserts
`epubLastChapterSentenceCount == 2`, and the two sentence texts together are 36 characters:
`"Hello there."` is 12 characters, `" This is chapter one."` is 22 characters typically split as
`"This is chapter one."` = 21 characters. Rather than hand-count, assert against whatever
`epubBookRepo.sentences(...)` actually reports, which the test already has access to):

```kotlin
    assertEquals(expected = 1, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 2, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    val expectedCharacterCount = epubBookRepo.sentences(bookId, 0).sumOf { it.text.length }
    assertEquals(expected = expectedCharacterCount, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
```

Also extend `skips parsing when chapters already exist` (this test seeds one chapter with
`sentences = emptyList()`, so its expected character count is `0`):

```kotlin
    assertEquals(expected = 1, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 0, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    assertEquals(expected = 0, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
```

And `does not touch progress fields when they are already populated` (seed
`epubTotalCharacterCount = 42` alongside the existing `epubChapterCount = 5`/
`epubLastChapterSentenceCount = 9`, and assert it's untouched):

```kotlin
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 5,
        epubLastChapterSentenceCount = 9,
        epubTotalCharacterCount = 42,
      ),
    )
```

```kotlin
    assertEquals(expected = 5, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 9, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    assertEquals(expected = 42, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
```

The test's `FakeEpubBookRepo` implements `EpubBookRepo` and must now also implement
`totalCharacterCount` — add to that class:

```kotlin
    override suspend fun totalCharacterCount(bookId: BookId): Int =
      sentences[bookId].orEmpty().sumOf { it.text.length }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubBookOpenerTest*"`
Expected: FAIL — `epubTotalCharacterCount` assertions fail (still `0` from the default, except the
"already populated" test which fails to compile until `FakeEpubBookRepo.totalCharacterCount` is
added; add that first if the module doesn't compile, then re-run to see the assertion failures).

- [ ] **Step 3: Populate the field in `EpubBookOpener`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`, extend
`withBackfilledProgressFields` (the single method both the fresh-import and legacy-backfill
branches already call):

```kotlin
  private suspend fun BookContent.withBackfilledProgressFields(
    bookId: BookId,
    chapters: List<EpubChapter>,
  ): BookContent {
    val lastChapterSentenceCount = epubBookRepo.sentences(bookId, chapters.size - 1).size
    return copy(
      epubChapterCount = chapters.size,
      epubLastChapterSentenceCount = lastChapterSentenceCount,
      epubTotalCharacterCount = epubBookRepo.totalCharacterCount(bookId),
    )
  }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubBookOpenerTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt \
  features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt
git commit -m "Populate epubTotalCharacterCount when an EPUB is parsed"
```

---

### Task 3: Library-card progress display for EPUBs

**Files:**
- Modify: `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewState.kt`
- Test: `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewStateTest.kt` (new file)

**Interfaces:**
- Consumes: `BookContent.epubTotalCharacterCount`/`epubChapterCount`/`currentEpubChapterIndex`
  (Task 1), `voice.core.data.estimatedEpubDurationMs(characterCount: Int): Long` (Task 1).
- Produces: no signature change to `Book.toItemViewState(): BookOverviewItemViewState` — same
  function, now source-type-aware internally. `BookRemainingProgressRow`/`BookProgressIndicator`
  (existing composables) require no changes.

- [ ] **Step 1: Write the failing tests**

Create `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewStateTest.kt`:

```kotlin
package voice.features.bookOverview.overview

import voice.core.data.BookSourceType
import voice.features.bookOverview.book
import kotlin.test.Test
import kotlin.test.assertEquals

class BookOverviewItemViewStateTest {

  @Test
  fun `audiobook progress and remaining time are unchanged`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          currentChapter = book.chapters.first().id,
          positionInChapter = book.chapters.first().duration / 2,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0.25F, actual = state.progress)
    assertEquals(expected = "0:15", actual = state.remainingTime)
  }

  @Test
  fun `unparsed epub shows zero progress and zero remaining time`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 0,
          epubTotalCharacterCount = 0,
          currentEpubChapterIndex = 0,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0F, actual = state.progress)
    assertEquals(expected = "0:00", actual = state.remainingTime)
  }

  @Test
  fun `unstarted but parsed epub shows its full estimated duration`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 4,
          epubTotalCharacterCount = 15 * 60, // 60 seconds at 15 chars/sec
          currentEpubChapterIndex = 0,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0F, actual = state.progress)
    assertEquals(expected = "1:00", actual = state.remainingTime)
  }

  @Test
  fun `epub progress advances by chapter fraction, not sentence position`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 4,
          epubTotalCharacterCount = 15 * 60,
          currentEpubChapterIndex = 1, // 1 of 4 chapters in -> 25%
          currentEpubSentenceIndex = 999, // irrelevant to the library-card estimate
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0.25F, actual = state.progress)
    assertEquals(expected = "0:45", actual = state.remainingTime)
  }
}
```

(`book()`'s default fixture — from `features/bookOverview/src/test/kotlin/voice/features/bookOverview/BookFactory.kt`
— has 2 chapters of `duration = 10000` ms each, so `duration = 20000`ms total; the first test sets
`positionInChapter = 5000` (half of the first chapter's `10000`ms), giving `position = 5000`,
`progress = 5000/20000 = 0.25`, `remainingTime = formatTime(15000) = "0:15"` — matching the
pre-existing, unchanged audiobook behavior.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :features:bookOverview:testDebugUnitTest --tests "*BookOverviewItemViewStateTest*"`
Expected: FAIL — the 3 EPUB tests fail (currently always `0F`/`"0:00"` regardless of the seeded
epub fields, since `toItemViewState()` doesn't branch on `sourceType` yet). The audiobook test
should already pass unchanged.

- [ ] **Step 3: Make `toItemViewState()` source-type-aware**

Replace the whole content of
`features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewState.kt`:

```kotlin
package voice.features.bookOverview.overview

import androidx.compose.runtime.Immutable
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.estimatedEpubDurationMs
import voice.core.logging.api.Logger
import voice.core.ui.formatTime

@Immutable
data class BookOverviewItemViewState(
  val name: String,
  val author: String?,
  val cover: String?,
  val progress: Float,
  val id: BookId,
  val remainingTime: String,
)

internal fun Book.toItemViewState(): BookOverviewItemViewState {
  val (currentPosition, totalDuration) = when (content.sourceType) {
    BookSourceType.Audio -> position to duration
    BookSourceType.Epub -> epubPosition() to epubDuration()
  }
  return BookOverviewItemViewState(
    name = content.name,
    author = content.author,
    cover = content.coverUrl,
    id = id,
    progress = progressFraction(currentPosition, totalDuration),
    remainingTime = formatTime(totalDuration - currentPosition),
  )
}

private fun Book.epubDuration(): Long = estimatedEpubDurationMs(content.epubTotalCharacterCount)

// Chapter-granular, not character-precise: advances in per-chapter steps rather than
// continuously. Keeps this function pure (no repo access), which matters because it runs for
// every visible library card on every render — see the EPUB progress display design doc.
private fun Book.epubPosition(): Long {
  val chapterCount = content.epubChapterCount
  if (chapterCount == 0) return 0L
  val elapsedFraction = content.currentEpubChapterIndex.toFloat() / chapterCount.toFloat()
  return (epubDuration() * elapsedFraction).toLong()
}

private fun progressFraction(
  position: Long,
  duration: Long,
): Float {
  // An unparsed EPUB has duration == 0 (epubTotalCharacterCount defaults to 0 until first open —
  // see Task 2), which the old audiobook-only code never had to guard against since a real
  // audiobook's duration is never 0. Without this guard, position.toFloat() / duration.toFloat()
  // is 0F / 0F = NaN, and Float.coerceIn compares with < / >, both of which are false for NaN, so
  // NaN would pass through uncaught instead of coercing to 0F.
  if (duration == 0L) return 0F
  val progress = position.toFloat() / duration.toFloat()
  if (progress < 0F) {
    Logger.w("Couldn't determine progress for position=$position duration=$duration")
  }
  return progress.coerceIn(0F, 1F)
}
```

(`progressFraction` is the old `Book.progress()` extension, renamed and generalized to take
explicit `position`/`duration` instead of always reading `Book.position`/`Book.duration` — those
two properties remain audiobook-only, per `Book.kt`, so the `Audio` branch still uses them
directly and behavior there is byte-for-byte unchanged.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :features:bookOverview:testDebugUnitTest --tests "*BookOverviewItemViewStateTest*"`
Expected: PASS

- [ ] **Step 5: Run the full `bookOverview` test suite to confirm no other test broke**

Run: `./gradlew :features:bookOverview:testDebugUnitTest`
Expected: PASS (the old `Book.progress()` private extension is gone, replaced by
`progressFraction` — confirm nothing else in the module referenced it directly; it was already
`private`, so nothing outside this file could have).

- [ ] **Step 6: Commit**

```bash
git add features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewState.kt \
  features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewItemViewStateTest.kt
git commit -m "Show estimated progress and remaining time for EPUBs on the library card"
```

---

### Task 4: Chapter-scoped progress and seek-position math (pure functions)

**Files:**
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubChapterProgress.kt`
- Test: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubChapterProgressTest.kt`

**Interfaces:**
- Consumes: `voice.core.data.estimatedEpubDurationMs`/`estimatedEpubCharacterCount` (Task 1).
- Produces: `internal data class ChapterProgress(val position: Duration, val duration: Duration)`.
  `internal fun chapterProgress(sentences: List<String>, activeSentenceIndex: Int): ChapterProgress`.
  `internal fun sentenceIndexForSeekPosition(sentences: List<String>, targetPosition: Duration): Int`.
  Both consumed by `EpubReaderViewModel` in Task 5.

- [ ] **Step 1: Write the failing tests**

Create `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubChapterProgressTest.kt`:

```kotlin
package voice.features.epubReader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EpubChapterProgressTest {

  // Each sentence is exactly 15 characters -> exactly 1 second at 15 chars/sec. Chosen so every
  // char<->ms conversion in these tests is exact, with no integer-division truncation to reason
  // about (estimatedEpubDurationMs/estimatedEpubCharacterCount both truncate, so an arbitrary
  // sentence length would make "exactly on a boundary" tests fragile/ambiguous).
  private val sentences = listOf(
    "a".repeat(15),
    "b".repeat(15),
    "c".repeat(15),
  )
  // total 45 chars -> 3 seconds at 15 chars/sec

  @Test
  fun `chapterProgress at the first sentence has zero position`() {
    val progress = chapterProgress(sentences, activeSentenceIndex = 0)

    assertEquals(expected = 0.milliseconds, actual = progress.position)
    assertEquals(expected = 3.seconds, actual = progress.duration)
  }

  @Test
  fun `chapterProgress position is the estimated duration of sentences before the active one`() {
    val progress = chapterProgress(sentences, activeSentenceIndex = 2)

    assertEquals(expected = 2.seconds, actual = progress.position) // 2 full 1-second sentences before index 2
    assertEquals(expected = 3.seconds, actual = progress.duration)
  }

  @Test
  fun `chapterProgress for an empty chapter is zero duration and zero position`() {
    val progress = chapterProgress(emptyList(), activeSentenceIndex = 0)

    assertEquals(expected = 0.milliseconds, actual = progress.position)
    assertEquals(expected = 0.milliseconds, actual = progress.duration)
  }

  @Test
  fun `seeking to zero resolves to the first sentence`() {
    assertEquals(expected = 0, actual = sentenceIndexForSeekPosition(sentences, 0.milliseconds))
  }

  @Test
  fun `seeking to the full duration resolves to the last sentence`() {
    assertEquals(expected = 2, actual = sentenceIndexForSeekPosition(sentences, 3.seconds))
  }

  @Test
  fun `seeking into the middle of the second sentence resolves to that sentence`() {
    assertEquals(expected = 1, actual = sentenceIndexForSeekPosition(sentences, 1_500.milliseconds))
  }

  @Test
  fun `seeking exactly onto a sentence boundary resolves to the next sentence`() {
    // exactly the end of sentence 0 / start of sentence 1
    assertEquals(expected = 1, actual = sentenceIndexForSeekPosition(sentences, 1.seconds))
  }

  @Test
  fun `seeking an empty chapter resolves to the first index`() {
    assertEquals(expected = 0, actual = sentenceIndexForSeekPosition(emptyList(), 5.seconds))
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubChapterProgressTest*"`
Expected: FAIL — `chapterProgress`/`sentenceIndexForSeekPosition`/`ChapterProgress` are unresolved
references (the file doesn't exist yet).

- [ ] **Step 3: Implement the pure functions**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubChapterProgress.kt`:

```kotlin
package voice.features.epubReader

import voice.core.data.estimatedEpubCharacterCount
import voice.core.data.estimatedEpubDurationMs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class ChapterProgress(
  val position: Duration,
  val duration: Duration,
)

/**
 * Estimated position/duration within the current chapter, from the character lengths of its
 * already-loaded sentence texts. Sentence-precise (unlike the library card's chapter-granular
 * estimate), since only one chapter's worth of text is ever in memory here.
 */
internal fun chapterProgress(
  sentences: List<String>,
  activeSentenceIndex: Int,
): ChapterProgress {
  val totalChars = sentences.sumOf { it.length }
  val elapsedChars = sentences.take(activeSentenceIndex).sumOf { it.length }
  return ChapterProgress(
    position = estimatedEpubDurationMs(elapsedChars).milliseconds,
    duration = estimatedEpubDurationMs(totalChars).milliseconds,
  )
}

/**
 * Converts a scrubber seek target (an absolute position within the current chapter) into the
 * sentence index to resume playback from. A target landing exactly on a sentence boundary
 * resolves to the start of the following sentence.
 */
internal fun sentenceIndexForSeekPosition(
  sentences: List<String>,
  targetPosition: Duration,
): Int {
  if (sentences.isEmpty()) return 0
  val targetChars = estimatedEpubCharacterCount(targetPosition.inWholeMilliseconds)
  var cumulative = 0
  sentences.forEachIndexed { index, sentence ->
    cumulative += sentence.length
    if (cumulative > targetChars) return index
  }
  return sentences.lastIndex
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubChapterProgressTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubChapterProgress.kt \
  features/epubReader/src/test/kotlin/voice/features/epubReader/EpubChapterProgressTest.kt
git commit -m "Add pure chapter-progress and seek-position math for the EPUB scrubber"
```

---

### Task 5: Wire chapter progress and seeking into `EpubReaderViewModel`

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`
- Test: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `chapterProgress`/`sentenceIndexForSeekPosition` (Task 4).
  `EpubPlaylistController.start(bookId, voiceId, bookTitle, chapterIndex, sentenceIndex)`
  (pre-existing).
- Produces: `EpubReaderViewState.Content` gains `chapterPosition: Duration`/
  `chapterDuration: Duration`. `EpubReaderViewModel.seekTo(position: Duration)` — consumed by the
  UI in Task 6.

- [ ] **Step 1: Write the failing tests**

Read the current `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`
first — it already has a `book()` fixture helper, a mocked `epubPlaylistController`, and a
`viewModel()` factory. Add these two tests (after the existing `active sentence index follows the
playlist controller's current sentence` test):

```kotlin
  @Test
  fun `chapter position and duration are estimated from the loaded sentence texts`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      // "Hello." (6 chars) + "World." (6 chars) = 12 chars -> 800ms at 15 chars/sec; position 0
      assertEquals(expected = 0.milliseconds, actual = state.chapterPosition)
      assertEquals(expected = 800.milliseconds, actual = state.chapterDuration)
    }
  }

  @Test
  fun `seekTo resumes playback at the sentence resolved from the seek position`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }

    viewModel.seekTo(400.milliseconds) // into "World." (the second sentence, index 1)

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 1) }
  }
```

Add the two missing imports at the top of the file:

```kotlin
import kotlin.time.Duration.Companion.milliseconds
```

(`coVerify` and `kotlin.time.Duration` companion are the only new imports needed — `coVerify` is
already imported in this file per the existing `coVerify { epubPlaylistController.start(...) }`
call in the `resumes from the persisted chapter and sentence position instead of restarting` test.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubReaderViewModelTest*"`
Expected: FAIL to compile — `EpubReaderViewState.Content` has no `chapterPosition`/
`chapterDuration` parameters yet, and `EpubReaderViewModel` has no `seekTo` method yet.

- [ ] **Step 3: Add the new fields to `EpubReaderViewState.Content`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`:

```kotlin
package voice.features.epubReader

import kotlin.time.Duration

public sealed interface EpubReaderViewState {
  public data object Loading : EpubReaderViewState

  public data class Error(val message: String) : EpubReaderViewState

  public data class Content(
    val bookTitle: String,
    val sentences: List<String>,
    val activeSentenceIndex: Int,
    val failedSentenceIndices: Set<Int>,
    val isPlaying: Boolean,
    val chapters: List<ChapterEntry>,
    val chapterPosition: Duration,
    val chapterDuration: Duration,
  ) : EpubReaderViewState

  public data class ChapterEntry(
    val index: Int,
    val title: String,
  )
}
```

- [ ] **Step 4: Compute chapter progress in `viewState()` and add `seekTo`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`, update
the `viewState()` function's `is OpenState.Ready ->` branch to compute and pass the new fields,
and add a `seekTo` method:

```kotlin
        is OpenState.Ready -> {
          val currentSentence = epubPlaylistController.currentSentenceFlow().collectAsState().value
          val playing = playStateManager.playStateFlow.collectAsState().value == PlayStateManager.PlayState.Playing
          val activeSentenceIndex = currentSentence?.second ?: 0
          val progress = chapterProgress(state.sentences, activeSentenceIndex)
          EpubReaderViewState.Content(
            bookTitle = state.bookTitle,
            sentences = state.sentences,
            activeSentenceIndex = activeSentenceIndex,
            failedSentenceIndices = emptySet(),
            isPlaying = playing,
            chapters = state.chapters,
            chapterPosition = progress.position,
            chapterDuration = progress.duration,
          )
        }
```

(This replaces the existing `is OpenState.Ready -> { ... }` branch body — same shape, with
`activeSentenceIndex` pulled into a local so `chapterProgress` can reuse it, and the two new
fields added.)

Add the `seekTo` method, near the existing `onChapterSelect`:

```kotlin
  public fun seekTo(position: Duration) {
    val voiceId = voiceId ?: return
    val bookTitle = bookTitle ?: return
    val current = openState.value
    if (current !is OpenState.Ready) return
    val targetSentenceIndex = sentenceIndexForSeekPosition(current.sentences, position)
    scope.launch {
      epubPlaylistController.start(bookId, voiceId, bookTitle, activeChapterIndex, targetSentenceIndex)
    }
  }
```

Add the new import to the top of the file:

```kotlin
import kotlin.time.Duration
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubReaderViewModelTest*"`
Expected: PASS

- [ ] **Step 6: Run the full `epubReader` test suite to confirm no other test broke**

Run: `./gradlew :features:epubReader:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt \
  features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt \
  features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt
git commit -m "Compute chapter-scoped progress and wire seeking into EpubReaderViewModel"
```

---

### Task 6: Reader-screen scrubber UI

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`

**Interfaces:**
- Consumes: `EpubReaderViewState.Content.chapterPosition`/`chapterDuration` (Task 5).
  `EpubReaderViewModel.seekTo(position: Duration)` (Task 5).
- Produces: `EpubReaderView` gains a new `onSeek: (Duration) -> Unit` parameter.

This task is UI-only; there is no isolated unit-testable behavior beyond what Task 5 already
covers; the deliverable is verified by building and running on a device/emulator (see Step 3).

- [ ] **Step 1: Add the scrubber composable**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`, add a
new composable mirroring `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/SliderRow.kt`
(the audiobook player's own chapter-scoped scrubber — same drag-detection pattern, same
`onSeek: (Duration) -> Unit` shape):

```kotlin
@Composable
private fun ChapterScrubberRow(
  duration: Duration,
  position: Duration,
  onSeek: (Duration) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    var localValue by remember { mutableFloatStateOf(0F) }
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    Text(
      text = formatTime(
        timeMs = if (dragging) {
          (duration * localValue.toDouble()).inWholeMilliseconds
        } else {
          position.inWholeMilliseconds
        },
        durationMs = duration.inWholeMilliseconds,
      ),
    )
    Slider(
      modifier = Modifier
        .weight(1F)
        .padding(horizontal = 8.dp),
      interactionSource = interactionSource,
      value = if (dragging) {
        localValue
      } else {
        (position / duration).toFloat().takeUnless { it.isNaN() }?.coerceIn(0F, 1F) ?: 0F
      },
      onValueChange = {
        localValue = it
      },
      onValueChangeFinished = {
        onSeek(duration * localValue.toDouble())
      },
    )
    Text(
      text = formatTime(
        timeMs = duration.inWholeMilliseconds,
        durationMs = duration.inWholeMilliseconds,
      ),
    )
  }
}
```

(`(position / duration)` is `Duration / Duration`, which yields a plain `Double` fraction — this
divides by zero and produces `NaN` when `duration` is zero, e.g. an empty first chapter still
synthesizing; the `takeUnless { it.isNaN() } ?: 0F` guard avoids feeding `NaN` to `Slider`, which
would otherwise crash or render incorrectly. `SliderRow`'s existing audiobook equivalent doesn't
need this guard because an audiobook's chapter `Duration` is never zero.)

Add the new imports this composable needs, alongside the existing ones at the top of the file:

```kotlin
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableFloatStateOf
import voice.core.ui.formatTime
import kotlin.time.Duration
```

- [ ] **Step 2: Wire the scrubber into the screen and thread `onSeek` through**

Still in `EpubReaderView.kt`, add the `onSeek` parameter to both `EpubReaderView` and
`EpubReaderContent`, and place the scrubber between the `TopAppBar` and the sentence list:

```kotlin
@Composable
public fun EpubReaderView(
  viewState: EpubReaderViewState,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  onSeek: (Duration) -> Unit,
  modifier: Modifier = Modifier,
) {
  when (viewState) {
    EpubReaderViewState.Loading -> {
      Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is EpubReaderViewState.Error -> {
      Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(viewState.message)
      }
    }
    is EpubReaderViewState.Content -> {
      EpubReaderContent(
        viewState = viewState,
        onPlayPauseClick = onPlayPauseClick,
        onChapterSelect = onChapterSelect,
        onSeek = onSeek,
        modifier = modifier,
      )
    }
  }
}

@Composable
private fun EpubReaderContent(
  viewState: EpubReaderViewState.Content,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  onSeek: (Duration) -> Unit,
  modifier: Modifier = Modifier,
) {
```

(Both signatures gain the one new `onSeek: (Duration) -> Unit` parameter, placed after
`onChapterSelect` — same position in both, and `EpubReaderContent`'s call site inside
`EpubReaderView` is updated to pass it through, shown above.)

Inside `EpubReaderContent`'s `Scaffold { contentPadding -> ... }` body, add the scrubber row right
before the existing `LazyColumn`:

```kotlin
  ) { contentPadding ->
    Column(modifier = Modifier.padding(contentPadding)) {
      ChapterScrubberRow(
        duration = viewState.chapterDuration,
        position = viewState.chapterPosition,
        onSeek = onSeek,
      )
      LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
```

(Wrapping in a `Column` so the scrubber sits above the scrolling sentence list within the same
`contentPadding`-aware area; the `LazyColumn`'s own `.padding(contentPadding)` moves up to the new
outer `Column`, and `LazyColumn` keeps `.fillMaxSize()` to take the remaining vertical space.)

Add the `Column` import:

```kotlin
import androidx.compose.foundation.layout.Column
```

- [ ] **Step 3: Wire `onSeek` from the screen into the ViewModel**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`:

```kotlin
  EpubReaderView(
    viewState = viewState,
    onPlayPauseClick = viewModel::playPause,
    onChapterSelect = viewModel::onChapterSelect,
    onSeek = viewModel::seekTo,
  )
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :app:assembleFreeDebug`
Expected: BUILD SUCCESSFUL. Then run the full regression suite:

Run: `./gradlew voiceUnitTest --continue`
Expected: only the documented pre-existing Windows failures
(`NaturalOrderComparatorTest.uriComparatorFiles`, `ConvertersTest.file`, all
`DataBaseMigratorTest` cases including the new `migrate65`).

- [ ] **Step 5: Manual on-device verification**

Install on a real device (this plan's math/wiring is unit-tested, but the actual dragging feel
and audio-seek behavior can only be confirmed live):
1. Open an EPUB with more than one sentence already synthesized (its cached window). Confirm the
   scrubber shows a non-zero duration and the position advances as sentences play.
2. Drag the scrubber to roughly the middle of the current chapter and release. Confirm playback
   resumes from a sentence in that neighborhood — chapter-relative, not exact to the second.
3. Return to the library screen and confirm the EPUB's card now shows a percentage and a
   remaining-time text that isn't "0:00" (assuming the book has been opened at least once, so
   `epubTotalCharacterCount` is populated per Task 2).
4. Add a folder containing a *freshly scanned but never-opened* EPUB; confirm its library card
   still correctly shows "Not Started" with `0:00`/no percentage (matches pre-existing behavior —
   `epubTotalCharacterCount` isn't populated until first open, same lazy-population design as
   `epubChapterCount`).

- [ ] **Step 6: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt \
  features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt
git commit -m "Add the EPUB reader's chapter-scoped, interactive scrubber"
```
