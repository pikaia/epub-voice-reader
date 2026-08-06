# EPUB Cover Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** EPUB books get a real cover image, extracted from the EPUB file itself, displayed the same way audiobook covers already are.

**Architecture:** `core/epub`'s existing OPF-parsing pass (already opens the zip, parses the manifest, and resolves chapter hrefs) is extended to also locate and read a cover image's raw bytes in the same pass, returned as a new `ParsedBook.coverBytes: ByteArray?` field. The first-open import flow (`EpubImporter`, `core/scanner`) decodes those bytes and saves them through the existing `CoverSaver` — the same storage path (`bookCovers/<uuid>.png`) and `BookContent.cover: File?` field audiobook covers already use, so no UI changes are needed anywhere. `EpubBookOpener` (`features/epubReader`) gets a backfill branch so EPUBs imported before this feature shipped pick up a cover the next time they're opened.

**Tech Stack:** Kotlin, `java.util.zip.ZipFile` (existing `core/epub` infra), `android.graphics.BitmapFactory`/`Bitmap` (existing `core/scanner` infra via `CoverSaver`), JUnit/`kotlin.test`, MockK, Robolectric (`AndroidJUnit4`) for the two Android-dependent test modules.

## Global Constraints

- Cover extraction is lazy, at first-open — never at folder-scan time. `core/epub`'s zip-opening/parsing only ever happens inside `EpubImporter.import()`, exactly as it does today.
- Cover detection checks, in this exact priority order: (1) EPUB3 `<item properties="cover-image">`, (2) EPUB2 `<meta name="cover" content="ID">` resolved against the manifest, (3) fallback to the first `image/*`-typed item in the manifest in document order. If none match, `coverBytes` is `null`.
- Cover entries are capped at 5 MB (`5 * 1024 * 1024L` bytes). An oversized cover entry is treated exactly like "no cover found" — never a parse failure.
- `core/epub` stays Android-free: it returns raw `ByteArray?`, never `Bitmap`/`File`/anything Android-specific.
- A cover that can't be decoded, is missing, or fails to save for any reason must never block a book from importing or opening successfully.
- Already-imported EPUBs (chapters already persisted under an earlier build) get backfilled with a cover the next time they're opened, via one extra re-import. A failed backfill re-import (e.g. the source file was moved/deleted since) must not block the book from opening — it already has chapters/sentences and is otherwise fully readable.
- Reuse `CoverSaver.save(bookId, Bitmap)` and its existing `bookCovers/<uuid>.png` storage — do not create a second cover storage path.

---

### Task 1: `core/epub` — locate and read the cover image

**Files:**
- Modify: `core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt`
- Modify: `core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt`
- Modify: `core/epub/src/test/kotlin/voice/core/epub/EpubTestFixtures.kt`
- Test: `core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt`

**Interfaces:**
- Consumes: nothing new — this task only touches `core/epub`, which has no dependencies on other project modules.
- Produces: `ParsedBook(chapters: List<ParsedChapter>, coverBytes: ByteArray? = null)` — the `coverBytes` field Task 2 reads. `EpubParser.parse(file: File): EpubParseResult` signature is unchanged; `EpubParseResult.Success.book.coverBytes` is the new surface.

- [ ] **Step 1: Add `coverBytes` to `ParsedBook`**

Edit `core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt`:

```kotlin
package voice.core.epub

data class ParsedBook(
  val chapters: List<ParsedChapter>,
  val coverBytes: ByteArray? = null,
)

data class ParsedChapter(
  val title: String,
  val sentences: List<String>,
)

sealed interface EpubParseResult {
  data class Success(val book: ParsedBook) : EpubParseResult
  data object DrmProtected : EpubParseResult
  data class Malformed(val reason: String) : EpubParseResult
}
```

The default `= null` keeps every existing call site (`ParsedBook(chapters)`, positional, in `DefaultEpubParserTest.kt`) compiling unchanged.

**Important — do not rely on `ParsedBook`/`EpubParseResult` structural `assertEquals` in any new test that sets a non-null `coverBytes`.** Kotlin data classes compare `ByteArray` fields by *reference*, not content, so `assertEquals(EpubParseResult.Success(ParsedBook(chapters, bytes)), result)` would fail even when the bytes are identical. The existing tests are safe (they never set `coverBytes`, so both sides are `null`). For every new cover-related test in Step 6, assert `coverBytes` separately via `.contentEquals(...)`, e.g. `assertEquals(expected = true, actual = result.book.coverBytes?.contentEquals(expectedBytes))`.

