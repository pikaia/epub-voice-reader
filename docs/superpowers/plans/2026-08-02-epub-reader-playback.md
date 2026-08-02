# Reader UI & Playback (Plan 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `features:epubReader`, the read-along screen: parse an EPUB on first open, auto-assign and
synthesize a voice, play sentence clips through Voice's existing background-playback infrastructure, and highlight
the sentence currently narrated in sync with playback.

**Architecture:** New module `features:epubReader` talks to the same `MediaController`/`PlaybackService`/
`MediaSession` audiobooks already use (background playback, notification, lock-screen controls come for free),
but owns all EPUB-specific playlist/windowing logic itself rather than routing it through `core:playback`'s
audiobook-shaped `VoicePlayer`/`MediaItemProvider` resolution machinery, which assumes every media item's
underlying file already exists on disk — untrue for EPUB clips, which are synthesized just ahead of playback.
`core:playback` gains two small, additive, generic methods on `PlayerController` (`setEpubPlaylist`,
`currentMediaItemIndexFlow`) that `EpubPlaylistController` uses to push already-built `MediaItem`s and observe
playback position; `VoicePlayer`/`MediaItemProvider`/`LibrarySessionCallback` — the audiobook-specific resolution
classes — are untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 (`MediaController`/`Player`), Metro DI (assisted injection for the
per-screen ViewModel, mirroring `BookPlayViewModel`), Room (one `AutoMigration`, two new columns on the existing
`content2` table), Molecule + Turbine for ViewModel state tests (matching `AGENTS.md`'s stated convention).

**Validation note for whoever executes this:** the two new `PlayerController` methods (Task 2) were written,
compiled, and verified against a full `:app:assembleFreeDebug` in this repo during plan-writing, then reverted —
they're not a guess. Everything else in this plan is modeled closely on real, working precedent read directly from
this codebase (`BookPlayViewModel.kt`/`BookPlayController.kt`'s assisted-injection + Molecule pattern,
`BookOverviewViewModel.kt`'s routing, `DataBaseMigratorTest.kt`'s migration-test template, `BookPlayViewModelTest.kt`'s
`launchMolecule`+Turbine test pattern) — not derived from documentation alone. Follow the steps for the TDD/review/
commit discipline; the codebase patterns you're mirroring are already proven correct elsewhere in this repo.

## Global Constraints

- This is Plan 4 of the 5-plan staged sequence in `docs/superpowers/specs/2026-07-30-epub-ai-voice-reader-design.md`:
  parsing foundation (done) → data model & scanner (done) → Piper TTS integration (done) →
  **reader UI & playback (this plan)** → settings & polish. Elaborates
  `docs/superpowers/specs/2026-08-02-epub-reader-playback-design.md`.
- Module dependency direction per `AGENTS.md`: `features:epubReader` depends on `core:tts`, `core:scanner`
  (`EpubImporter` only, not `core:epub` directly — matches Plan 2's existing boundary), `core:data`,
  `core:documentfile`, `core:playback`, `core:common`, `core:ui`, `voice.navigation`. No feature-to-feature deps.
  `core:playback` gains two additive methods on `PlayerController` only — no changes to `VoicePlayer`,
  `MediaItemProvider`, or `LibrarySessionCallback`.
- Voice auto-assignment (no picker UI in this plan): on first open, if `BookContent.voiceId == null`, silently
  install and assign `VoiceCatalog.entries.first()`. The full voice picker/download UI is Plan 5's job.
- A permanently-failing sentence (after retry) is skipped from the media timeline entirely (no `MediaItem` added)
  and marked visually in the reader UI — not replaced with a literal silent-audio placeholder.
- Window reload is batched: when playback nears the end of the loaded ~30-sentence window (at window-position
  `size - 5`), synthesize the next batch and do one atomic `setMediaItems` reload preserving the current item's
  position — not fine-grained per-item `addMediaItem`/`removeMediaItem`.
- Dependency versions go in `gradle/libs.versions.toml` only — this plan adds no new third-party dependencies,
  only a new `include(":features:epubReader")` project edge and its `implementation(projects.core.X)` lines.
- 2-space indentation, no unnecessary comments, named constructor arguments — matching existing Voice source style.
- `EpubBookOpener`/`EpubPlaylistController`/`PlayerController`'s new methods never throw — synthesis, install, and
  playback-control failures surface as typed results or are logged and skipped, matching the never-throws
  convention `core:tts` already established in Plan 3.
- **Known pre-existing, environment-specific test failures on this Windows dev machine** (confirmed across
  Plans 1–3, unrelated to this plan): `core:common`'s `NaturalOrderComparatorTest.uriComparatorFiles`;
  `core:data:impl`'s `ConvertersTest.file` and all `DataBaseMigratorTest` tests (Room's `MigrationTestHelper` has a
  Windows path-handling bug). Task 1's new migration test is written and correct but **cannot be verified to pass
  on this machine** — verify it via the regenerated schema JSON diff and code review instead, matching how Plan 2's
  Task 1 handled the same limitation. `Room.inMemoryDatabaseBuilder`-based tests are unaffected.
- Building requires the Android SDK/JDK toolchain already set up on this machine (prerequisite, not part of this
  plan) — `export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"` before any `./gradlew` call
  in a fresh shell/subagent.

---

### Task 1: Add EPUB reading position to `BookContent`

**Files:**
- Modify: `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces (for Task 5): `BookContent.currentEpubChapterIndex: Int = 0` and
  `BookContent.currentEpubSentenceIndex: Int = 0` — both defaulted, so every existing `BookContent`-building call
  site keeps compiling unchanged.

- [ ] **Step 1: Write a failing migration test**

Add to `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt` (inside
the `DataBaseMigratorTest` class, alongside the existing `migrate60` test):

```kotlin
  @Test
  fun migrate63() {
    val dbName = "testDb"
    val db = helper.createDatabase(dbName, 62)
    db.execSQL(
      "INSERT INTO `content2`(`id`,`playbackSpeed`,`skipSilence`,`isActive`,`lastPlayedAt`,`author`,`name`," +
        "`addedAt`,`chapters`,`currentChapter`,`positionInChapter`,`cover`,`gain`,`genre`,`narrator`,`series`," +
        "`part`,`sourceType`,`voiceId`) " +
        "VALUES ('book1', 1.0, 0, 1, '1970-01-01T00:00:00Z', NULL, 'A Book', '1970-01-01T00:00:00Z', '[]', " +
        "'chapter1', 0, NULL, 0, NULL, NULL, NULL, NULL, 'Epub', NULL)",
    )
    db.close()

    val migratedDb = helper.runMigrationsAndValidate(
      dbName,
      63,
      true,
      *allMigrations(),
    )

    val cursor = migratedDb.query("SELECT * FROM content2 WHERE id = 'book1'")
    cursor.moveToFirst()
    assertEquals(expected = 0, actual = cursor.getInt("currentEpubChapterIndex"))
    assertEquals(expected = 0, actual = cursor.getInt("currentEpubSentenceIndex"))
    cursor.close()
  }
```

- [ ] **Step 2: Run the test and note the expected result**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.internals.internals.DataBaseMigratorTest.migrate63"
```

Expected: fails, either from the missing schema/version (before Step 3) or from the pre-existing Windows
`MigrationTestHelper` bug (after Step 3 — see Global Constraints). Either way, do not spend time chasing a local
pass/fail here; Step 5 verifies correctness a different way.

- [ ] **Step 3: Add the new fields to `BookContent`**

In `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`, add two trailing constructor parameters (after
`voiceId: String? = null`) and extend the existing `init` check:

```kotlin
  val sourceType: BookSourceType = BookSourceType.Audio,
  val voiceId: String? = null,
  @ColumnInfo(defaultValue = "0")
  val currentEpubChapterIndex: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val currentEpubSentenceIndex: Int = 0,
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
  }
}
```

- [ ] **Step 4: Bump the database version and add the `AutoMigration`**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add one more `AutoMigration` entry
and bump `VERSION`:

```kotlin
    AutoMigration(from = 60, to = 61),
    AutoMigration(from = 61, to = 62),
    AutoMigration(from = 62, to = 63),
  ],
)
```

```kotlin
  internal companion object {
    const val VERSION = 63
    const val DATABASE_NAME = "autoBookDB"
  }
```

- [ ] **Step 5: Verify via schema JSON, not the migration test**

```bash
./gradlew :core:data:impl:compileDebugKotlin :core:data:impl:kspDebugKotlin
```

Expected: `BUILD SUCCESSFUL`, and `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/63.json` is
generated containing `currentEpubChapterIndex`/`currentEpubSentenceIndex` columns with `"defaultValue": "0"`. Open
the file and confirm this by inspection — this is the real verification for this task, per Global Constraints.

- [ ] **Step 6: Run the full `core:data:impl` unit test suite**

```bash
./gradlew :core:data:impl:testDebugUnitTest
```

Expected: same known pre-existing failures as every prior plan (`ConvertersTest.file` + all `DataBaseMigratorTest`
tests, now including the new `migrate63`) — no other regressions. `BookTest`/`BookComparatorTest`/`ChapterTest` in
`core:data:api` (which construct `BookContent` directly) must still pass unchanged, since the new fields default.

- [ ] **Step 7: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/BookContent.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt core/data/impl/schemas
git commit -m "Add EPUB reading position fields to BookContent"
```

---

### Task 2: Add low-level playlist control to `PlayerController`

**Files:**
- Modify: `core/playback/src/main/kotlin/voice/core/playback/PlayerController.kt`

**Interfaces:**
- Consumes: nothing new (Media3 `MediaController`/`Player`, already a dependency)
- Produces (for Task 4): `PlayerController.setEpubPlaylist(mediaItems: List<MediaItem>, startIndex: Int,
  startPositionMs: Long)` — connects, sets the given already-built media items directly (no book/mediaId
  resolution), prepares, and plays. `PlayerController.currentMediaItemIndexFlow(): Flow<Int>` — emits the
  controller's current media item index on every `Player.EVENT` media-item transition, starting with the current
  value immediately on collection. `PlayerController.toggleEpubPlayPause()` — play/pause without going through
  `maybePrepare()`. **This third method exists because the existing `playPause()` is unsafe to reuse for EPUB
  playback**: it routes through `executeAfterPrepare`/`maybePrepare`, which reads the audiobook-specific
  `currentBookStoreId` and can call `controller.setMediaItem(mediaItemProvider.mediaItem(book))` — silently
  overwriting the already-set EPUB sentence-clip playlist with an audiobook placeholder item, since EPUB media
  items don't carry a `MediaId` at all (`controller.currentBookId()` returns null for them, so `maybePrepare`
  falls through to resolving whatever `currentBookStoreId` currently holds instead). This was caught during
  plan-writing while working out how the Task 6 ViewModel should call play/pause — not a hypothetical risk.

This task has no unit test of its own — matching this codebase's existing precedent, `PlayerController`'s methods
(including all its current ones: `play()`, `fastForward()`, `setSpeed()`, etc.) have no direct unit tests; they're
thin `MediaController` delegations verified through the consuming ViewModel's tests (with `PlayerController`
mocked) and manual on-device checks. This task's three new methods follow the same pattern. Do not add a
`PlayerControllerTest` file — there isn't one to extend, and adding one here would be inventing a new testing
approach for a class this codebase deliberately doesn't unit-test directly.

- [ ] **Step 1: Add the import**

In `core/playback/src/main/kotlin/voice/core/playback/PlayerController.kt`:

```kotlin
import androidx.media3.common.MediaItem
```

(alphabetically, between the existing `androidx.media3.common.C` and `androidx.media3.common.Player` imports)

- [ ] **Step 2: Add the three methods**

In the same file, insert before `suspend fun livePlaybackState(bookId: BookId? = null): LivePlaybackState?`:

```kotlin
  fun setEpubPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      controller.setMediaItems(mediaItems, startIndex, startPositionMs)
      controller.prepare()
      controller.play()
    }
  }

  fun currentMediaItemIndexFlow(): Flow<Int> = callbackFlow {
    val controller = awaitConnect()
    if (controller == null) {
      close()
      return@callbackFlow
    }

    fun emitIndex() {
      trySend(controller.currentMediaItemIndex)
    }

    val listener = object : Player.Listener {
      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
      ) {
        emitIndex()
      }
    }

    controller.addListener(listener)
    emitIndex()
    awaitClose {
      controller.removeListener(listener)
    }
  }

  fun toggleEpubPlayPause() {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (controller.isPlaying) {
        controller.pause()
      } else {
        controller.play()
      }
    }
  }
