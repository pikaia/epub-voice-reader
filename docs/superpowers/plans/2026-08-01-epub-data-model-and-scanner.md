# EPUB Data Model & Scanner Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `.epub` files show up as books in the library. Extend `core:data`'s `BookContent` with a source-type discriminator and a voice-choice field, add new `EpubChapter`/`EpubSentence` tables for the text `core:epub` extracts, and extend `core:scanner` to detect `.epub` files, create a library entry for each one, and (via a not-yet-wired `EpubImporter`) parse and persist their chapters/sentences.

**Architecture:** An EPUB file is a single-file "book" with no natural audio chapters, so scanning creates exactly one synthetic `Chapter` row (id = the file's own `ChapterId`) so the existing `Book`/`BookContent` invariants (`currentChapter in chapters`) hold unchanged — this is additive, not a rewrite of `Book`/`Chapter`. The EPUB-internal chapter/sentence structure that `core:epub` extracts is stored separately in new `EpubChapter`/`EpubSentence` tables, keyed by `(bookId, index)`/`(bookId, chapterIndex, index)` rather than by `ChapterId`, since there's no file-per-chapter to derive an id from. Per the design spec, this text is parsed lazily on first open, not during the folder scan (parsing many large books up front would block app-start scanning) — so this plan builds `EpubImporter` (the `Uri`→`File` bridge plus parse-and-persist logic) as a standalone, unit-tested class that Plan 4 (reader UI) will call when a book is first opened. It is deliberately **not** wired into `MediaScanner`'s automatic scan.

**Tech Stack:** Kotlin, Room (AutoMigration for all schema changes — every change in this plan is a pure column/table addition, no data transform), Metro DI, Robolectric + `AndroidJUnit4` for tests that touch Room/`Uri`/`Context` (unlike `core:epub`'s pure-JVM tests from Plan 1).

## Global Constraints

- This is Plan 2 of the 5-plan staged sequence in `docs/superpowers/specs/2026-07-30-epub-ai-voice-reader-design.md`: parsing foundation (done) → **data model & scanner (this plan)** → Piper TTS integration → reader UI & playback → settings & polish.
- Module dependency direction per `AGENTS.md`/`docs/architecture.md`: core modules may depend on other core modules but never on features; no feature-to-feature deps. `core:scanner` gains a new dependency on `core:epub` in this plan (Task 4) — that edge is core-to-core and allowed.
- `core:epub` must keep zero Android-framework dependencies (Plan 1 constraint, unchanged by this plan). The `Uri`→`File` bridge therefore lives in `core:scanner` (`EpubImporter`), never inside `core:epub`.
- Package namespaces: `voice.core.data` (existing), `voice.core.scanner` (existing) — no new modules are created in this plan.
- Dependency versions go in `gradle/libs.versions.toml` only, via the version catalog — this plan adds no new third-party dependencies (only a new `implementation(projects.core.epub)` project edge).
- 2-space indentation, no unnecessary comments, named constructor arguments for data classes (matches existing `BookContent`/`Chapter` call sites) — matching existing Voice source style.
- All Room schema changes in this plan are `AutoMigration` entries (column/table additions only, following this codebase's established convention of using `AutoMigration` for every schema-compatible change since v51) — no hand-written `Migration` subclass is needed.
- Building requires Android Studio / Android SDK + JDK 17+ already installed — prerequisite, not part of this plan (already set up on this machine, see prior session notes).

---

### Task 1: Add `sourceType`/`voiceId` to `BookContent`

**Files:**
- Create: `core/data/api/src/main/kotlin/voice/core/data/BookSourceType.kt`
- Modify: `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/Converters.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt`

**Interfaces:**
- Consumes: nothing new
- Produces (for later tasks/plans): `BookSourceType` enum (`Audio`, `Epub`); `BookContent.sourceType: BookSourceType = BookSourceType.Audio` and `BookContent.voiceId: String? = null` — both defaulted, so every existing call site that builds a `BookContent` with named arguments (e.g. `BookParser.parse`) keeps compiling unchanged and continues to produce `Audio` books.

- [ ] **Step 1: Write a failing migration test**

Add to `core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt` (inside the `DataBaseMigratorTest` class, alongside the existing `migrate44`/`migrate43` tests):

```kotlin
  @Test
  fun migrate60() {
    val dbName = "testDb"
    val db = helper.createDatabase(dbName, 59)
    db.execSQL(
      "INSERT INTO `content2`(`id`,`playbackSpeed`,`skipSilence`,`isActive`,`lastPlayedAt`,`author`,`name`," +
        "`addedAt`,`chapters`,`currentChapter`,`positionInChapter`,`cover`,`gain`,`genre`,`narrator`,`series`,`part`) " +
        "VALUES ('book1', 1.0, 0, 1, '1970-01-01T00:00:00Z', NULL, 'A Book', '1970-01-01T00:00:00Z', '[]', " +
        "'chapter1', 0, NULL, 0, NULL, NULL, NULL, NULL)",
    )
    db.close()

    val migratedDb = helper.runMigrationsAndValidate(
      dbName,
      60,
      true,
      *allMigrations(),
    )

    val cursor = migratedDb.query("SELECT * FROM content2 WHERE id = 'book1'")
    cursor.moveToFirst()
    assertEquals(expected = "Audio", actual = cursor.getString("sourceType"))
    assertEquals(expected = null, actual = cursor.getStringOrNull("voiceId"))
    cursor.close()
  }
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.internals.internals.DataBaseMigratorTest.migrate60"
```

Expected: failure — Room has no exported schema for version 60 yet (`AppDb.VERSION` is still 59).

- [ ] **Step 3: Add the `BookSourceType` enum**

Create `core/data/api/src/main/kotlin/voice/core/data/BookSourceType.kt`:

```kotlin
package voice.core.data

public enum class BookSourceType {
  Audio,
  Epub,
}
```

- [ ] **Step 4: Add the new fields to `BookContent`**

In `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`, add two trailing constructor parameters (after `part: String?`):

```kotlin
  val part: String?,
  @ColumnInfo(defaultValue = "'Audio'")
  val sourceType: BookSourceType = BookSourceType.Audio,
  val voiceId: String? = null,
) {
```

- [ ] **Step 5: Add `BookSourceType` Room `TypeConverter`s**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/Converters.kt`, add the import `voice.core.data.BookSourceType` and these two methods (anywhere among the existing `@TypeConverter` pairs):

```kotlin
  @TypeConverter
  fun fromBookSourceType(value: BookSourceType): String = value.name

  @TypeConverter
  fun toBookSourceType(value: String): BookSourceType = BookSourceType.valueOf(value)
```

- [ ] **Step 6: Bump the database version and add the `AutoMigration`**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add one more `AutoMigration` entry and bump `VERSION`:

```kotlin
  autoMigrations = [
    AutoMigration(from = 51, to = 52),
    AutoMigration(from = 52, to = 53),
    AutoMigration(from = 54, to = 55),
    AutoMigration(from = 55, to = 56),
    AutoMigration(from = 56, to = 57, spec = Migration56::class),
    AutoMigration(from = 57, to = 58),
    AutoMigration(from = 58, to = 59),
    AutoMigration(from = 59, to = 60),
  ],
```

```kotlin
  internal companion object {
    const val VERSION = 60
    const val DATABASE_NAME = "autoBookDB"
  }
```

- [ ] **Step 7: Run the test and verify it passes**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.internals.internals.DataBaseMigratorTest.migrate60"
```

Expected: `BUILD SUCCESSFUL`. (This also regenerates `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/60.json` — stage it in the commit below.)

- [ ] **Step 8: Run the full `core:data:impl` unit test suite**

```bash
./gradlew :core:data:impl:testDebugUnitTest :core:data:api:compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`, no regressions in the existing migration/DAO tests.

- [ ] **Step 9: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/BookSourceType.kt core/data/api/src/main/kotlin/voice/core/data/BookContent.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/Converters.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/src/test/kotlin/voice/core/data/repo/internals/internals/DataBaseMigratorTest.kt core/data/impl/schemas
git commit -m "Add BookSourceType discriminator and voiceId to BookContent"
```

---

### Task 2: Add `EpubChapter`/`EpubSentence` tables and `EpubBookRepo`

**Files:**
- Create: `core/data/api/src/main/kotlin/voice/core/data/EpubChapter.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubChapterDao.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt`
- Create: `core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt`
- Create: `core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt`
- Create: `core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt`

**Interfaces:**
- Consumes: `BookId` (Task 1 module, unchanged)
- Produces (for later tasks): `EpubChapter(bookId: BookId, index: Int, title: String)`, `EpubSentence(bookId: BookId, chapterIndex: Int, index: Int, text: String)`, and
  `interface EpubBookRepo { suspend fun replaceChapters(bookId, chapters, sentences); suspend fun chapters(bookId): List<EpubChapter>; suspend fun sentences(bookId, chapterIndex): List<EpubSentence> }`
  bound into the Metro graph via `@ContributesBinding(AppScope::class)` — Task 4's `EpubImporter` consumes this directly.

- [ ] **Step 1: Add the entities**

Create `core/data/api/src/main/kotlin/voice/core/data/EpubChapter.kt`:

```kotlin
package voice.core.data

import androidx.room.Entity

@Entity(tableName = "epubChapter", primaryKeys = ["bookId", "index"])
public data class EpubChapter(
  val bookId: BookId,
  val index: Int,
  val title: String,
)
```

Create `core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt`:

```kotlin
package voice.core.data

import androidx.room.Entity

@Entity(tableName = "epubSentence", primaryKeys = ["bookId", "chapterIndex", "index"])
public data class EpubSentence(
  val bookId: BookId,
  val chapterIndex: Int,
  val index: Int,
  val text: String,
)
```

- [ ] **Step 2: Add the DAOs**

Create `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubChapterDao.kt`:

```kotlin
package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.EpubChapter

@Dao
public interface EpubChapterDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insertAll(chapters: List<EpubChapter>)

  @Query("SELECT * FROM epubChapter WHERE bookId = :bookId ORDER BY `index`")
  public suspend fun chapters(bookId: BookId): List<EpubChapter>

  @Query("DELETE FROM epubChapter WHERE bookId = :bookId")
  public suspend fun deleteForBook(bookId: BookId)
}
```

Create `core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt`:

```kotlin
package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.EpubSentence

@Dao
public interface EpubSentenceDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insertAll(sentences: List<EpubSentence>)

  @Query("SELECT * FROM epubSentence WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY `index`")
  public suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence>

  @Query("DELETE FROM epubSentence WHERE bookId = :bookId")
  public suspend fun deleteForBook(bookId: BookId)
}
```

- [ ] **Step 3: Add the repo interface**

Create `core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt`:

```kotlin
package voice.core.data.repo

import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence

public interface EpubBookRepo {

  public suspend fun replaceChapters(
    bookId: BookId,
    chapters: List<EpubChapter>,
    sentences: List<EpubSentence>,
  )

  public suspend fun chapters(bookId: BookId): List<EpubChapter>

  public suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence>
}
```

- [ ] **Step 4: Write a failing test for the repo impl**

Create `core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt`:

```kotlin
package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.internals.AppDb
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class EpubBookRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = EpubBookRepoImpl(
    chapterDao = db.epubChapterDao(),
    sentenceDao = db.epubSentenceDao(),
  )

  @Test
  fun replaceChaptersStoresChaptersAndSentences() = runTest {
    val bookId = BookId("content://book1")
    val chapters = listOf(
      EpubChapter(bookId = bookId, index = 0, title = "Chapter One"),
      EpubChapter(bookId = bookId, index = 1, title = "Chapter Two"),
    )
    val sentences = listOf(
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "First sentence."),
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "Second sentence."),
      EpubSentence(bookId = bookId, chapterIndex = 1, index = 0, text = "Third sentence."),
    )

    repo.replaceChapters(bookId, chapters, sentences)

    assertEquals(expected = chapters, actual = repo.chapters(bookId))
    assertEquals(
      expected = listOf("First sentence.", "Second sentence."),
      actual = repo.sentences(bookId, chapterIndex = 0).map { it.text },
    )
  }

  @Test
  fun replaceChaptersReplacesPreviousData() = runTest {
    val bookId = BookId("content://book1")
    repo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Old")),
      sentences = listOf(EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Old sentence.")),
    )

    repo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "New")),
      sentences = listOf(EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "New sentence.")),
    )

    assertEquals(expected = listOf("New"), actual = repo.chapters(bookId).map { it.title })
    assertEquals(expected = listOf("New sentence."), actual = repo.sentences(bookId, 0).map { it.text })
  }
}
```

- [ ] **Step 5: Run the test and verify it fails**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.EpubBookRepoImplTest"
```

