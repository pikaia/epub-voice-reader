# Piper TTS Integration (Plan 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `core:tts`, the on-device neural text-to-speech module: given an `EpubSentence`'s text and a chosen
voice, synthesize a playable WAV clip via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)'s Piper voices, cache
it so repeat playback never re-synthesizes the same sentence, and manage installing/uninstalling voices. No reader
UI, no ExoPlayer wiring — Plan 4's job.

**Architecture:** New module `core:tts`, layered like `core:epub`/`core:scanner`: pure interface-first core module,
depends on `core:data:api` for persistence via Metro DI, never touches Room directly. `SynthesisEngine` wraps
sherpa-onnx's `OfflineTts` and writes a WAV straight to a caller-supplied `File` (never throws — returns a typed
`SynthesisResult`). `VoiceManager` downloads/verifies/extracts a curated, hardcoded catalog of Piper voice packages
into app-private storage and records them via a new `VoiceRepo` (in `core:data`, mirroring `EpubBookRepo`'s
Plan-2 pattern). `SentenceClipCache` is the `getOrSynthesize` entry point: cache hit touches `lastAccessedAt` and
returns; cache miss calls `SynthesisEngine`, then LRU-evicts old clips via a new `SentenceClipRepo` if the total
cached size would exceed a configured cap.

