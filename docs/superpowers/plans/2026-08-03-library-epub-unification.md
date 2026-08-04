# Library EPUB Unification (Plan 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three concrete gaps where the library screen treats EPUB books as second-class: progress
categorization (Not Started/Current/Finished) always shows Not Started for EPUBs, the global play/pause FAB
silently fails on a cold app start if the last thing read was an EPUB, and the folder-add flow has zero EPUB
awareness (file counts show 0, EPUB-only folders collapse into one bogus "book").

**Architecture:** No new modules. Two new cached `Int` columns on `BookContent` (Room v63→v64) let EPUB progress
be compared the same way audiobook position/duration already are. The library's "resume the right thing" FAB
logic compares `lastPlayedAt` across audiobook and EPUB candidates instead of unifying them into one DataStore —
`currentBookStoreId` stays audiobook-only, so none of the `VoicePlayer`/`MediaItemProvider`/`LibrarySessionCallback`
hijacking-bug fixes from the previous session's on-device testing get reopened. The folder-add flow gets
generalized `isEpubFile()`/`bookFileCount()` helpers used everywhere it currently uses audio-only ones.

**Tech Stack:** Kotlin, Room (one `AutoMigration`, two new columns), Jetpack Compose/Molecule+Turbine for
ViewModel tests (matching `AGENTS.md`'s stated convention), mockk.

**Validation note for whoever executes this:** every task in this plan was written, compiled, and verified
against a full test run in this repo during plan-writing, then reverted — this is not a guess. All 4 new tests in
`BookOverviewCategoryTest`, all 4 new tests in `BookOverviewViewModelTest`, 1 new assertion pair in
`EpubBookOpenerTest`, 1 new test in `EpubReaderViewModelTest`, and 3 new tests in `SelectFolderTypeViewModelTest`
passed. A full `voiceUnitTest` run showed only the pre-existing Windows `MigrationTestHelper`/Robolectric failures
documented below — no new regressions anywhere in the project. Follow the steps for the TDD/review/commit
discipline; the code itself is already proven correct in this exact repo.

## Global Constraints

- This is Plan 5, elaborating `docs/superpowers/specs/2026-08-03-library-epub-unification-design.md`. Voice picker
  UI, EPUB cover extraction, and the Kindle-style highlighting/reading redesign are explicitly out of scope — a
  separate, later plan.
- **Do not modify `VoicePlayer`, `MediaItemProvider`, or `LibrarySessionCallback`.** The previous session found and
  fixed two separate bugs where these classes hijacked an active EPUB session by resolving the audiobook-only
  `currentBookStoreId` unconditionally. This plan's FAB fix deliberately does not write an EPUB's `BookId` into
  that DataStore, to avoid reopening that bug class. If you find yourself wanting to touch any of these three
  files, stop and re-read the design spec's Decisions section.
- **Known pre-existing, environment-specific test failures on this Windows dev machine** (confirmed across every
  prior plan in this project, unrelated to this plan): `core:common`'s `NaturalOrderComparatorTest.uriComparatorFiles`;
  `core:data:impl`'s `ConvertersTest.file` and all `DataBaseMigratorTest` tests (Room's `MigrationTestHelper` has a
  Windows path-handling bug). This plan's new `migrate64` test is written and correct but **cannot be verified to
  pass on this machine** — verify it via the regenerated schema JSON diff and code review instead, matching every
  prior plan's Room migration task.
- 2-space indentation, no unnecessary comments, named constructor arguments — matching existing Voice source style.
- Building requires the Android SDK/JDK toolchain already set up on this machine (prerequisite, not part of this
  plan) — `export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"` before any `./gradlew` call
  in a fresh shell/subagent.

---

### Task 1: Cache EPUB progress fields on `BookContent`