Expected: compile failure — `EpubBookRepoImpl` doesn't exist, and `AppDb` has no `epubChapterDao()`/`epubSentenceDao()`.

- [ ] **Step 6: Implement the repo and wire it into `AppDb`/`PersistenceModule`**

Create `core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt`:

```kotlin
package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.internals.dao.EpubChapterDao
import voice.core.data.repo.internals.dao.EpubSentenceDao

@ContributesBinding(AppScope::class)
public class EpubBookRepoImpl(
  private val chapterDao: EpubChapterDao,
  private val sentenceDao: EpubSentenceDao,
) : EpubBookRepo {

  override suspend fun replaceChapters(
    bookId: BookId,
    chapters: List<EpubChapter>,
    sentences: List<EpubSentence>,
  ) {
    chapterDao.deleteForBook(bookId)
    sentenceDao.deleteForBook(bookId)
    chapterDao.insertAll(chapters)
    sentenceDao.insertAll(sentences)
  }

  override suspend fun chapters(bookId: BookId): List<EpubChapter> = chapterDao.chapters(bookId)

  override suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence> = sentenceDao.sentences(bookId, chapterIndex)
}
```

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add the two entities, bump the version again, and declare the DAO accessors:

```kotlin
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.internals.dao.EpubChapterDao
import voice.core.data.repo.internals.dao.EpubSentenceDao

@Database(
  entities = [
    Chapter::class,
    BookContent::class,
    Bookmark::class,
    BookSearchFts::class,
    RecentBookSearch::class,
    EpubChapter::class,
    EpubSentence::class,
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
  ],
)
@TypeConverters(Converters::class)
public abstract class AppDb : RoomDatabase() {

  public abstract fun chapterDao(): ChapterDao
  public abstract fun bookContentDao(): BookContentDao
  public abstract fun bookmarkDao(): BookmarkDao
  public abstract fun epubChapterDao(): EpubChapterDao
  public abstract fun epubSentenceDao(): EpubSentenceDao

  public abstract fun recentBookSearchDao(): RecentBookSearchDao

  internal companion object {
    const val VERSION = 61
    const val DATABASE_NAME = "autoBookDB"
  }
}
```

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt`, add:

```kotlin
  @Provides
  private fun epubChapterDao(appDb: AppDb): EpubChapterDao = appDb.epubChapterDao()

  @Provides
  private fun epubSentenceDao(appDb: AppDb): EpubSentenceDao = appDb.epubSentenceDao()
