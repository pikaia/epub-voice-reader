# Kindle-Style EPUB Reading UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the EPUB reader's one-sentence-per-row list with continuous paragraph-flowing text (soft highlight, keep-in-view scroll, tap-to-seek), and align its chrome (icon bar, cover header, chapter picker, bottom playback bar) with the audiobook player screen.

**Architecture:** `core/epub`'s parser is extended to capture real paragraph boundaries (a Room migration adds `paragraphIndex` to `EpubSentence` and a `hasParagraphData` backfill marker to `BookContent`). `EpubReaderViewModel` groups sentences into paragraphs and gains sleep-timer/speed/volume-gain/skip-silence wiring that calls the same `PlayerController`/`SleepTimer` collaborators `BookPlayViewModel` already uses. Several small, already-decoupled chrome composables (`ChapterRow`, `Cover`, `AppBarTitle`, `CloseIcon`, `OverflowMenu`, `SkipButton`) move from `features:playbackScreen` into `core:ui` for direct reuse by both screens; `SpeedDialog`/`VolumeGainDialog`/`VolumeGainFormatter` move too, decoupled from `BookPlayViewModel` first. `EpubReaderView` is rewritten last, on top of all of the above.

**Tech Stack:** Kotlin, Jetpack Compose, Room (KSP), Jsoup, Metro DI, Molecule (`launchMolecule`)/Turbine for ViewModel tests, MockK, JUnit4/Robolectric for Android-module tests.

## Global Constraints

- Room schema is at `AppDb.VERSION = 65` (`core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt:69`); this plan bumps it to 66 via a single `AutoMigration(from = 65, to = 66)` covering both new columns.
- This machine has no JDK/Android SDK on `PATH` — every Gradle invocation needs `JAVA_HOME` exported first: `export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"`.
- `androidx.room.testing.MigrationTestHelper`-based tests fail unconditionally on this Windows machine (a pre-existing, environment-specific bug unrelated to any branch) — the Room migration in Task 2 is verified via the generated schema JSON diff, not a `MigrationTestHelper` run.
- All touched modules use `testDebugUnitTest` (confirmed via `./gradlew :features:epubReader:tasks` etc.), not the bare `test` alias.
- No Compose UI test infrastructure exists anywhere in this project (`core:epub`, `features:epubReader`, `features:playbackScreen` all confirmed to have zero `createComposeRule` usage) — Compose UI changes in Tasks 7–8 are verified by compiling (`:features:epubReader:compileDebugKotlin`) and a full build, not new UI test scaffolding; final visual correctness needs on-device manual verification (out of scope for this plan's automated steps, call it out at the end).
- Follow this project's existing 2-space Kotlin indentation, no semicolons, and match each touched module's existing `public`/no-modifier convention exactly as observed in that module's other files (`core:epub`/`core:ui` use implicit-public with no `public` keyword; `features:epubReader` uses explicit `public` modifiers — match whichever the target file already does).

---

## Task 1: Paragraph-aware sentence parsing in `core/epub`

**Files:**
- Modify: `core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt`
- Modify: `core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt`
- Test: `core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt`

**Interfaces:**
- Consumes: nothing new — `Jsoup.parse(html): Document` (already used), `Document.body(): Element`, `Element.tagName(): String`, `Element.children(): Elements`, `Element.select(cssQuery: String): Elements`, `Element.text(): String` (all existing Jsoup API, already imported in this file).
- Produces: `ParsedSentence(val text: String, val paragraphIndex: Int)` (new data class in `EpubModels.kt`); `ParsedChapter.sentences: List<ParsedSentence>` (changed from `List<String>`) — consumed by Task 2's `EpubImporter.persist()`.

- [ ] **Step 1: Write the failing test for the new `ParsedSentence` shape and paragraph boundaries**

Replace the three existing sentence-shape assertions and add new paragraph-boundary cases in `core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt`:

```kotlin
  @Test
  fun `parses a single chapter with a single sentence`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello there.</p>")),
    )

    val result = parser.parse(file)

    assertEquals(
      expected = EpubParseResult.Success(
        ParsedBook(
          chapters = listOf(
            ParsedChapter(
              title = "Intro",
              sentences = listOf(ParsedSentence(text = "Hello there.", paragraphIndex = 0)),
            ),
          ),
        ),
      ),
      actual = result,
    )
  }

  @Test
  fun `splits chapter body into multiple sentences within the same paragraph`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(
          title = "Intro",
          bodyHtml = "<p>Hello there. This is chapter one. Great stuff.</p>",
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf(
        ParsedSentence("Hello there.", paragraphIndex = 0),
        ParsedSentence("This is chapter one.", paragraphIndex = 0),
        ParsedSentence("Great stuff.", paragraphIndex = 0),
      ),
      actual = result.book.chapters.single().sentences,
    )
  }

  @Test
  fun `assigns increasing paragraph indices to separate p tags in document order`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(
          title = "Intro",
          bodyHtml = "<p>First paragraph.</p><p>Second paragraph.</p><p>Third paragraph.</p>",
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf(
        ParsedSentence("First paragraph.", paragraphIndex = 0),
        ParsedSentence("Second paragraph.", paragraphIndex = 1),
        ParsedSentence("Third paragraph.", paragraphIndex = 2),
      ),
      actual = result.book.chapters.single().sentences,
    )
  }

  @Test
  fun `does not double-count a wrapper div's own text alongside its nested p children`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(
          title = "Intro",
          bodyHtml = "<div><p>Wrapped first.</p><p>Wrapped second.</p></div>",
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf(
        ParsedSentence("Wrapped first.", paragraphIndex = 0),
        ParsedSentence("Wrapped second.", paragraphIndex = 1),
      ),
      actual = result.book.chapters.single().sentences,
    )
  }

  @Test
  fun `treats list items and headers as their own paragraphs`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(
          title = "Intro",
          bodyHtml = "<h1>A Heading.</h1><ul><li>First item.</li><li>Second item.</li></ul>",
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf(
        ParsedSentence("A Heading.", paragraphIndex = 0),
        ParsedSentence("First item.", paragraphIndex = 1),
        ParsedSentence("Second item.", paragraphIndex = 2),
      ),
      actual = result.book.chapters.single().sentences,
    )
  }

  @Test
  fun `falls back to a single paragraph when the chapter has no block elements at all`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(
          title = "Intro",
          bodyHtml = "Bare text with no wrapping element. A second sentence.",
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf(
        ParsedSentence("Bare text with no wrapping element.", paragraphIndex = 0),
        ParsedSentence("A second sentence.", paragraphIndex = 0),
      ),
      actual = result.book.chapters.single().sentences,
    )
  }
```

Also update the existing `` `returns chapters in spine order` `` test — it only asserts on `.title`, so it needs no change. Leave it as-is.

- [ ] **Step 2: Run the tests to verify they fail**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :core:epub:testDebugUnitTest --tests "voice.core.epub.DefaultEpubParserTest"
```

Expected: compile failure — `ParsedSentence` doesn't exist yet, `ParsedChapter(sentences = listOf("Hello there."))` (a `String` list) doesn't type-check against the new expected shape once `EpubModels.kt` is touched. (If `EpubModels.kt` hasn't been touched yet, these fail with an `AssertionError` comparing `List<String>` values against nothing meaningful — either way, not passing.)

- [ ] **Step 3: Add `ParsedSentence` and change `ParsedChapter.sentences`'s type**

Replace `core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt` in full:

```kotlin
package voice.core.epub

data class ParsedBook(
  val chapters: List<ParsedChapter>,
  val coverBytes: ByteArray? = null,
)

data class ParsedChapter(
  val title: String,
  val sentences: List<ParsedSentence>,
)

data class ParsedSentence(
  val text: String,
  val paragraphIndex: Int,
)

sealed interface EpubParseResult {
  data class Success(val book: ParsedBook) : EpubParseResult
  data object DrmProtected : EpubParseResult
  data class Malformed(val reason: String) : EpubParseResult
}
```

- [ ] **Step 4: Rewrite `DefaultEpubParser.parseChapter` to walk leaf block-level elements**

In `core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt`, add the import `org.jsoup.select.Elements` is not needed (already using `org.jsoup.Jsoup`/`org.w3c.dom.Element` for XML — note the OPF parsing uses `org.w3c.dom.Element`, but `parseChapter` uses Jsoup's own `org.jsoup.nodes.Element`; add `import org.jsoup.nodes.Element as JsoupElement` is unnecessary since Jsoup's `Element` is already implicitly used via `Jsoup.parse(html)`'s return type inference — just add the two new private members below `parseChapter`).

Replace the existing `parseChapter` and `splitSentences` functions with:

```kotlin
  private val BLOCK_TAGS = setOf("p", "div", "li", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6")

  private fun parseChapter(
    html: String,
    fallbackTitle: String,
  ): ParsedChapter {
    val document = Jsoup.parse(html)
    val title = document.title().ifBlank { fallbackTitle }
    val blocks = leafBlocks(document.body()).ifEmpty { listOf(document.body()) }
    val sentences = blocks.flatMapIndexed { paragraphIndex, block ->
      splitSentences(block.text()).map { text -> ParsedSentence(text, paragraphIndex) }
    }
    return ParsedChapter(title = title, sentences = sentences)
  }

  // Depth-first, document-order walk collecting "leaf" block-level elements — a block tag with no
  // block-level descendant anywhere below it. A wrapper <div> containing several <p>s is NOT a
  // leaf (it has block descendants) and is skipped in favor of descending into its <p> children;
  // a plain <p> with only inline children (e.g. <em>, <span>) IS a leaf. Any of this element's own
  // direct text mixed alongside block children (a rare, malformed-ish pattern) is not captured by
  // any leaf and is intentionally dropped, matching how the single-paragraph fallback below only
  // covers the "no block elements anywhere" case, not partial/mixed structures.
  private fun leafBlocks(root: org.jsoup.nodes.Element): List<org.jsoup.nodes.Element> {
    val result = mutableListOf<org.jsoup.nodes.Element>()
    fun visit(element: org.jsoup.nodes.Element) {
      val isBlock = element.tagName() in BLOCK_TAGS
      val hasBlockDescendant = isBlock && element.select(BLOCK_TAGS.joinToString(",")).isNotEmpty()
      if (isBlock && !hasBlockDescendant) {
        result += element
        return
      }
      for (child in element.children()) {
        visit(child)
      }
    }
    visit(root)
    return result
  }

  private fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(SENTENCE_LOCALE)
    iterator.setText(text)
    val sentences = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      val sentence = text.substring(start, end).trim()
      if (sentence.isNotEmpty()) {
        sentences += sentence
      }
      start = end
      end = iterator.next()
    }
    return sentences
  }
```

Note `hasBlockDescendant` is short-circuited on `isBlock` — for the root `<body>` call (which is never itself a block tag), this evaluates to `false` immediately without running `select(...)`, so `visit(body)` always falls through to visiting `body`'s children, exactly as intended.

- [ ] **Step 5: Run the tests to verify they pass**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :core:epub:testDebugUnitTest --tests "voice.core.epub.DefaultEpubParserTest"
```

Expected: PASS, all cases including the pre-existing cover-extraction and malformed/DRM tests (unaffected by this change, but must still compile and pass since `ParsedChapter`'s shape changed).

- [ ] **Step 6: Commit**

```
git add core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt
git commit -m "Capture real paragraph boundaries when parsing EPUB chapters"
```

---

## Task 2: Room migration (v65→v66) and threading `paragraphIndex` through import

**Files:**
- Modify: `core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt`
- Modify: `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`
- Modify: `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`
- Modify: `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt`
- Test: `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt`
- Create (generated by build, not hand-written): `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/66.json`

**Interfaces:**
- Consumes: `ParsedSentence(text, paragraphIndex)` from Task 1's `ParsedChapter.sentences`.
- Produces: `EpubSentence.paragraphIndex: Int` (consumed by Task 4's paragraph-grouping); `BookContent.hasParagraphData: Boolean` (consumed by Task 3's merged backfill condition).

- [ ] **Step 1: Write the failing test for `paragraphIndex` persistence**

In `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt`, add:

```kotlin
  @Test
  fun persistsTheParagraphIndexParsedForEachSentence() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toUri())

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    assertEquals(
      expected = listOf(0, 0),
      actual = repo.sentences(bookId, chapterIndex = 0).map { it.paragraphIndex },
    )
  }