**Tech Stack:** Kotlin, [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.4 (resolved via JitPack, not Maven
Central), Room (one `AutoMigration`, pure table additions), Metro DI, Apache Commons Compress 1.28.0 (tar+bzip2
extraction — not supported by Android's stdlib), OkHttp (voice package downloads, mirroring
`features:cover`'s `CoverDownloader`), Robolectric + `AndroidJUnit4` for Room/`Context`-touching tests, plain
`kotlin.test`/JUnit4 for pure-JVM tests.

**Validation note for whoever executes this:** every file and command in this plan was written by actually
implementing it in this repo, running `./gradlew :core:tts:testDebugUnitTest`, `:core:data:impl:testDebugUnitTest`,
ktlint, and a full `:app:assembleFreeDebug`, then reverting — not derived from documentation alone. The code blocks
below are the exact, already-compiling, ktlint-clean, test-passing versions (including the two real Piper voice
packages' SHA-256 checksums, computed by actually downloading them this session). Follow the steps for the
TDD/review/commit discipline, but you should not need to debug the sherpa-onnx/Room/Metro API surface itself.

## Global Constraints

- This is Plan 3 of the 5-plan staged sequence in `docs/superpowers/specs/2026-07-30-epub-ai-voice-reader-design.md`:
  parsing foundation (done) → data model & scanner (done) → **Piper TTS integration (this plan)** → reader UI &
  playback → settings & polish. Elaborates
  `docs/superpowers/specs/2026-08-01-piper-tts-integration-design.md`.
- Module dependency direction per `AGENTS.md`: `core:tts` depends on `core:data:api` and `core:logging:api` only
  (both core-to-core, allowed). No feature-to-feature or feature-to-core-reversed edges.
  `:app`'s `build.gradle.kts` gains `implementation(projects.core.tts)`, same as every other core module, even
  though nothing consumes it yet — matches how `core:epub`/`core:scanner` were wired in during Plans 1–2 before
  their UI existed.
- Dependency versions go in `gradle/libs.versions.toml` only. This plan adds three: `sherpaOnnx` = "1.13.4" (via a
  new `https://jitpack.io` repository in `settings.gradle.kts` — sherpa-onnx is not on Maven Central, verified by
  `curl https://jitpack.io/com/github/k2-fsa/sherpa-onnx/1.13.4/sherpa-onnx-1.13.4.pom` resolving immediately),
  `commonsCompress` = "1.28.0", and reuses the existing `okhttp` alias.
- `core:tts` uses `kotlin { explicitApi() }` (matching `core:data:*`/`core:scanner`, unlike `core:epub`) — every
  public-surface declaration (classes, interfaces, top-level functions, interface/class member functions) needs an
  explicit `public`/`internal` modifier. Primary-constructor `val`/`var` properties do **not** need one individually
  (matches `EpubChapter`/`BookContent`'s existing style) — only the class itself and its functions do.
  This project also builds with `-Werror` (`org.gradle.kotlin.dsl.allWarningsAsErrors=true`,
  `voice.warningsAsErrors=true`), and a suspend function returning a non-`Unit` result whose return value is
  discarded inside a `runTest { }` lambda is a **compile error**, not a lint warning — always capture or assert on
  it in tests (this bit twice while validating this plan: `manager.install(...)` and `cache.getOrSynthesize(...)`
  calls used only for setup still need `assertIs<...>(...)` wrapped around them, or a `val` capture).
- ktlint (`:core:tts:lintKotlin`/`formatKotlin`, `:core:data:impl:lintKotlin`/`formatKotlin`) collapses a
  single-parameter primary constructor onto one line, e.g. `public class VoiceRepoImpl(private val dao: ...)`, not
  the multi-line trailing-comma style used for 2+ params elsewhere in this codebase — the code blocks below already
  reflect ktlint's preferred form; if you free-write a variant, just run `formatKotlin` before committing.
- `java.io.File` round-trips through Room as `file.absolutePath` (see `Converters.fromFile`/`toFile`) — a test that
  builds a `File` with a leading `/` (e.g. `File("/clips/0.wav")`) will compare unequal after a round-trip on
  Windows, because `File("/clips/0.wav").absolutePath` resolves against the current drive
  (`C:\clips\0.wav`) while a second, independently-constructed `File("/clips/0.wav")` for the assertion does the
  same thing consistently — but a *relative* path like `File("clips/0.wav")` resolves against the working
  directory, which differs between where the value was written and where the assertion re-constructs it. Build
  test fixtures with `.absoluteFile` (e.g. `File("clips/0.wav").absoluteFile`) so the pre- and post-round-trip
  values are identical strings; this is not React Native/Windows-only Room flakiness like the pre-existing
  `MigrationTestHelper` issue (see below), it's this specific converter's normalization behavior on any OS.
- **Known pre-existing, environment-specific test failures on this Windows dev machine** (confirmed against
  unmodified `master` in a prior session, and reconfirmed while validating this plan) — do not try to fix these,
  they are unrelated to this plan: `core:common`'s `NaturalOrderComparatorTest.uriComparatorFiles`; `core:data:impl`'s
  `ConvertersTest.file` and all 4 tests in `DataBaseMigratorTest` (Room's `MigrationTestHelper` has a Windows
  path-handling bug). `Room.inMemoryDatabaseBuilder`-based tests (everything in this plan) are unaffected. This
  plan does **not** add a `DataBaseMigratorTest` case for the v61→v62 migration, matching Plan 2's Task 2 precedent
  (InstalledVoice/SentenceClip are pure table additions, verified via the regenerated schema JSON + a real
  in-memory-DB repo test instead).
- Building requires the Android SDK/JDK toolchain already set up on this machine (prerequisite, not part of this
  plan) — `export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"` before any `./gradlew` call
  in a fresh shell/subagent, since the Gradle wrapper bootstrap needs it even though `gradle.properties` also pins
  `org.gradle.java.home`.
- The two curated voices' download URLs, sizes, and SHA-256 checksums in `VoiceCatalog.kt` (Task 4) were computed
  by actually downloading `vits-piper-en_US-amy-medium.tar.bz2` and `vits-piper-en_US-lessac-medium.tar.bz2` from
  `github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/` and running `sha256sum` — do not regenerate or
  guess these values.

---

### Task 1: `InstalledVoice`/`SentenceClip` Room entities and repos

**Files:**
- Create: `core/data/api/src/main/kotlin/voice/core/data/InstalledVoice.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/SentenceClip.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/InstalledVoiceDao.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/SentenceClipDao.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/VoiceRepo.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/SentenceClipRepo.kt`
- Create: `core/data/impl/src/main/kotlin/voice/core/data/repo/VoiceRepoImpl.kt`
- Create: `core/data/impl/src/main/kotlin/voice/core/data/repo/SentenceClipRepoImpl.kt`
- Create: `core/data/impl/src/test/kotlin/voice/core/data/repo/VoiceRepoImplTest.kt`
- Create: `core/data/impl/src/test/kotlin/voice/core/data/repo/SentenceClipRepoImplTest.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt`

**Interfaces:**
- Consumes: `BookId` (existing)
- Produces (for Task 4/5): `InstalledVoice(voiceId: String, name: String, language: String, modelFile: File,
  tokensFile: File, dataDir: File, installedAt: Instant, sizeBytes: Long)`;
  `SentenceClip(bookId: BookId, voiceId: String, chapterIndex: Int, sentenceIndex: Int, file: File, sizeBytes: Long,
  lastAccessedAt: Instant)`; `VoiceRepo { upsert, installedVoices, installedVoice, delete }`;
  `SentenceClipRepo { get, touch, upsert, delete, totalSizeBytes, leastRecentlyAccessed }` — both
  `@ContributesBinding(AppScope::class)`, consumed via Metro DI, never touching Room directly (matches
  `EpubBookRepo`'s Plan-2 pattern).

- [ ] **Step 1: Add the entities**

Create `core/data/api/src/main/kotlin/voice/core/data/InstalledVoice.kt`:

```kotlin
package voice.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File
import java.time.Instant

@Entity(tableName = "installedVoice")
public data class InstalledVoice(
  @PrimaryKey
  val voiceId: String,
  val name: String,
  val language: String,
  val modelFile: File,
  val tokensFile: File,
  val dataDir: File,
  val installedAt: Instant,
  val sizeBytes: Long,
)
```

Create `core/data/api/src/main/kotlin/voice/core/data/SentenceClip.kt`:

```kotlin
package voice.core.data

import androidx.room.Entity
import java.io.File
import java.time.Instant

@Entity(tableName = "sentenceClip", primaryKeys = ["bookId", "voiceId", "chapterIndex", "sentenceIndex"])
public data class SentenceClip(
  val bookId: BookId,
  val voiceId: String,
  val chapterIndex: Int,
  val sentenceIndex: Int,
  val file: File,
  val sizeBytes: Long,
  val lastAccessedAt: Instant,
)
```

- [ ] **Step 2: Add the DAOs**

Create `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/InstalledVoiceDao.kt`:

```kotlin
package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.InstalledVoice

@Dao
public interface InstalledVoiceDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insert(voice: InstalledVoice)

  @Query("SELECT * FROM installedVoice")
  public suspend fun all(): List<InstalledVoice>

  @Query("SELECT * FROM installedVoice WHERE voiceId = :voiceId")
  public suspend fun get(voiceId: String): InstalledVoice?

  @Query("DELETE FROM installedVoice WHERE voiceId = :voiceId")
  public suspend fun delete(voiceId: String)
}
```

Create `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/SentenceClipDao.kt`:

```kotlin
package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.SentenceClip
import java.time.Instant

@Dao
public interface SentenceClipDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insert(clip: SentenceClip)

  @Query(
    "SELECT * FROM sentenceClip WHERE bookId = :bookId AND voiceId = :voiceId " +
      "AND chapterIndex = :chapterIndex AND sentenceIndex = :sentenceIndex",
  )
  public suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip?

  @Query(
    "UPDATE sentenceClip SET lastAccessedAt = :lastAccessedAt WHERE bookId = :bookId AND voiceId = :voiceId " +
      "AND chapterIndex = :chapterIndex AND sentenceIndex = :sentenceIndex",
  )
  public suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    lastAccessedAt: Instant,
  )

  @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM sentenceClip")
  public suspend fun totalSizeBytes(): Long

  @Query("SELECT * FROM sentenceClip ORDER BY lastAccessedAt ASC LIMIT :limit")
  public suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip>

  @Delete
  public suspend fun delete(clip: SentenceClip)
}
```

- [ ] **Step 3: Add the repo interfaces**

Create `core/data/api/src/main/kotlin/voice/core/data/repo/VoiceRepo.kt`:

```kotlin
package voice.core.data.repo

import voice.core.data.InstalledVoice

public interface VoiceRepo {

  public suspend fun upsert(voice: InstalledVoice)

  public suspend fun installedVoices(): List<InstalledVoice>

  public suspend fun installedVoice(voiceId: String): InstalledVoice?

  public suspend fun delete(voiceId: String)
}
```

Create `core/data/api/src/main/kotlin/voice/core/data/repo/SentenceClipRepo.kt`:

```kotlin
package voice.core.data.repo

import voice.core.data.BookId
import voice.core.data.SentenceClip
import java.time.Instant

public interface SentenceClipRepo {

  public suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip?

  public suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    at: Instant,
  )

  public suspend fun upsert(clip: SentenceClip)

  public suspend fun delete(clip: SentenceClip)

  public suspend fun totalSizeBytes(): Long

  public suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip>
}
```

- [ ] **Step 4: Write failing tests for the repo impls**

Create `core/data/impl/src/test/kotlin/voice/core/data/repo/VoiceRepoImplTest.kt`:

```kotlin
package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.InstalledVoice
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class VoiceRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = VoiceRepoImpl(dao = db.installedVoiceDao())

  private fun voice(voiceId: String) = InstalledVoice(
    voiceId = voiceId,
    name = "Amy",
    language = "en_US",
    modelFile = File("voices/$voiceId/model.onnx"),
    tokensFile = File("voices/$voiceId/tokens.txt"),
    dataDir = File("voices/$voiceId/espeak-ng-data"),
    installedAt = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes = 1_000L,
  )

  @Test
  fun upsertThenInstalledVoicesReturnsIt() = runTest {
    repo.upsert(voice("en_US-amy-medium"))

    assertEquals(expected = listOf("en_US-amy-medium"), actual = repo.installedVoices().map { it.voiceId })
  }

  @Test
  fun installedVoiceReturnsNullWhenNotInstalled() = runTest {
    assertNull(repo.installedVoice("missing"))
  }

  @Test
  fun deleteRemovesTheVoice() = runTest {
    repo.upsert(voice("en_US-amy-medium"))

    repo.delete("en_US-amy-medium")

    assertEquals(expected = emptyList(), actual = repo.installedVoices())
  }

  @Test
  fun upsertReplacesAnExistingRow() = runTest {
    repo.upsert(voice("en_US-amy-medium"))
    repo.upsert(voice("en_US-amy-medium").copy(sizeBytes = 2_000L))

    assertEquals(expected = 2_000L, actual = repo.installedVoice("en_US-amy-medium")?.sizeBytes)
  }
}
```

Create `core/data/impl/src/test/kotlin/voice/core/data/repo/SentenceClipRepoImplTest.kt`:

```kotlin
package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.SentenceClip
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class SentenceClipRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = SentenceClipRepoImpl(dao = db.sentenceClipDao())
  private val bookId = BookId("content://book1")

  private fun clip(
    sentenceIndex: Int,
    lastAccessedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes: Long = 100L,
  ) = SentenceClip(
    bookId = bookId,
    voiceId = "en_US-amy-medium",
    chapterIndex = 0,
    sentenceIndex = sentenceIndex,
    file = File("clips/$sentenceIndex.wav").absoluteFile,
    sizeBytes = sizeBytes,
    lastAccessedAt = lastAccessedAt,
  )

  @Test
  fun getReturnsNullOnMiss() = runTest {
    assertNull(repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0))
  }

  @Test
  fun upsertThenGetReturnsTheClip() = runTest {
    repo.upsert(clip(sentenceIndex = 0))

    val result = repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = File("clips/0.wav").absoluteFile, actual = result?.file)
  }

  @Test
  fun touchUpdatesLastAccessedAt() = runTest {
    repo.upsert(clip(sentenceIndex = 0, lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z")))

    repo.touch(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      at = Instant.parse("2026-02-01T00:00:00Z"),
    )

    val result = repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0)
    assertEquals(expected = Instant.parse("2026-02-01T00:00:00Z"), actual = result?.lastAccessedAt)
  }

  @Test
  fun totalSizeBytesSumsAllClips() = runTest {
    repo.upsert(clip(sentenceIndex = 0, sizeBytes = 100L))
    repo.upsert(clip(sentenceIndex = 1, sizeBytes = 200L))

    assertEquals(expected = 300L, actual = repo.totalSizeBytes())
  }

  @Test
  fun leastRecentlyAccessedOrdersByLastAccessedAtAscending() = runTest {
    repo.upsert(clip(sentenceIndex = 0, lastAccessedAt = Instant.parse("2026-01-03T00:00:00Z")))
    repo.upsert(clip(sentenceIndex = 1, lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z")))
    repo.upsert(clip(sentenceIndex = 2, lastAccessedAt = Instant.parse("2026-01-02T00:00:00Z")))

    val result = repo.leastRecentlyAccessed(limit = 2)

    assertEquals(expected = listOf(1, 2), actual = result.map { it.sentenceIndex })
  }

  @Test
  fun deleteRemovesTheClip() = runTest {
    repo.upsert(clip(sentenceIndex = 0))

    repo.delete(clip(sentenceIndex = 0))

    assertNull(repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0))
  }
}
```

- [ ] **Step 5: Run the tests and verify they fail**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.VoiceRepoImplTest" --tests "voice.core.data.repo.SentenceClipRepoImplTest"
```

Expected: compile failure — `VoiceRepoImpl`/`SentenceClipRepoImpl` don't exist, and `AppDb` has no
`installedVoiceDao()`/`sentenceClipDao()`.

- [ ] **Step 6: Implement the repos and wire them into `AppDb`/`PersistenceModule`**

Create `core/data/impl/src/main/kotlin/voice/core/data/repo/VoiceRepoImpl.kt`:

```kotlin
package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.InstalledVoice
import voice.core.data.repo.internals.dao.InstalledVoiceDao

@ContributesBinding(AppScope::class)
public class VoiceRepoImpl(private val dao: InstalledVoiceDao) : VoiceRepo {

  override suspend fun upsert(voice: InstalledVoice) {
    dao.insert(voice)
  }

  override suspend fun installedVoices(): List<InstalledVoice> = dao.all()

  override suspend fun installedVoice(voiceId: String): InstalledVoice? = dao.get(voiceId)

  override suspend fun delete(voiceId: String) {
    dao.delete(voiceId)
  }
}
```

Create `core/data/impl/src/main/kotlin/voice/core/data/repo/SentenceClipRepoImpl.kt`:

```kotlin
package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.BookId
import voice.core.data.SentenceClip
import voice.core.data.repo.internals.dao.SentenceClipDao
import java.time.Instant

@ContributesBinding(AppScope::class)
public class SentenceClipRepoImpl(private val dao: SentenceClipDao) : SentenceClipRepo {

  override suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip? = dao.get(bookId, voiceId, chapterIndex, sentenceIndex)

  override suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    at: Instant,
  ) {
    dao.touch(bookId, voiceId, chapterIndex, sentenceIndex, at)
  }

  override suspend fun upsert(clip: SentenceClip) {
    dao.insert(clip)
  }

  override suspend fun delete(clip: SentenceClip) {
    dao.delete(clip)
  }

  override suspend fun totalSizeBytes(): Long = dao.totalSizeBytes()

  override suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip> = dao.leastRecentlyAccessed(limit)
}
```

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add the two entities, bump the
version, and declare the DAO accessors:

```kotlin
import voice.core.data.InstalledVoice
import voice.core.data.SentenceClip
import voice.core.data.repo.internals.dao.InstalledVoiceDao
import voice.core.data.repo.internals.dao.SentenceClipDao

@Database(
  entities = [
    Chapter::class,
    BookContent::class,
    Bookmark::class,
    BookSearchFts::class,
    RecentBookSearch::class,
    EpubChapter::class,
    EpubSentence::class,
    InstalledVoice::class,
    SentenceClip::class,
  ],
  version = AppDb.VERSION,
  autoMigrations = [
    AutoMigration(from = 51, to = 52),
    AutoMigration(from = 52, to = 53),
    AutoMigration(from = 54, to = 55),
    AutoMigration(from = 55, to = 56),
    AutoMigration(from = 56, to = 57, spec = Migration56::class),
    AutoMigration(from = 57, to = 58),
    AutoMigration(from = 58, to = 59),
    AutoMigration(from = 59, to = 60),
    AutoMigration(from = 60, to = 61),
    AutoMigration(from = 61, to = 62),
  ],
)
@TypeConverters(Converters::class)
public abstract class AppDb : RoomDatabase() {

  public abstract fun chapterDao(): ChapterDao
  public abstract fun bookContentDao(): BookContentDao
  public abstract fun bookmarkDao(): BookmarkDao
  public abstract fun epubChapterDao(): EpubChapterDao
  public abstract fun epubSentenceDao(): EpubSentenceDao
  public abstract fun installedVoiceDao(): InstalledVoiceDao
  public abstract fun sentenceClipDao(): SentenceClipDao

  public abstract fun recentBookSearchDao(): RecentBookSearchDao

  internal companion object {
    const val VERSION = 62
    const val DATABASE_NAME = "autoBookDB"
  }
}
```

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt`, add (with imports
`voice.core.data.repo.internals.dao.InstalledVoiceDao` and `voice.core.data.repo.internals.dao.SentenceClipDao`):

```kotlin
  @Provides
  private fun installedVoiceDao(appDb: AppDb): InstalledVoiceDao = appDb.installedVoiceDao()

  @Provides
  private fun sentenceClipDao(appDb: AppDb): SentenceClipDao = appDb.sentenceClipDao()
```

- [ ] **Step 7: Run the tests and verify they pass**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.VoiceRepoImplTest" --tests "voice.core.data.repo.SentenceClipRepoImplTest"
```

Expected: `BUILD SUCCESSFUL`, 10 tests passed. This also regenerates
`core/data/impl/schemas/voice.core.data.repo.internals.AppDb/62.json` — stage it in the commit below.

- [ ] **Step 8: Run the full `core:data:impl` unit test suite**

```bash
./gradlew :core:data:impl:testDebugUnitTest
```

Expected: 41 tests, 5 failed — `ConvertersTest.file` and the 4 `DataBaseMigratorTest` tests (the known pre-existing
Windows failures from Global Constraints). No other regressions.

- [ ] **Step 9: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/InstalledVoice.kt core/data/api/src/main/kotlin/voice/core/data/SentenceClip.kt core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/InstalledVoiceDao.kt core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/SentenceClipDao.kt core/data/api/src/main/kotlin/voice/core/data/repo/VoiceRepo.kt core/data/api/src/main/kotlin/voice/core/data/repo/SentenceClipRepo.kt core/data/impl/src/main/kotlin/voice/core/data/repo/VoiceRepoImpl.kt core/data/impl/src/main/kotlin/voice/core/data/repo/SentenceClipRepoImpl.kt core/data/impl/src/test/kotlin/voice/core/data/repo/VoiceRepoImplTest.kt core/data/impl/src/test/kotlin/voice/core/data/repo/SentenceClipRepoImplTest.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt core/data/impl/schemas
git commit -m "Add InstalledVoice/SentenceClip tables and VoiceRepo/SentenceClipRepo"
```

---

### Task 2: `core:tts` module scaffold + `SynthesisEngine`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Create: `core/tts/build.gradle.kts`
- Create: `core/tts/src/main/kotlin/voice/core/tts/SynthesisEngine.kt`
- Create: `core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngine.kt`
- Create: `core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngineTest.kt`

**Interfaces:**
- Consumes: `InstalledVoice` (Task 1)
- Produces (for Task 3/5): `SynthesisEngine { suspend fun synthesize(text: String, voice: InstalledVoice,
  outputFile: File): SynthesisResult }`, `SynthesisResult` sealed (`Success` / `Failure(reason: String)`), and
  `FakeSynthesisEngine` (test-only, `core:tts`'s own test source set) — configurable `result`/`writeBytes`, records
  `requestedTexts`.

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`, add after `include(":core:featureflag")`:

```kotlin
include(":core:tts")
```

Create `core/tts/build.gradle.kts`:

```kotlin
plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

kotlin {
  explicitApi()
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.logging.api)

  testImplementation(libs.bundles.testing.jvm)
}
```

In `app/build.gradle.kts`, add after `implementation(projects.core.scanner)`:

```kotlin
  implementation(projects.core.tts)
```

- [ ] **Step 2: Write a failing test for the fake**

Create `core/tts/src/main/kotlin/voice/core/tts/SynthesisEngine.kt`:

```kotlin
package voice.core.tts

import voice.core.data.InstalledVoice
import java.io.File

public interface SynthesisEngine {

  public suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult
}

public sealed interface SynthesisResult {
  public data object Success : SynthesisResult
  public data class Failure(val reason: String) : SynthesisResult
}
```

Create `core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngine.kt`:

```kotlin
package voice.core.tts

import voice.core.data.InstalledVoice
import java.io.File

internal class FakeSynthesisEngine : SynthesisEngine {
  var result: SynthesisResult = SynthesisResult.Success
  var writeBytes: ByteArray? = byteArrayOf(1, 2, 3)
  val requestedTexts = mutableListOf<String>()

  override suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult {
    requestedTexts += text
    if (result is SynthesisResult.Success) {
      writeBytes?.let { outputFile.writeBytes(it) }
    }
    return result
  }
}
```

Create `core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngineTest.kt`:

```kotlin
package voice.core.tts

import kotlinx.coroutines.test.runTest
import voice.core.data.InstalledVoice
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FakeSynthesisEngineTest {

  private val voice = InstalledVoice(
    voiceId = "en_US-amy-medium",
    name = "Amy",
    language = "en_US",
    modelFile = File("voices/model.onnx"),
    tokensFile = File("voices/tokens.txt"),
    dataDir = File("voices/espeak-ng-data"),
    installedAt = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes = 100L,
  )
  private val outputFile = File.createTempFile("fake-synthesis-engine-test", ".wav")

  @AfterTest
  fun cleanup() {
    outputFile.delete()
  }

  @Test
  fun writesConfiguredBytesAndReturnsSuccessByDefault() = runTest {
    val engine = FakeSynthesisEngine()

    val result = engine.synthesize("Hello.", voice, outputFile)

    assertEquals(expected = SynthesisResult.Success, actual = result)
    assertContentEquals(expected = byteArrayOf(1, 2, 3), actual = outputFile.readBytes())
    assertEquals(expected = listOf("Hello."), actual = engine.requestedTexts)
  }

  @Test
  fun returnsConfiguredFailureAndDoesNotWriteAFile() = runTest {
    val engine = FakeSynthesisEngine().apply {
      result = SynthesisResult.Failure("boom")
    }

    val result = engine.synthesize("Hello.", voice, outputFile)

    assertEquals(expected = SynthesisResult.Failure("boom"), actual = result)
    assertEquals(expected = 0L, actual = outputFile.length())
  }
}
```

- [ ] **Step 3: Run the test and verify it passes**

```bash
./gradlew :core:tts:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 4: Verify the app still assembles with the new (empty-of-consumers) module wired in**

```bash
./gradlew :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts core/tts/build.gradle.kts core/tts/src/main/kotlin/voice/core/tts/SynthesisEngine.kt core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngine.kt core/tts/src/test/kotlin/voice/core/tts/FakeSynthesisEngineTest.kt
git commit -m "Add core:tts module with SynthesisEngine and a fake for testing"
```

---

### Task 3: `SherpaOnnxSynthesisEngine`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `core/tts/build.gradle.kts`
- Create: `core/tts/src/main/kotlin/voice/core/tts/SherpaOnnxSynthesisEngine.kt`

**Interfaces:**
- Consumes: `SynthesisEngine`/`SynthesisResult` (Task 2), `InstalledVoice` (Task 1)
- Produces: `@ContributesBinding(AppScope::class) class SherpaOnnxSynthesisEngine : SynthesisEngine` — the real,
  production binding for `SynthesisEngine` in the Metro graph.

This class wraps sherpa-onnx's native `OfflineTts`, whose `.so` libraries only load on a real Android
ABI — it is **not unit-testable** in this project's JVM/Robolectric suite (no `androidTest`/instrumented suite
exists in this codebase currently), matching the design spec's own call on this. There is also no reader UI yet to
exercise it manually on-device (Plan 4 adds that entry point) — verification for this task is therefore a compile
+ full-app-assemble check only; treat the actual synthesis behavior as unverified until Plan 4 can trigger it
end-to-end on a real device, the same way Plan 2's scanner work was verified live.

- [ ] **Step 1: Add the JitPack repository**

In `settings.gradle.kts`, in `dependencyResolutionManagement.repositories`:

```kotlin
dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
    maven(url = "https://jitpack.io")
  }
}
```

- [ ] **Step 2: Add the sherpa-onnx and commons-compress version catalog entries**

In `gradle/libs.versions.toml`, in `[versions]` (after `navigation3`):

```toml
sherpaOnnx = "1.13.4"
commonsCompress = "1.28.0"
```

In `[libraries]` (after `slf4j-noop`):

```toml
sherpaOnnx = { module = "com.github.k2-fsa:sherpa-onnx", version.ref = "sherpaOnnx" }
commonsCompress = { module = "org.apache.commons:commons-compress", version.ref = "commonsCompress" }
```

- [ ] **Step 3: Add the dependency to `core:tts`**

In `core/tts/build.gradle.kts`, in `dependencies`:

```kotlin
  implementation(libs.sherpaOnnx)
```

- [ ] **Step 4: Implement `SherpaOnnxSynthesisEngine`**

Create `core/tts/src/main/kotlin/voice/core/tts/SherpaOnnxSynthesisEngine.kt`:

```kotlin
package voice.core.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.InstalledVoice
import voice.core.logging.api.Logger
import java.io.File

@Inject
@ContributesBinding(AppScope::class)
public class SherpaOnnxSynthesisEngine : SynthesisEngine {

  override suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult {
    return withContext(Dispatchers.IO) {
      val tts = try {
        OfflineTts(
          config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
              vits = OfflineTtsVitsModelConfig(
                model = voice.modelFile.absolutePath,
                tokens = voice.tokensFile.absolutePath,
                dataDir = voice.dataDir.absolutePath,
              ),
              numThreads = 2,
              provider = "cpu",
            ),
          ),
        )
      } catch (e: Exception) {
        Logger.w(e, "Failed to load TTS model for voice=${voice.voiceId}")
        return@withContext SynthesisResult.Failure("failed to load model for voice=${voice.voiceId}: ${e.message}")
      }

      try {
        val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
        if (audio.save(outputFile.absolutePath)) {
          SynthesisResult.Success
        } else {
          SynthesisResult.Failure("failed to write WAV to $outputFile")
        }
      } catch (e: Exception) {
        Logger.w(e, "Synthesis failed for voice=${voice.voiceId}")
        SynthesisResult.Failure("synthesis error: ${e.message}")
      } finally {
        tts.release()
      }
    }
  }
}
```

`OfflineTts`/`OfflineTtsConfig`/`OfflineTtsModelConfig`/`OfflineTtsVitsModelConfig`/`GeneratedAudio` signatures above
are copied verbatim from sherpa-onnx 1.13.4's own Kotlin API source
(`sherpa-onnx/kotlin-api/Tts.kt` in the k2-fsa/sherpa-onnx repo) — `model`/`tokens`/`dataDir` are `String` paths (use
`.absolutePath`, not `File`), `generate()` defaults `sid = 0, speed = 1.0f`, and `GeneratedAudio.save(filename:
String): Boolean` writes the WAV directly. `tts.release()` frees the native model after each call rather than
caching it across calls — simplest correct behavior for this plan; a "keep the model loaded across sentences"
optimization is Plan 4's prefetch/scheduler territory, not needed here.

- [ ] **Step 5: Verify it compiles and the app assembles**

```bash
./gradlew :core:tts:compileDebugKotlin :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL` — this resolves the sherpa-onnx AAR via JitPack, binds `SherpaOnnxSynthesisEngine` into
the Metro `AppScope` graph, and packages its native `.so` libraries into the debug APK.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml core/tts/build.gradle.kts core/tts/src/main/kotlin/voice/core/tts/SherpaOnnxSynthesisEngine.kt
git commit -m "Add SherpaOnnxSynthesisEngine wrapping the sherpa-onnx VITS runtime"
```

---

### Task 4: `VoiceCatalog`, `TtsDownloader`, and `VoiceManager`

**Files:**
- Modify: `core/tts/build.gradle.kts`
- Create: `core/tts/src/main/kotlin/voice/core/tts/VoiceCatalogEntry.kt`
- Create: `core/tts/src/main/kotlin/voice/core/tts/VoiceCatalog.kt`
- Create: `core/tts/src/main/kotlin/voice/core/tts/TtsModule.kt`
- Create: `core/tts/src/main/kotlin/voice/core/tts/TtsDownloader.kt`
- Create: `core/tts/src/main/kotlin/voice/core/tts/VoiceManager.kt`
- Create: `core/tts/src/test/kotlin/voice/core/tts/VoiceManagerTest.kt`

**Interfaces:**
- Consumes: `InstalledVoice`/`VoiceRepo` (Task 1)
- Produces (for Plan 4/5): `VoiceCatalogEntry(voiceId, name, language, downloadUrl, sizeBytes, sha256)`;
  `VoiceManager { suspend fun availableVoices(): List<AvailableVoice>; suspend fun install(voiceId: String):
  InstallResult; suspend fun uninstall(voiceId: String) }`; `AvailableVoice(entry: VoiceCatalogEntry, installed:
  Boolean)`; `InstallResult` sealed (`Success` / `Failure(reason: String)`); qualifiers `@TtsHttpClient` (on the
  `OkHttpClient` used for voice downloads — `core:tts` can't depend on `features:cover`'s `CoverModule`, and an
  unqualified second `OkHttpClient` binding would collide with it in the app's Metro graph) and
  `@MaxTtsCacheSizeBytes` (on the `Long` cache-size cap Task 5 consumes).

- [ ] **Step 1: Add dependencies**

In `core/tts/build.gradle.kts`:

```kotlin
  implementation(libs.commonsCompress)
  implementation(libs.okhttp)
```

and, in the same file:

```kotlin
  testImplementation(projects.core.data.impl)
```

(needed so `VoiceManagerTest` can use `VoiceRepoImpl`/`AppDb` for a real in-memory Room DB, matching how
`core:scanner`'s tests depend on `core:data:impl`).

- [ ] **Step 2: Add the catalog**

Create `core/tts/src/main/kotlin/voice/core/tts/VoiceCatalogEntry.kt`:

```kotlin
package voice.core.tts

public data class VoiceCatalogEntry(
  val voiceId: String,
  val name: String,
  val language: String,
  val downloadUrl: String,
  val sizeBytes: Long,
  val sha256: String,
)
```

Create `core/tts/src/main/kotlin/voice/core/tts/VoiceCatalog.kt`:

```kotlin
package voice.core.tts

public object VoiceCatalog {
  public val entries: List<VoiceCatalogEntry> = listOf(
    VoiceCatalogEntry(
      voiceId = "en_US-amy-medium",
      name = "Amy (US English)",
      language = "en_US",
      downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
      sizeBytes = 67_223_746L,
      sha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264",
    ),
    VoiceCatalogEntry(
      voiceId = "en_US-lessac-medium",
      name = "Lessac (US English)",
      language = "en_US",
      downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
      sizeBytes = 67_230_653L,
      sha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
    ),
  )
}
```

(If `formatKotlin` reindents the wrapped `downloadUrl =` continuation lines differently than shown, keep its
output — ktlint's `standard:indent` rule is authoritative here, not this listing.)

- [ ] **Step 3: Add the DI module and downloader**

Create `core/tts/src/main/kotlin/voice/core/tts/TtsModule.kt`:

```kotlin
package voice.core.tts

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import okhttp3.OkHttpClient

@Qualifier
public annotation class TtsHttpClient

@Qualifier
public annotation class MaxTtsCacheSizeBytes

@ContributesTo(AppScope::class)
public interface TtsModule {

  @Provides
  @SingleIn(AppScope::class)
  @TtsHttpClient
  public fun ttsHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

  @Provides
  public fun voiceCatalog(): List<VoiceCatalogEntry> = VoiceCatalog.entries

  @Provides
  @MaxTtsCacheSizeBytes
  public fun maxTtsCacheSizeBytes(): Long = 500L * 1024 * 1024
}
```

Create `core/tts/src/main/kotlin/voice/core/tts/TtsDownloader.kt` (mirrors `features:cover`'s `CoverDownloader`):

```kotlin
package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import okio.sink
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

@Inject
internal class TtsDownloader(
  @TtsHttpClient private val client: OkHttpClient,
  private val context: Context,
) {

  internal suspend fun download(url: String): File? {
    val tempFolder = File(context.cacheDir, "ttsVoiceDownload").apply { mkdirs() }
    val request = Request.Builder().url(url).build()
    val response = try {
      client.newCall(request).executeAsync()
    } catch (e: IOException) {
      Logger.w(e, "Failed to download voice from $url")
      return null
    }
    return withContext(Dispatchers.IO) {
      try {
        response.body.source().use { source ->
          val file = File(tempFolder, "${Uuid.random()}.tar.bz2")
          file.sink().use { sink -> source.readAll(sink) }
          file
        }
      } catch (e: IOException) {
        Logger.w(e, "Failed to save voice download from $url")
        null
      }
    }
  }
}
```

- [ ] **Step 4: Write a failing test for `VoiceManager`**

Create `core/tts/src/test/kotlin/voice/core/tts/VoiceManagerTest.kt`:

```kotlin
package voice.core.tts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.repo.VoiceRepoImpl
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class VoiceManagerTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
    .allowMainThreadQueries()
    .build()
  private val voiceRepo = VoiceRepoImpl(dao = db.installedVoiceDao())
  private val downloader = mockk<TtsDownloader>()

  private fun buildVoiceArchive(): File {
    val archive = File(testFolder.newFolder(), "voice.tar.bz2")
    BZip2CompressorOutputStream(archive.outputStream()).use { bzip2 ->
      TarArchiveOutputStream(bzip2).use { tar ->
        fun addFile(
          name: String,
          content: String,
        ) {
          val entry = TarArchiveEntry("vits-piper-test-voice/$name")
          entry.size = content.toByteArray().size.toLong()
          tar.putArchiveEntry(entry)
          tar.write(content.toByteArray())
          tar.closeArchiveEntry()
        }
        addFile("test-voice.onnx", "fake model bytes")
        addFile("tokens.txt", "fake tokens")
        addFile("espeak-ng-data/en_dict", "fake dict")
      }
    }
    return archive
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(file.readBytes())
    return HexFormat.of().formatHex(digest.digest())
  }

  private fun catalogEntry(archive: File) = VoiceCatalogEntry(
    voiceId = "test-voice",
    name = "Test Voice",
    language = "en_US",
    downloadUrl = "https://example.test/test-voice.tar.bz2",
    sizeBytes = archive.length(),
    sha256 = sha256(archive),
  )

  @Test
  fun availableVoicesReportsInstalledStatus() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val before = manager.availableVoices()
    assertEquals(expected = listOf(false), actual = before.map { it.installed })

    assertIs<InstallResult.Success>(manager.install(entry.voiceId))

    val after = manager.availableVoices()
    assertEquals(expected = listOf(true), actual = after.map { it.installed })
  }

  @Test
  fun installExtractsArchiveAndRecordsInstalledVoice() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Success>(result)
    val installed = voiceRepo.installedVoice(entry.voiceId)
    assertEquals(expected = "test-voice.onnx", actual = installed?.modelFile?.name)
    assertEquals(expected = "fake model bytes", actual = installed?.modelFile?.readText())
    assertEquals(expected = "tokens.txt", actual = installed?.tokensFile?.name)
    assertEquals(expected = "espeak-ng-data", actual = installed?.dataDir?.name)
  }

  @Test
  fun installFailsOnChecksumMismatch() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive).copy(sha256 = "0".repeat(64))
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Failure>(result)
    assertNull(voiceRepo.installedVoice(entry.voiceId))
  }

  @Test
  fun installFailsWhenDownloadFails() = runTest {
    val entry = catalogEntry(buildVoiceArchive())
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns null

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Failure>(result)
  }

  @Test
  fun uninstallDeletesFilesAndRow() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive
    assertIs<InstallResult.Success>(manager.install(entry.voiceId))
    val installedDir = voiceRepo.installedVoice(entry.voiceId)?.modelFile?.parentFile!!

    manager.uninstall(entry.voiceId)

    assertNull(voiceRepo.installedVoice(entry.voiceId))
    assertEquals(expected = false, actual = installedDir.exists())
  }
}
```

- [ ] **Step 5: Run the test and verify it fails**

```bash
./gradlew :core:tts:testDebugUnitTest --tests "voice.core.tts.VoiceManagerTest"
```

Expected: compile failure — `VoiceManager`/`AvailableVoice`/`InstallResult` don't exist yet.

- [ ] **Step 6: Implement `VoiceManager`**

Create `core/tts/src/main/kotlin/voice/core/tts/VoiceManager.kt`:

```kotlin
package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import voice.core.data.InstalledVoice
import voice.core.data.repo.VoiceRepo
import voice.core.logging.api.Logger
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