```

- [ ] **Step 3: Verify it compiles and the app still assembles**

```bash
./gradlew :core:playback:compileDebugKotlin :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add core/playback/src/main/kotlin/voice/core/playback/PlayerController.kt
git commit -m "Add low-level playlist control methods to PlayerController for EPUB playback"
```

---

### Task 3: Route EPUB books to a new reader destination

**Files:**
- Modify: `navigation/src/main/kotlin/voice/navigation/Destination.kt`
- Modify: `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt`
- Modify: `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt`

**Interfaces:**
- Consumes: `BookContent.sourceType` (existing, Plan 2), `BookContentRepo.get(id): BookContent?` (existing)
- Produces (for Task 4): `Destination.EpubReader(bookId: BookId) : Destination.Compose` — the destination
  `features:epubReader` will register a `NavEntryProvider` for.

This task adds the `Destination` and the routing decision now, ahead of the screen existing — matches this
codebase's own precedent of adding a `Destination` case before the feature module that serves it exists (e.g. `AddContent`).
Nothing renders for `Destination.EpubReader` until Task 6 registers its `NavEntryProvider`; `NavEntryProvider`
lookups are keyed by destination type in a `Set` multibinding (per `NavEntryProvider.kt`), so an unregistered
destination simply has no provider yet — harmless until Task 6, at which point navigating to an EPUB book starts
working.

- [ ] **Step 1: Add the destination**

In `navigation/src/main/kotlin/voice/navigation/Destination.kt`, add (after the existing `Playback` case):

```kotlin
  @Serializable
  data class Playback(val bookId: BookId) : Compose {
    override val trackingName: String get() = "Playback"
  }

  @Serializable
  data class EpubReader(val bookId: BookId) : Compose {
    override val trackingName: String get() = "EpubReader"
  }
