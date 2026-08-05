package voice.features.epubReader

import dev.zacsweers.metro.Inject
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.EpubBookRepo
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.epub.EpubParseResult
import voice.core.scanner.EpubImporter
import voice.core.tts.InstallResult
import voice.core.tts.VoiceManager

@Inject
public class EpubBookOpener(
  private val epubImporter: EpubImporter,
  private val epubBookRepo: EpubBookRepo,
  private val bookContentRepo: BookContentRepo,
  private val voiceManager: VoiceManager,
  private val cachedDocumentFileFactory: CachedDocumentFileFactory,
) {

  public sealed interface OpenResult {
    public data class Ready(
      val chapters: List<EpubChapter>,
      val voiceId: String,
    ) : OpenResult
    public data class Malformed(val reason: String) : OpenResult
    public data object DrmProtected : OpenResult
    public data class VoiceInstallFailed(val reason: String) : OpenResult
  }

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
    } else if (content.epubChapterCount == 0) {
      content = content.withBackfilledProgressFields(bookId, chapters)
      bookContentRepo.put(content)
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

  private suspend fun BookContent.withBackfilledProgressFields(
    bookId: BookId,
    chapters: List<EpubChapter>,
  ): BookContent {
    val lastChapterSentenceCount = epubBookRepo.sentences(bookId, chapters.size - 1).size
    return copy(
      epubChapterCount = chapters.size,
      epubLastChapterSentenceCount = lastChapterSentenceCount,
      epubTotalCharacterCount = epubBookRepo.totalCharacterCount(bookId),
    )
  }
}
