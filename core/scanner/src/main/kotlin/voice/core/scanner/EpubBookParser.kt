package voice.core.scanner

import dev.zacsweers.metro.Inject
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.data.repo.getOrPut
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.nameWithoutExtension
import java.time.Instant

@Inject
internal class EpubBookParser(
  private val contentRepo: BookContentRepo,
  private val chapterRepo: ChapterRepo,
) {

  @IgnorableReturnValue
  suspend fun parseAndStore(file: CachedDocumentFile): BookContent {
    val chapterId = ChapterId(file.uri)
    val name = file.nameWithoutExtension()
    chapterRepo.put(
      Chapter(
        id = chapterId,
        name = name,
        duration = 0L,
        fileLastModified = Instant.ofEpochMilli(file.lastModified),
        fileSize = file.length,
        markData = emptyList(),
      ),
    )

    val id = BookId(file.uri)
    val content = contentRepo.getOrPut(id) {
      BookContent(
        id = id,
        isActive = true,
        addedAt = Instant.now(),
        author = null,
        lastPlayedAt = Instant.EPOCH,
        name = name,
        playbackSpeed = 1F,
        skipSilence = false,
        chapters = listOf(chapterId),
        positionInChapter = 0L,
        currentChapter = chapterId,
        cover = null,
        gain = 0F,
        genre = null,
        narrator = null,
        series = null,
        part = null,
        sourceType = BookSourceType.Epub,
      )
    }
    if (!content.isActive) {
      val reactivated = content.copy(isActive = true)
      contentRepo.put(reactivated)
      return reactivated
    }
    return content
  }
}