```

`buildMinimalEpub`'s chapter body is `<p>Hello there. This is chapter one.</p>` (both sentences already in the assertion above at line 65) — a single `<p>`, so both persisted sentences should carry `paragraphIndex = 0`.

- [ ] **Step 2: Run the test to verify it fails**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubImporterTest"
```

Expected: compile failure — `EpubSentence` has no `paragraphIndex` property yet.

- [ ] **Step 3: Add `paragraphIndex` to `EpubSentence`**

Replace `core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt` in full:

```kotlin
package voice.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "epubSentence", primaryKeys = ["bookId", "chapterIndex", "index"])
public data class EpubSentence(
  val bookId: BookId,
  val chapterIndex: Int,
  val index: Int,
  val text: String,
  @ColumnInfo(defaultValue = "-1")
  val paragraphIndex: Int = -1,
)
```

`-1` is an unambiguous "not yet computed" sentinel — real paragraph indices from Task 1's parser are always `>= 0`.

- [ ] **Step 4: Add `hasParagraphData` to `BookContent`**

In `core/data/api/src/main/kotlin/voice/core/data/BookContent.kt`, add a new field after `epubTotalCharacterCount`:

```kotlin
  @ColumnInfo(defaultValue = "0")
  val epubTotalCharacterCount: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val hasParagraphData: Boolean = false,
) {
```

(Room stores `Boolean` columns as `0`/`1`; `defaultValue = "0"` is the established pattern already used for every other `Int`-default field in this same entity.)

- [ ] **Step 5: Bump the schema version and add the migration**

In `core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt`, add `AutoMigration(from = 65, to = 66)` to the `autoMigrations` list (after the `64, to = 65` entry) and bump `VERSION`:

```kotlin
    AutoMigration(from = 63, to = 64),
    AutoMigration(from = 64, to = 65),
    AutoMigration(from = 65, to = 66),
  ],
)
@TypeConverters(Converters::class)
public abstract class AppDb : RoomDatabase() {
```

```kotlin
  internal companion object {
    const val VERSION = 66
    const val DATABASE_NAME = "autoBookDB"
  }
```

Both new columns are purely additive with default values, matching every prior version bump in this list (no `Migration56`-style manual spec class needed).

- [ ] **Step 6: Thread `paragraphIndex` through `EpubImporter.persist()`**

In `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt`, update `persist()`:

```kotlin
  private suspend fun persist(
    bookId: BookId,
    result: EpubParseResult.Success,
  ) {
    val chapters = result.book.chapters.mapIndexed { index, chapter ->
      EpubChapter(bookId = bookId, index = index, title = chapter.title)
    }
    val sentences = result.book.chapters.flatMapIndexed { chapterIndex, chapter ->
      chapter.sentences.mapIndexed { sentenceIndex, sentence ->
        EpubSentence(
          bookId = bookId,
          chapterIndex = chapterIndex,
          index = sentenceIndex,
          text = sentence.text,
          paragraphIndex = sentence.paragraphIndex,
        )
      }
    }
    epubBookRepo.replaceChapters(bookId, chapters, sentences)
  }
```

- [ ] **Step 7: Build to generate the v66 schema JSON, then run the test**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :core:data:impl:kspDebugKotlin
```

This generates `core/data/impl/schemas/voice.core.data.repo.internals.AppDb/66.json` from the `@Database` annotation processor (`room.schemaLocation` is configured at `core/data/impl/build.gradle.kts:9`). Confirm the file was created:

```
ls core/data/impl/schemas/voice.core.data.repo.internals.AppDb/66.json
```

Then:

```
./gradlew :core:scanner:testDebugUnitTest --tests "voice.core.scanner.EpubImporterTest"
```

Expected: PASS.

- [ ] **Step 8: Verify the schema diff by eye**

```
git diff --stat core/data/impl/schemas/
```

Expected: exactly one new file, `voice.core.data.repo.internals.AppDb/66.json`, and no changes to `65.json`. Open the new file and confirm it declares `epubSentence.paragraphIndex` (`INTEGER`, `NOT NULL`, default `-1`) and `content2.hasParagraphData` (`INTEGER`, `NOT NULL`, default `0`) — this stands in for a real migration-test run, per the Global Constraints note about `MigrationTestHelper` on this machine.

- [ ] **Step 9: Commit**

```
git add core/data/api/src/main/kotlin/voice/core/data/EpubSentence.kt core/data/api/src/main/kotlin/voice/core/data/BookContent.kt core/data/impl/src/main/kotlin/voice/core/data/repo/internals/AppDb.kt core/data/impl/schemas/voice.core.data.repo.internals.AppDb/66.json core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt
git commit -m "Add paragraphIndex/hasParagraphData columns (Room v65->v66) and persist paragraphIndex on import"
```

---

## Task 3: Merge the paragraph-data backfill into `EpubBookOpener`'s existing cover-backfill reimport

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`
- Test: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`

**Interfaces:**
- Consumes: `BookContent.hasParagraphData` (Task 2).
- Produces: nothing new for later tasks — this task only changes when/how `EpubBookOpener.open()` reimports, not its `OpenResult` shape.

- [ ] **Step 1: Write the failing tests for the merged condition**

Add to `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`:

```kotlin
  @Test
  fun `backfills paragraph data for a book whose chapters were parsed before paragraphIndex existed`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = false,
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello there.", paragraphIndex = -1),
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "This is chapter one.", paragraphIndex = -1),
      ),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = true, actual = bookContentRepo.get(bookId)?.hasParagraphData)
    assertEquals(
      expected = listOf(0, 0),
      actual = epubBookRepo.sentences(bookId, 0).map { it.paragraphIndex },
    )
  }

  @Test
  fun `does not re-import when both cover and paragraph data are already present`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = true,
      ),
    )
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
  fun `re-imports exactly once when both cover and paragraph data are missing`() = runTest {
    coEvery { coverSaver.save(any(), any()) } just Runs
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"), includeCover = true)
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = null,
        hasParagraphData = false,
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello there.", paragraphIndex = -1),
      ),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    coVerify(exactly = 1) { coverSaver.save(bookId, any()) }
    assertEquals(expected = true, actual = bookContentRepo.get(bookId)?.hasParagraphData)
  }
```

The existing `bookContent(bookId, voiceId)` test helper (`EpubBookOpenerTest.kt:399`) builds a `BookContent` without specifying `hasParagraphData`, which now defaults to `false` (Task 2, Step 4). Every *existing* test in this file that sets a non-null `cover` but does **not** also set `hasParagraphData = true` would now spuriously trigger the merged reimport branch, breaking assertions that assumed "cover already set" meant "no reimport happens." Three existing tests are affected — add `hasParagraphData = true` to each of their `.copy(...)` calls:

1. `` `skips parsing when chapters already exist` `` (its `bookContentRepo.put(...)` call currently only sets `cover = File("/fake/existing-cover.png")`):

```kotlin
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = true,
      ),
    )
```

2. `` `does not touch progress fields when they are already populated` ``:

```kotlin
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 5,
        epubLastChapterSentenceCount = 9,
        epubTotalCharacterCount = 42,
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = true,
      ),
    )
```

3. `` `backfills epubTotalCharacterCount for a book whose chapters were parsed by an earlier version of the app` `` — this test's whole point is isolating the progress-only backfill branch (triggered by `epubTotalCharacterCount == 0`) from the reimport branch; without this fix it would still happen to pass (the real EPUB's body text coincidentally matches the test's manually-seeded sentence text), but only by accident, since it would silently start exercising the reimport path instead of the one it's named for:

```kotlin
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 0,
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = true,
      ),
    )
```

4. `` `does not re-import when a cover is already set` `` (the test this task's Step 1 test cases sit next to) — same fix:

```kotlin
  @Test
  fun `does not re-import when a cover is already set`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = File("/fake/existing-cover.png"),
        hasParagraphData = true,
      ),
    )
```

The remaining existing tests are unaffected: five hit the `chapters.isEmpty()` first-import branch entirely (untouched by this task's change to the `else` branch), and two (`` `backfills a cover for a book whose chapters and progress fields are already imported` ``, `` `still opens successfully when the cover backfill re-import fails` ``, `` `recomputes progress fields after a cover backfill replaces the chapters` ``) already set `cover = null` explicitly, so they already exercised the reimport path before this task and continue to.

- [ ] **Step 2: Run the tests to verify the new ones fail**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: the three brand-new tests fail (`hasParagraphData` stays `false` / reimport doesn't fire since the condition doesn't check it yet). The four pre-existing tests touched in Step 1 (`` `skips parsing when chapters already exist` ``, `` `does not touch progress fields when they are already populated` ``, `` `backfills epubTotalCharacterCount...` ``, `` `does not re-import when a cover is already set` ``) all still pass unchanged — they only gained an extra `hasParagraphData = true` in their setup, and the old single-condition `content.cover == null` check (still in place until Step 3) doesn't look at that field at all yet.

- [ ] **Step 3: Merge the reimport condition and backfill `hasParagraphData`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`, change the `else` branch's cover-only condition:

```kotlin
      if (content.cover == null) {
```

to:

```kotlin
      if (content.cover == null || !content.hasParagraphData) {
```

and update `withBackfilledProgressFields` to also set `hasParagraphData = true`:

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
      hasParagraphData = true,
    )
  }