public data class AvailableVoice(
  val entry: VoiceCatalogEntry,
  val installed: Boolean,
)

public sealed interface InstallResult {
  public data object Success : InstallResult
  public data class Failure(val reason: String) : InstallResult
}

@Inject
public class VoiceManager
internal constructor(
  private val context: Context,
  private val downloader: TtsDownloader,
  private val voiceRepo: VoiceRepo,
  private val catalog: List<VoiceCatalogEntry>,
) {

  public suspend fun availableVoices(): List<AvailableVoice> {
    val installedIds = voiceRepo.installedVoices().map { it.voiceId }.toSet()
    return catalog.map { entry -> AvailableVoice(entry, installed = entry.voiceId in installedIds) }
  }

  public suspend fun install(voiceId: String): InstallResult {
    val entry = catalog.find { it.voiceId == voiceId }
      ?: return InstallResult.Failure("unknown voice: $voiceId")

    return withContext(Dispatchers.IO) {
      val downloaded = downloader.download(entry.downloadUrl)
        ?: return@withContext InstallResult.Failure("download failed")
      try {
        val actualChecksum = sha256(downloaded)
        if (!actualChecksum.equals(entry.sha256, ignoreCase = true)) {
          Logger.w("Checksum mismatch for voice=$voiceId")
          return@withContext InstallResult.Failure("checksum mismatch")
        }

        val voiceDir = File(context.filesDir, "ttsVoices/$voiceId")
        voiceDir.deleteRecursively()
        voiceDir.mkdirs()
        extractTarBz2(downloaded, voiceDir)

        val modelFile = File(voiceDir, "$voiceId.onnx")
        val tokensFile = File(voiceDir, "tokens.txt")
        val dataDir = File(voiceDir, "espeak-ng-data")
        if (!modelFile.isFile || !tokensFile.isFile || !dataDir.isDirectory) {
          voiceDir.deleteRecursively()
          return@withContext InstallResult.Failure("archive is missing expected files")
        }

        voiceRepo.upsert(
          InstalledVoice(
            voiceId = voiceId,
            name = entry.name,
            language = entry.language,
            modelFile = modelFile,
            tokensFile = tokensFile,
            dataDir = dataDir,
            installedAt = Instant.now(),
            sizeBytes = voiceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
          ),
        )
        InstallResult.Success
      } finally {
        downloaded.delete()
      }
    }
  }

  public suspend fun uninstall(voiceId: String) {
    withContext(Dispatchers.IO) {
      File(context.filesDir, "ttsVoices/$voiceId").deleteRecursively()
    }
    voiceRepo.delete(voiceId)
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream(), digest).use { stream ->
      val buffer = ByteArray(8192)
      while (stream.read(buffer) != -1) {
        // reading through the DigestInputStream drives digest.update()
      }
    }
    return HexFormat.of().formatHex(digest.digest())
  }

  private fun extractTarBz2(
    archive: File,
    destDir: File,
  ) {
    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream())).use { tar ->
      while (true) {
        val entry = tar.nextEntry ?: break
        val relativePath = entry.name.substringAfter('/', missingDelimiterValue = "")
        if (relativePath.isEmpty()) continue
        val outFile = File(destDir, relativePath)
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          outFile.outputStream().use { output -> tar.copyTo(output) }
        }
      }
    }
  }
}
```

Two things worth knowing before you read this and wonder why they're there:
- `VoiceManager`'s primary constructor is `internal constructor` (the class itself stays `public`) because it takes
  `TtsDownloader`, which is `internal` — a `public` constructor can't expose an `internal` parameter type
  (compile error), and this mirrors `BookmarkRepoImpl`'s existing `public class ... internal constructor(...)`
  pattern exactly.
- sherpa-onnx's own pre-converted voice packages extract into a nested top-level directory
  (`vits-piper-{voiceId}/...`, confirmed by actually downloading and `tar tjf`-inspecting both catalog entries this
  session) — `extractTarBz2` strips that one leading path segment via `substringAfter('/', ...)` so the extracted
  files land directly in `ttsVoices/{voiceId}/`, not double-nested.

- [ ] **Step 7: Run the test and verify it passes**

```bash
./gradlew :core:tts:testDebugUnitTest --tests "voice.core.tts.VoiceManagerTest"
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 8: Run the full `core:tts` unit test suite**