- [ ] **Step 2: Extend `EpubTestFixtures.buildTestEpub` to support manifest images**

Replace the full contents of `core/epub/src/test/kotlin/voice/core/epub/EpubTestFixtures.kt` with:

```kotlin
package voice.core.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class TestEpubChapter(
  val title: String?,
  val bodyHtml: String,
)

internal data class TestManifestImage(
  val id: String,
  val href: String,
  val mediaType: String,
  val content: ByteArray,
  val coverImageProperty: Boolean = false,
)

internal fun buildTestEpub(
  file: File,
  chapters: List<TestEpubChapter>,
  encrypted: Boolean = false,
  images: List<TestManifestImage> = emptyList(),
  coverMetaContentId: String? = null,
): File {
  ZipOutputStream(file.outputStream()).use { zip ->
    fun writeEntry(
      name: String,
      content: String,
    ) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(content.toByteArray())
      zip.closeEntry()
    }
    fun writeBinaryEntry(
      name: String,
      content: ByteArray,
    ) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(content)
      zip.closeEntry()
    }

    writeEntry("mimetype", "application/epub+zip")
    writeEntry(
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
    if (encrypted) {
      writeEntry(
        "META-INF/encryption.xml",
        """<?xml version="1.0" encoding="UTF-8"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>""",
      )
    }

    val chapterManifestItems = chapters.indices.joinToString("\n          ") { index ->
      """<item id="chapter$index" href="chapter$index.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val imageManifestItems = images.joinToString("") { image ->
      val properties = if (image.coverImageProperty) " properties=\"cover-image\"" else ""
      "\n          " +
        """<item id="${image.id}" href="${image.href}" media-type="${image.mediaType}"$properties/>"""
    }
    val spineItems = chapters.indices.joinToString("\n          ") { index ->
      """<itemref idref="chapter$index"/>"""
    }
    val metadata = if (coverMetaContentId != null) {
      """<meta name="cover" content="$coverMetaContentId"/>"""
    } else {
      ""
    }
    writeEntry(
      "OEBPS/content.opf",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata>$metadata</metadata>
        <manifest>
          $chapterManifestItems$imageManifestItems
        </manifest>
        <spine>
          $spineItems
        </spine>
      </package>
      """.trimIndent(),
    )

    chapters.forEachIndexed { index, chapter ->
      val head = if (chapter.title != null) "<head><title>${chapter.title}</title></head>" else "<head></head>"
      writeEntry(
        "OEBPS/chapter$index.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          $head
          <body>${chapter.bodyHtml}</body>
        </html>
        """.trimIndent(),
      )
    }

    images.forEach { image ->
      writeBinaryEntry("OEBPS/${image.href}", image.content)
    }
  }
  return file
}
```

- [ ] **Step 3: Run the existing `core/epub` tests to confirm nothing broke**

Run: `./gradlew :core:epub:testDebugUnitTest`
Expected: all existing tests in `DefaultEpubParserTest.kt` still PASS unchanged — the fixture change is purely additive (new optional parameters).

- [ ] **Step 4: Write the failing tests for cover detection**

Add these test cases to `core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt` (inside the existing `class DefaultEpubParserTest`, alongside the existing tests — do not remove or modify any existing test):