```

This one function is called from both the first-import branch (`chapters.isEmpty()`) and the reimport branch, so a fresh import always ends up with `hasParagraphData = true` too — consistent with Task 1's parser always producing real paragraph indices now.

- [ ] **Step 4: Run the tests to verify they pass**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubBookOpenerTest"
```

Expected: PASS, all cases (new and pre-existing).

- [ ] **Step 5: Commit**

```
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt
git commit -m "Merge paragraph-data backfill into the existing cover-backfill reimport condition"
```

---

## Task 4: `EpubReaderViewModel` — paragraph grouping, skip-to-sentence, tap-to-seek

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubParagraphGrouping.kt`
- Modify: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`
- Test (new file): `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubParagraphGroupingTest.kt`

**Interfaces:**
- Consumes: `EpubSentence.paragraphIndex` (Task 2).
- Produces: `EpubReaderViewState.Content.paragraphs: List<List<String>>` (replaces `.sentences`, consumed by Task 8's rendering); `EpubReaderViewModel.skipToPreviousSentence()`, `.skipToNextSentence()`, `.onSentenceTapped(sentenceIndex: Int)` (consumed by Task 7/8's callbacks).

- [ ] **Step 1: Write the failing test for paragraph grouping**

Create `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubParagraphGroupingTest.kt`:

```kotlin
package voice.features.epubReader

import voice.core.data.BookId
import voice.core.data.EpubSentence
import kotlin.test.Test
import kotlin.test.assertEquals

class EpubParagraphGroupingTest {

  private val bookId = BookId("content://book1")

  @Test
  fun `groups consecutive same-paragraph sentences together`() {
    val sentences = listOf(
      EpubSentence(bookId, chapterIndex = 0, index = 0, text = "First sentence.", paragraphIndex = 0),
      EpubSentence(bookId, chapterIndex = 0, index = 1, text = "Second sentence.", paragraphIndex = 0),
      EpubSentence(bookId, chapterIndex = 0, index = 2, text = "Third sentence.", paragraphIndex = 1),
    )

    val paragraphs = groupIntoParagraphs(sentences)

    assertEquals(
      expected = listOf(
        listOf("First sentence.", "Second sentence."),
        listOf("Third sentence."),
      ),
      actual = paragraphs,
    )
  }

  @Test
  fun `returns an empty list for an empty chapter`() {
    assertEquals(expected = emptyList(), actual = groupIntoParagraphs(emptyList()))
  }

  @Test
  fun `treats every sentence as its own paragraph when paragraphIndex is the legacy -1 sentinel throughout`() {
    val sentences = listOf(
      EpubSentence(bookId, chapterIndex = 0, index = 0, text = "One.", paragraphIndex = -1),
      EpubSentence(bookId, chapterIndex = 0, index = 1, text = "Two.", paragraphIndex = -1),
    )

    val paragraphs = groupIntoParagraphs(sentences)

    // All-same-index (even the -1 sentinel) still groups into a single run — this defensive case
    // shouldn't normally occur once EpubBookOpener's backfill (Task 3) has run, but grouping
    // degrades safely rather than crashing if it somehow does.
    assertEquals(expected = listOf(listOf("One.", "Two.")), actual = paragraphs)
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubParagraphGroupingTest"
```

Expected: compile failure — `groupIntoParagraphs` doesn't exist yet.

- [ ] **Step 3: Implement `groupIntoParagraphs`**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubParagraphGrouping.kt`:

```kotlin
package voice.features.epubReader

import voice.core.data.EpubSentence

/**
 * Groups a chapter's flat, index-ordered sentence list into paragraphs by run-length grouping on
 * [EpubSentence.paragraphIndex] — safe because the parser (and the DB's `ORDER BY index` query)
 * guarantee paragraphIndex is non-decreasing within a chapter.
 */
internal fun groupIntoParagraphs(sentences: List<EpubSentence>): List<List<String>> {
  val paragraphs = mutableListOf<MutableList<String>>()
  var lastParagraphIndex: Int? = null
  for (sentence in sentences) {
    if (sentence.paragraphIndex != lastParagraphIndex) {
      paragraphs += mutableListOf()
      lastParagraphIndex = sentence.paragraphIndex
    }
    paragraphs.last() += sentence.text
  }
  return paragraphs
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubParagraphGroupingTest"
```

Expected: PASS.

- [ ] **Step 5: Change `EpubReaderViewState.Content.sentences` to `paragraphs`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`, replace `sentences: List<String>` with `paragraphs: List<List<String>>`:

```kotlin
  public data class Content(
    val bookTitle: String,
    val paragraphs: List<List<String>>,
    val activeSentenceIndex: Int,
    val failedSentenceIndices: Set<Int>,
    val isPlaying: Boolean,
    val chapters: List<ChapterEntry>,
    val chapterPosition: Duration,
    val chapterDuration: Duration,
  ) : EpubReaderViewState
```

- [ ] **Step 6: Write the failing tests for the ViewModel changes**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`:

Update the existing `` `starts loading then becomes content once the book opens` `` test's assertion (it currently checks `state.sentences`):

```kotlin
      assertEquals(expected = listOf(listOf("Hello.", "World.")), actual = state.paragraphs)
```

(The `epubBookRepo` mock at the top of the file returns plain `EpubSentence(...)` rows with no explicit `paragraphIndex`, which now defaults to `-1` — both sentences share that same sentinel, so they group into one paragraph, matching the updated assertion above.)

Update the existing `` `resumes from the persisted chapter and sentence position instead of restarting` `` test similarly:

```kotlin
      assertEquals(expected = listOf(listOf("Second chapter.")), actual = state.paragraphs)
```

Add new tests for the skip/tap methods, after the existing `` `seekTo resumes playback at the sentence resolved from the seek position` `` test:

```kotlin
  @Test
  fun `skipToNextSentence jumps to the sentence after the currently active one`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }
    currentSentenceFlow.value = 0 to 0

    viewModel.skipToNextSentence()
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 1) }
  }

  @Test
  fun `skipToPreviousSentence jumps to the sentence before the currently active one`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }
    currentSentenceFlow.value = 0 to 1

    viewModel.skipToPreviousSentence()
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 0) }
  }

  @Test
  fun `skipToPreviousSentence clamps at the first sentence of the chapter`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }
    currentSentenceFlow.value = 0 to 0

    viewModel.skipToPreviousSentence()
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 0) }
  }

  @Test
  fun `skipToNextSentence clamps at the last sentence of the chapter`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }
    currentSentenceFlow.value = 0 to 1 // "World." — the last sentence in chapter 0's 2-sentence list

    viewModel.skipToNextSentence()
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 1) }
  }

  @Test
  fun `onSentenceTapped jumps directly to the tapped sentence index`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }

    viewModel.onSentenceTapped(1)
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 1) }
  }
```

- [ ] **Step 7: Run the tests to verify the new/updated ones fail**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: compile failure (`state.paragraphs`, `viewModel.skipToNextSentence()` etc. don't exist yet).

- [ ] **Step 8: Update `EpubReaderViewModel`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`:

Change `OpenState.Ready.sentences`'s type from `List<String>` to `List<EpubSentence>` (needed to preserve `paragraphIndex` for grouping):

```kotlin
  private sealed interface OpenState {
    data object Loading : OpenState
    data class Failed(val message: String) : OpenState
    data class Ready(
      val bookTitle: String,
      val chapters: List<EpubReaderViewState.ChapterEntry>,
      val sentences: List<EpubSentence>,
    ) : OpenState
  }
```

Add the import:

```kotlin
import voice.core.data.EpubSentence
```

Update every place that built `sentences` via `.map { it.text }` to keep the full `EpubSentence` list instead — in `init`'s first `scope.launch` block:

```kotlin
          val sentences = epubBookRepo.sentences(bookId, chapterIndex = resumeChapterIndex)
          openState.value = OpenState.Ready(resolvedBookTitle, chapters, sentences)
```

and in `updateSentencesForChapter`:

```kotlin
  private suspend fun updateSentencesForChapter(chapterIndex: Int) {
    val current = openState.value
    if (current is OpenState.Ready) {
      openState.value = current.copy(sentences = epubBookRepo.sentences(bookId, chapterIndex))
    }
  }
```

Update `seekTo` to convert to text at the point it's actually needed (it feeds `sentenceIndexForSeekPosition`, which operates on `List<String>` and stays unchanged in `EpubChapterProgress.kt`), and route through the new shared `jumpToSentence` helper:

```kotlin
  public fun seekTo(position: Duration) {
    val current = openState.value
    if (current !is OpenState.Ready) return
    val targetSentenceIndex = sentenceIndexForSeekPosition(current.sentences.map { it.text }, position)
    jumpToSentence(targetSentenceIndex)
  }

  public fun skipToPreviousSentence() {
    jumpRelativeToActiveSentence(-1)
  }

  public fun skipToNextSentence() {
    jumpRelativeToActiveSentence(1)
  }

  public fun onSentenceTapped(sentenceIndex: Int) {
    jumpToSentence(sentenceIndex)
  }

  private fun jumpRelativeToActiveSentence(delta: Int) {
    val current = openState.value
    if (current !is OpenState.Ready || current.sentences.isEmpty()) return
    val activeIndex = epubPlaylistController.currentSentenceFlow().value?.second ?: 0
    val target = (activeIndex + delta).coerceIn(0, current.sentences.lastIndex)
    jumpToSentence(target)
  }

  private fun jumpToSentence(index: Int) {
    val voiceId = voiceId ?: return
    val bookTitle = bookTitle ?: return
    playlistTransitionJob?.cancel()
    playlistTransitionJob = scope.launch {
      epubPlaylistController.start(bookId, voiceId, bookTitle, activeChapterIndex, index)
    }
  }
```

Remove the now-unused old body of `seekTo` (the `voiceId`/`bookTitle` null-check + `scope.launch { epubPlaylistController.start(...) }` block) — it's fully replaced by the shared `jumpToSentence` above.

Update `viewState()` to compute `paragraphs` via `groupIntoParagraphs` and derive `chapterProgress` from the text-mapped list:

```kotlin
      is OpenState.Ready -> {
        val currentSentence = epubPlaylistController.currentSentenceFlow().collectAsState().value
        val playing = playStateManager.playStateFlow.collectAsState().value == PlayStateManager.PlayState.Playing
        val activeSentenceIndex = currentSentence?.second ?: 0
        val progress = chapterProgress(state.sentences.map { it.text }, activeSentenceIndex)
        EpubReaderViewState.Content(
          bookTitle = state.bookTitle,
          paragraphs = groupIntoParagraphs(state.sentences),
          activeSentenceIndex = activeSentenceIndex,
          failedSentenceIndices = emptySet(),
          isPlaying = playing,
          chapters = state.chapters,
          chapterPosition = progress.position,
          chapterDuration = progress.duration,
        )
      }
```