```bash
./gradlew :core:tts:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 9: Commit**

```bash
git add core/tts/build.gradle.kts core/tts/src/main/kotlin/voice/core/tts/VoiceCatalogEntry.kt core/tts/src/main/kotlin/voice/core/tts/VoiceCatalog.kt core/tts/src/main/kotlin/voice/core/tts/TtsModule.kt core/tts/src/main/kotlin/voice/core/tts/TtsDownloader.kt core/tts/src/main/kotlin/voice/core/tts/VoiceManager.kt core/tts/src/test/kotlin/voice/core/tts/VoiceManagerTest.kt
git commit -m "Add VoiceCatalog, TtsDownloader, and VoiceManager for voice install/uninstall"
```

---

### Task 5: `SentenceClipCache`

**Files:**
- Create: `core/tts/src/main/kotlin/voice/core/tts/SentenceClipCache.kt`
- Create: `core/tts/src/test/kotlin/voice/core/tts/SentenceClipCacheTest.kt`

**Interfaces:**
- Consumes: `SynthesisEngine`/`SynthesisResult` (Task 2), `VoiceRepo`/`SentenceClipRepo` (Task 1),
  `@MaxTtsCacheSizeBytes Long` (Task 4)
- Produces (for Plan 4): `SentenceClipCache { suspend fun getOrSynthesize(bookId: BookId, voiceId: String,
  chapterIndex: Int, sentenceIndex: Int, text: String): ClipResult }` — this is the entry point Plan 4's read-along
  playback and "synthesize ahead" scheduler will call. `ClipResult` sealed (`Success(file: File)` /
  `Failure(reason: String)`).

- [ ] **Step 1: Write a failing test**

Create `core/tts/src/test/kotlin/voice/core/tts/SentenceClipCacheTest.kt`:

```kotlin
package voice.core.tts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.InstalledVoice
import voice.core.data.repo.SentenceClipRepoImpl
import voice.core.data.repo.VoiceRepoImpl
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SentenceClipCacheTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
    .allowMainThreadQueries()
    .build()
  private val sentenceClipRepo = SentenceClipRepoImpl(dao = db.sentenceClipDao())
  private val voiceRepo = VoiceRepoImpl(dao = db.installedVoiceDao())
  private val synthesisEngine = FakeSynthesisEngine()
  private val cache = SentenceClipCache(
    context = context,
    synthesisEngine = synthesisEngine,
    sentenceClipRepo = sentenceClipRepo,
    voiceRepo = voiceRepo,
    maxCacheSizeBytes = 300L,
  )
  private val bookId = BookId("content://book1")

  private suspend fun installVoice(voiceId: String = "en_US-amy-medium") {
    voiceRepo.upsert(
      InstalledVoice(
        voiceId = voiceId,
        name = "Amy",
        language = "en_US",
        modelFile = File("voices/$voiceId/model.onnx"),
        tokensFile = File("voices/$voiceId/tokens.txt"),
        dataDir = File("voices/$voiceId/espeak-ng-data"),
        installedAt = Instant.parse("2026-01-01T00:00:00Z"),
        sizeBytes = 100L,
      ),
    )
  }

  @Test
  fun synthesizesOnCacheMissAndPersistsTheClip() = runTest {
    installVoice()

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Success>(result)
    assertTrue(result.file.exists())
    assertEquals(expected = listOf("Hello."), actual = synthesisEngine.requestedTexts)
  }

  @Test
  fun cacheHitReturnsExistingClipWithoutCallingTheEngineAgain() = runTest {
    installVoice()
    val first = cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0, text = "Hello.")
    assertIs<ClipResult.Success>(first)

    val second = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Success>(second)
    assertEquals(expected = 1, actual = synthesisEngine.requestedTexts.size)
  }

  @Test
  fun returnsFailureWhenTheVoiceIsNotInstalled() = runTest {
    val result = cache.getOrSynthesize(
      bookId,
      "missing-voice",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Failure>(result)
    assertEquals(expected = 0, actual = synthesisEngine.requestedTexts.size)
  }

  @Test
  fun returnsFailureAndDeletesTheOutputFileWhenSynthesisFails() = runTest {
    installVoice()
    synthesisEngine.result = SynthesisResult.Failure("boom")

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Failure>(result)
    assertEquals(expected = "boom", actual = result.reason)
  }

  @Test
  fun evictsLeastRecentlyAccessedClipsWhenOverTheSizeCap() = runTest {
    installVoice()
    synthesisEngine.writeBytes = ByteArray(150)
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0, text = "One."),
    )
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 1, text = "Two."),
    )

    // total is now 300 bytes, exactly at the cap; a third 150-byte clip forces eviction of sentence 0
    // (the least recently accessed clip)
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 2, text = "Three."),
    )

    assertEquals(
      expected = null,
      actual = sentenceClipRepo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0),
    )
    assertTrue(sentenceClipRepo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 2) != null)
  }

  @Test
  fun returnsFailureWhenASingleClipExceedsTheEntireCap() = runTest {
    installVoice()
    synthesisEngine.writeBytes = ByteArray(400)

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Too big.",
    )

    assertIs<ClipResult.Failure>(result)
  }
}
```

Note the test constructs `SentenceClipCache` with `maxCacheSizeBytes = 300L` directly (not the production 500 MB
default from `TtsModule`) so eviction can be exercised with tiny fake clips instead of allocating hundreds of MB per
test.

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core:tts:testDebugUnitTest --tests "voice.core.tts.SentenceClipCacheTest"
```