```

- [ ] **Step 2: Write a failing test for the routing decision**

In `features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt`, add
`voice.core.data.BookSourceType` to the imports, add a `contentRepo: BookContentRepo = mockk()` parameter to the
existing `viewModel(...)` helper function (passed through to the `BookOverviewViewModel(...)` constructor call in
place of the currently-hardcoded `contentRepo = mockk<BookContentRepo>()`), and add these two tests (matching the
file's existing `mockk<Navigator>(relaxed = true)` + `verify { navigator.goTo(...) }` pattern, e.g. from `folder
picker click shows moved dialog instead of navigating`):

```kotlin
  @Test
  fun `onBookClick routes epub books to the epub reader`() = runTest {
    val bookId = BookId("content://epub-book")
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      contentRepo = mockk {
        coEvery { get(bookId) } returns mockk { every { sourceType } returns BookSourceType.Epub }
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.onBookClick(bookId)

    verify { navigator.goTo(Destination.EpubReader(bookId)) }
  }

  @Test
  fun `onBookClick routes audio books to the playback screen`() = runTest {
    val bookId = BookId("content://audio-book")
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      contentRepo = mockk {
        coEvery { get(bookId) } returns mockk { every { sourceType } returns BookSourceType.Audio }
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.onBookClick(bookId)

    verify { navigator.goTo(Destination.Playback(bookId)) }
  }
```

The class-level `dispatcherProvider` in this test file is backed by `UnconfinedTestDispatcher()`, so the
`scope.launch { }` inside `navigateToBook` (Step 4) runs eagerly to completion within the `onBookClick(bookId)`
call itself — no `runCurrent()`/`advanceUntilIdle()` needed before the `verify`.

- [ ] **Step 3: Run the tests and verify they fail**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.overview.BookOverviewViewModelTest"
```

Expected: compile failure or assertion failure — `Destination.EpubReader` doesn't exist yet before Step 1 is
applied, or `onBookClick` doesn't route conditionally yet.

- [ ] **Step 4: Implement the routing decision**

In `features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt`, add the
import `voice.core.data.BookSourceType`, then replace:

```kotlin
  fun onBookClick(id: BookId) {
    navigator.goTo(Destination.Playback(id))
  }
```

with:

```kotlin
  fun onBookClick(id: BookId) {
    navigateToBook(id)
  }
```

and replace the tail of `onSearchBookClick`:

```kotlin
    searchActive = false
    navigator.goTo(Destination.Playback(id))
  }
```

with:

```kotlin
    searchActive = false
    navigateToBook(id)
  }

  private fun navigateToBook(id: BookId) {
    scope.launch {
      val destination = if (contentRepo.get(id)?.sourceType == BookSourceType.Epub) {
        Destination.EpubReader(id)
      } else {
        Destination.Playback(id)
      }
      navigator.goTo(destination)
    }
  }
```

- [ ] **Step 5: Run the tests and verify they pass**

```bash
./gradlew :features:bookOverview:testDebugUnitTest --tests "voice.features.bookOverview.overview.BookOverviewViewModelTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Run the full `features:bookOverview` unit test suite**

```bash
./gradlew :features:bookOverview:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions in existing `BookOverviewViewModelTest` cases.

- [ ] **Step 7: Commit**

```bash
git add navigation/src/main/kotlin/voice/navigation/Destination.kt features/bookOverview/src/main/kotlin/voice/features/bookOverview/overview/BookOverviewViewModel.kt features/bookOverview/src/test/kotlin/voice/features/bookOverview/overview/BookOverviewViewModelTest.kt
git commit -m "Route epub books to a new EpubReader destination"
```

---

### Task 4: `features:epubReader` module scaffold + `EpubBookOpener`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `features/epubReader/build.gradle.kts`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`
- Create: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`

**Interfaces:**
- Consumes: `EpubImporter.import(bookId, file): EpubParseResult` (`core:scanner`, Plan 2),
  `EpubBookRepo.chapters(bookId): List<EpubChapter>` (`core:data`, Plan 2),
  `CachedDocumentFileFactory.create(uri): CachedDocumentFile` (`core:documentfile`, existing),
  `VoiceManager.availableVoices(): List<AvailableVoice>` / `install(voiceId): InstallResult` (`core:tts`, Plan 3),
  `BookRepository.updateBook(id, update)` (`core:data`, existing), `BookId.toUri()` (existing)
- Produces (for Task 5): `EpubBookOpener { suspend fun open(bookId: BookId): OpenResult }` where `OpenResult` is
  sealed: `Ready(chapters: List<EpubChapter>, voiceId: String)`, `Malformed(reason: String)`, `DrmProtected`,
  `VoiceInstallFailed(reason: String)`. Handles both "parse on first open" (Plan 2's deliberately-unwired trigger
  point) and "auto-assign a voice if none is set" in one place, so `EpubReaderViewModel` (Task 5) just calls
  `open()` once and branches on the result.

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`, add after `include(":core:tts")`:

```kotlin
include(":features:epubReader")
```

Create `features/epubReader/build.gradle.kts`:

```kotlin
plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.documentfile)
  implementation(projects.core.playback)
  implementation(projects.core.scanner)
  implementation(projects.core.tts)
  implementation(projects.core.ui)
  implementation(projects.navigation)

  testImplementation(projects.core.data.impl)
  testImplementation(libs.bundles.testing.jvm)
  testImplementation(libs.molecule)
}
```

`libs.molecule` is required separately from the `testing.jvm` bundle — that bundle includes `turbine` but not
`molecule`, and Task 6's ViewModel test needs `app.cash.molecule.launchMolecule`. Confirmed by reading
`features/playbackScreen/build.gradle.kts`, which declares the identical pair of test dependencies
(`testImplementation(libs.molecule)` alongside the bundle) for exactly this reason.

In `app/build.gradle.kts`, add after `implementation(projects.features.bookOverview)`:

```kotlin
  implementation(projects.features.epubReader)
```

- [ ] **Step 2: Write a failing test for `EpubBookOpener`**

Create `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`:

```kotlin
package voice.features.epubReader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.documentfile.FileBasedDocumentFile
import voice.core.epub.DefaultEpubParser
import voice.core.scanner.EpubImporter
import voice.core.tts.AvailableVoice
import voice.core.tts.InstallResult
import voice.core.tts.VoiceCatalogEntry
import voice.core.tts.VoiceManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class EpubBookOpenerTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val epubBookRepo = FakeEpubBookRepo()
  private val bookContentRepo = FakeBookContentRepo()
  private val defaultVoiceEntry = VoiceCatalogEntry(
    voiceId = "voice-a",
    name = "Voice A",
    language = "en_US",
    downloadUrl = "https://example.test/voice-a.tar.bz2",
    sizeBytes = 100L,
    sha256 = "0".repeat(64),
  )
  private var installResult: InstallResult = InstallResult.Success
  private var installCallCount = 0
  private val voiceManager = mockk<VoiceManager> {
    coEvery { availableVoices() } returns listOf(AvailableVoice(entry = defaultVoiceEntry, installed = false))
    coEvery { install(any()) } answers {
      installCallCount++
      installResult
    }
  }
  private val epubImporter = EpubImporter(
    context = context,
    epubParser = DefaultEpubParser(),
    epubBookRepo = epubBookRepo,
  )
  private val cachedDocumentFileFactory = object : CachedDocumentFileFactory {
    override fun create(uri: android.net.Uri) = FileBasedDocumentFile(File(uri.path!!))
  }
  private val opener = EpubBookOpener(
    epubImporter = epubImporter,
    epubBookRepo = epubBookRepo,
    bookContentRepo = bookContentRepo,
    voiceManager = voiceManager,
    cachedDocumentFileFactory = cachedDocumentFileFactory,
  )

  @Test
  fun `parses on first open and auto-assigns the first catalog voice`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = null))

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = "voice-a", actual = result.voiceId)
    assertEquals(expected = listOf("Chapter One"), actual = epubBookRepo.chapters(bookId).map { it.title })
    assertEquals(expected = "voice-a", actual = bookContentRepo.get(bookId)?.voiceId)
  }

  @Test
  fun `skips parsing when chapters already exist`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = "voice-a"))
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = emptyList(),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = listOf("Already Parsed"), actual = epubBookRepo.chapters(bookId).map { it.title })
  }

  @Test
  fun `skips voice assignment when a voice is already set`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = "voice-b"))

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = "voice-b", actual = result.voiceId)
    assertEquals(expected = 0, actual = installCallCount)
  }

  @Test
  fun `returns malformed without touching voice assignment when the file is not an epub`() = runTest {
    val file = File(testFolder.newFolder(), "not-a-book.epub")
    file.writeBytes(byteArrayOf(1, 2, 3))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = null))

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Malformed>(result)
    assertEquals(expected = 0, actual = installCallCount)
  }

  @Test
  fun `returns VoiceInstallFailed when the auto-assigned voice fails to install`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = null))
    installResult = InstallResult.Failure("network error")

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.VoiceInstallFailed>(result)
    assertEquals(expected = null, actual = bookContentRepo.get(bookId)?.voiceId)
  }

  private fun buildMinimalEpub(file: File): File {
    ZipOutputStream(file.outputStream()).use { zip ->
      fun entry(
        name: String,
        content: String,
      ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
      entry("mimetype", "application/epub+zip")
      entry(
        "META-INF/container.xml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent(),
      )
      entry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata/>
          <manifest>
            <item id="chapter0" href="chapter0.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="chapter0"/>
          </spine>
        </package>
        """.trimIndent(),
      )
      entry(
        "OEBPS/chapter0.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>Chapter One</title></head>
          <body><p>Hello there. This is chapter one.</p></body>
        </html>
        """.trimIndent(),
      )
    }
    return file
  }

  private fun bookContent(
    bookId: BookId,
    voiceId: String?,
  ) = voice.core.data.BookContent(
    id = bookId,
    playbackSpeed = 1F,
    skipSilence = false,
    isActive = true,
    lastPlayedAt = java.time.Instant.EPOCH,
    author = null,
    name = "Test Book",
    addedAt = java.time.Instant.EPOCH,
    chapters = listOf(voice.core.data.ChapterId(bookId.toUri())),
    currentChapter = voice.core.data.ChapterId(bookId.toUri()),
    positionInChapter = 0L,
    cover = null,
    gain = 0F,
    genre = null,
    narrator = null,
    series = null,
    part = null,
    sourceType = voice.core.data.BookSourceType.Epub,
    voiceId = voiceId,
  )

  private class FakeEpubBookRepo : EpubBookRepo {
    private val chapters = mutableMapOf<BookId, List<EpubChapter>>()
    private val sentences = mutableMapOf<BookId, List<EpubSentence>>()

    override suspend fun replaceChapters(
      bookId: BookId,
      chapters: List<EpubChapter>,
      sentences: List<EpubSentence>,
    ) {
      this.chapters[bookId] = chapters
      this.sentences[bookId] = sentences
    }

    override suspend fun chapters(bookId: BookId): List<EpubChapter> = chapters[bookId].orEmpty()

    override suspend fun sentences(
      bookId: BookId,
      chapterIndex: Int,
    ): List<EpubSentence> = sentences[bookId].orEmpty().filter { it.chapterIndex == chapterIndex }
  }

  private class FakeBookContentRepo : BookContentRepo {
    private val content = mutableMapOf<BookId, voice.core.data.BookContent>()

    override fun flow() = throw NotImplementedError()
    override suspend fun all() = content.values.toList()
    override fun flow(id: BookId) = throw NotImplementedError()
    override suspend fun get(id: BookId): voice.core.data.BookContent? = content[id]
    override suspend fun setAllInactiveExcept(ids: List<BookId>) {}
    override suspend fun put(content: voice.core.data.BookContent) {
      this.content[content.id] = content
    }
  }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: compile failure — `EpubBookOpener` doesn't exist yet, and `VoiceManager` may not be an interface yet
(see note in Step 4).

- [ ] **Step 4: Implement `EpubBookOpener`**

`VoiceManager` (Plan 3) is a concrete class with a public API (`availableVoices()`/`install()`/`uninstall()`), not
an interface — the test above mocks it with `mockk<VoiceManager>()` rather than a hand-written fake, matching how
Plan 3's own `VoiceManagerTest` mocks `TtsDownloader` the same way. `CachedDocumentFileFactory` (`core:documentfile`)
is a plain interface (`RealCachedDocumentFileFactory` is its `@ContributesBinding` implementation) — inject it
directly rather than injecting `Context` and constructing a document file factory by hand.

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`:

```kotlin
package voice.features.epubReader

import dev.zacsweers.metro.Inject
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.epub.EpubParseResult
import voice.core.scanner.EpubImporter
import voice.core.tts.InstallResult
import voice.core.tts.VoiceManager

@Inject
public class EpubBookOpener(
  private val epubImporter: EpubImporter,
  private val epubBookRepo: EpubBookRepo,
  private val bookContentRepo: BookContentRepo,
  private val voiceManager: VoiceManager,
  private val cachedDocumentFileFactory: CachedDocumentFileFactory,
) {

  public sealed interface OpenResult {
    public data class Ready(
      val chapters: List<EpubChapter>,
      val voiceId: String,
    ) : OpenResult
    public data class Malformed(val reason: String) : OpenResult
    public data object DrmProtected : OpenResult
    public data class VoiceInstallFailed(val reason: String) : OpenResult
  }

  public suspend fun open(bookId: BookId): OpenResult {
    var chapters = epubBookRepo.chapters(bookId)
    if (chapters.isEmpty()) {
      val documentFile = cachedDocumentFileFactory.create(bookId.toUri())
      when (val result = epubImporter.import(bookId, documentFile)) {
        is EpubParseResult.Malformed -> return OpenResult.Malformed(result.reason)
        EpubParseResult.DrmProtected -> return OpenResult.DrmProtected
        is EpubParseResult.Success -> Unit
      }
      chapters = epubBookRepo.chapters(bookId)
    }

    val content = bookContentRepo.get(bookId) ?: return OpenResult.Malformed("book not found: $bookId")
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
}
```

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 6: Verify the app still assembles**

```bash
./gradlew :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts features/epubReader/build.gradle.kts features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt
git commit -m "Add features:epubReader module with EpubBookOpener"
```

---

### Task 5: `EpubPlaylistController` — the windowed sentence-clip scheduler

**Files:**
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaybackControl.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaylistController.kt`
- Create: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubPlaylistControllerTest.kt`

**Interfaces:**
- Consumes: `EpubBookRepo.sentences(bookId, chapterIndex): List<EpubSentence>` (Plan 2),
  `SentenceClipCache.getOrSynthesize(bookId, voiceId, chapterIndex, sentenceIndex, text): ClipResult` (Plan 3),
  `PlayerController.setEpubPlaylist`/`currentMediaItemIndexFlow`/`toggleEpubPlayPause` (Task 2)
- Produces (for Task 6): `EpubPlaylistController { suspend fun start(bookId, voiceId, chapterIndex,
  sentenceIndex); fun currentSentenceFlow(): Flow<Pair<Int, Int>?>; fun togglePlayPause() }` — starts windowed
  playback from a given position, exposes the currently-playing `(chapterIndex, sentenceIndex)` for the reader UI
  to highlight, and toggles play/pause without touching the window. `EpubPlaybackControl` is the thin interface
  `EpubPlaylistController` depends on instead of `PlayerController` directly, so its tests don't need a real
  `MediaController`/`MediaSession`.

- [ ] **Step 1: Add the thin playback-control interface and its real implementation**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaybackControl.kt`:

```kotlin
package voice.features.epubReader

import androidx.media3.common.MediaItem
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import voice.core.playback.PlayerController

public interface EpubPlaybackControl {
  public fun setPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  )

  public fun currentMediaItemIndexFlow(): Flow<Int>

  public fun togglePlayPause()
}

@Inject
@ContributesBinding(AppScope::class)
public class RealEpubPlaybackControl(private val playerController: PlayerController) : EpubPlaybackControl {

  override fun setPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    playerController.setEpubPlaylist(mediaItems, startIndex, startPositionMs)
  }

  override fun currentMediaItemIndexFlow(): Flow<Int> = playerController.currentMediaItemIndexFlow()

  override fun togglePlayPause() {
    playerController.toggleEpubPlayPause()
  }
}
```

- [ ] **Step 2: Write a failing test for `EpubPlaylistController`**

Create `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubPlaylistControllerTest.kt`:

```kotlin
package voice.features.epubReader