```

(with imports `voice.core.data.repo.internals.dao.EpubChapterDao` and `voice.core.data.repo.internals.dao.EpubSentenceDao`.)

- [ ] **Step 7: Run the test and verify it passes**

```bash
./gradlew :core:data:impl:testDebugUnitTest --tests "voice.core.data.repo.EpubBookRepoImplTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed. (This regenerates `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/61.json`.)

- [ ] **Step 8: Run the full `core:data:impl` unit test suite**

```bash
./gradlew :core:data:impl:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 9: Commit**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/EpubChapter.kt core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubChapterDao.kt core/data/api/src/main/kotlin/voice/core/data/repo/internals/dao/EpubSentenceDao.kt core/data/api/src/main/kotlin/voice/core/data/repo/EpubBookRepo.kt core/data/impl/src/main/kotlin/voice/core/data/repo/EpubBookRepoImpl.kt core/data/impl/src/test/kotlin/voice/core/data/repo/EpubBookRepoImplTest.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/PersistenceModule.kt core/data/impl/schemas
git commit -m "Add EpubChapter/EpubSentence tables and EpubBookRepo"
```

---

### Task 3: Detect `.epub` files in the scanner and create a library entry

**Files:**
- Create: `core/data/api/src/main/kotlin/voice/core/data/SupportedEpubFormats.kt`
- Create: `core/scanner/src/main/kotlin/voice/core/scanner/EpubBookParser.kt`
- Create: `core/scanner/src/test/kotlin/voice/core/scanner/EpubBookParserTest.kt`
- Modify: `core/scanner/src/main/kotlin/voice/core/scanner/MediaScanner.kt`
- Modify: `core/scanner/src/test/kotlin/voice/core/scanner/MediaScannerTest.kt`