```kotlin
  @Test
  fun `extracts the cover declared via the EPUB3 cover-image property`() {
    val coverBytes = "fake-cover-bytes-epub3".toByteArray()
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      images = listOf(
        TestManifestImage(
          id = "cover-image",
          href = "cover.jpg",
          mediaType = "image/jpeg",
          content = coverBytes,
          coverImageProperty = true,
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = true, actual = result.book.coverBytes?.contentEquals(coverBytes))
  }

  @Test
  fun `extracts the cover declared via the EPUB2 meta name cover convention`() {
    val coverBytes = "fake-cover-bytes-epub2".toByteArray()
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      images = listOf(
        TestManifestImage(
          id = "my-cover",
          href = "cover.png",
          mediaType = "image/png",
          content = coverBytes,
        ),
      ),
      coverMetaContentId = "my-cover",
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = true, actual = result.book.coverBytes?.contentEquals(coverBytes))
  }

  @Test
  fun `falls back to the first manifest image when no cover convention is declared`() {
    val coverBytes = "fake-cover-bytes-fallback".toByteArray()
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      images = listOf(
        TestManifestImage(id = "img-1", href = "first.png", mediaType = "image/png", content = coverBytes),
        TestManifestImage(
          id = "img-2",
          href = "second.png",
          mediaType = "image/png",
          content = "second-image-bytes".toByteArray(),
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = true, actual = result.book.coverBytes?.contentEquals(coverBytes))
  }

  @Test
  fun `prefers the EPUB3 cover-image property over the EPUB2 meta convention`() {
    val propertyBytes = "cover-via-property".toByteArray()
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      images = listOf(
        TestManifestImage(
          id = "property-cover",
          href = "property.png",
          mediaType = "image/png",
          content = propertyBytes,
          coverImageProperty = true,
        ),
        TestManifestImage(
          id = "meta-cover",
          href = "meta.png",
          mediaType = "image/png",
          content = "cover-via-meta".toByteArray(),
        ),
      ),
      coverMetaContentId = "meta-cover",
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = true, actual = result.book.coverBytes?.contentEquals(propertyBytes))
  }

  @Test
  fun `returns a null cover when the manifest has no images at all`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = null, actual = result.book.coverBytes)
  }

  @Test
  fun `treats an oversized cover entry as no cover instead of failing the parse`() {
    val oversized = ByteArray(5 * 1024 * 1024 + 1)
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      images = listOf(
        TestManifestImage(
          id = "cover-image",
          href = "huge.jpg",
          mediaType = "image/jpeg",
          content = oversized,
          coverImageProperty = true,
        ),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = null, actual = result.book.coverBytes)
  }
```

- [ ] **Step 5: Run the new tests to verify they fail**

Run: `./gradlew :core:epub:testDebugUnitTest --tests "*DefaultEpubParserTest*"`
Expected: the 6 new tests FAIL (cover detection doesn't exist yet — `result.book.coverBytes` doesn't compile or is always `null`/absent).

- [ ] **Step 6: Implement cover detection and binary reads in `DefaultEpubParser`**

Replace the full contents of `core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt` with:

```kotlin
package voice.core.epub

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

private val SENTENCE_LOCALE: Locale = Locale.US

// 20 MB per entry (a single EPUB chapter or OPF/container file)
private const val MAX_ENTRY_SIZE_BYTES = 20 * 1024 * 1024L

// 5 MB per cover image entry — generous for a cover, well under the text-entry cap above.
private const val MAX_COVER_SIZE_BYTES = 5 * 1024 * 1024L

@ContributesBinding(AppScope::class)
class DefaultEpubParser : EpubParser {

  private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val isCoverImage: Boolean,
  )

  override fun parse(file: File): EpubParseResult {
    return try {
      ZipFile(file).use { zip -> parseZip(zip) }
    } catch (e: Exception) {
      EpubParseResult.Malformed(e.message ?: e.javaClass.simpleName)
    }
  }

  private fun parseZip(zip: ZipFile): EpubParseResult {
    if (zip.getEntry("META-INF/encryption.xml") != null) {
      return EpubParseResult.DrmProtected
    }

    val containerXml = zip.readEntryOrNull("META-INF/container.xml")
      ?: return EpubParseResult.Malformed("missing META-INF/container.xml")
    val opfPath = parseOpfPath(containerXml)
      ?: return EpubParseResult.Malformed("no rootfile in container.xml")
    val opfXml = zip.readEntryOrNull(opfPath)
      ?: return EpubParseResult.Malformed("missing OPF file at $opfPath")
    val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val opfDocument = parseXml(opfXml)
      ?: return EpubParseResult.Malformed("could not parse OPF at $opfPath")

    val manifestItems = parseManifestItems(opfDocument)
    val manifest = manifestItems.associate { it.id to it.href }
    val spineHrefs = parseSpineHrefs(opfDocument, manifest)
      ?: return EpubParseResult.Malformed("could not resolve manifest/spine")

    fun resolvePath(href: String) = if (opfDir.isEmpty()) href else "$opfDir/$href"

    val chapters = spineHrefs.mapIndexed { index, href ->
      val path = resolvePath(href)
      val html = zip.readEntryOrNull(path)
        ?: return EpubParseResult.Malformed("missing chapter file at $path")
      parseChapter(html, fallbackTitle = "Chapter ${index + 1}")
    }

    val coverHref = findCoverHref(opfDocument, manifestItems)
    val coverBytes = coverHref?.let { href -> zip.readEntryBytesOrNull(resolvePath(href)) }

    return EpubParseResult.Success(ParsedBook(chapters, coverBytes))
  }

  private fun parseChapter(
    html: String,
    fallbackTitle: String,
  ): ParsedChapter {
    val document = Jsoup.parse(html)
    val title = document.title().ifBlank { fallbackTitle }
    val sentences = splitSentences(document.body().text())
    return ParsedChapter(title = title, sentences = sentences)
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

  private fun parseOpfPath(containerXml: String): String? {
    val document = parseXml(containerXml) ?: return null
    val rootFiles = document.getElementsByTagName("rootfile")
    if (rootFiles.length == 0) return null
    val element = rootFiles.item(0) as? Element ?: return null
    return element.getAttribute("full-path").ifBlank { null }
  }

  private fun parseManifestItems(document: Document): List<ManifestItem> {
    val items = document.getElementsByTagName("item")
    val result = mutableListOf<ManifestItem>()
    for (i in 0 until items.length) {
      val item = items.item(i) as? Element ?: continue
      val id = item.getAttribute("id")
      val href = item.getAttribute("href")
      if (id.isBlank() || href.isBlank()) continue
      val mediaType = item.getAttribute("media-type")
      val properties = item.getAttribute("properties")
      val isCoverImage = properties.split(" ").any { it == "cover-image" }
      result += ManifestItem(id = id, href = href, mediaType = mediaType, isCoverImage = isCoverImage)
    }
    return result
  }

  private fun parseSpineHrefs(
    document: Document,
    manifest: Map<String, String>,
  ): List<String>? {
    val spine = mutableListOf<String>()
    val itemRefs = document.getElementsByTagName("itemref")
    for (i in 0 until itemRefs.length) {
      val itemRef = itemRefs.item(i) as? Element ?: continue
      val href = manifest[itemRef.getAttribute("idref")] ?: return null
      spine += href
    }
    return spine.ifEmpty { null }
  }

  private fun findCoverHref(
    document: Document,
    manifestItems: List<ManifestItem>,
  ): String? {
    manifestItems.firstOrNull { it.isCoverImage }?.let { return it.href }
    val coverMetaId = findCoverMetaContentId(document)
    if (coverMetaId != null) {
      manifestItems.firstOrNull { it.id == coverMetaId }?.let { return it.href }
    }
    return manifestItems.firstOrNull { it.mediaType.startsWith("image/") }?.href
  }

  private fun findCoverMetaContentId(document: Document): String? {
    val metas = document.getElementsByTagName("meta")
    for (i in 0 until metas.length) {
      val meta = metas.item(i) as? Element ?: continue
      if (meta.getAttribute("name") == "cover") {
        return meta.getAttribute("content").ifBlank { null }
      }
    }
    return null
  }

  private fun parseXml(xml: String): Document? {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setPropertyIfSupported { isXIncludeAware = false }
    factory.setPropertyIfSupported { isExpandEntityReferences = false }
    return factory.newDocumentBuilder().parse(xml.byteInputStream())
  }

  private inline fun DocumentBuilderFactory.setPropertyIfSupported(set: DocumentBuilderFactory.() -> Unit) {
    try {
      set()
    } catch (e: UnsupportedOperationException) {
      // Android's built-in DocumentBuilderFactory doesn't support configuring some of these
      // properties at all (even to set them to their already-default value) — a no-op there.
    }
  }

  private fun DocumentBuilderFactory.setFeatureIfSupported(
    name: String,
    value: Boolean,
  ) {
    try {
      setFeature(name, value)
    } catch (e: ParserConfigurationException) {
      // Android's built-in XML parser doesn't recognize Xerces-specific hardening features
      // (unlike the JVM's Xerces used in unit tests) — the other hardening calls in parseXml
      // still apply on every platform regardless of this feature's support.
    }
  }

  private fun ZipFile.readEntryOrNull(path: String): String? {
    val entry = getEntry(path) ?: return null
    if (entry.size < 0 || entry.size > MAX_ENTRY_SIZE_BYTES) return null
    return getInputStream(entry).bufferedReader().use { it.readText() }
  }

  private fun ZipFile.readEntryBytesOrNull(path: String): ByteArray? {
    val entry = getEntry(path) ?: return null
    if (entry.size < 0 || entry.size > MAX_COVER_SIZE_BYTES) return null
    return getInputStream(entry).use { it.readBytes() }
  }
}
```