import androidx.media3.common.MediaItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.tts.ClipResult
import voice.core.tts.SentenceClipCache
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class EpubPlaylistControllerTest {

  private val scope = TestScope()
  private val bookId = BookId("content://book1")
  private val voiceId = "en_US-amy-medium"
  private val epubBookRepo = FakeEpubBookRepo()
  private val failingSentences = mutableSetOf<Pair<Int, Int>>()
  private val sentenceClipCache = mockk<SentenceClipCache> {
    coEvery { getOrSynthesize(any(), any(), any(), any(), any()) } answers {
      val chapterIndex = thirdArg<Int>()
      val sentenceIndex = fourthArg<Int>()
      if (chapterIndex to sentenceIndex in failingSentences) {
        ClipResult.Failure("synthesis failed")
      } else {
        ClipResult.Success(File("clips/$chapterIndex-$sentenceIndex.wav"))
      }
    }
  }

  private val playbackControl = FakePlaybackControl()
  private val controller = EpubPlaylistController(
    epubBookRepo = epubBookRepo,
    sentenceClipCache = sentenceClipCache,
    playbackControl = playbackControl,
    dispatcherProvider = DispatcherProvider(
      scope.backgroundScope.coroutineContext,
      scope.backgroundScope.coroutineContext,
      scope.backgroundScope.coroutineContext,
    ),
  )

  @Test
  fun `start synthesizes the initial window and sets it as the playlist`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 5)

    controller.start(bookId, voiceId, chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = 5, actual = playbackControl.lastPlaylist?.size)
    assertEquals(expected = 0, actual = playbackControl.lastStartIndex)
  }

  @Test
  fun `currentSentenceFlow maps the media item index back to chapter and sentence`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 5)
    controller.start(bookId, voiceId, chapterIndex = 0, sentenceIndex = 0)

    playbackControl.currentIndex.value = 2
    runCurrent()

    assertEquals(expected = 0 to 2, actual = controller.currentSentenceFlow().value)
  }

  @Test
  fun `skips a sentence that fails to synthesize without breaking the window`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 3)
    failingSentences += 0 to 1

    controller.start(bookId, voiceId, chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = 2, actual = playbackControl.lastPlaylist?.size)
  }

  @Test
  fun `reload is not triggered before reaching the window's reload margin`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 30)
    controller.start(bookId, voiceId, chapterIndex = 0, sentenceIndex = 0)
    val setPlaylistCallsBefore = playbackControl.setPlaylistCallCount

    playbackControl.currentIndex.value = 10 // well before index 25 (window size 30 - reload margin 5)
    runCurrent()

    assertEquals(expected = setPlaylistCallsBefore, actual = playbackControl.setPlaylistCallCount)
  }

  @Test
  fun `reloads the window once playback nears its end`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 30)
    epubBookRepo.seed(chapterIndex = 1, sentenceCount = 10)
    controller.start(bookId, voiceId, chapterIndex = 0, sentenceIndex = 0)
    val setPlaylistCallsBefore = playbackControl.setPlaylistCallCount

    playbackControl.currentIndex.value = 26 // >= 25 (window size 30 - reload margin 5), triggers a reload
    runCurrent()

    assertEquals(expected = setPlaylistCallsBefore + 1, actual = playbackControl.setPlaylistCallCount)
  }

  private class FakeEpubBookRepo : EpubBookRepo {
    private val bySentenceIndex = mutableMapOf<Int, List<EpubSentence>>()

    fun seed(
      chapterIndex: Int,
      sentenceCount: Int,
    ) {
      bySentenceIndex[chapterIndex] = (0 until sentenceCount).map { index ->
        EpubSentence(bookId = BookId("content://book1"), chapterIndex = chapterIndex, index = index, text = "Sentence $index.")
      }
    }

    override suspend fun replaceChapters(
      bookId: BookId,
      chapters: List<voice.core.data.EpubChapter>,
      sentences: List<EpubSentence>,
    ) = error("not used in this test")

    override suspend fun chapters(bookId: BookId) = error("not used in this test")

    override suspend fun sentences(
      bookId: BookId,
      chapterIndex: Int,
    ): List<EpubSentence> = bySentenceIndex[chapterIndex].orEmpty()
  }

  private class FakePlaybackControl : EpubPlaybackControl {
    var lastPlaylist: List<MediaItem>? = null
    var lastStartIndex: Int? = null
    var setPlaylistCallCount = 0
    var togglePlayPauseCallCount = 0
    val currentIndex = MutableStateFlow(0)

    override fun setPlaylist(
      mediaItems: List<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ) {
      lastPlaylist = mediaItems
      lastStartIndex = startIndex
      setPlaylistCallCount++
    }

    override fun currentMediaItemIndexFlow() = currentIndex

    override fun togglePlayPause() {
      togglePlayPauseCallCount++
    }
  }
}
```

`SentenceClipCache` (Plan 3) is a concrete class, mocked above with `mockk<SentenceClipCache>()`, matching the same
reasoning as `VoiceManager` in Task 4. `EpubBookRepo` is a confirmed interface (Plan 2), so `FakeEpubBookRepo :
EpubBookRepo` is a plain hand-written fake as written.

**The `DispatcherProvider` must be built from `scope.backgroundScope.coroutineContext`, not `scope.coroutineContext`
directly.** `EpubPlaylistController.start()` launches an internal collector
(`scope.launch { playbackControl.currentMediaItemIndexFlow().collect { ... } }`) that runs for the controller's
whole lifetime by design — it's what makes the controller self-driving in production (see Step 4). `runTest`
requires every coroutine launched in the *regular* test scope to complete before the test body returns; a
deliberately-infinite collector launched there fails every test with `UncompletedCoroutinesError`, confirmed while
validating this plan. `backgroundScope` is exempt from that requirement by design (the same reason Molecule tests
use `backgroundScope.launchMolecule` rather than the plain test scope) — using it here for all three
`DispatcherProvider` slots fixes it. This bites again in Task 6, whose `EpubReaderViewModel` launches an
analogous infinite collector in `init`.

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubPlaylistControllerTest"
```