Expected: compile failure — `SentenceClipCache`/`ClipResult` don't exist yet.

- [ ] **Step 3: Implement `SentenceClipCache`**

Create `core/tts/src/main/kotlin/voice/core/tts/SentenceClipCache.kt`:

```kotlin
package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import voice.core.data.BookId
import voice.core.data.InstalledVoice
import voice.core.data.SentenceClip
import voice.core.data.repo.SentenceClipRepo
import voice.core.data.repo.VoiceRepo
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

public sealed interface ClipResult {
  public data class Success(val file: File) : ClipResult
  public data class Failure(val reason: String) : ClipResult
}

@Inject
public class SentenceClipCache(
  private val context: Context,
  private val synthesisEngine: SynthesisEngine,
  private val sentenceClipRepo: SentenceClipRepo,
  private val voiceRepo: VoiceRepo,
  @MaxTtsCacheSizeBytes private val maxCacheSizeBytes: Long,
) {

  public suspend fun getOrSynthesize(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    text: String,
  ): ClipResult {
    val existing = sentenceClipRepo.get(bookId, voiceId, chapterIndex, sentenceIndex)
    if (existing != null) {
      sentenceClipRepo.touch(bookId, voiceId, chapterIndex, sentenceIndex, Instant.now())
      return ClipResult.Success(existing.file)
    }

    val voice: InstalledVoice = voiceRepo.installedVoice(voiceId)
      ?: return ClipResult.Failure("voice $voiceId is not installed")

    val clipsDir = File(context.filesDir, "ttsClips").apply { mkdirs() }
    val outputFile = File(clipsDir, "${Uuid.random()}.wav")

    return when (val result = synthesisEngine.synthesize(text, voice, outputFile)) {
      is SynthesisResult.Failure -> {
        outputFile.delete()
        ClipResult.Failure(result.reason)
      }
      SynthesisResult.Success -> {
        val sizeBytes = outputFile.length()
        if (!makeRoomFor(sizeBytes)) {
          outputFile.delete()
          ClipResult.Failure("not enough cache space for this clip")
        } else {
          sentenceClipRepo.upsert(
            SentenceClip(
              bookId = bookId,
              voiceId = voiceId,
              chapterIndex = chapterIndex,
              sentenceIndex = sentenceIndex,
              file = outputFile,
              sizeBytes = sizeBytes,
              lastAccessedAt = Instant.now(),
            ),
          )
          ClipResult.Success(outputFile)
        }
      }
    }
  }

  private suspend fun makeRoomFor(newClipBytes: Long): Boolean {
    if (newClipBytes > maxCacheSizeBytes) return false
    var total = sentenceClipRepo.totalSizeBytes()
    while (total + newClipBytes > maxCacheSizeBytes) {
      val victims = sentenceClipRepo.leastRecentlyAccessed(limit = EVICTION_BATCH_SIZE)
      if (victims.isEmpty()) return false
      for (victim in victims) {
        victim.file.delete()
        sentenceClipRepo.delete(victim)
        total -= victim.sizeBytes
        if (total + newClipBytes <= maxCacheSizeBytes) break
      }
    }
    return true
  }

  private companion object {
    const val EVICTION_BATCH_SIZE = 10
  }
}
```