**Files:**
- Modify: `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces (for Task 2 and Task 3): `BookContent.epubChapterCount: Int = 0` and
  `BookContent.epubLastChapterSentenceCount: Int = 0` — both defaulted, so every existing `BookContent`-building
  call site keeps compiling unchanged.

- [ ] **Step 1: Write a failing migration test**

Add to `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt` (inside
the `DataBaseMigratorTest` class, alongside the existing `migrate63` test):

```kotlin
  @Test
  fun migrate64() {
    val dbName = "testDb"
    val db = helper.createDatabase(dbName, 63)
    db.execSQL(
      "INSERT INTO `content2`(`id`,`playbackSpeed`,`skipSilence`,`isActive`,`lastPlayedAt`,`author`,`name`," +
        "`addedAt`,`chapters`,`currentChapter`,`positionInChapter`,`cover`,`gain`,`genre`,`narrator`,`series`," +
        "`part`,`sourceType`,`voiceId`,`currentEpubChapterIndex`,`currentEpubSentenceIndex`) " +
        "VALUES ('book1', 1.0, 0, 1, '1970-01-01T00:00:00Z', NULL, 'A Book', '1970-01-01T00:00:00Z', '[]', " +
        "'chapter1', 0, NULL, 0, NULL, NULL, NULL, NULL, 'Epub', NULL, 0, 0)",
    )
    db.close()

    val migratedDb = helper.runMigrationsAndValidate(
      dbName,
      64,
      true,
      *allMigrations(),
    )

    val cursor = migratedDb.query("SELECT * FROM content2 WHERE id = 'book1'")
    cursor.moveToFirst()
    assertEquals(expected = 0, actual = cursor.getInt("epubChapterCount"))
    assertEquals(expected = 0, actual = cursor.getInt("epubLastChapterSentenceCount"))
    cursor.close()
  }
```

- [ ] **Step 2: Run the test and note the expected result**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.internals.internals.DataBaseMigratorTest.migrate64"
```

Expected: fails, either from the missing schema/version (before Step 3) or from the pre-existing Windows
`MigrationTestHelper` bug (after Step 3 — see Global Constraints). Either way, do not spend time chasing a local
pass/fail here; Step 5 verifies correctness a different way.

- [ ] **Step 3: Add the new fields to `BookContent`**

In `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`, add two trailing constructor parameters (after
`currentEpubSentenceIndex: Int = 0`) and extend the existing `init` check:

```kotlin
  @ColumnInfo(defaultValue = "0")
  val currentEpubChapterIndex: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val currentEpubSentenceIndex: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubChapterCount: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubLastChapterSentenceCount: Int = 0,
) {

  @Ignore
  val currentChapterIndex: Int = chapters.indexOf(currentChapter)

  val coverUrl: String? get() = cover?.toURI()?.toString()

  init {
    require(currentChapter in chapters && positionInChapter >= 0) {
      "invalid data in $this"
    }
    require(currentEpubChapterIndex >= 0 && currentEpubSentenceIndex >= 0) {
      "invalid epub position in $this"
    }
    require(epubChapterCount >= 0 && epubLastChapterSentenceCount >= 0) {
      "invalid epub progress cache in $this"
    }
  }
}
```

- [ ] **Step 4: Bump the database version and add the `AutoMigration`**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add one more `AutoMigration` entry
and bump `VERSION`:

```kotlin
    AutoMigration(from = 62, to = 63),
    AutoMigration(from = 63, to = 64),
  ],
)
```

```kotlin
  internal companion object {
    const val VERSION = 64
    const val DATABASE_NAME = "autoBookDB"
  }
```

- [ ] **Step 5: Verify via schema JSON, not the migration test**

```bash
./gradlew :core:data:impl:compileDebugKotlin :core:data:impl:kspDebugKotlin --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, and `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/64.json` is
generated containing `epubChapterCount`/`epubLastChapterSentenceCount` columns with `"defaultValue": "0"`. Open
the file and confirm this by inspection — this is the real verification for this task, per Global Constraints.
The `--rerun-tasks` flag matters here: without it, Gradle may report the ksp task as cached/up-to-date without
actually regenerating the schema file on disk (confirmed while validating this plan).

- [ ] **Step 6: Run the full `core:data:impl` and `core:data:api` unit test suites**

```bash
./gradlew :core:data:impl:testDebugUnitTest :core:data:api:testDebugUnitTest
```

Expected: same known pre-existing failures as every prior plan (`ConvertersTest.file` + all `DataBaseMigratorTest`
tests, now including the new `migrate64`) — no other regressions.

- [ ] **Step 7: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/BookContent.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt core/data/impl/schemas
git commit -m "Add cached EPUB progress fields to BookContent"
```

---

### Task 2: Write the new fields when a book is first parsed

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`
- Modify: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`

**Interfaces:**
- Consumes: `BookContent.epubChapterCount`/`epubLastChapterSentenceCount` (Task 1), `EpubBookRepo.sentences(bookId,
  chapterIndex): List<EpubSentence>` (existing)