- [ ] **Step 9: Run the tests to verify they pass**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest" --tests "voice.features.epubReader.EpubParagraphGroupingTest"
```

Expected: PASS, all cases.

- [ ] **Step 10: Commit**

```
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt features/epubReader/src/main/kotlin/voice/features/epubReader/EpubParagraphGrouping.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubParagraphGroupingTest.kt
git commit -m "Group EPUB sentences into paragraphs and add skip-to-sentence/tap-to-seek"
```

---

## Task 5: Move reusable playback-screen chrome components into `core:ui`

**Files:**
- Create: `core/ui/src/main/kotlin/voice/core/ui/AppBarTitle.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/CloseIcon.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/OverflowMenu.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/ChapterRow.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/Cover.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/SkipButton.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/SpeedDialog.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/VolumeGainDialog.kt`
- Create: `core/ui/src/main/kotlin/voice/core/ui/VolumeGainFormatter.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/AppBarTitle.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/CloseIcon.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/OverflowMenu.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/ChapterRow.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/Cover.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/SkipButton.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/SpeedDialog.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainDialog.kt`
- Delete: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainFormatter.kt`
- Modify: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/BookPlayAppBar.kt`
- Modify: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/CoverRow.kt`
- Modify: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/PlaybackRow.kt`
- Modify: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/BookPlayController.kt`
- Modify: `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/BookPlayViewModel.kt`
- Modify: `core/ui/build.gradle.kts`
- Modify: `features/playbackScreen/src/test/kotlin/voice/features/playbackScreen/BookPlayViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `voice.core.ui.AppBarTitle(title: String)`, `voice.core.ui.CloseIcon(onCloseClick: () -> Unit)`, `voice.core.ui.OverflowMenu(skipSilence: Boolean, onSkipSilenceClick: () -> Unit, onVolumeBoostClick: () -> Unit)`, `voice.core.ui.ChapterRow(chapterName: String, nextPreviousVisible: Boolean, onSkipToNext: () -> Unit, onSkipToPrevious: () -> Unit, onCurrentChapterClick: () -> Unit)`, `voice.core.ui.Cover(bookId: BookId, onDoubleClick: () -> Unit, cover: String?)`, `voice.core.ui.SkipButton(forward: Boolean, contentDescription: String, onClick: () -> Unit)`, `voice.core.ui.SpeedDialog(speed: Float, maxSpeed: Float, onSpeedChanged: (Float) -> Unit, onDismiss: () -> Unit)`, `voice.core.ui.VolumeGainDialog(gain: Decibel, maxGain: Decibel, valueFormatted: String, onGainChanged: (Decibel) -> Unit, onDismiss: () -> Unit)`, `voice.core.ui.VolumeGainFormatter` — all consumed by Task 7's EPUB chrome and by `features:playbackScreen`'s own updated call sites.

This task is a mechanical move-and-decouple with no new behavior — its "test" is that `features:playbackScreen`'s existing test suite (which already covers `BookPlayViewModel`'s speed/gain/skip-silence/chapter/sleep-timer methods end-to-end) still passes unchanged after the move, proving nothing regressed.

- [ ] **Step 1: Add the `core:ui` → `core:playback` dependency**

In `core/ui/build.gradle.kts`, add to `dependencies`:

```kotlin
  implementation(projects.core.playback)
```

(`core:playback` has no dependency back on `core:ui` — confirmed via its own `build.gradle.kts` — so this doesn't create a cycle.)

- [ ] **Step 2: Move `AppBarTitle`, `CloseIcon`, `OverflowMenu`, `ChapterRow`, `Cover` verbatim**

For each of these five files, move it from `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/<Name>.kt` to `core/ui/src/main/kotlin/voice/core/ui/<Name>.kt`, changing only the `package` line from `voice.features.playbackScreen.view` to `voice.core.ui`, and removing the `internal` modifier from the top-level `@Composable fun` (Kotlin's default visibility is already public with no modifier — matching every other file already in `core:ui`, e.g. `PlayButton.kt`). No other line changes — every import in these five files (`voice.core.strings.R`, `voice.core.ui.icons.VoiceIcons`, `voice.core.ui.sharedCoverElementModifier`, `voice.core.data.BookId`) already resolves correctly from `core:ui`'s existing dependencies.

- [ ] **Step 3: Move and decouple `SkipButton`**

Move `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/SkipButton.kt` to `core/ui/src/main/kotlin/voice/core/ui/SkipButton.kt`, changing the package and adding an explicit `contentDescription` parameter instead of the hardcoded audiobook-specific string lookup (so the same button can be reused with "Previous sentence"/"Next sentence" wording in Task 7):

```kotlin
package voice.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import voice.core.ui.icons.VoiceIcons

@Composable
fun SkipButton(
  forward: Boolean,
  contentDescription: String,
  onClick: () -> Unit,
) {
  Icon(
    modifier = Modifier
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(bounded = false),
        onClick = onClick,
      )
      .size(48.dp)
      .scale(scaleX = if (forward) -1f else 1F, scaleY = 1f),
    imageVector = VoiceIcons.Undo,
    contentDescription = contentDescription,
  )
}
```

- [ ] **Step 4: Update `PlaybackRow` for `SkipButton`'s new parameter**

`features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/PlaybackRow.kt` now needs `voice.core.ui.SkipButton` and to supply the content description itself (previously baked into the old `SkipButton`):

```kotlin
package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.ui.PlayButton
import voice.core.ui.SkipButton
import voice.core.ui.playButtonSharedBoundsModifier
import voice.core.strings.R

@Composable
internal fun PlaybackRow(
  playing: Boolean,
  onPlayClick: () -> Unit,
  onRewindClick: () -> Unit,
  onFastForwardClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    SkipButton(
      forward = false,
      contentDescription = stringResource(id = R.string.playback_action_rewind),
      onClick = onRewindClick,
    )
    Spacer(modifier = Modifier.size(16.dp))

    PlayButton(
      playing = playing,
      fabSize = 80.dp,
      iconSize = 36.dp,
      onPlayClick = onPlayClick,
      sharedElementModifier = Modifier.playButtonSharedBoundsModifier(),
    )
    Spacer(modifier = Modifier.size(16.dp))
    SkipButton(
      forward = true,
      contentDescription = stringResource(id = R.string.playback_action_fast_forward),
      onClick = onFastForwardClick,
    )
  }
}
```

- [ ] **Step 5: Move and decouple `SpeedDialog`**

Move `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/SpeedDialog.kt` to `core/ui/src/main/kotlin/voice/core/ui/SpeedDialog.kt`, replacing the `BookPlayDialogViewState.SpeedDialog`/`BookPlayViewModel` parameters with plain primitives and callbacks:

```kotlin
package voice.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import java.text.DecimalFormat
import voice.core.strings.R as StringsR

@Composable
fun SpeedDialog(
  speed: Float,
  maxSpeed: Float,
  onSpeedChanged: (Float) -> Unit,
  onDismiss: () -> Unit,
) {
  val speedFormatter = remember { DecimalFormat("0.00 x") }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {},
    title = {
      Text(stringResource(id = StringsR.string.playback_speed_title))
    },
    text = {
      Column {
        Text(stringResource(id = StringsR.string.playback_speed_title) + ": " + speedFormatter.format(speed))
        val valueRange = 0.5F..maxSpeed
        val rangeSize = valueRange.endInclusive - valueRange.start
        val stepSize = 0.05
        val steps = (rangeSize / stepSize).toInt() - 1
        Slider(
          steps = steps,
          valueRange = valueRange,
          value = speed,
          onValueChange = onSpeedChanged,
        )
      }
    },
  )
}
```

- [ ] **Step 6: Move and decouple `VolumeGainDialog`**

Move `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainDialog.kt` to `core/ui/src/main/kotlin/voice/core/ui/VolumeGainDialog.kt`:

```kotlin
package voice.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import voice.core.playback.misc.Decibel
import voice.core.strings.R as StringsR

@Composable
fun VolumeGainDialog(
  gain: Decibel,
  maxGain: Decibel,
  valueFormatted: String,
  onGainChanged: (Decibel) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {},
    text = {
      Column {
        Text(stringResource(id = StringsR.string.playback_option_volume_boost) + ": " + valueFormatted)
        Slider(
          valueRange = 0F..maxGain.value,
          value = gain.value,
          onValueChange = { onGainChanged(Decibel(it)) },
        )
      }
    },
  )
}
```

- [ ] **Step 7: Move `VolumeGainFormatter`**

Move `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainFormatter.kt` to `core/ui/src/main/kotlin/voice/core/ui/VolumeGainFormatter.kt`, changing only the package:

```kotlin
package voice.core.ui

import dev.zacsweers.metro.Inject
import voice.core.playback.misc.Decibel
import java.text.DecimalFormat

@Inject
class VolumeGainFormatter {

  private val dbFormat = DecimalFormat("0.0 dB")

  fun format(gain: Decibel): String {
    return dbFormat.format(gain.value)
  }
}
```

- [ ] **Step 8: Update `features:playbackScreen`'s call sites**

In `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/BookPlayAppBar.kt`, replace the `CloseIcon(onCloseClick)`/`AppBarTitle(...)`/`OverflowMenu(...)` calls' imports:

```kotlin
import voice.core.ui.AppBarTitle
import voice.core.ui.CloseIcon
import voice.core.ui.OverflowMenu
```

(remove the old `import` lines that referenced the now-deleted `voice.features.playbackScreen.view.AppBarTitle`/`CloseIcon`/`OverflowMenu` — since they were in the same package before the move, there were no explicit imports for them; add these three new ones.)

In `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/CoverRow.kt`, add:

```kotlin
import voice.core.ui.Cover
```

In `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/BookPlayController.kt`, update the dialog-rendering `when` block to call the decoupled composables with explicit arguments, and add the new imports:

```kotlin
import voice.core.ui.SpeedDialog
import voice.core.ui.VolumeGainDialog
```

```kotlin
      is BookPlayDialogViewState.SpeedDialog -> {
        SpeedDialog(
          speed = dialogState.speed,
          maxSpeed = dialogState.maxSpeed,
          onSpeedChanged = viewModel::onPlaybackSpeedChanged,
          onDismiss = viewModel::dismissDialog,
        )
      }
      is BookPlayDialogViewState.VolumeGainDialog -> {
        VolumeGainDialog(
          gain = dialogState.gain,
          maxGain = dialogState.maxGain,
          valueFormatted = dialogState.valueFormatted,
          onGainChanged = viewModel::onVolumeGainChanged,
          onDismiss = viewModel::dismissDialog,
        )
      }
      is BookPlayDialogViewState.SelectChapterDialog -> {
        SelectChapterDialog(dialogState, viewModel)
      }
      is BookPlayDialogViewState.SleepTimer -> {
        SleepTimerDialog(
          viewState = dialogState.viewState,
          onDismiss = viewModel::dismissDialog,
          onIncrementSleepTime = viewModel::incrementSleepTime,
          onDecrementSleepTime = viewModel::decrementSleepTime,
          onAcceptSleepTime = viewModel::onAcceptSleepTime,
          onAcceptSleepAtEndOfChapter = viewModel::onAcceptSleepAtEndOfChapter,
        )
      }