This is a refactor of the parsing internals (the OPF XML `Document` is now parsed once and shared between manifest/spine/cover resolution, instead of being re-parsed inside a `parseSpineHrefs(opfXml: String)` helper) but produces byte-for-byte identical chapter output to before — every existing test from Step 3 must still pass.

- [ ] **Step 7: Run the tests again to verify they pass**

Run: `./gradlew :core:epub:testDebugUnitTest`
Expected: ALL tests in `DefaultEpubParserTest.kt` PASS — the pre-existing ones (unchanged behavior) and the 6 new ones from Step 4.

- [ ] **Step 8: Commit**

```bash
git add core/epub/src/main/kotlin/voice/core/epub/EpubModels.kt core/epub/src/main/kotlin/voice/core/epub/DefaultEpubParser.kt core/epub/src/test/kotlin/voice/core/epub/EpubTestFixtures.kt core/epub/src/test/kotlin/voice/core/epub/DefaultEpubParserTest.kt
git commit -m "Locate and read EPUB cover images in the existing OPF parse pass"
```

---

### Task 2: `core/scanner` — `EpubImporter` saves the extracted cover

**Files:**
- Modify: `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt`
- Test: `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt`

**Interfaces:**
- Consumes: `ParsedBook.coverBytes: ByteArray?` (Task 1). `CoverSaver.save(bookId: BookId, cover: Bitmap)` (existing, `core/scanner/src/main/kotlin/voice/core/scanner/CoverSaver.kt` — already used by `CoverScanner`, not modified by this task).
- Produces: `EpubImporter`'s public constructor gains a new required `coverSaver: CoverSaver` parameter — Task 3 must update every place that constructs `EpubImporter` directly.

- [ ] **Step 1: Write the failing tests**

