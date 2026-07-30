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
}