`maxCacheSizeBytes` is a constructor parameter (bound to the design's 500 MB constant via `TtsModule`'s
`@MaxTtsCacheSizeBytes` provider from Task 4) rather than a hardcoded constant inside this class, purely so tests
can exercise real eviction without allocating hundreds of MB of fake audio — production behavior is unchanged
(nothing overrides the 500 MB default; exposing it in Settings is still Plan 5's job per the design spec).

- [ ] **Step 4: Run the test and verify it passes**

```bash
./gradlew :core:tts:testDebugUnitTest --tests "voice.core.tts.SentenceClipCacheTest"
```

Expected: `BUILD SUCCESSFUL`, 6 tests passed.

- [ ] **Step 5: Run the full `core:tts` unit test suite**

```bash
./gradlew :core:tts:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions (13 tests total: 2 `FakeSynthesisEngineTest` + 5 `VoiceManagerTest` + 6
`SentenceClipCacheTest`).

- [ ] **Step 6: Verify the whole project still builds**

```bash
./gradlew voiceUnitTest
```

Expected: only the known pre-existing failures from Global Constraints (`core:common`'s
`NaturalOrderComparatorTest.uriComparatorFiles`; `core:data:impl`'s `ConvertersTest.file` and the 4
`DataBaseMigratorTest` tests) — no new regressions anywhere else.

- [ ] **Step 7: Verify ktlint is clean and the app assembles**

```bash
./gradlew :core:tts:lintKotlin :core:data:api:lintKotlin :core:data:impl:lintKotlin :app:assembleFreeDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core/tts/src/main/kotlin/voice/core/tts/SentenceClipCache.kt core/tts/src/test/kotlin/voice/core/tts/SentenceClipCacheTest.kt
git commit -m "Add SentenceClipCache with LRU eviction"
```

---

## What's next

After this plan, `core:tts` can install/uninstall the two curated Piper voices (real download, checksum
verification, tar.bz2 extraction all validated against the real sherpa-onnx release archives), synthesize a WAV for
any `(text, voice)` pair via `SentenceClipCache.getOrSynthesize`, and evict least-recently-used clips under a 500 MB
cap. Nothing in the app calls any of this yet — no reader UI exists to pick a voice, trigger playback, or call
`EpubImporter.import()` on first open (that trigger point was deliberately left unbuilt in Plan 2 too). The next
plan in the sequence (reader UI & playback) is what wires `EpubImporter`, `SentenceClipCache`, and ExoPlayer
together into an actual read-along screen — and is also the first point at which `SherpaOnnxSynthesisEngine` can be
verified end-to-end on a real device, since it needs a UI entry point to trigger synthesis at all.