`EpubImporterTest.kt` (`core/scanner`'s test source) is in the **same Gradle module** as `CoverSaver.kt`, so it can construct a real `CoverSaver` directly (its constructor is `internal`, visible within the module). Replace the full contents of `core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt` with:

```kotlin
package voice.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.ChapterId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.BookRepository
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.FileBasedDocumentFile
import voice.core.epub.DefaultEpubParser
import voice.core.epub.EpubParseResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class EpubImporterTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val repo = FakeEpubBookRepo()
  private val bookRepository = FakeBookRepository()
  private val coverSaver = CoverSaver(repo = bookRepository, context = context)
  private val importer = EpubImporter(
    context = context,
    epubParser = DefaultEpubParser(),
    epubBookRepo = repo,
    coverSaver = coverSaver,
  )

  @Test
  fun importsAndPersistsChaptersAndSentencesFromAContentUri() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toUri())

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    assertEquals(expected = listOf("Chapter One"), actual = repo.chapters(bookId).map { it.title })
    assertEquals(
      expected = listOf("Hello there.", "This is chapter one."),
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

  @Test
  fun savesTheCoverWhenTheEpubDeclaresAValidOne() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"), includeCover = true)
    val bookId = BookId(file.toUri())
    bookRepository.seed(bookId)

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    val savedCover = bookRepository.content[bookId]?.cover
    assertEquals(expected = true, actual = savedCover != null && savedCover.exists())
  }

  @Test
  fun leavesTheCoverUnsetWhenTheEpubDeclaresNoCover() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"), includeCover = false)
    val bookId = BookId(file.toUri())
    bookRepository.seed(bookId)

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    assertNull(bookRepository.content[bookId]?.cover)
  }

  @Test
  fun leavesTheCoverUnsetWhenTheDeclaredCoverBytesAreNotADecodableImage() = runTest {
    val file = buildMinimalEpub(
      File(testFolder.newFolder(), "book.epub"),
      includeCover = true,
      coverBytes = "not a real image".toByteArray(),
    )
    val bookId = BookId(file.toUri())
    bookRepository.seed(bookId)

    val result = importer.import(bookId, FileBasedDocumentFile(file))

    assertIs<EpubParseResult.Success>(result)
    assertNull(bookRepository.content[bookId]?.cover)
  }

  private fun buildMinimalEpub(
    file: File,
    includeCover: Boolean = false,
    coverBytes: ByteArray = tinyPngBytes(),
  ): File {
    ZipOutputStream(file.outputStream()).use { zip ->
      fun entry(
        name: String,
        content: String,
      ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
      fun binaryEntry(
        name: String,
        content: ByteArray,
      ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
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
      val coverManifestItem = if (includeCover) {
        """<item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>"""
      } else {
        ""
      }
      entry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata/>
          <manifest>
            <item id="chapter0" href="chapter0.xhtml" media-type="application/xhtml+xml"/>
            $coverManifestItem
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
      if (includeCover) {
        binaryEntry("OEBPS/cover.png", coverBytes)
      }
    }
    return file
  }

  private fun tinyPngBytes(): ByteArray {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(Color.RED)
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
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

    override suspend fun totalCharacterCount(bookId: BookId): Int =
      sentences[bookId].orEmpty().sumOf { it.text.length }
  }

  private class FakeBookRepository : BookRepository {
    val content = mutableMapOf<BookId, BookContent>()

    fun seed(bookId: BookId) {
      content[bookId] = BookContent(
        id = bookId,
        playbackSpeed = 1F,
        skipSilence = false,
        isActive = true,
        lastPlayedAt = Instant.EPOCH,
        author = null,
        name = "Test Book",
        addedAt = Instant.EPOCH,
        chapters = listOf(ChapterId(bookId.toUri())),
        currentChapter = ChapterId(bookId.toUri()),
        positionInChapter = 0L,
        cover = null,
        gain = 0F,
        genre = null,
        narrator = null,
        series = null,
        part = null,
        sourceType = BookSourceType.Epub,
        voiceId = null,
      )
    }

    override fun flow(): Flow<List<Book>> = throw NotImplementedError()
    override suspend fun all(): List<Book> = throw NotImplementedError()
    override fun flow(id: BookId): Flow<Book?> = throw NotImplementedError()
    override suspend fun get(id: BookId): Book? = null

    override suspend fun updateBook(
      id: BookId,
      update: (BookContent) -> BookContent,
    ) {
      val existing = content[id] ?: return
      content[id] = update(existing)
    }
  }
}
```

`FakeBookRepository.get()` always returns `null` — that's fine, it only affects `CoverSaver.setBookCover`'s "delete the old cover file" step (nothing to delete on a freshly-seeded book), not whether the new cover gets saved.

- [ ] **Step 2: Run the tests to verify the 3 new ones fail**

Run: `./gradlew :core:scanner:testDebugUnitTest --tests "*EpubImporterTest*"`
Expected: `importsAndPersistsChaptersAndSentencesFromAContentUri` and `returnsMalformedWithoutPersistingWhenTheFileIsNotAnEpub` FAIL to even compile initially (constructor now requires `coverSaver`) — once compiling, `savesTheCoverWhenTheEpubDeclaresAValidOne` should be the one that fails on assertions (no cover gets saved yet), while the "leaves cover unset" tests may pass vacuously since nothing sets a cover at all yet. The important signal here is the compile error until Step 3 lands the new constructor parameter.

- [ ] **Step 3: Wire `CoverSaver` into `EpubImporter`**

Replace the full contents of `core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt` with:

```kotlin
package voice.core.scanner

import android.content.Context
import android.graphics.BitmapFactory
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
  private val coverSaver: CoverSaver,
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
          saveCoverIfPresent(bookId, result)
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

  private suspend fun saveCoverIfPresent(
    bookId: BookId,
    result: EpubParseResult.Success,
  ) {
    val coverBytes = result.book.coverBytes ?: return
    val bitmap = BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
    if (bitmap == null) {
      Logger.w("Could not decode EPUB cover image for $bookId")
      return
    }
    coverSaver.save(bookId, bitmap)
  }
}
```

`saveCoverIfPresent` runs after `persist()` has already succeeded and never affects the returned `EpubParseResult` — a missing, undecodable, or unsaveable cover never turns a successful import into a failure.

- [ ] **Step 4: Run the tests again to verify they pass**

Run: `./gradlew :core:scanner:testDebugUnitTest --tests "*EpubImporterTest*"`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/scanner/src/main/kotlin/voice/core/scanner/EpubImporter.kt core/scanner/src/test/kotlin/voice/core/scanner/EpubImporterTest.kt
git commit -m "Save the extracted EPUB cover through CoverSaver on import"
```

---

### Task 3: `features/epubReader` — backfill covers for already-imported EPUBs

**Files:**
- Modify: `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`
- Test: `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`

**Interfaces:**
- Consumes: `EpubImporter`'s new `coverSaver: CoverSaver` constructor parameter (Task 2) — this test file constructs `EpubImporter` directly and must be updated.
- Produces: no new public surface — `EpubBookOpener.open()`'s signature and `OpenResult` are unchanged.

**Important — `features/epubReader` is a different Gradle module than `core/scanner`, where `CoverSaver`'s constructor is `internal`.** Unlike Task 2's test (same module as `CoverSaver`, can construct it directly), this test file **cannot** construct a real `CoverSaver` — its `internal constructor` isn't visible across the module boundary. Use `mockk<CoverSaver>()` instead. Because `CoverSaver` is a concrete (not interface) class, this relies on MockK's inline mock maker for final classes, the same mechanism already implicitly available to every other MockK usage in this codebase's Robolectric tests.

Also — because `CoverSaver.save()` writes through `BookRepository` (a different repo abstraction than the `BookContentRepo` fake this test file already uses), a mocked `CoverSaver` means this test **cannot** assert that `bookContentRepo` ends up with a cover file (that path is only real in Task 2's `EpubImporterTest`, where `CoverSaver` is a real instance backed by a real `BookRepository` fake). The test in Step 1 below only verifies the *interaction* — that a cover-only backfill re-triggers `epubImporter.import()`, which in turn calls `coverSaver.save(...)` — not the end-to-end persisted state.

- [ ] **Step 1: Write the failing test**

Add this test to `features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt`, alongside the existing tests (do not remove or modify any existing test):

```kotlin
  @Test
  fun `backfills a cover for a book whose chapters and progress fields are already imported`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"), includeCover = true)
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = null,
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello there."),
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "This is chapter one."),
      ),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    coVerify { coverSaver.save(bookId, any()) }
  }

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
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = emptyList(),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    coVerify(exactly = 0) { coverSaver.save(any(), any()) }
  }

  @Test
  fun `still opens successfully when the cover backfill re-import fails`() = runTest {
    val file = File(testFolder.newFolder(), "book.epub")
    file.writeBytes(byteArrayOf(1, 2, 3)) // not a valid epub — the backfill re-import will report Malformed
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 32,
        cover = null,
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello there."),
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "This is chapter one."),
      ),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = listOf("Already Parsed"), actual = epubBookRepo.chapters(bookId).map { it.title })
  }