Expected: compile failure — `EpubPlaylistController` doesn't exist yet.

- [ ] **Step 4: Implement `EpubPlaylistController`**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaylistController.kt`:

```kotlin
package voice.features.epubReader

import androidx.media3.common.MediaItem
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.logging.api.Logger
import voice.core.tts.ClipResult
import voice.core.tts.SentenceClipCache

@Inject
public class EpubPlaylistController(
  private val epubBookRepo: EpubBookRepo,
  private val sentenceClipCache: SentenceClipCache,
  private val playbackControl: EpubPlaybackControl,
  dispatcherProvider: DispatcherProvider,
) {

  private data class WindowEntry(
    val chapterIndex: Int,
    val sentenceIndex: Int,
  )

  private val scope = MainScope(dispatcherProvider)
  private var indexCollectionJob: Job? = null

  private var bookId: BookId? = null
  private var voiceId: String? = null
  private var window: List<WindowEntry> = emptyList()

  private val currentSentence = MutableStateFlow<Pair<Int, Int>?>(null)

  public fun currentSentenceFlow(): StateFlow<Pair<Int, Int>?> = currentSentence

  public suspend fun start(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ) {
    this.bookId = bookId
    this.voiceId = voiceId
    val (mediaItems, entries) = loadWindow(bookId, voiceId, chapterIndex, sentenceIndex)
    window = entries
    currentSentence.value = entries.firstOrNull()?.let { it.chapterIndex to it.sentenceIndex }
    playbackControl.setPlaylist(mediaItems, startIndex = 0, startPositionMs = 0)
    indexCollectionJob?.cancel()
    indexCollectionJob = scope.launch {
      playbackControl.currentMediaItemIndexFlow().collect { index ->
        onCurrentMediaItemIndexChanged(index)
      }
    }
  }

  public fun togglePlayPause() {
    playbackControl.togglePlayPause()
  }

  public suspend fun onCurrentMediaItemIndexChanged(index: Int) {
    val entry = window.getOrNull(index) ?: return
    currentSentence.value = entry.chapterIndex to entry.sentenceIndex
    if (index >= window.size - RELOAD_MARGIN) {
      reload(entry)
    }
  }

  private suspend fun reload(from: WindowEntry) {
    val bookId = bookId ?: return
    val voiceId = voiceId ?: return
    val next = nextPosition(from) ?: return
    val (mediaItems, entries) = loadWindow(bookId, voiceId, next.chapterIndex, next.sentenceIndex)
    if (entries.isEmpty()) return
    window = entries
    playbackControl.setPlaylist(mediaItems, startIndex = 0, startPositionMs = 0)
  }

  private fun nextPosition(entry: WindowEntry): WindowEntry? {
    return WindowEntry(entry.chapterIndex, entry.sentenceIndex + 1)
  }

  private suspend fun loadWindow(
    bookId: BookId,
    voiceId: String,
    startChapterIndex: Int,
    startSentenceIndex: Int,
  ): Pair<List<MediaItem>, List<WindowEntry>> {
    val mediaItems = mutableListOf<MediaItem>()
    val entries = mutableListOf<WindowEntry>()
    var chapterIndex = startChapterIndex
    var sentenceIndex = startSentenceIndex
    var sentences = epubBookRepo.sentences(bookId, chapterIndex)

    while (entries.size < WINDOW_SIZE) {
      if (sentenceIndex >= sentences.size) {
        chapterIndex += 1
        sentenceIndex = 0
        sentences = epubBookRepo.sentences(bookId, chapterIndex)
        if (sentences.isEmpty()) break
        continue
      }
      val sentence: EpubSentence = sentences[sentenceIndex]
      when (val result = sentenceClipCache.getOrSynthesize(bookId, voiceId, chapterIndex, sentenceIndex, sentence.text)) {
        is ClipResult.Success -> {
          mediaItems += MediaItem.Builder()
            .setUri(result.file.toURI().toString())
            .build()
          entries += WindowEntry(chapterIndex, sentenceIndex)
        }
        is ClipResult.Failure -> {
          Logger.w("Skipping sentence chapter=$chapterIndex sentence=$sentenceIndex: ${result.reason}")
        }
      }
      sentenceIndex += 1
    }

    return mediaItems to entries
  }

  private companion object {
    const val WINDOW_SIZE = 30
    const val RELOAD_MARGIN = 5
  }
}
```

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubPlaylistControllerTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 6: Run the full `features:epubReader` unit test suite**

```bash
./gradlew :features:epubReader:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions from Task 4.

