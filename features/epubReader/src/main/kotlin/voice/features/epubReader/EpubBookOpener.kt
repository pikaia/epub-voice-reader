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
        //
        // Known limitation (accepted, deferred): a book whose EPUB genuinely has no
        // extractable cover anywhere (checked via all 3 detection conventions in
        // core/epub) will retry this backfill on every future open() call, since there's
        // no cheap way to distinguish "not yet attempted" from "attempted, found nothing"
        // without new persisted state. This is low severity — wasted re-parse work on a
        // deterministic, unchanged file, not data corruption — and is rare in practice
        // since detection already checks 3 conventions before giving up.
        val documentFile = cachedDocumentFileFactory.create(bookId.toUri())
        val result = epubImporter.import(bookId, documentFile)
        chapters = epubBookRepo.chapters(bookId)
        content = bookContentRepo.get(bookId) ?: content
        if (result is EpubParseResult.Success) {
          content = content.withBackfilledProgressFields(bookId, chapters)
          bookContentRepo.put(content)
        }
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