```

This test proves the Global Constraint that a failed cover-backfill re-import (here, the on-disk file no longer parses as a valid EPUB) never blocks opening a book that already has its chapters/sentences persisted — `open()` must still return `OpenResult.Ready`, not propagate the `Malformed` result from the discarded backfill attempt.

Then update the shared test fixtures at the top of the same class:

1. Add the import `import io.mockk.coVerify` alongside the existing `io.mockk.*` imports.
2. Add a shared `coverSaver` mock and wire it into the shared `epubImporter`:

```kotlin
  private val coverSaver = mockk<CoverSaver>()
  private val epubImporter = EpubImporter(
    context = context,
    epubParser = DefaultEpubParser(),
    epubBookRepo = epubBookRepo,
    coverSaver = coverSaver,
  )
```

(This replaces the existing `epubImporter` declaration, which currently omits `coverSaver`.)

3. Extend the existing `buildMinimalEpub` helper to optionally include a cover, and add its supporting `tinyPngBytes()` helper:

```kotlin
  private fun buildMinimalEpub(
    file: File,
    includeCover: Boolean = false,
  ): File {
    ZipOutputStream(file.outputStream()).use { zip ->
      fun entry(
        name: String,
        content: String,
      ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
      fun binaryEntry(
        name: String,
        content: ByteArray,
      ) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content)
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
      val coverManifestItem = if (includeCover) {
        """<item id="cover-image" href="cover.png" media-type="image/png" properties="cover-image"/>"""
      } else {
        ""
      }
      entry(
        "OEBPS/content.opf",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata/>
          <manifest>
            <item id="chapter0" href="chapter0.xhtml" media-type="application/xhtml+xml"/>
            $coverManifestItem
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
      if (includeCover) {
        binaryEntry("OEBPS/cover.png", tinyPngBytes())
      }
    }
    return file
  }

  private fun tinyPngBytes(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(android.graphics.Color.RED)
    val stream = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
```

(This replaces the existing `buildMinimalEpub(file: File): File` — every existing call site (`buildMinimalEpub(File(...))`, no second argument) keeps working unchanged since `includeCover` defaults to `false`.)

4. The new `backfills a cover...` test calls `coverSaver.save(bookId, any())` through the mock, which needs stubbing since the mock is not relaxed:

```kotlin
  @Test
  fun `backfills a cover for a book whose chapters and progress fields are already imported`() = runTest {
    coEvery { coverSaver.save(any(), any()) } just Runs
    // ...(rest of test body from above)
  }
```

Add that `coEvery { coverSaver.save(any(), any()) } just Runs` line as the first line inside this one test only — the existing shared `voiceManager` mock stubbing pattern already in this file (`coEvery { ... } just Runs` style) is the precedent to follow.

- [ ] **Step 2: Run the tests to verify the new ones fail**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubBookOpenerTest*"`
Expected: `backfills a cover for a book whose chapters and progress fields are already imported` FAILS (`coverSaver.save` is never called — no backfill branch exists yet). `does not re-import when a cover is already set` and `still opens successfully when the cover backfill re-import fails` should already PASS vacuously (nothing triggers a backfill re-import at all today) — that's fine, they become real regression guards once Step 3 lands.

- [ ] **Step 3: Add the cover-backfill branch to `EpubBookOpener.open()`**

In `features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt`, replace the `open()` function body:

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
      content = bookContentRepo.get(bookId) ?: content
      chapters = epubBookRepo.chapters(bookId)
      content = content.withBackfilledProgressFields(bookId, chapters)
      bookContentRepo.put(content)
    } else {
      if (content.epubChapterCount == 0 || content.epubTotalCharacterCount == 0) {
        content = content.withBackfilledProgressFields(bookId, chapters)
        bookContentRepo.put(content)
      }
      if (content.cover == null) {
        // A failed re-import here (e.g. the source file was moved or deleted since) must not
        // block opening an already-readable book — the cover is a nice-to-have, not required
        // for playback. Any Malformed/DrmProtected result is intentionally discarded; the
        // re-read below simply reflects whatever is in the repo, unchanged if this didn't work.
        val documentFile = cachedDocumentFileFactory.create(bookId.toUri())
        epubImporter.import(bookId, documentFile)
        content = bookContentRepo.get(bookId) ?: content
      }
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

Everything below `val voiceId = ...` is unchanged from the current file — only the `if (chapters.isEmpty()) { ... } else if (...) { ... }` block becomes `if (chapters.isEmpty()) { ... } else { ...two inner ifs... }`.

- [ ] **Step 4: Run the tests again to verify they all pass**

Run: `./gradlew :features:epubReader:testDebugUnitTest --tests "*EpubBookOpenerTest*"`
Expected: all tests PASS, including every pre-existing test in this file (the `chapters.isEmpty()` branch and the progress-field backfill branch are behaviorally unchanged) and the 3 new ones.

- [ ] **Step 5: Run the full regression suite**

Run: `./gradlew voiceUnitTest --continue`
Expected: no failures beyond the pre-existing, documented Windows-environment gaps (`NaturalOrderComparatorTest.uriComparatorFiles`, `ConvertersTest.file`, all `DataBaseMigratorTest` cases). Any other failure is a real regression — stop and investigate rather than proceeding to commit.

- [ ] **Step 6: Commit**

```bash
git add features/epubReader/src/main/kotlin/voice/features/epubReader/EpubBookOpener.kt features/epubReader/src/test/kotlin/voice/features/epubReader/EpubBookOpenerTest.kt
git commit -m "Backfill covers for EPUBs already imported before cover extraction existed"
```