```

In `features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/BookPlayViewModel.kt`, update the `VolumeGainFormatter` import:

```kotlin
import voice.core.ui.VolumeGainFormatter
```

(remove the old same-package reference — there wasn't an explicit import before since it lived in the same package; add this new one.)

- [ ] **Step 9: Delete the nine now-empty source files**

```
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/AppBarTitle.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/CloseIcon.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/OverflowMenu.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/ChapterRow.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/Cover.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/view/SkipButton.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/SpeedDialog.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainDialog.kt
git rm features/playbackScreen/src/main/kotlin/voice/features/playbackScreen/VolumeGainFormatter.kt
```

(If Step 2's "move" was done as a fresh create at the new path without deleting the old one, this step removes the stale originals — do this after confirming the new `core/ui` files exist and compile, so nothing is lost mid-move.)

- [ ] **Step 10: Update `BookPlayViewModelTest`'s `VolumeGainFormatter` import**

In `features/playbackScreen/src/test/kotlin/voice/features/playbackScreen/BookPlayViewModelTest.kt`, the `volumeGainFormatter = mockk()` construction (line ~353) references `VolumeGainFormatter` — add the import:

```kotlin
import voice.core.ui.VolumeGainFormatter
```

- [ ] **Step 11: Build and run the full `playbackScreen` test suite to verify nothing regressed**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :core:ui:compileDebugKotlin :features:playbackScreen:testDebugUnitTest
```

Expected: both succeed — `core:ui` compiles with the nine new/moved files, and every existing `BookPlayViewModelTest`/`BookPlayContent` test (which exercises the speed/gain/skip-silence/sleep-timer/chapter-select flows end-to-end) still passes, confirming the move+decouple didn't change behavior.

- [ ] **Step 12: Commit**

```
git add -A core/ui features/playbackScreen
git commit -m "Move reusable playback-screen chrome components into core:ui"
```

---

## Task 6: `EpubReaderViewModel` — sleep timer / speed / volume gain / skip silence wiring

**Files:**
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderDialogViewState.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`
- Modify: `features/epubReader/build.gradle.kts`
- Modify: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`

**Interfaces:**
- Consumes: `SleepTimer`, `PlayerController.setSpeed/setGain/skipSilence` (`core:playback`, already a dependency), `VolumeGainFormatter`/`VolumeGain.MAX_GAIN` (Task 5), `SleepTimerViewState`/`SleepTimerDialog` (`features:sleepTimer`, new dependency), `voice.core.data.sleeptimer.SleepTimerPreference`/`@SleepTimerPreferenceStore` (`core:data:api`, already a dependency).
- Produces: `EpubReaderViewModel.dialogState: State<EpubReaderDialogViewState?>`, `.dismissDialog()`, `.onPlaybackSpeedIconClick()`, `.onPlaybackSpeedChanged(Float)`, `.onVolumeGainIconClick()`, `.onVolumeGainChanged(Decibel)`, `.toggleSkipSilence()`, `.toggleSleepTimer()`, `.incrementSleepTime()`, `.decrementSleepTime()`, `.onAcceptSleepTime(Int)`, `.onAcceptSleepAtEndOfChapter()`, and `EpubReaderViewState.Content.skipSilence: Boolean` — all consumed by Task 7's icon bar wiring.

- [ ] **Step 1: Add the `features:sleepTimer` dependency**

In `features/epubReader/build.gradle.kts`, add to `dependencies`:

```kotlin
  implementation(projects.features.sleepTimer)
```

- [ ] **Step 2: Write the failing tests**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`, add the new collaborators to the `viewModel()` factory and `book()` fixture, and add test cases. The file already imports `io.mockk.{Runs, coEvery, coVerify, every, just, mockk, verify}` and `kotlinx.coroutines.flow.MutableStateFlow` — add only the genuinely new imports, alongside the existing block:

```kotlin
import androidx.datastore.core.DataStore
import io.mockk.verifyOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.playback.PlayerController
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerMode.TimedWithDuration
import voice.core.sleeptimer.SleepTimerState
import voice.core.ui.VolumeGainFormatter
import voice.features.sleepTimer.SleepTimerViewState
import kotlin.time.Duration.Companion.minutes
```

Add fixtures alongside the existing `epubPlaylistController`/`epubBookRepo`/`bookRepository`/`playStateManager` fields:

```kotlin
  private val sleepTimerDataStore = MemoryDataStore(SleepTimerPreference.Default.copy(duration = 5.minutes))
  private val sleepTimer = mockk<SleepTimer> {
    val stateFlow = MutableStateFlow<SleepTimerState>(SleepTimerState.Disabled)
    every { state } returns stateFlow
    every { enable(any()) } answers {
      stateFlow.value = when (val mode = firstArg<SleepTimerMode>()) {
        is TimedWithDuration -> SleepTimerState.Enabled.WithDuration(mode.duration)
        SleepTimerMode.TimedWithDefault -> SleepTimerState.Enabled.WithDuration(runBlocking { sleepTimerDataStore.data.first() }.duration)
        SleepTimerMode.EndOfChapter -> SleepTimerState.Enabled.WithEndOfChapter
      }
    }
    every { disable() } answers { stateFlow.value = SleepTimerState.Disabled }
  }
  private val player = mockk<PlayerController>(relaxed = true)
  private val volumeGainFormatter = mockk<VolumeGainFormatter> {
    every { format(any()) } answers { "${firstArg<Decibel>().value} dB" }
  }
```

(`MemoryDataStore` already exists as a test double in `features/playbackScreen/src/test/kotlin/voice/features/playbackScreen/MemoryDataStore.kt` — since `features:epubReader`'s tests don't currently depend on `features:playbackScreen`'s test sources, copy this small class instead of adding a cross-module test dependency. Create `features/epubReader/src/test/kotlin/voice/features/epubReader/MemoryDataStore.kt`:

```kotlin
package voice.features.epubReader

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class MemoryDataStore<T>(initial: T) : DataStore<T> {
  private val state = MutableStateFlow(initial)
  override val data: StateFlow<T> = state

  override suspend fun updateData(transform: suspend (T) -> T): T {
    val updated = transform(state.value)
    state.value = updated
    return updated
  }
}
```
)

Update the `viewModel()` factory to pass the new constructor parameters:

```kotlin
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
      sleepTimer = sleepTimer,
      player = player,
      volumeGainFormatter = volumeGainFormatter,
      sleepTimerPreferenceStore = sleepTimerDataStore,
      dispatcherProvider = DispatcherProvider(
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
      ),
      bookId = bookId,
    )
  }
```

Add test cases at the end of the class:

```kotlin
  @Test
  fun `toggleSleepTimer opens the sleep timer dialog with the persisted default duration`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.toggleSleepTimer()
    yield()

    assertEquals(
      expected = EpubReaderDialogViewState.SleepTimer(SleepTimerViewState(5)),
      actual = viewModel.dialogState.value,
    )
  }

  @Test
  fun `onAcceptSleepTime enables the sleep timer and dismisses the dialog without touching bookmarks`() = scope.runTest {
    val viewModel = viewModel()
    viewModel.toggleSleepTimer()

    viewModel.onAcceptSleepTime(10)
    yield()

    verify { sleepTimer.enable(TimedWithDuration(10.minutes)) }
    assertEquals(expected = null, actual = viewModel.dialogState.value)
  }

  @Test
  fun `toggleSleepTimer disables an active timer instead of reopening the dialog`() = scope.runTest {
    val viewModel = viewModel()
    viewModel.toggleSleepTimer()
    viewModel.onAcceptSleepTime(10)
    yield()

    viewModel.toggleSleepTimer()
    yield()

    verifyOrder {
      sleepTimer.enable(TimedWithDuration(10.minutes))
      sleepTimer.disable()
    }
    assertEquals(expected = null, actual = viewModel.dialogState.value)
  }

  @Test
  fun `onPlaybackSpeedIconClick opens the speed dialog with the book's current speed`() = scope.runTest {
    bookFixture = bookFixture.copy(content = bookFixture.content.copy(playbackSpeed = 1.5F))
    val viewModel = viewModel()

    viewModel.onPlaybackSpeedIconClick()
    yield()

    assertEquals(
      expected = EpubReaderDialogViewState.SpeedDialog(speed = 1.5F, maxSpeed = 2F),
      actual = viewModel.dialogState.value,
    )
  }

  @Test
  fun `onPlaybackSpeedChanged applies the new speed to the player`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.onPlaybackSpeedChanged(1.75F)

    verify { player.setSpeed(1.75F) }
  }

  @Test
  fun `onVolumeGainIconClick opens the volume gain dialog with the book's current gain`() = scope.runTest {
    bookFixture = bookFixture.copy(content = bookFixture.content.copy(gain = 3F))
    val viewModel = viewModel()

    viewModel.onVolumeGainIconClick()
    yield()

    assertEquals(
      expected = EpubReaderDialogViewState.VolumeGainDialog(
        gain = Decibel(3F),
        valueFormatted = "3.0 dB",
        maxGain = VolumeGain.MAX_GAIN,
      ),
      actual = viewModel.dialogState.value,
    )
  }

  @Test
  fun `onVolumeGainChanged applies the new gain to the player`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.onVolumeGainChanged(Decibel(4F))

    verify { player.setGain(Decibel(4F)) }
  }

  @Test
  fun `toggleSkipSilence flips the book's skip-silence setting on the player`() = scope.runTest {
    bookFixture = bookFixture.copy(content = bookFixture.content.copy(skipSilence = false))
    val viewModel = viewModel()

    viewModel.toggleSkipSilence()
    yield()

    verify { player.skipSilence(true) }
  }
```

- [ ] **Step 3: Run the tests to verify they fail**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: compile failure — `EpubReaderDialogViewState`, the new constructor parameters, and the new methods don't exist yet.

- [ ] **Step 4: Create `EpubReaderDialogViewState`**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderDialogViewState.kt`:

```kotlin
package voice.features.epubReader

import voice.core.playback.misc.Decibel
import voice.features.sleepTimer.SleepTimerViewState

public sealed interface EpubReaderDialogViewState {
  public data class SpeedDialog(val speed: Float, val maxSpeed: Float) : EpubReaderDialogViewState

  public data class VolumeGainDialog(
    val gain: Decibel,
    val valueFormatted: String,
    val maxGain: Decibel,
  ) : EpubReaderDialogViewState

  @JvmInline
  public value class SleepTimer(val viewState: SleepTimerViewState) : EpubReaderDialogViewState
}
```

