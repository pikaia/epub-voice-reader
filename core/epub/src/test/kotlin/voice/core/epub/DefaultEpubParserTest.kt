package voice.core.epub

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultEpubParserTest {

  private val parser = DefaultEpubParser()

  private fun tempDir(): File = Files.createTempDirectory("epub-test").toFile()

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
            ParsedChapter(title = "Intro", sentences = listOf("Hello there.")),
          ),
        ),
      ),
      actual = result,
    )
  }

  @Test
  fun `splits chapter body into multiple sentences`() {
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
      expected = listOf("Hello there.", "This is chapter one.", "Great stuff."),
      actual = result.book.chapters.single().sentences,
    )
  }

  @Test
  fun `returns chapters in spine order`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(
        TestEpubChapter(title = "One", bodyHtml = "<p>First chapter text.</p>"),
        TestEpubChapter(title = "Two", bodyHtml = "<p>Second chapter text.</p>"),
      ),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(
      expected = listOf("One", "Two"),
      actual = result.book.chapters.map { it.title },
    )
  }

  @Test
  fun `falls back to a generated title when the chapter has none`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = null, bodyHtml = "<p>No title here.</p>")),
    )

    val result = parser.parse(file) as EpubParseResult.Success

    assertEquals(expected = "Chapter 1", actual = result.book.chapters.single().title)
  }

  @Test
  fun `reports DRM-protected books instead of parsing them`() {
    val file = buildTestEpub(
      file = File(tempDir(), "book.epub"),
      chapters = listOf(TestEpubChapter(title = "Intro", bodyHtml = "<p>Hello.</p>")),
      encrypted = true,
    )

    val result = parser.parse(file)

    assertEquals(expected = EpubParseResult.DrmProtected, actual = result)
  }

  @Test
  fun `reports a malformed result when container_xml is missing`() {
    val file = File(tempDir(), "broken.epub")
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
      zip.write("application/epub+zip".toByteArray())
      zip.closeEntry()
    }

    val result = parser.parse(file)

    assertEquals(
      expected = EpubParseResult.Malformed("missing META-INF/container.xml"),
      actual = result,
    )
  }

  @Test
  fun `reports a malformed result for a file that is not a zip`() {
    val file = File(tempDir(), "not-a-zip.epub")
    file.writeBytes(byteArrayOf(1, 2, 3, 4))

    val result = parser.parse(file)

    assertEquals(expected = true, actual = result is EpubParseResult.Malformed)
  }

  @Test
  fun `reports a malformed result when container_xml declares an external entity`() {
    val file = File(tempDir(), "xxe.epub")
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
      zip.write("application/epub+zip".toByteArray())
      zip.closeEntry()

      zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
      zip.write(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE container [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="&xxe;" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent().toByteArray(),
      )
      zip.closeEntry()
    }

    val result = parser.parse(file)

    assertEquals(expected = true, actual = result is EpubParseResult.Malformed)
  }

  @Test
  fun `reports a malformed result when a zip entry exceeds the size cap`() {
    val file = File(tempDir(), "bomb.epub")
    java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
      zip.putNextEntry(java.util.zip.ZipEntry("mimetype"))
      zip.write("application/epub+zip".toByteArray())
      zip.closeEntry()

      zip.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml"))
      val oversized = "a".repeat(21 * 1024 * 1024)
      zip.write(oversized.toByteArray())
      zip.closeEntry()
    }

    val result = parser.parse(file)

    assertEquals(
      expected = EpubParseResult.Malformed("missing META-INF/container.xml"),
      actual = result,
    )
  }
}