- [ ] **Step 7: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaybackControl.kt features/epubReader/src/main/kotlin/voice/features/epubReader/EpubPlaylistController.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubPlaylistControllerTest.kt
git commit -m "Add EpubPlaylistController for windowed sentence-clip playback"
```

---

### Task 6: `EpubReaderViewModel` + Compose screen

**Files:**
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`
- Create: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `EpubBookOpener` (Task 4), `EpubPlaylistController` (Task 5), `EpubBookRepo.sentences` (Plan 2),
  `BookRepository.updateBook` (existing)
- Produces: the actual reader screen, registered against `Destination.EpubReader` (Task 3).

- [ ] **Step 1: Add the view state**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`:

```kotlin
package voice.features.epubReader

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
  ) : EpubReaderViewState

  public data class ChapterEntry(
    val index: Int,
    val title: String,
  )
}
```

- [ ] **Step 2: Write a failing test for the open→ready→highlight flow**

Create `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`, mirroring
`BookPlayViewModelTest.kt`'s `launchMolecule`/Turbine pattern and its `viewModel(...)` fixture-factory style:

```kotlin
package voice.features.epubReader

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.ChapterId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.BookRepository
import voice.core.data.repo.EpubBookRepo
import voice.core.playback.playstate.PlayStateManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EpubReaderViewModelTest {

  private val scope = TestScope()
  private val bookId = BookId("content://book1")
  private val currentSentenceFlow = MutableStateFlow<Pair<Int, Int>?>(null)
  private val epubPlaylistController = mockk<EpubPlaylistController> {
    coEvery { start(any(), any(), any(), any()) } just Runs
    every { currentSentenceFlow() } returns currentSentenceFlow
    every { togglePlayPause() } just Runs
  }
  private val epubBookRepo = mockk<EpubBookRepo> {
    coEvery { sentences(bookId, 0) } returns listOf(
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello."),
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "World."),
    )
  }
  private val bookRepository = mockk<BookRepository> {
    coEvery { get(bookId) } returns book()
    coEvery { updateBook(bookId, any()) } just Runs
  }
  private val playStateManager = PlayStateManager()

  private fun book() = Book(
    content = BookContent(
      id = bookId,
      playbackSpeed = 1F,
      skipSilence = false,
      isActive = true,
      lastPlayedAt = Instant.EPOCH,
      author = null,
      name = "Test Book",
      addedAt = Instant.EPOCH,
      chapters = listOf(ChapterId(bookId.value)),
      currentChapter = ChapterId(bookId.value),
      positionInChapter = 0L,
      cover = null,
      gain = 0F,
      genre = null,
      narrator = null,
      series = null,
      part = null,
      sourceType = BookSourceType.Epub,
      voiceId = "voice-a",
    ),
    chapters = listOf(
      voice.core.data.Chapter(
        id = ChapterId(bookId.value),
        name = "Test Book",
        duration = 0L,
        fileLastModified = Instant.EPOCH,
        fileSize = 0L,
        markData = emptyList(),
      ),
    ),
  )

  private fun viewModel(
    openResult: EpubBookOpener.OpenResult = EpubBookOpener.OpenResult.Ready(
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Chapter One")),
      voiceId = "voice-a",
    ),
  ): EpubReaderViewModel {
    return EpubReaderViewModel(
      epubBookOpener = mockk { coEvery { open(bookId) } returns openResult },
      epubPlaylistController = epubPlaylistController,
      epubBookRepo = epubBookRepo,
      bookRepository = bookRepository,
      playStateManager = playStateManager,
      dispatcherProvider = DispatcherProvider(
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
      ),
      bookId = bookId,
    )
  }

  @Test
  fun `starts loading then becomes content once the book opens`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = EpubReaderViewState.Loading, actual = awaitItem())
      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      assertEquals(expected = "Test Book", actual = state.bookTitle)
      assertEquals(expected = listOf("Hello.", "World."), actual = state.sentences)
      assertEquals(expected = listOf(EpubReaderViewState.ChapterEntry(0, "Chapter One")), actual = state.chapters)
    }
  }

  @Test
  fun `active sentence index follows the playlist controller's current sentence`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // initial Content, activeSentenceIndex = 0

      currentSentenceFlow.value = 0 to 1

      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      assertEquals(expected = 1, actual = state.activeSentenceIndex)
    }
  }

  @Test
  fun `malformed open result produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.Malformed("bad epub"))

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertEquals(expected = EpubReaderViewState.Error("bad epub"), actual = awaitItem())
    }
  }

  @Test
  fun `drm protected open result produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.DrmProtected)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertIs<EpubReaderViewState.Error>(awaitItem())
    }
  }

  @Test
  fun `voice install failure produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.VoiceInstallFailed("network error"))

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertEquals(expected = EpubReaderViewState.Error("network error"), actual = awaitItem())
    }
  }

  @Test
  fun `playPause toggles playback through the playlist controller`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.playPause()

    verify { epubPlaylistController.togglePlayPause() }
  }
}
```