- Produces: correctly-populated `epubChapterCount`/`epubLastChapterSentenceCount` for Task 3 (`Book.category`) to
  read.

This is the *only* point in the codebase where a book's chapters/sentences go from empty to populated — Plan 2's
`MediaScanner` stub-entry creation deliberately never persists real `EpubChapter`/`EpubSentence` rows, and parsing
is lazy-on-first-open. So these two fields never need recomputing on later opens.

- [ ] **Step 1: Write a failing test**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`, extend the existing
`parses on first open and auto-assigns the first catalog voice` test (the minimal test EPUB it builds has exactly
1 chapter with 2 sentences: "Hello there." and "This is chapter one."):

```kotlin
    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = "voice-a", actual = result.voiceId)
    assertEquals(expected = listOf("Chapter One"), actual = epubBookRepo.chapters(bookId).map { it.title })
    assertEquals(expected = "voice-a", actual = bookContentRepo.get(bookId)?.voiceId)
    assertEquals(expected = 1, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 2, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
  }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: fails on the two new assertions (both read `0`, since nothing writes these fields yet).

- [ ] **Step 3: Write the fields during the fresh-import branch**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`, restructure `open()` so
`content` is fetched once, up front, and mutated as a local `var` — this lets the fresh-import branch's write and
the (independent, conditional) voice-assignment write below both build on the latest state rather than the
voice-assignment's `content.copy(...)` accidentally reverting the epub-progress fields back to their stale,
pre-fetch values:

```kotlin
  public suspend fun open(bookId: BookId): OpenResult {
    var chapters = epubBookRepo.chapters(bookId)
    var content = bookContentRepo.get(bookId) ?: return OpenResult.Malformed("book not found: $bookId")
    if (chapters.isEmpty()) {
      val documentFile = cachedDocumentFileFactory.create(bookId.toUri())
      when (val result = epubImporter.import(bookId, documentFile)) {
        is EpubParseResult.Malformed -> return OpenResult.Malformed(result.reason)
        EpubParseResult.DrmProtected -> return OpenResult.DrmProtected
        is EpubParseResult.Success -> Unit
      }
      chapters = epubBookRepo.chapters(bookId)
      val lastChapterSentenceCount = epubBookRepo.sentences(bookId, chapters.size - 1).size
      content = content.copy(
        epubChapterCount = chapters.size,
        epubLastChapterSentenceCount = lastChapterSentenceCount,
      )
      bookContentRepo.put(content)
    }

    val voiceId = content.voiceId ?: run {
      val firstVoice = voiceManager.availableVoices().first()
      if (!firstVoice.installed) {
        when (val install = voiceManager.install(firstVoice.entry.voiceId)) {
          is InstallResult.Failure -> return OpenResult.VoiceInstallFailed(install.reason)
          InstallResult.Success -> Unit
        }
      }
      bookContentRepo.put(content.copy(voiceId = firstVoice.entry.voiceId))
      firstVoice.entry.voiceId
    }

    return OpenResult.Ready(chapters, voiceId)
  }