**Interfaces:**
- Consumes: `BookSourceType`, `BookContent`, `Chapter`, `BookContentRepo`, `ChapterRepo` (Tasks 1–2); `CachedDocumentFile`, `CachedDocumentFile.nameWithoutExtension()` (existing, `core:documentfile`)
- Produces: `fun CachedDocumentFile.isEpubFile(): Boolean`; `internal class EpubBookParser { suspend fun parseAndStore(file: CachedDocumentFile): BookContent }` — creates one `BookContent` (`sourceType = Epub`) plus one synthetic `Chapter` per `.epub` file, so it shows up in the library like any other book. Does not parse the EPUB's internal chapters/sentences — that's Task 4/`EpubImporter`.

- [ ] **Step 1: Add EPUB file detection**

Create `core/data/api/src/main/kotlin/voice/core/data/SupportedEpubFormats.kt`:

```kotlin
package voice.core.data

import voice.core.documentfile.CachedDocumentFile

public fun CachedDocumentFile.isEpubFile(): Boolean {
  if (!isFile) return false
  val name = name ?: return false
  return name.substringAfterLast(".").lowercase() == "epub"
}
```

- [ ] **Step 2: Write a failing test for `EpubBookParser`**

Create `core/scanner/src/test/kotlin/voice/core/scanner/EpubBookParserTest.kt`:

```kotlin
package voice.core.scanner

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.documentfile.FileBasedDocumentFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class EpubBookParserTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val contentRepo = FakeBookContentRepo()
  private val chapterRepo = FakeChapterRepo()
  private val parser = EpubBookParser(contentRepo, chapterRepo)

  @Test
  fun createsBookContentWithEpubSourceTypeAndFileNameAsTitle() = runTest {
    val file = testFolder.newFile("My Book.epub")

    val content = parser.parseAndStore(FileBasedDocumentFile(file))

    assertEquals(expected = "My Book", actual = content.name)
    assertEquals(expected = BookSourceType.Epub, actual = content.sourceType)
    assertEquals(expected = listOf(ChapterId(file.toUri())), actual = content.chapters)
    assertTrue(content.isActive)
  }

  @Test
  fun reactivatesAnExistingBookOnRescan() = runTest {
    val file = testFolder.newFile("My Book.epub")

    val firstScan = parser.parseAndStore(FileBasedDocumentFile(file))
    contentRepo.put(firstScan.copy(isActive = false))

    val secondScan = parser.parseAndStore(FileBasedDocumentFile(file))

    assertTrue(secondScan.isActive)
  }

  private class FakeBookContentRepo : BookContentRepo {
    private val content = MutableStateFlow<Map<BookId, BookContent>>(emptyMap())

    override fun flow(): Flow<List<BookContent>> = content.map { it.values.toList() }
    override suspend fun all(): List<BookContent> = content.value.values.toList()
    override fun flow(id: BookId): Flow<BookContent?> = content.map { it[id] }
    override suspend fun get(id: BookId): BookContent? = content.value[id]
    override suspend fun setAllInactiveExcept(ids: List<BookId>) {}
    override suspend fun put(content: BookContent) {
      this.content.value = this.content.value + (content.id to content)
    }
  }

  private class FakeChapterRepo : ChapterRepo {
    private val chapters = mutableMapOf<ChapterId, Chapter>()
    override suspend fun get(id: ChapterId): Chapter? = chapters[id]
    override suspend fun put(chapter: Chapter) {
      chapters[chapter.id] = chapter
    }
  }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubBookParserTest"
```

