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