```

Note the `bookContentRepo.get(bookId)` call moved from after the `if (chapters.isEmpty())` block to before it —
harmless, since importing only touches `EpubChapter`/`EpubSentence` rows via `epubBookRepo`, never `BookContent`.

- [ ] **Step 4: Run the test and verify it passes**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed (the existing 4 plus the extended assertions on the first one — this
plan doesn't add a new test case, it extends an existing one).

- [ ] **Step 5: Run the full `features:epubReader` unit test suite**

```bash
./gradlew :features:epubReader:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt
git commit -m "Write epubChapterCount/epubLastChapterSentenceCount when a book is first parsed"
```

---

### Task 3: Make `Book.category` source-type-aware

**Files:**
- Modify: `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewCategory.kt`
- Modify: `features/bookOverview/src/test/kotlin/voice/features/bookOverview/BookOverviewCategoryTest.kt`

**Interfaces:**
- Consumes: `BookContent.sourceType`/`currentEpubChapterIndex`/`currentEpubSentenceIndex`/`epubChapterCount`/
  `epubLastChapterSentenceCount` (Task 1/2, all existing except the two new fields)
- Produces: nothing new for later tasks — this is purely a display-logic fix, consumed by the existing
  `BookOverview` list-rendering code unchanged at the call site.

**Exact thresholds** (from the design spec): Not Started is `epubChapterCount == 0` (unparsed) OR
`currentEpubChapterIndex == 0 && currentEpubSentenceIndex == 0` (never advanced). Finished is within the last 2
sentences of the last chapter (mirroring audiobooks' 5-second-before-the-end buffer): `currentEpubChapterIndex >=
epubChapterCount - 1 && currentEpubSentenceIndex >= epubLastChapterSentenceCount - 2`. Everything else is Current.

- [ ] **Step 1: Write failing tests**

In `features/bookOverview/src/test/kotlin/voice/features/bookOverview/BookOverviewCategoryTest.kt`, add the import
and the following 5 test cases:

```kotlin
import voice.core.data.BookSourceType
```

```kotlin
  @Test
  fun `epub not started when unparsed`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 0,
          currentEpubChapterIndex = 0,
          currentEpubSentenceIndex = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun `epub not started at the beginning`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 0,
          currentEpubSentenceIndex = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun `epub current mid-book`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 1,
          currentEpubSentenceIndex = 5,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }

  @Test
  fun `epub finished at the last sentence`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 2,
          currentEpubSentenceIndex = 9,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.FINISHED, actual = book.category)
  }

  @Test
  fun `epub current near the end but outside the finished buffer`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 2,
          currentEpubSentenceIndex = 5,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }
```

- [ ] **Step 2: Run the tests and verify they fail**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.BookOverviewCategoryTest"
```

Expected: the 3 existing tests pass (audiobook behavior unchanged), the 5 new ones fail — every book currently
defaults to `BookSourceType.Audio`'s logic regardless of `sourceType`, so an Epub-sourced book with `position ==
0L` (inherited default, since `position`/`duration` are computed from audiobook-shaped `Chapter`/`positionInChapter`
fields the epub fixture doesn't meaningfully set) will misclassify.

- [ ] **Step 3: Implement the source-type branch**

In `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewCategory.kt`, add the
import and replace the `Book.category` property:

```kotlin
import voice.core.data.BookSourceType
```

```kotlin
val Book.category: BookOverviewCategory
  get() {
    return when (content.sourceType) {
      BookSourceType.Audio -> audioCategory()
      BookSourceType.Epub -> epubCategory()
    }
  }

private fun Book.audioCategory(): BookOverviewCategory {
  return if (position == 0L) {
    BookOverviewCategory.NOT_STARTED
  } else {
    if (position >= duration - SECONDS.toMillis(5)) {
      BookOverviewCategory.FINISHED
    } else {
      BookOverviewCategory.CURRENT
    }
  }
}

private fun Book.epubCategory(): BookOverviewCategory {
  val chapterCount = content.epubChapterCount
  val chapterIndex = content.currentEpubChapterIndex
  val sentenceIndex = content.currentEpubSentenceIndex
  return when {
    chapterCount == 0 -> BookOverviewCategory.NOT_STARTED
    chapterIndex == 0 && sentenceIndex == 0 -> BookOverviewCategory.NOT_STARTED
    chapterIndex >= chapterCount - 1 && sentenceIndex >= content.epubLastChapterSentenceCount - 2 -> {
      BookOverviewCategory.FINISHED
    }
    else -> BookOverviewCategory.CURRENT
  }
}
```

The `audioCategory()` extraction is a pure rename of the existing logic — no behavior change for audiobooks.

- [ ] **Step 4: Run the tests and verify they pass**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.BookOverviewCategoryTest"
```

Expected: `BUILD SUCCESSFUL`, all 8 tests passed.

- [ ] **Step 5: Run the full `features:bookOverview` unit test suite**

```bash
./gradlew :features:bookOverview:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewCategory.kt features/bookOverview/src/test/kotlin/voice/features/bookOverview/BookOverviewCategoryTest.kt
git commit -m "Make Book.category source-type-aware for EPUB progress"
```

---

### Task 4: Update `lastPlayedAt` while reading an EPUB

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`
- Modify: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces (for Task 5): a real, advancing `BookContent.lastPlayedAt` for EPUB books — the one signal Task 5's FAB
  fix needs to compare "most recently active audiobook" against "most recently active EPUB" without touching
  `currentBookStoreId`.

- [ ] **Step 1: Write a failing test**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`, first change the
class-level `bookRepository` mock so `bookFixture` stays in sync with whatever update lambda gets applied (needed
to assert on the result afterward):