- [ ] **Step 5: Add `skipSilence` and `sleepTimerActive` to `EpubReaderViewState.Content`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewState.kt`, add fields for the icon bar's overflow-menu checkbox and its sleep-timer icon toggle:

```kotlin
  public data class Content(
    val bookTitle: String,
    val paragraphs: List<List<String>>,
    val activeSentenceIndex: Int,
    val failedSentenceIndices: Set<Int>,
    val isPlaying: Boolean,
    val skipSilence: Boolean,
    val sleepTimerActive: Boolean,
    val chapters: List<ChapterEntry>,
    val chapterPosition: Duration,
    val chapterDuration: Duration,
  ) : EpubReaderViewState
```

- [ ] **Step 6: Wire the new collaborators and methods into `EpubReaderViewModel`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`, add the new constructor parameters:

```kotlin
@AssistedInject
public class EpubReaderViewModel(
  private val epubBookOpener: EpubBookOpener,
  private val epubPlaylistController: EpubPlaylistController,
  private val epubBookRepo: EpubBookRepo,
  private val bookRepository: BookRepository,
  private val playStateManager: PlayStateManager,
  private val sleepTimer: SleepTimer,
  private val player: PlayerController,
  private val volumeGainFormatter: VolumeGainFormatter,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  dispatcherProvider: DispatcherProvider,
  @Assisted
  private val bookId: BookId,
) {
```

Add the imports:

```kotlin
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.first
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.playback.PlayerController
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerMode.TimedWithDuration
import voice.core.ui.VolumeGainFormatter
import voice.features.sleepTimer.SleepTimerViewState
import kotlin.time.Duration.Companion.minutes
```

Add the dialog state field (alongside the existing `private val scope = MainScope(dispatcherProvider)`):

```kotlin
  public val dialogState: State<EpubReaderDialogViewState?>
    field = mutableStateOf<EpubReaderDialogViewState?>(null)
```

Add the new public methods (near `playPause`/`onChapterSelect`):

```kotlin
  public fun dismissDialog() {
    dialogState.value = null
  }

  public fun onPlaybackSpeedIconClick() {
    scope.launch {
      val speed = bookRepository.get(bookId)?.content?.playbackSpeed ?: return@launch
      dialogState.value = EpubReaderDialogViewState.SpeedDialog(
        speed = speed,
        maxSpeed = if (speed < 2F) 2F else 3.5F,
      )
    }
  }

  public fun onPlaybackSpeedChanged(speed: Float) {
    val current = dialogState.value
    if (current is EpubReaderDialogViewState.SpeedDialog) {
      dialogState.value = current.copy(speed = speed)
    }
    player.setSpeed(speed)
  }

  public fun onVolumeGainIconClick() {
    scope.launch {
      val gain = bookRepository.get(bookId)?.content?.gain ?: return@launch
      dialogState.value = EpubReaderDialogViewState.VolumeGainDialog(
        gain = Decibel(gain),
        valueFormatted = volumeGainFormatter.format(Decibel(gain)),
        maxGain = VolumeGain.MAX_GAIN,
      )
    }
  }

  public fun onVolumeGainChanged(gain: Decibel) {
    dialogState.value = EpubReaderDialogViewState.VolumeGainDialog(
      gain = gain,
      valueFormatted = volumeGainFormatter.format(gain),
      maxGain = VolumeGain.MAX_GAIN,
    )
    player.setGain(gain)
  }

  public fun toggleSkipSilence() {
    scope.launch {
      val skipSilence = bookRepository.get(bookId)?.content?.skipSilence ?: return@launch
      player.skipSilence(!skipSilence)
    }
  }

  public fun toggleSleepTimer() {
    scope.launch {
      if (sleepTimer.state.value.enabled) {
        sleepTimer.disable()
        dialogState.value = null
      } else {
        dialogState.value = EpubReaderDialogViewState.SleepTimer(
          viewState = SleepTimerViewState(
            customSleepTime = sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt(),
          ),
        )
      }
    }
  }

  public fun incrementSleepTime() {
    updateSleepTimeViewState {
      val newTime = it.customSleepTime + 1
      sleepTimerPreferenceStore.updateData { preference -> preference.copy(duration = newTime.minutes) }
      SleepTimerViewState(newTime)
    }
  }

  public fun decrementSleepTime() {
    updateSleepTimeViewState {
      val newTime = (it.customSleepTime - 1).coerceAtLeast(1)
      sleepTimerPreferenceStore.updateData { preference -> preference.copy(duration = newTime.minutes) }
      SleepTimerViewState(newTime)
    }
  }

  public fun onAcceptSleepTime(time: Int) {
    updateSleepTimeViewState {
      // Unlike BookPlayViewModel.onAcceptSleepTime, no bookmark is dropped here — Bookmark can't
      // represent an EPUB chapter+sentence position (see the design spec's "Bookmarks" decision).
      sleepTimer.enable(TimedWithDuration(time.minutes))
      null
    }
  }

  public fun onAcceptSleepAtEndOfChapter() {
    updateSleepTimeViewState {
      sleepTimer.enable(SleepTimerMode.EndOfChapter)
      null
    }
  }

  private fun updateSleepTimeViewState(update: suspend (SleepTimerViewState) -> SleepTimerViewState?) {
    scope.launch {
      val current = dialogState.value
      val updated: SleepTimerViewState? = if (current is EpubReaderDialogViewState.SleepTimer) {
        update(current.viewState)
      } else {
        update(SleepTimerViewState(sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt()))
      }
      dialogState.value = updated?.let(EpubReaderDialogViewState::SleepTimer)
    }
  }
```

Finally, update the `viewState()` function's `OpenState.Ready` branch to read the book's persisted `skipSilence` setting reactively (`viewState()` is `@Composable`, so `bookRepository.flow(bookId)` — read via `remember`/`collectAsState`, matching how `BookPlayViewModel.viewState()` already reads `persistedBook` the same way — is the right tool here, not a one-off suspend `get()` call) and pass it into `Content(...)`:

```kotlin
      is OpenState.Ready -> {
        val currentSentence = epubPlaylistController.currentSentenceFlow().collectAsState().value
        val playing = playStateManager.playStateFlow.collectAsState().value == PlayStateManager.PlayState.Playing
        val activeSentenceIndex = currentSentence?.second ?: 0
        val progress = chapterProgress(state.sentences.map { it.text }, activeSentenceIndex)
        val skipSilence = remember(bookId) { bookRepository.flow(bookId) }
          .collectAsState(initial = null).value?.content?.skipSilence ?: false
        val sleepTimerActive = sleepTimer.state.collectAsState().value.enabled
        EpubReaderViewState.Content(
          bookTitle = state.bookTitle,
          paragraphs = groupIntoParagraphs(state.sentences),
          activeSentenceIndex = activeSentenceIndex,
          failedSentenceIndices = emptySet(),
          isPlaying = playing,
          skipSilence = skipSilence,
          sleepTimerActive = sleepTimerActive,
          chapters = state.chapters,
          chapterPosition = progress.position,
          chapterDuration = progress.duration,
        )
      }
```

Add `import androidx.compose.runtime.remember` to this file's import block — it isn't there yet (the existing block only has `Composable`/`collectAsState`/`getValue`). `SleepTimerState.enabled` is an existing extension property on `voice.core.sleeptimer.SleepTimerState` (already imported transitively via the `SleepTimer` type — no new import needed for it specifically, since `sleepTimer.state` is already a `StateFlow<SleepTimerState>` in scope).

- [ ] **Step 7: Run the tests to verify they pass**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest --tests "voice.features.epubReader.EpubReaderViewModelTest"
```

Expected: PASS, all cases (new and pre-existing).

- [ ] **Step 8: Commit**

```
git add features/epubReader
git commit -m "Wire sleep timer/speed/volume-gain/skip-silence controls into EpubReaderViewModel"
```

---

## Task 7: `EpubReaderView` chrome — icon bar, cover header, chapter dialog, bottom playback bar

**Files:**
- Modify: `features/epubReader/build.gradle.kts`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubChapterDialog.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubPlaybackRow.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`
- Modify: `core/strings/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `voice.core.ui.{AppBarTitle, CloseIcon, OverflowMenu, ChapterRow, Cover, SkipButton, SpeedDialog, VolumeGainDialog, PlayButton}` (Task 5); `EpubReaderViewModel`'s dialog/skip/tap/sleep-timer/speed/gain/skip-silence methods (Task 4, Task 6); `EpubReaderViewState.Content.paragraphs` (Task 4, rendered by Task 8 — this task only needs to place the `LazyColumn` items list, not render paragraph content yet).
- Produces: the finished `EpubReaderScreen`/`EpubReaderView` composable shell that Task 8 fills in with paragraph rendering.

- [ ] **Step 0: Add the `core:strings` dependency**

`features/epubReader` has never needed string resources directly until this task (its UI text was previously all hardcoded, e.g. `"Chapters"`, `"Play"`). `core:ui` already depends on `core:strings`, but only as `implementation`, which doesn't transitively expose it — add it directly. In `features/epubReader/build.gradle.kts`, add to `dependencies`:

```kotlin
  implementation(projects.core.strings)
```

- [ ] **Step 1: Create the EPUB chapter picker dialog**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubChapterDialog.kt` — mirrors `SelectChapterDialog`'s `ModalBottomSheet`/`LazyColumn`/`ListItem` visual pattern, but over EPUB's simpler flat chapter list (no marks, no per-item time):

```kotlin
package voice.features.epubReader.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import voice.features.epubReader.EpubReaderViewState

@Composable
internal fun EpubChapterDialog(
  chapters: List<EpubReaderViewState.ChapterEntry>,
  activeChapterIndex: Int,
  onChapterClick: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    sheetState = rememberBottomSheetState(
      initialValue = Hidden,
      enabledValues = setOf(Hidden, Expanded),
    ),
    onDismissRequest = onDismiss,
    content = {
      val initialFirstVisibleItemIndex = (activeChapterIndex - 1).coerceAtLeast(0)
      LazyColumn(
        state = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstVisibleItemIndex),
        content = {
          items(chapters) { chapter ->
            val backgroundColor = if (chapter.index == activeChapterIndex) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              Color.Transparent
            }
            ListItem(
              colors = ListItemDefaults.colors(containerColor = backgroundColor),
              modifier = Modifier
                .padding(3.dp)
                .clip(shape = RoundedCornerShape(12.dp))
                .clickable { onChapterClick(chapter.index) },
              headlineContent = {
                Text(text = chapter.title)
              },
            )
          }
        },
      )
    },
  )
}
```