**`ChapterId(bookId.value)`, not `ChapterId(bookId.toUri())`, in the `book()` fixture.** This test class has no
`@RunWith(AndroidJUnit4::class)` (Molecule/Turbine tests don't need Robolectric the way Room/`CachedDocumentFile`
tests do), so `android.net.Uri`-backed calls like `.toUri()` aren't shadowed and throw a `NullPointerException` at
fixture-construction time — confirmed while validating this plan (all 6 tests failed with an NPE inside `book()`
before this fix). `ChapterId` has a plain-`String` constructor specifically for cases like this; `BookPlayViewModelTest`'s
own `chapter()` fixture already uses it (`ChapterId("http://...")`, never `.toUri()`) for the same reason.

**`playStateManager = PlayStateManager()`, a real instance, not a mockk stub.** An earlier version of this test
mocked it with `mockk<PlayStateManager> { every { this@mockk.playStateFlow } returns playStateFlow; every {
playState } returns playStateFlow.value }`, mirroring `BookPlayViewModelTest`'s own stubbing pattern for the same
class — but that specific stub setup failed at runtime with `io.mockk.MockKException: no answer provided for
PlayStateManager(#N).getPlayStateFlow()`, confirmed while validating this plan. `PlayStateManager` is a trivial,
dependency-free, real `@Inject class` with a no-arg constructor, and `BookOverviewViewModelTest`'s own `viewModel(...)`
helper already uses a real instance (`playStateManager = PlayStateManager()`) rather than mocking it — the simpler,
already-precedented choice, and it sidesteps the stubbing failure entirely. None of this task's tests need to
control `isPlaying` directly, so the default `Paused` state is sufficient; a future test that does can just call
`.playState = PlayStateManager.PlayState.Playing` on the real instance rather than reaching for a mock.

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: compile failure — `EpubReaderViewModel` doesn't exist yet.

- [ ] **Step 4: Implement `EpubReaderViewModel`**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`, following
`BookPlayViewModel`'s exact shape (`@AssistedInject` constructor, `@Assisted bookId: BookId`,
`private val scope = MainScope(dispatcherProvider)`, a `@Composable fun viewState()`, an `@AssistedFactory
interface Factory { fun create(bookId: BookId): EpubReaderViewModel }`):

```kotlin
package voice.features.epubReader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.repo.EpubBookRepo
import voice.core.playback.playstate.PlayStateManager

@AssistedInject
public class EpubReaderViewModel(
  private val epubBookOpener: EpubBookOpener,
  private val epubPlaylistController: EpubPlaylistController,
  private val epubBookRepo: EpubBookRepo,
  private val bookRepository: BookRepository,
  private val playStateManager: PlayStateManager,
  dispatcherProvider: DispatcherProvider,
  @Assisted
  private val bookId: BookId,
) {

  private val scope = MainScope(dispatcherProvider)

  private sealed interface OpenState {
    data object Loading : OpenState
    data class Failed(val message: String) : OpenState
    data class Ready(
      val bookTitle: String,
      val chapters: List<EpubReaderViewState.ChapterEntry>,
      val sentences: List<String>,
    ) : OpenState
  }

  private val openState = MutableStateFlow<OpenState>(OpenState.Loading)
  private var voiceId: String? = null
  private var activeChapterIndex = 0

  init {
    scope.launch {
      when (val result = epubBookOpener.open(bookId)) {
        is EpubBookOpener.OpenResult.Ready -> {
          voiceId = result.voiceId
          val bookTitle = bookRepository.get(bookId)?.content?.name.orEmpty()
          val chapters = result.chapters.map { EpubReaderViewState.ChapterEntry(index = it.index, title = it.title) }
          val sentences = epubBookRepo.sentences(bookId, chapterIndex = 0).map { it.text }
          openState.value = OpenState.Ready(bookTitle, chapters, sentences)
          epubPlaylistController.start(
            bookId = bookId,
            voiceId = result.voiceId,
            chapterIndex = 0,
            sentenceIndex = 0,
          )
        }
        is EpubBookOpener.OpenResult.Malformed -> {
          openState.value = OpenState.Failed(result.reason)
        }
        EpubBookOpener.OpenResult.DrmProtected -> {
          openState.value = OpenState.Failed("This book is DRM-protected and can't be read.")
        }
        is EpubBookOpener.OpenResult.VoiceInstallFailed -> {
          openState.value = OpenState.Failed(result.reason)
        }
      }
    }
    scope.launch {
      epubPlaylistController.currentSentenceFlow().collect { position ->
        if (position != null) {
          val (chapterIndex, sentenceIndex) = position
          if (chapterIndex != activeChapterIndex) {
            activeChapterIndex = chapterIndex
            updateSentencesForChapter(chapterIndex)
          }
          bookRepository.updateBook(bookId) {
            it.copy(currentEpubChapterIndex = chapterIndex, currentEpubSentenceIndex = sentenceIndex)
          }
        }
      }
    }
  }

  public fun playPause() {
    epubPlaylistController.togglePlayPause()
  }

  public fun onChapterSelect(chapterIndex: Int) {
    val voiceId = voiceId ?: return
    activeChapterIndex = chapterIndex
    scope.launch {
      updateSentencesForChapter(chapterIndex)
      epubPlaylistController.start(bookId, voiceId, chapterIndex, sentenceIndex = 0)
    }
  }

  private suspend fun updateSentencesForChapter(chapterIndex: Int) {
    val current = openState.value
    if (current is OpenState.Ready) {
      openState.value = current.copy(sentences = epubBookRepo.sentences(bookId, chapterIndex).map { it.text })
    }
  }

  @Composable
  public fun viewState(): EpubReaderViewState {
    return when (val state = openState.collectAsState().value) {
      OpenState.Loading -> EpubReaderViewState.Loading
      is OpenState.Failed -> EpubReaderViewState.Error(state.message)
      is OpenState.Ready -> {
        val currentSentence = epubPlaylistController.currentSentenceFlow().collectAsState().value
        val playing = playStateManager.playStateFlow.collectAsState().value == PlayStateManager.PlayState.Playing
        EpubReaderViewState.Content(
          bookTitle = state.bookTitle,
          sentences = state.sentences,
          activeSentenceIndex = currentSentence?.second ?: 0,
          failedSentenceIndices = emptySet(),
          isPlaying = playing,
          chapters = state.chapters,
        )
      }
    }
  }

  @AssistedFactory
  public interface Factory {
    public fun create(bookId: BookId): EpubReaderViewModel
  }
}
```

`viewState()` is a genuinely reactive composable deriving from `collectAsState()` on `StateFlow`s, matching
`BookPlayViewModel.viewState()`'s real shape — not an imperative "reassign a stored `State` field from inside a
coroutine" pattern (an earlier draft of this plan used that shape, but a stored `var` reassignment doesn't
participate in Compose's snapshot/recomposition system the way a `StateFlow` read via `collectAsState()` does;
this was caught and fixed during plan-writing). `sentences` lives inside `OpenState.Ready` and is updated in the
same write as everything else when the chapter changes, rather than as a separately-timed `StateFlow`, so a chapter
transition produces one atomic `Content` emission, not a briefly-empty-then-populated flash.

`playStateManager.playStateFlow` is the same book-type-agnostic global playback-state signal `BookOverviewViewModel`
already uses for its own list-level play/pause icon — safe to reuse here directly, no new plumbing needed.
`playPause()` calls `epubPlaylistController.togglePlayPause()` (Task 5) rather than the existing generic
`PlayerController.playPause()` — see Task 2's note on why that one is unsafe to reuse for EPUB playback.
`onChapterSelect` re-starts the playlist controller at the new chapter's first sentence — the same mechanism
`start()` already uses, matching this plan's Data Flow decision to always restart the window from the current
position rather than mutate it in place for anything other than the ordinary forward-sliding case.

**`failedSentenceIndices` is always empty in this plan.** `EpubPlaylistController` (Task 5) currently only logs a
skipped sentence (`Logger.w(...)` in `loadWindow`) rather than reporting it anywhere a UI could observe. Wiring
real failed-sentence tracking end-to-end (a flow of failed `(chapterIndex, sentenceIndex)` pairs, scoped to the
currently-displayed chapter) is a small, natural follow-up, deferred here rather than guessed at without a concrete
UI design for how a "this sentence failed" marker should look — flag this to your human partner rather than
inventing one. The field stays in `EpubReaderViewState.Content` so the UI layer (Step 6) has a stable place to
render it once it's wired.

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Build the Compose screen and register the destination**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`:

```kotlin
package voice.features.epubReader.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import voice.features.epubReader.EpubReaderViewState

@Composable
public fun EpubReaderView(
  viewState: EpubReaderViewState,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
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
  modifier: Modifier = Modifier,
) {
  var chapterMenuExpanded by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  LaunchedEffect(viewState.activeSentenceIndex) {
    listState.animateScrollToItem(viewState.activeSentenceIndex)
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(viewState.bookTitle) },
        actions = {
          Box {
            Text(
              text = "Chapters",
              modifier = Modifier
                .clickable { chapterMenuExpanded = true }
                .padding(8.dp),
            )
            DropdownMenu(
              expanded = chapterMenuExpanded,
              onDismissRequest = { chapterMenuExpanded = false },
            ) {
              viewState.chapters.forEach { chapter ->
                DropdownMenuItem(
                  text = { Text(chapter.title) },
                  onClick = {
                    chapterMenuExpanded = false
                    onChapterSelect(chapter.index)
                  },
                )
              }
            }
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = onPlayPauseClick) {
        Text(if (viewState.isPlaying) "Pause" else "Play")
      }
    },
  ) { contentPadding ->
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      itemsIndexed(viewState.sentences) { index, sentence ->
        val isActive = index == viewState.activeSentenceIndex
        Text(
          text = sentence,
          modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(
              if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            ),
          color = if (isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onBackground
          },
        )
      }
    }
  }
}
```

`EpubReaderView` and `EpubReaderContent` both take a `modifier: Modifier = Modifier` parameter (this codebase's
ktlint config enforces `compose:modifier-missing-check` on public composables emitting content), threaded into
`Scaffold`.

Text-label play/pause button rather than an icon — avoids depending on whichever icon set happens to bundle a
"pause" glyph, keeping this screen's dependencies to what's already pulled in. Swap for an icon later if desired;
not a functional requirement.

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`, mirroring
`BookPlayController.kt`'s exact structure:

```kotlin
package voice.features.epubReader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.features.epubReader.view.EpubReaderView
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@Composable
public fun EpubReaderScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<EpubReaderGraph>()
      .epubReaderViewModelFactory
      .create(bookId)
  }
  val viewState = viewModel.viewState()
  EpubReaderView(
    viewState = viewState,
    onPlayPauseClick = viewModel::playPause,
    onChapterSelect = viewModel::onChapterSelect,
  )
}

@ContributesTo(AppScope::class)
public interface EpubReaderGraph {
  public val epubReaderViewModelFactory: EpubReaderViewModel.Factory
}

@ContributesTo(AppScope::class)
public interface EpubReaderProvider {

  @Provides
  @IntoSet
  public fun epubReaderNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.EpubReader> { key ->
    NavEntry(key) {
      EpubReaderScreen(bookId = key.bookId)
    }
  }
}
```

(`playPause`/`onChapterSelect` here assume you completed those methods on `EpubReaderViewModel` in Step 4 —
adjust the call sites to whatever you actually named them.)

- [ ] **Step 7: Run the full `features:epubReader` unit test suite**

```bash
./gradlew :features:epubReader:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 8: Verify the whole project still builds**

```bash
./gradlew :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Manual on-device verification**

Install the debug build on a real device (per the toolchain notes: real Pixel via `adb`, or the shared MSI App
Player emulator — ask before using the latter). Open a real `.epub` from the library: confirm it routes to the
reader screen (not the audiobook player), narration starts automatically with the auto-assigned voice, the active
sentence highlights and scrolls as playback advances, the app can be backgrounded with lock-screen controls
working, and reopening the book resumes at the same sentence. This is the first point this whole feature can be
verified end-to-end on a real device — Plan 3's `SherpaOnnxSynthesisEngine` was explicitly deferred to this check.

- [ ] **Step 10: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt
git commit -m "Add EpubReaderViewModel and the read-along Compose screen"
```

---

## What's next

After this plan, opening an EPUB from the library routes to a dedicated read-along screen: it parses on first
open, auto-assigns a voice, plays sentence-by-sentence narration through the same background-playback
infrastructure audiobooks use, and highlights the active sentence in sync. Resuming a book picks up at the exact
sentence it left off. What's still missing, deliberately deferred to Plan 5: any UI to pick a different voice or
manage installed voices, a setting for the clip-cache size cap, and app icon/branding polish for debug builds
(noted since Plan 1). `core:playback`'s audiobook code is unchanged throughout.