```kotlin
  private var bookFixture = book()
  private val bookRepository = mockk<BookRepository> {
    coEvery { get(bookId) } answers { bookFixture }
    coEvery { updateBook(bookId, any()) } answers {
      val update = secondArg<(BookContent) -> BookContent>()
      bookFixture = bookFixture.copy(content = update(bookFixture.content))
    }
  }
```

Then add the new test (right before the existing `playPause toggles playback...` test):

```kotlin
  @Test
  fun `updates lastPlayedAt when the sentence position changes`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // initial Content

      currentSentenceFlow.value = 0 to 1
      awaitItem()
    }

    assertEquals(expected = true, actual = bookFixture.content.lastPlayedAt.isAfter(Instant.EPOCH))
  }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: the new test fails (`lastPlayedAt` stays at `Instant.EPOCH`, the fixture's default, since nothing writes
it yet). The other existing tests should still pass unaffected by the mock restructuring in Step 1.

- [ ] **Step 3: Update `lastPlayedAt` in the position-tracking collector**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`, add the import:

```kotlin
import java.time.Instant
```

Then extend the existing `bookRepository.updateBook(...)` call inside the `currentSentenceFlow()` collector:

```kotlin
          bookRepository.updateBook(bookId) {
            it.copy(
              currentEpubChapterIndex = chapterIndex,
              currentEpubSentenceIndex = sentenceIndex,
              lastPlayedAt = Instant.now(),
            )
          }
```

- [ ] **Step 4: Run the test and verify it passes**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: `BUILD SUCCESSFUL`, all 8 tests passed.

- [ ] **Step 5: Run the full `features:epubReader` unit test suite**

```bash
./gradlew :features:epubReader:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt
git commit -m "Update lastPlayedAt while reading an EPUB"
```

---

### Task 5: Fix the cold-start library FAB

**Files:**
- Modify: `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt`
- Modify: `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt`

**Interfaces:**
- Consumes: `BookContentRepo.all(): List<BookContent>` (existing), `BookContentRepo.get(id): BookContent?`
  (existing), `BookContent.lastPlayedAt` (Task 4 makes this real for EPUBs), `Destination.EpubReader` (existing,
  Plan 4)
- Produces: nothing new for later tasks.

**Do not touch `PlayerController`, `VoicePlayer`, `MediaItemProvider`, or `LibrarySessionCallback`** — this fix is
entirely within `BookOverviewViewModel`, per Global Constraints.

- [ ] **Step 1: Write failing tests**

In `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt`, add
`currentBookStoreDataStore`/`playerController` as configurable parameters to the shared `viewModel(...)` helper
(the current hardcoded `currentBookStoreDataStore = MemoryDataStore(null)` / `playerController = mockk()` lines):

```kotlin
  private fun viewModel(
    folderPickerInSettingsFeatureFlag: MemoryFeatureFlag<Boolean>,
    folderPickerMovedDialogShownStore: DataStore<Boolean>,
    navigator: Navigator = mockk(),
    appInfoProvider: AppInfoProvider = appInfoProvider(),
    contentRepo: BookContentRepo = mockk(),
    currentBookStoreDataStore: DataStore<BookId?> = MemoryDataStore(null),
    playerController: PlayerController = mockk(relaxed = true),
  ): BookOverviewViewModel {
    return BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(emptyList())
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = playerController,
      currentBookStoreDataStore = currentBookStoreDataStore,
      folderPickerMovedDialogShownStore = folderPickerMovedDialogShownStore,
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = navigator,
      appInfoProvider = appInfoProvider,
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = contentRepo,
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = folderPickerInSettingsFeatureFlag,
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      kioskModeFeatureFlag = MemoryFeatureFlag(false),
      dispatcherProvider = dispatcherProvider,
    )
  }
```

Then add 4 new tests (right before the `private fun BookOverviewViewState.currentBook(...)` helper). Note: use
fully-qualified `java.time.Instant` in these tests, not the bare `Instant` name — this file already imports
`kotlin.time.Instant` for `appInfoProvider()`, and `BookContent.lastPlayedAt` is `java.time.Instant`:

```kotlin
  @Test
  fun `playPause routes to the epub reader when the epub is more recently played than the audiobook`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { navigator.goTo(Destination.EpubReader(epubId)) }
    verify(exactly = 0) { playerController.playPause() }
  }

  @Test
  fun `playPause defers to the player controller when the audiobook is more recently played than the epub`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
    verify(exactly = 0) { navigator.goTo(Destination.EpubReader(epubId)) }
  }

  @Test
  fun `playPause routes to the epub reader when no audiobook has ever been played`() = runTest {
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(null),
      contentRepo = mockk {
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { navigator.goTo(Destination.EpubReader(epubId)) }
    verify(exactly = 0) { playerController.playPause() }
  }

  @Test
  fun `playPause defers to the player controller when no epub exists`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns emptyList()
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
  }
```

The class-level `dispatcherProvider` in this test file is backed by `UnconfinedTestDispatcher()`, so `playPause()`'s
`scope.launch { }` runs eagerly to completion within the `playPause()` call itself — no `runCurrent()` needed
before the `verify` calls, matching this file's existing precedent for `navigateToBook`.

- [ ] **Step 2: Run the tests and verify they fail**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.overview.BookOverviewViewModelTest"
```

Expected: compile failure or the 4 new tests fail — `playPause()` doesn't have this branching logic yet.

- [ ] **Step 3: Implement the fix**

In `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt`, add the
import:

```kotlin
import kotlinx.coroutines.flow.first
```

Then replace:

```kotlin
  fun playPause() {
    playerController.playPause()
  }
```

with:

```kotlin
  fun playPause() {
    scope.launch {
      val audiobookCandidate = currentBookStoreDataStore.data.first()?.let { contentRepo.get(it) }
      val epubCandidate = contentRepo.all()
        .filter { it.sourceType == BookSourceType.Epub }
        .maxByOrNull { it.lastPlayedAt }
      if (epubCandidate != null &&
        (audiobookCandidate == null || epubCandidate.lastPlayedAt.isAfter(audiobookCandidate.lastPlayedAt))
      ) {
        navigator.goTo(Destination.EpubReader(epubCandidate.id))
      } else {
        playerController.playPause()
      }
    }
  }
```

Keep the null-check directly in the `if` condition (rather than an intermediate boolean variable) — Kotlin's
smart-cast needs it structured this way to treat `epubCandidate` as non-null inside the block without a redundant
`!!` (confirmed while validating this plan: `-Werror` flagged an unnecessary non-null assertion when this was
refactored into a separate boolean first).

- [ ] **Step 4: Run the tests and verify they pass**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.overview.BookOverviewViewModelTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full `features:bookOverview` unit test suite**

```bash
./gradlew :features:bookOverview:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 6: Commit**

```bash
git add features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt
git commit -m "Fix cold-start library FAB routing to a recently-read EPUB"
```

---

### Task 6: Generalize folder-add file detection to include EPUBs

**Files:**
- Modify: `core/data/api/src/main/kotlin/voice/core/data/SupportedAudioFormats.kt`
- Modify: `features/folderPicker/src/main/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModel.kt`
- Modify: `features/folderPicker/src/test/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModelTest.kt`

**Interfaces:**
- Consumes: `CachedDocumentFile.isEpubFile(): Boolean` (already exists, from Plan 2's
  `core/data/api/src/main/kotlin/voice/core/data/SupportedEpubFormats.kt` — **do not redefine it**, doing so causes
  a "conflicting overloads" compile error, confirmed while validating this plan)
- Produces: `CachedDocumentFile.epubFileCount(): Int`, `CachedDocumentFile.isBookFile(): Boolean`,
  `CachedDocumentFile.bookFileCount(): Int` — new, generalized helpers for anything that currently only checks for
  audio.

- [ ] **Step 1: Add the generalized file-detection helpers**

In `core/data/api/src/main/kotlin/voice/core/data/SupportedAudioFormats.kt`, add after the existing
`audioFileCount()`:

```kotlin
public fun CachedDocumentFile.epubFileCount(): Int {
  return if (isEpubFile()) {
    1
  } else {
    walk().count { it.isEpubFile() }
  }
}

public fun CachedDocumentFile.isBookFile(): Boolean = isAudioFile() || isEpubFile()

public fun CachedDocumentFile.bookFileCount(): Int = audioFileCount() + epubFileCount()
```

No new import needed — `isEpubFile()` lives in the same `voice.core.data` package (in
`SupportedEpubFormats.kt`), so it's already visible here without qualification.