- [ ] **Step 2: Create the EPUB bottom playback row**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubPlaybackRow.kt` — assembles `core:ui`'s `SkipButton`/`PlayButton` with EPUB-appropriate labels, rather than reusing `PlaybackRow` (which hardcodes audiobook rewind/fast-forward semantics):

```kotlin
package voice.features.epubReader.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.strings.R as StringsR
import voice.core.ui.PlayButton
import voice.core.ui.SkipButton

@Composable
internal fun EpubPlaybackRow(
  playing: Boolean,
  onPlayClick: () -> Unit,
  onPreviousSentenceClick: () -> Unit,
  onNextSentenceClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    SkipButton(
      forward = false,
      contentDescription = stringResource(id = StringsR.string.epub_reader_action_previous_sentence),
      onClick = onPreviousSentenceClick,
    )
    Spacer(modifier = Modifier.size(16.dp))
    PlayButton(
      playing = playing,
      fabSize = 80.dp,
      iconSize = 36.dp,
      onPlayClick = onPlayClick,
    )
    Spacer(modifier = Modifier.size(16.dp))
    SkipButton(
      forward = true,
      contentDescription = stringResource(id = StringsR.string.epub_reader_action_next_sentence),
      onClick = onNextSentenceClick,
    )
  }
}
```

Add the two new string resources to `core/strings/src/main/res/values/strings.xml` (only the English source file — this project's other ~38 locale files sync via Transifex, not hand-edited, per this project's established convention), near the other `playback_action_*` strings:

```xml
    <string name="epub_reader_action_previous_sentence">Previous sentence</string>
    <string name="epub_reader_action_next_sentence">Next sentence</string>
```

- [ ] **Step 3: Rewrite `EpubReaderView`'s chrome**

Replace `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt` in full. This step establishes the Scaffold/icon-bar/cover-header/bottom-bar shell with the paragraph items left as the existing one-sentence-per-row rendering for now — Task 8 replaces the `itemsIndexed(...)` body with real paragraph flow/highlight/scroll/tap:

```kotlin
package voice.features.epubReader.view

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.data.BookId
import voice.core.playback.misc.Decibel
import voice.core.strings.R as StringsR
import voice.core.ui.AppBarTitle
import voice.core.ui.ChapterRow
import voice.core.ui.CloseIcon
import voice.core.ui.Cover
import voice.core.ui.OverflowMenu
import voice.core.ui.SpeedDialog
import voice.core.ui.VolumeGainDialog
import voice.core.ui.formatTime
import voice.core.ui.icons.VoiceIcons
import voice.features.epubReader.EpubReaderDialogViewState
import voice.features.epubReader.EpubReaderViewState
import voice.features.sleepTimer.SleepTimerDialog
import kotlin.time.Duration

@Composable
public fun EpubReaderView(
  viewState: EpubReaderViewState,
  bookId: BookId,
  coverUrl: String?,
  dialogState: EpubReaderDialogViewState?,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  onSeek: (Duration) -> Unit,
  onSkipToPreviousSentence: () -> Unit,
  onSkipToNextSentence: () -> Unit,
  onSentenceTapped: (Int) -> Unit,
  onCloseClick: () -> Unit,
  onSleepTimerClick: () -> Unit,
  onSpeedChangeClick: () -> Unit,
  onSkipSilenceClick: () -> Unit,
  onVolumeBoostClick: () -> Unit,
  onDismissDialog: () -> Unit,
  onPlaybackSpeedChanged: (Float) -> Unit,
  onVolumeGainChanged: (Decibel) -> Unit,
  onIncrementSleepTime: () -> Unit,
  onDecrementSleepTime: () -> Unit,
  onAcceptSleepTime: (Int) -> Unit,
  onAcceptSleepAtEndOfChapter: () -> Unit,
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
        bookId = bookId,
        coverUrl = coverUrl,
        onPlayPauseClick = onPlayPauseClick,
        onChapterSelect = onChapterSelect,
        onSeek = onSeek,
        onSkipToPreviousSentence = onSkipToPreviousSentence,
        onSkipToNextSentence = onSkipToNextSentence,
        onSentenceTapped = onSentenceTapped,
        onCloseClick = onCloseClick,
        onSleepTimerClick = onSleepTimerClick,
        onSpeedChangeClick = onSpeedChangeClick,
        onSkipSilenceClick = onSkipSilenceClick,
        onVolumeBoostClick = onVolumeBoostClick,
        modifier = modifier,
      )
    }
  }
  if (dialogState != null) {
    when (dialogState) {
      is EpubReaderDialogViewState.SpeedDialog -> {
        SpeedDialog(
          speed = dialogState.speed,
          maxSpeed = dialogState.maxSpeed,
          onSpeedChanged = onPlaybackSpeedChanged,
          onDismiss = onDismissDialog,
        )
      }
      is EpubReaderDialogViewState.VolumeGainDialog -> {
        VolumeGainDialog(
          gain = dialogState.gain,
          maxGain = dialogState.maxGain,
          valueFormatted = dialogState.valueFormatted,
          onGainChanged = onVolumeGainChanged,
          onDismiss = onDismissDialog,
        )
      }
      is EpubReaderDialogViewState.SleepTimer -> {
        SleepTimerDialog(
          viewState = dialogState.viewState,
          onDismiss = onDismissDialog,
          onIncrementSleepTime = onIncrementSleepTime,
          onDecrementSleepTime = onDecrementSleepTime,
          onAcceptSleepTime = onAcceptSleepTime,
          onAcceptSleepAtEndOfChapter = onAcceptSleepAtEndOfChapter,
        )
      }
    }
  }
}

@Composable
private fun EpubReaderContent(
  viewState: EpubReaderViewState.Content,
  bookId: BookId,
  coverUrl: String?,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  onSeek: (Duration) -> Unit,
  onSkipToPreviousSentence: () -> Unit,
  onSkipToNextSentence: () -> Unit,
  onSentenceTapped: (Int) -> Unit,
  onCloseClick: () -> Unit,
  onSleepTimerClick: () -> Unit,
  onSpeedChangeClick: () -> Unit,
  onSkipSilenceClick: () -> Unit,
  onVolumeBoostClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var chapterDialogVisible by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()
  val activeChapterIndex = viewState.chapters.firstOrNull()?.index ?: 0

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        navigationIcon = { CloseIcon(onCloseClick) },
        title = { AppBarTitle(viewState.bookTitle) },
        actions = {
          IconButton(onClick = onSleepTimerClick) {
            Icon(
              imageVector = if (viewState.sleepTimerActive) VoiceIcons.BedtimeOff else VoiceIcons.Bedtime,
              contentDescription = stringResource(id = StringsR.string.sleep_timer_action_open),
            )
          }
          IconButton(onClick = {}, enabled = false) {
            Icon(
              imageVector = VoiceIcons.CollectionsBookmark,
              contentDescription = stringResource(id = StringsR.string.bookmark_title),
            )
          }
          IconButton(onClick = onSpeedChangeClick) {
            Icon(
              imageVector = VoiceIcons.Speed,
              contentDescription = stringResource(id = StringsR.string.playback_speed_title),
            )
          }
          OverflowMenu(
            skipSilence = viewState.skipSilence,
            onSkipSilenceClick = onSkipSilenceClick,
            onVolumeBoostClick = onVolumeBoostClick,
          )
        },
      )
    },
    bottomBar = {
      Column {
        ChapterScrubberRow(
          duration = viewState.chapterDuration,
          position = viewState.chapterPosition,
          onSeek = onSeek,
        )
        EpubPlaybackRow(
          playing = viewState.isPlaying,
          onPlayClick = onPlayPauseClick,
          onPreviousSentenceClick = onSkipToPreviousSentence,
          onNextSentenceClick = onSkipToNextSentence,
        )
      }
    },
  ) { contentPadding ->
    LazyColumn(
      state = listState,
      modifier = Modifier
        .padding(contentPadding)
        .fillMaxSize(),
    ) {
      item {
        Box(modifier = Modifier.padding(16.dp).aspectRatio(1F)) {
          Cover(bookId = bookId, onDoubleClick = onPlayPauseClick, cover = coverUrl)
        }
      }
      item {
        val currentChapterTitle = viewState.chapters.getOrNull(activeChapterIndex)?.title.orEmpty()
        ChapterRow(
          chapterName = currentChapterTitle,
          nextPreviousVisible = viewState.chapters.size > 1,
          onSkipToNext = { onChapterSelect((activeChapterIndex + 1).coerceAtMost(viewState.chapters.lastIndex)) },
          onSkipToPrevious = { onChapterSelect((activeChapterIndex - 1).coerceAtLeast(0)) },
          onCurrentChapterClick = { chapterDialogVisible = true },
        )
      }
      var flatIndex = 0
      viewState.paragraphs.forEach { paragraph ->
        val paragraphStart = flatIndex
        flatIndex += paragraph.size
        itemsIndexed(paragraph) { localIndex, sentence ->
          val globalIndex = paragraphStart + localIndex
          val isActive = globalIndex == viewState.activeSentenceIndex
          Text(
            text = sentence,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 20.dp, vertical = 2.dp),
            color = if (isActive) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onBackground
            },
          )
        }
      }
    }
  }
  if (chapterDialogVisible) {
    EpubChapterDialog(
      chapters = viewState.chapters,
      activeChapterIndex = activeChapterIndex,
      onChapterClick = { index ->
        chapterDialogVisible = false
        onChapterSelect(index)
      },
      onDismiss = { chapterDialogVisible = false },
    )
  }
}

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
    var localValue by remember { mutableStateOf(0F) }
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
      onValueChange = { localValue = it },
      onValueChangeFinished = { onSeek(duration * localValue.toDouble()) },
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

`ChapterScrubberRow` is carried over unchanged from the previous version of this file (same logic, just relocated within the rewritten file).

- [ ] **Step 4: Wire the new callbacks and dialog state into `EpubReaderScreen`**