Expected: compile failure — `EpubBookParser` doesn't exist yet.

- [ ] **Step 4: Implement `EpubBookParser`**

Create `core/scanner/src/main/kotlin/voice/core/scanner/EpubBookParser.kt`:

```kotlin
package voice.core.scanner

import dev.zacsweers.metro.Inject
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.data.repo.getOrPut
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.nameWithoutExtension
import java.time.Instant

@Inject
internal class EpubBookParser(
  private val contentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
) {

  suspend fun parseAndStore(file: CachedDocumentFile): BookContent {
    val chapterId = ChapterId(file.uri)
    val name = file.nameWithoutExtension()
    chapterRepo.put(
      Chapter(
        id = chapterId,
        name = name,
        duration = 0L,
        fileLastModified = Instant.ofEpochMilli(file.lastModified),
        fileSize = file.length,
        markData = emptyList(),
      ),
    )

    val id = BookId(file.uri)
    val content = contentRepo.getOrPut(id) {
      BookContent(
        id = id,
        isActive = true,
        addedAt = Instant.now(),
        author = null,
        lastPlayedAt = Instant.EPOCH,
        name = name,
        playbackSpeed = 1F,
        skipSilence = false,
        chapters = listOf(chapterId),
        positionInChapter = 0L,
        currentChapter = chapterId,
        cover = null,
        gain = 0F,
        genre = null,
        narrator = null,
        series = null,
        part = null,
        sourceType = BookSourceType.Epub,
      )
    }
    if (!content.isActive) {
      val reactivated = content.copy(isActive = true)
      contentRepo.put(reactivated)
      return reactivated
    }
    return content
  }
}
```

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubBookParserTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 6: Commit the standalone parser**

```bash
git add core/data/api/src/main/kotlin/voice/core/data/SupportedEpubFormats.kt core/scanner/src/main/kotlin/voice/core/scanner/EpubBookParser.kt core/scanner/src/test/kotlin/voice/core/scanner/EpubBookParserTest.kt
git commit -m "Detect epub files and parse them into a stub BookContent"
```

- [ ] **Step 7: Write a failing end-to-end scanner test**

In `core/scanner/src/test/kotlin/voice/core/scanner/MediaScannerTest.kt`, add this test (anywhere among the other `@Test` methods):

```kotlin
  @Test
  fun scanSingleEpubFile() = test {
    val book = epubFile(parent = folder("books"), "test.epub")
    scan(FolderType.SingleFile, book)
    assertBookContents(
      BookContentView(book, chapters = listOf(book)),
    )
  }
```

In the same file's `TestEnvironment` class, add the `epubFile` helper next to `audioFile`:

```kotlin
    fun epubFile(
      parent: File,
      name: String,
    ): File {
      check(name.endsWith(".epub"))
      return File(parent, name)
        .also {
          it.parentFile?.mkdirs()
          check(it.createNewFile())
        }
    }
```

And update the `scanner` construction inside `TestEnvironment` to pass the new dependency:

```kotlin
    private val scanner = MediaScanner(
      contentRepo = bookContentRepo,
      chapterParser = ChapterParser(
        chapterRepo = chapterRepo,
        mediaAnalyzer = mediaAnalyzer,
      ),
      bookParser = BookParser(
        contentRepo = bookContentRepo,
        mediaAnalyzer = mediaAnalyzer,
        fileFactory = FileBasedDocumentFactory,
      ),
      epubBookParser = EpubBookParser(
        contentRepo = bookContentRepo,
        chapterRepo = chapterRepo,
      ),
      deviceHasPermissionBug = mockk(),
    )
```

- [ ] **Step 8: Run the test and verify it fails**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.MediaScannerTest"
```

Expected: compile failure — `MediaScanner`'s constructor doesn't have an `epubBookParser` parameter yet.

- [ ] **Step 9: Wire `EpubBookParser` into `MediaScanner`**

In `core/scanner/src/main/kotlin/voice/core/scanner/MediaScanner.kt`, add the constructor parameter and the routing branch:

```kotlin
import voice.core.data.isEpubFile

@Inject
internal class MediaScanner(
  private val contentRepo: BookContentRepo,
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val epubBookParser: EpubBookParser,
  private val deviceHasPermissionBug: DeviceHasStoragePermissionBug,
) {
```

```kotlin
  private suspend fun scan(file: CachedDocumentFile) {
    if (file.isEpubFile()) {
      epubBookParser.parseAndStore(file)
      return
    }

    val parseResult = chapterParser.parse(file)
    val chapters = parseResult.chapters
    if (chapters.isEmpty()) return
    ...
```

(Leave the rest of `scan(file)` — the audio path below the `if (chapters.isEmpty()) return` line — unchanged.)

- [ ] **Step 10: Run the test and verify it passes**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.MediaScannerTest"
```

Expected: `BUILD SUCCESSFUL`, all `MediaScannerTest` tests (including the new `scanSingleEpubFile`) pass.

- [ ] **Step 11: Run the full `core:scanner` unit test suite**

```bash
./gradlew :core:scanner:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions in `ChapterParserTest`/`BookParserTest`/etc.

- [ ] **Step 12: Commit**

```bash
git add core/scanner/src/main/kotlin/voice/core/scanner/MediaScanner.kt core/scanner/src/test/kotlin/voice/core/scanner/MediaScannerTest.kt
git commit -m "Route epub files to EpubBookParser during folder scanning"
```

---

### Task 4: Build `EpubImporter` (Uri→File bridge, parse, and persist)

**Files:**
- Modify: `core/scanner/build.gradle.kts`
- Create: `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt`
- Create: `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt`

**Interfaces:**
- Consumes: `EpubParser`, `EpubParseResult` (`core:epub`, Plan 1 — already `@ContributesBinding`-bound); `EpubBookRepo` (Task 2)
- Produces: `public class EpubImporter { suspend fun import(bookId: BookId, file: CachedDocumentFile): EpubParseResult }` — copies the `Uri`'s bytes to a private cache file (since `EpubParser.parse()` takes a `java.io.File` and must stay Android-free), parses it, and on success persists `EpubChapter`/`EpubSentence` rows via `EpubBookRepo`. This resolves the `File` vs `Uri` mismatch Plan 1's review flagged. **Not called from `MediaScanner` in this plan** — Plan 4 (reader UI) will call it when a book is first opened, per the design's lazy-parsing decision.

- [ ] **Step 1: Add the `core:epub` dependency**

In `core/scanner/build.gradle.kts`, add to the `dependencies` block:

```kotlin
  implementation(projects.core.epub)
```

- [ ] **Step 2: Write a failing test**

Create `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt`:

```kotlin
package voice.core.scanner

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.FileBasedDocumentFile
import voice.core.epub.DefaultEpubParser
import voice.core.epub.EpubParseResult
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(AndroidJUnit4::class)
class EpubImporterTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val repo = FakeEpubBookRepo()
  private val importer = EpubImporter(
    context = context,
    epubParser = DefaultEpubParser(),
    epubBookRepo = repo,
  )

  @Test
  fun importsAndPersistsChaptersAndSentencesFromAContentUri() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toUri())

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    assertEquals(expected = listOf("Chapter One"), actual = repo.chapters(bookId).map { it.title })
    assertEquals(
      expected = listOf("Hello there. This is chapter one."),
      actual = repo.sentences(bookId, chapterIndex = 0).map { it.text },
    )
  }

  @Test
  fun returnsMalformedWithoutPersistingWhenTheFileIsNotAnEpub() = runTest {
    val file = File(testFolder.newFolder(), "not-a-book.epub")
    file.writeBytes(byteArrayOf(1, 2, 3))
    val bookId = BookId(file.toUri())

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Malformed>(result)
    assertEquals(expected = emptyList(), actual = repo.chapters(bookId))
  }

  private fun buildMinimalEpub(file: File): File {
    ZipOutputStream(file.outputStream()).use { zip ->
      fun entry(name: String, content: String) {
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
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubImporterTest"
```

Expected: compile failure — `EpubImporter` doesn't exist yet.

- [ ] **Step 4: Implement `EpubImporter`**

Create `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt`:

```kotlin
package voice.core.scanner

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.epub.EpubParseResult
import voice.core.epub.EpubParser
import voice.core.logging.api.Logger
import java.io.File
import java.util.UUID

@Inject
public class EpubImporter(
  private val context: Context,
  private val epubParser: EpubParser,
  private val epubBookRepo: EpubBookRepo,
) {

  public suspend fun import(
    bookId: BookId,
    file: CachedDocumentFile,
  ): EpubParseResult {
    return withContext(Dispatchers.IO) {
      val cacheFile = File(context.cacheDir, "epub-import-${UUID.randomUUID()}.epub")
      try {
        val input = context.contentResolver.openInputStream(file.uri)
        if (input == null) {
          Logger.w("Could not open input stream for $file")
          return@withContext EpubParseResult.Malformed("could not open $file")
        }
        input.use { source ->
          cacheFile.outputStream().use { output -> source.copyTo(output) }
        }
        val result = epubParser.parse(cacheFile)
        if (result is EpubParseResult.Success) {
          persist(bookId, result)
        }
        result
      } finally {
        cacheFile.delete()
      }
    }
  }

  private suspend fun persist(
    bookId: BookId,
    result: EpubParseResult.Success,
  ) {
    val chapters = result.book.chapters.mapIndexed { index, chapter ->
      EpubChapter(bookId = bookId, index = index, title = chapter.title)
    }
    val sentences = result.book.chapters.flatMapIndexed { chapterIndex, chapter ->
      chapter.sentences.mapIndexed { sentenceIndex, text ->
        EpubSentence(bookId = bookId, chapterIndex = chapterIndex, index = sentenceIndex, text = text)
      }
    }
    epubBookRepo.replaceChapters(bookId, chapters, sentences)
  }
}
```

- [ ] **Step 5: Run the test and verify it passes**

```bash
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubImporterTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 6: Run the full `core:scanner` unit test suite**

```bash
./gradlew :core:scanner:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, no regressions.

- [ ] **Step 7: Verify the whole project still builds**

```bash
./gradlew voiceUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add core/scanner/build.gradle.kts core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt
git commit -m "Add EpubImporter to bridge SAF Uris into EpubParser and persist results"
```

---

## What's next

After this plan, `.epub` files dropped into a watched folder show up in the library as books (`sourceType = Epub`, one synthetic chapter, title from the filename), and `core:data` has `EpubChapter`/`EpubSentence` tables plus `EpubBookRepo` ready to hold their real internal structure. `EpubImporter` can turn a `CachedDocumentFile` into persisted chapters/sentences via `core:epub`'s parser, but nothing calls it yet — no reader screen exists to trigger "first open." The next plan in the sequence (Piper TTS integration) builds `core:tts` (voice manager, synthesis engine, sentence-clip cache) independently of the scanner; the plan after that (reader UI & playback) is what will finally call `EpubImporter.import()` when a user opens an EPUB book for the first time, and render the persisted `EpubChapter`/`EpubSentence` rows in the read-along screen.