This file has no dedicated test of its own (neither did the original `isAudioFile()`/`audioFileCount()` before
it) — it's exercised indirectly through `SelectFolderTypeViewModelTest` in Step 5.

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :core:data:api:compileDebugKotlin :core:data:api:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`. If you see "Conflicting overloads: fun CachedDocumentFile.isEpubFile()", you've
accidentally redefined a function that already exists in `SupportedEpubFormats.kt` — remove your duplicate, don't
rename the existing one.

- [ ] **Step 3: Write failing tests for folder-add EPUB detection**

In `features/folderPicker/src/test/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModelTest.kt`,
add 3 new tests (after the existing `test()` test), following its established real-file-via-`TemporaryFolder`
pattern:

```kotlin
  @Test
  fun `folder of flat epub files auto-detects to Audiobooks mode with correct counts`() = runTest {
    val epubFolder = temporaryFolder.newFolder("epubs")
    with(temporaryFolder) {
      newFile("epubs/FirstBook.epub")
      newFile("epubs/SecondBook.epub")
    }
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = epubFolder.toUri(),
      documentFile = DocumentFile.fromFile(epubFolder),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.Audiobooks, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(
          SelectFolderTypeViewState.Book("FirstBook", 1),
          SelectFolderTypeViewState.Book("SecondBook", 1),
        ).sortedBy { it.name },
        actual = state.books.sortedBy { it.name },
      )
    }
  }

  @Test
  fun `single loose epub file shows a file count of 1, not 0`() = runTest {
    val file = temporaryFolder.newFile("LoneBook.epub")
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = file.toUri(),
      documentFile = DocumentFile.fromFile(file),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.SingleBook, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(SelectFolderTypeViewState.Book("LoneBook", 1)),
        actual = state.books,
      )
    }
  }

  @Test
  fun `mixed audio subfolder and flat epub files both count correctly`() = runTest {
    val mixedFolder = temporaryFolder.newFolder("mixed")
    with(temporaryFolder) {
      newFolder("mixed/AudioBook")
      newFile("mixed/AudioBook/1.mp3")
      newFile("mixed/EpubBook.epub")
      newFile("mixed/SecondEpubBook.epub")
    }
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = mixedFolder.toUri(),
      documentFile = DocumentFile.fromFile(mixedFolder),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.Audiobooks, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(
          SelectFolderTypeViewState.Book("AudioBook", 1),
          SelectFolderTypeViewState.Book("EpubBook", 1),
          SelectFolderTypeViewState.Book("SecondEpubBook", 1),
        ).sortedBy { it.name },
        actual = state.books.sortedBy { it.name },
      )
    }
  }
```

**Note on the third test's file layout:** it deliberately uses *two* flat `.epub` files alongside the audio
subfolder, not one. With only one audio subfolder and one flat epub file, *neither* of the existing detection
heuristics fires — the "audio mixed with directories" check requires an audio file that is itself a *direct*
child (not nested inside a subfolder), and the new epub check (Step 4) requires more than one epub file — so that
narrower layout falls through to `FolderMode.SingleBook` instead, which was caught as a real test failure while
validating this plan, not a hypothetical.

- [ ] **Step 4: Run the tests and verify they fail**

```bash
./gradlew :features:folderPicker:testDebugUnitTest --tests "voice.features.folderPicker.selectType.SelectFolderTypeViewModelTest"
```

Expected: the existing `test()` case passes unchanged; the 3 new ones fail (EPUB files show a count of 0 and/or
the folder-mode auto-detection picks `SingleBook` instead of `Audiobooks` for the flat-epub-files case).

- [ ] **Step 5: Implement the fix**

In `features/folderPicker/src/main/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModel.kt`,
update the imports:

```kotlin
import voice.core.data.bookFileCount
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.data.isAudioFile
import voice.core.data.isBookFile
import voice.core.data.isEpubFile
import voice.core.documentfile.CachedDocumentFile
```

Add the new auto-detection case to `defaultFolderMode()` — additive to the existing audio-specific checks, which
stay exactly as they are:

```kotlin
  private fun CachedDocumentFile.defaultFolderMode(): FolderMode {
    return when {
      name in listOf("Audiobooks", "Hörbücher") -> FolderMode.Audiobooks
      children.any { it.isAudioFile() } && children.any { it.isDirectory } -> {
        FolderMode.Audiobooks
      }
      children.any {
        val fileIsAudiobookThresholdMb = 200
        it.isAudioFile() && it.length > fileIsAudiobookThresholdMb * 1_000_000
      } -> {
        FolderMode.Audiobooks
      }
      children.count { it.isEpubFile() } > 1 -> {
        // EPUB collections are flat: each .epub file is already a complete book on its own, no
        // subfolder needed. This reuses Audiobooks mode's existing "list each child individually"
        // behavior rather than introducing a new FolderMode.
        FolderMode.Audiobooks
      }
      else -> FolderMode.SingleBook
    }
  }
