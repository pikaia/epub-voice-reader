package voice.features.epubReader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
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
import voice.core.scanner.CoverSaver
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
  private val coverSaver = mockk<CoverSaver>()
  private val epubImporter = EpubImporter(
    context = context,
    epubParser = DefaultEpubParser(),
    epubBookRepo = epubBookRepo,
    coverSaver = coverSaver,
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
    assertEquals(expected = 1, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 2, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    val expectedCharacterCount = epubBookRepo.sentences(bookId, 0).sumOf { it.text.length }
    assertEquals(expected = expectedCharacterCount, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
  }

  @Test
  fun `skips parsing when chapters already exist`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(bookContent(bookId, voiceId = "voice-a").copy(cover = File("/fake/existing-cover.png")))
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Already Parsed")),
      sentences = emptyList(),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    assertEquals(expected = listOf("Already Parsed"), actual = epubBookRepo.chapters(bookId).map { it.title })
    assertEquals(expected = 1, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 0, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    assertEquals(expected = 0, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
  }

  @Test
  fun `does not touch progress fields when they are already populated`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 5,
        epubLastChapterSentenceCount = 9,
        epubTotalCharacterCount = 42,
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
    assertEquals(expected = 5, actual = bookContentRepo.get(bookId)?.epubChapterCount)
    assertEquals(expected = 9, actual = bookContentRepo.get(bookId)?.epubLastChapterSentenceCount)
    assertEquals(expected = 42, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
  }

  @Test
  fun `backfills epubTotalCharacterCount for a book whose chapters were parsed by an earlier version of the app`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"))
    val bookId = BookId(file.toURI().toString())
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 1,
        epubLastChapterSentenceCount = 2,
        epubTotalCharacterCount = 0,
        cover = File("/fake/existing-cover.png"),
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
    // "Hello there.".length (12) + "This is chapter one.".length (20)
    assertEquals(expected = 32, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
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

  @Test
  fun `backfills a cover for a book whose chapters and progress fields are already imported`() = runTest {
    coEvery { coverSaver.save(any(), any()) } just Runs
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

  @Test
  fun `recomputes progress fields after a cover backfill replaces the chapters`() = runTest {
    val file = buildMinimalEpub(File(testFolder.newFolder(), "book.epub"), includeCover = true)
    val bookId = BookId(file.toURI().toString())
    coEvery { coverSaver.save(any(), any()) } just Runs
    bookContentRepo.put(
      bookContent(bookId, voiceId = "voice-a").copy(
        epubChapterCount = 0,
        epubLastChapterSentenceCount = 0,
        epubTotalCharacterCount = 0,
        cover = null,
      ),
    )
    epubBookRepo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Stale Chapter")),
      sentences = listOf(
        EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Short stale sentence."),
      ),
    )

    val result = opener.open(bookId)

    assertIs<EpubBookOpener.OpenResult.Ready>(result)
    val expectedCharacterCount = epubBookRepo.sentences(bookId, 0).sumOf { it.text.length }
    assertEquals(expected = expectedCharacterCount, actual = bookContentRepo.get(bookId)?.epubTotalCharacterCount)
    assertEquals(expected = listOf("Chapter One"), actual = epubBookRepo.chapters(bookId).map { it.title })
  }

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

    override suspend fun totalCharacterCount(bookId: BookId): Int =
      sentences[bookId].orEmpty().sumOf { it.text.length }
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
