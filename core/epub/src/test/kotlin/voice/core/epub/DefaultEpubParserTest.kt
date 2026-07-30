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
}