```

Then switch every `audioFileCount()` call in `viewState()` to `bookFileCount()`, and the `Authors` mode's
structural `isAudioFile()` check to `isBookFile()` (so a single loose `.epub` file directly under an author's
folder is also recognized as one complete book rather than something to descend into):

```kotlin
          FolderMode.Audiobooks -> {
            documentFile.children.map { child ->
              SelectFolderTypeViewState.Book(
                name = child.nameWithoutExtension(),
                fileCount = child.bookFileCount(),
              )
            }
          }
          FolderMode.SingleBook -> {
            listOf(
              SelectFolderTypeViewState.Book(
                name = documentFile.nameWithoutExtension(),
                fileCount = documentFile.bookFileCount(),
              ),
            )
          }
          FolderMode.Authors -> {
            documentFile.children.flatMap { author ->
              val authorName = author.nameWithoutExtension()
              if (author.isBookFile()) {
                listOf(
                  SelectFolderTypeViewState.Book(
                    name = author.nameWithoutExtension(),
                    fileCount = author.bookFileCount(),
                  ),
                )
              } else {
                author.children.map { child ->
                  SelectFolderTypeViewState.Book(
                    name = "${child.nameWithoutExtension()} ($authorName)",
                    fileCount = child.bookFileCount(),
                  )
                }
              }
            }
          }
```

- [ ] **Step 6: Run the tests and verify they pass**

```bash
./gradlew :features:folderPicker:testDebugUnitTest --tests "voice.features.folderPicker.selectType.SelectFolderTypeViewModelTest"
```

Expected: `BUILD SUCCESSFUL`, all 4 tests passed.

- [ ] **Step 7: Run the full `features:folderPicker` unit test suite**

```bash
./gradlew :features:folderPicker:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 8: Verify the whole project still builds and run the full regression suite**

```bash
./gradlew :app:assembleFreeDebug
./gradlew voiceUnitTest --continue
```

Expected: app assembles successfully. Test failures should be *exactly* the pre-existing set from Global
Constraints (`NaturalOrderComparatorTest.uriComparatorFiles`, `ConvertersTest.file`, all `DataBaseMigratorTest`
cases including `migrate64`) — nothing else.

- [ ] **Step 9: Manual on-device verification**

Install the debug build on a real device. Confirm: a previously-read EPUB shows under "Current" (not "Not Started
Yet") in the library list; reading to the end of a short EPUB moves it to "Finished"; killing the app after
reading an EPUB, then tapping the library's global play/pause FAB without opening the book first, opens the
reader screen and auto-plays that EPUB instead of silently failing; adding a folder of `.epub` files via the SAF
picker shows each one individually with a correct file count, not collapsed into one bogus entry.

- [ ] **Step 10: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/SupportedAudioFormats.kt features/folderPicker/src/main/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModel.kt features/folderPicker/src/test/kotlin/voice/features/folderPicker/selectType/SelectFolderTypeViewModelTest.kt
git commit -m "Generalize folder-add file detection to include EPUBs"
```

---

## What's next

After this plan, the library screen treats EPUB and audiobook progress symmetrically: the three-category list
(Not Started/Current/Finished) reflects real EPUB reading progress, the global play/pause FAB correctly resumes
the most recently active book of either type even from a cold start, and adding a folder of EPUB files works the
same way adding a folder of audiobooks does. `VoicePlayer`, `MediaItemProvider`, and `LibrarySessionCallback` —
and the hijacking-bug fixes already living there — are untouched throughout.

What's still missing, deliberately deferred to a later plan: the per-book voice picker/download UI, EPUB cover
extraction, the Kindle-style highlighting/reading UI redesign, a visual "synthesizing/constructing cache" loading
indicator, and wiring `EpubReaderViewState.Content.failedSentenceIndices` end-to-end.
