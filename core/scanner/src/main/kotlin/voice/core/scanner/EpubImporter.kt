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
    if (!isValidImageFormat(coverBytes)) {
      Logger.w("Could not decode EPUB cover image for $bookId")
      return
    }
    val bitmap = try {
      BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
    } catch (e: Exception) {
      Logger.w(e, "Error decoding EPUB cover image for $bookId")
      null
    }
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
      Logger.w("Could not decode EPUB cover image for $bookId")
      return
    }
    coverSaver.save(bookId, bitmap)
  }

  private fun isValidImageFormat(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    // Check for PNG magic bytes: 89 50 4E 47
    if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
      return true
    }
    // Check for JPEG magic bytes: FF D8 FF
    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
      return true
    }
    // Check for WebP magic bytes: RIFF...WEBP
    if (bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) {
      if (bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() && bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()) {
        return true
      }
    }
    return false
  }
}