`EpubReaderView` needs a `Navigator` (for the icon bar's close button) and the book's cover URL (for the cover header) — neither is available on `EpubReaderViewState.Content` today. Rather than widening that state class (it's specifically the *reading* state, and `EpubReaderViewModel` already reads `BookContent` directly elsewhere, e.g. in the new speed/gain methods from Task 6), add both directly to `EpubReaderViewModel`, mirroring `BookPlayViewModel.onCloseClick()`'s pattern:

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderViewModel.kt`, add the constructor parameter and import:

```kotlin
  private val navigator: Navigator,
```

```kotlin
import voice.navigation.Navigator
```

and two new members — a plain method for the close action, and a small `@Composable` read of the book's cover (using `remember`/`collectAsState`, already imported in this file):

```kotlin
  public fun onCloseClick() {
    navigator.goBack()
  }

  @Composable
  public fun coverUrl(): String? {
    return remember(bookId) { bookRepository.flow(bookId) }
      .collectAsState(initial = null).value?.content?.coverUrl
  }
```

Then replace `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubReaderScreen.kt`'s `EpubReaderScreen` function body:

```kotlin
@Composable
public fun EpubReaderScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<EpubReaderGraph>()
      .epubReaderViewModelFactory
      .create(bookId)
  }
  val viewState = viewModel.viewState()
  val dialogState = viewModel.dialogState.value
  EpubReaderView(
    viewState = viewState,
    bookId = bookId,
    coverUrl = viewModel.coverUrl(),
    dialogState = dialogState,
    onPlayPauseClick = viewModel::playPause,
    onChapterSelect = viewModel::onChapterSelect,
    onSeek = viewModel::seekTo,
    onSkipToPreviousSentence = viewModel::skipToPreviousSentence,
    onSkipToNextSentence = viewModel::skipToNextSentence,
    onSentenceTapped = viewModel::onSentenceTapped,
    onCloseClick = viewModel::onCloseClick,
    onSleepTimerClick = viewModel::toggleSleepTimer,
    onSpeedChangeClick = viewModel::onPlaybackSpeedIconClick,
    onSkipSilenceClick = viewModel::toggleSkipSilence,
    onVolumeBoostClick = viewModel::onVolumeGainIconClick,
    onDismissDialog = viewModel::dismissDialog,
    onPlaybackSpeedChanged = viewModel::onPlaybackSpeedChanged,
    onVolumeGainChanged = viewModel::onVolumeGainChanged,
    onIncrementSleepTime = viewModel::incrementSleepTime,
    onDecrementSleepTime = viewModel::decrementSleepTime,
    onAcceptSleepTime = viewModel::onAcceptSleepTime,
    onAcceptSleepAtEndOfChapter = viewModel::onAcceptSleepAtEndOfChapter,
  )
}
```

- [ ] **Step 5: Update the `EpubReaderViewModelTest` factory for the new `navigator` parameter**

In `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubReaderViewModelTest.kt`, add `navigator = mockk()` (with `import voice.navigation.Navigator` and `import io.mockk.mockk` already present) to the `viewModel()` factory's `EpubReaderViewModel(...)` construction from Task 6/Step 2.

- [ ] **Step 6: Build to verify everything compiles**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:compileDebugKotlin :features:epubReader:testDebugUnitTest
```

Expected: compiles cleanly, all existing `EpubReaderViewModelTest`/`EpubPlaylistControllerTest`/`EpubBookOpenerTest`/`EpubParagraphGroupingTest`/`EpubChapterProgressTest`/`EpubBookOpenerTest` cases still pass (this task only touched Compose UI files plus `onCloseClick`/`coverUrl`, neither of which existing tests exercise yet — no regressions expected, but confirm the suite is still green).

- [ ] **Step 7: Commit**

```
git add features/epubReader
git commit -m "Rewrite EpubReaderView chrome to match the audiobook player screen"
```

---

## Task 8: Paragraph flowing text — soft highlight, tap-to-seek, keep-in-view scroll

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`
- Create: `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubHighlightColors.kt`

**Interfaces:**
- Consumes: `EpubReaderViewState.Content.paragraphs`/`activeSentenceIndex` (Task 4); `onSentenceTapped: (Int) -> Unit` (Task 7's already-wired callback).
- Produces: the finished reading experience — no further tasks depend on this one.

- [ ] **Step 1: Add theme-aware highlight colors**

Create `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubHighlightColors.kt`:

```kotlin
package voice.features.epubReader.view

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun activeSentenceHighlightColor(): Color {
  return if (isSystemInDarkTheme()) {
    Color(0xFF5C4A1E) // dimmer amber/gold, legible against a dark background
  } else {
    Color(0xFFFFF3B0) // soft, muted warm yellow — Kindle-style read-along highlight
  }
}
```

Deliberately not derived from `MaterialTheme.colorScheme` — a read-along highlight is a fixed warm color independent of the app's dynamic theme, matching how Kindle's highlight color doesn't follow the reading app's accent color either.

- [ ] **Step 2: Replace the paragraph-rendering section of `EpubReaderContent`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt`, add these imports alongside the existing ones from Task 7:

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
```

Replace the `var flatIndex = 0 ... itemsIndexed(paragraph) { ... }` block from Task 7/Step 3 with:

```kotlin
      itemsIndexed(paragraphStarts(viewState.paragraphs).zip(viewState.paragraphs)) { _, (paragraphStart, paragraph) ->
        EpubParagraph(
          sentences = paragraph,
          paragraphStart = paragraphStart,
          activeSentenceIndex = viewState.activeSentenceIndex,
          onSentenceTapped = onSentenceTapped,
        )
      }
    }
  }
  LaunchedEffect(viewState.activeSentenceIndex, viewState.paragraphs) {
    val (activeParagraphIndex, _) = paragraphIndexAndOffsetFor(viewState.paragraphs, viewState.activeSentenceIndex) ?: return@LaunchedEffect
    val itemIndex = activeParagraphIndex + PARAGRAPH_ITEMS_OFFSET
    val visibleItem = listState.layoutInfo.visibleItemsInfo.find { it.index == itemIndex }
    val viewportHeight = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    val comfortableZoneEnd = (viewportHeight * 0.7F).toInt()
    val needsScroll = visibleItem == null || visibleItem.offset > comfortableZoneEnd || visibleItem.offset < 0
    if (needsScroll) {
      listState.animateScrollToItem(itemIndex)
    }
  }
```

(The `LaunchedEffect` call sits at the same level as the `Scaffold(...)` call within `EpubReaderContent` — after it, not nested inside its content lambda — since it needs to observe `listState`, which is already in scope from the `val listState = rememberLazyListState()` declared earlier in this function.)

Add the two small pure helpers near the bottom of the file (or in a new small file `EpubParagraphLayout.kt` if preferred — keeping them in `EpubReaderView.kt` is fine given their small size and single caller):

```kotlin
// Two chrome items (cover, chapter row) precede the paragraph items in the LazyColumn — see
// EpubReaderContent's `item { Cover(...) }` / `item { ChapterRow(...) }` calls above.
private const val PARAGRAPH_ITEMS_OFFSET = 2

private fun paragraphStarts(paragraphs: List<List<String>>): List<Int> {
  val starts = mutableListOf<Int>()
  var cumulative = 0
  for (paragraph in paragraphs) {
    starts += cumulative
    cumulative += paragraph.size
  }
  return starts
}

private fun paragraphIndexAndOffsetFor(
  paragraphs: List<List<String>>,
  flatIndex: Int,
): Pair<Int, Int>? {
  var remaining = flatIndex
  for ((paragraphIndex, paragraph) in paragraphs.withIndex()) {
    if (remaining < paragraph.size) return paragraphIndex to remaining
    remaining -= paragraph.size
  }
  return null
}
```

Add the new `EpubParagraph` composable — builds one `AnnotatedString` per paragraph with the active sentence's character range highlighted, and resolves taps to a sentence via `TextLayoutResult.getOffsetForPosition`:

```kotlin
@Composable
private fun EpubParagraph(
  sentences: List<String>,
  paragraphStart: Int,
  activeSentenceIndex: Int,
  onSentenceTapped: (Int) -> Unit,
) {
  val highlightColor = activeSentenceHighlightColor()
  val localActiveIndex = (activeSentenceIndex - paragraphStart).takeIf { it in sentences.indices }

  val ranges = remember(sentences) {
    val result = mutableListOf<IntRange>()
    var offset = 0
    for (sentence in sentences) {
      val end = offset + sentence.length
      result += offset until end
      offset = end + 1 // account for the joining space added below
    }
    result
  }
  val text = remember(sentences) { sentences.joinToString(" ") }

  val annotated = remember(text, localActiveIndex, highlightColor) {
    buildAnnotatedString {
      append(text)
      localActiveIndex?.let { index ->
        val range = ranges[index]
        addStyle(
          style = SpanStyle(background = highlightColor),
          start = range.first,
          end = range.last + 1,
        )
      }
    }
  }

  var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

  Text(
    text = annotated,
    onTextLayout = { layoutResult = it },
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 20.dp, vertical = 6.dp)
      .pointerInput(text) {
        detectTapGestures { tapOffset ->
          val layout = layoutResult ?: return@detectTapGestures
          val charOffset = layout.getOffsetForPosition(tapOffset)
          val tappedSentenceIndex = ranges.indexOfFirst { charOffset in it }.takeIf { it >= 0 } ?: return@detectTapGestures
          onSentenceTapped(paragraphStart + tappedSentenceIndex)
        }
      },
  )
}
```

- [ ] **Step 3: Build to verify everything compiles**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:compileDebugKotlin
```

Expected: compiles with no errors. Fix any import/reference mistakes surfaced here before proceeding (this step exists specifically because there is no Compose UI test to catch them automatically).

- [ ] **Step 4: Run the full `features:epubReader` test suite**

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew :features:epubReader:testDebugUnitTest
```

Expected: PASS, every test in the module (this task touched no ViewModel/logic code, only `EpubReaderView.kt` and the new `EpubHighlightColors.kt`, so no test file changes are expected here — this step is a final confirmation that Task 8's UI-only changes didn't somehow break the ViewModel layer, e.g. via an accidental signature change).

- [ ] **Step 5: Commit**

```
git add features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubReaderView.kt features/epubReader/src/main/kotlin/voice/features/epubReader/view/EpubHighlightColors.kt
git commit -m "Render EPUB chapters as flowing paragraphs with soft highlight, tap-to-seek, and keep-in-view scroll"
```

---

## Final regression check

After all 8 tasks are committed, run the project's full unit test suite once to confirm nothing outside the touched modules regressed:

```
export JAVA_HOME="/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1"
./gradlew voiceUnitTest
```

Expected: green except the pre-existing, environment-specific Windows failures already documented in this project's toolchain notes (`NaturalOrderComparatorTest.uriComparatorFiles`, `ConvertersTest.file`, all `DataBaseMigratorTest` cases) — confirm no *new* failures beyond that known set.

This plan's automated steps stop at compile + unit-test verification for the Compose UI work (Tasks 7–8), per the Global Constraints note about this project having no Compose UI test infrastructure anywhere. **On-device manual verification is required before considering this feature done** — build and install `:app:assembleFreeDebug`, then check: paragraph text flows continuously (no per-sentence boxes); the active sentence highlights in soft yellow (light) / dimmer amber (dark) without a hard block background; scrolling stays put during most sentence advances and only nudges forward as the highlight nears the bottom of the screen (no snap-to-top); tapping a sentence jumps narration there; the icon bar's sleep timer/speed/skip-silence/volume-boost controls all take effect on EPUB playback; the bookmark icon renders visibly disabled and does nothing when tapped; the cover-forward layout matches the audiobook screen's proportions and scrolls away naturally into the text; the bottom playback row's prev/next-sentence buttons work and clamp at chapter boundaries; and a legacy EPUB (opened under the previous build) picks up paragraph structure on next open without losing its resume position.
