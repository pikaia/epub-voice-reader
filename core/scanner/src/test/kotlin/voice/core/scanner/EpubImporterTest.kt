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
import org.robolectric.annotation.GraphicsMode
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
