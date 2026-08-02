package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.BookId
import voice.core.data.SentenceClip
import voice.core.data.repo.internals.dao.SentenceClipDao
import java.time.Instant

@ContributesBinding(AppScope::class)
public class SentenceClipRepoImpl(private val dao: SentenceClipDao) : SentenceClipRepo {

  override suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip? = dao.get(bookId, voiceId, chapterIndex, sentenceIndex)

  override suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    at: Instant,
  ) {
    dao.touch(bookId, voiceId, chapterIndex, sentenceIndex, at)
  }

  override suspend fun upsert(clip: SentenceClip) {
    dao.insert(clip)
  }

  override suspend fun delete(clip: SentenceClip) {
    dao.delete(clip)
  }

  override suspend fun totalSizeBytes(): Long = dao.totalSizeBytes()

  override suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip> = dao.leastRecentlyAccessed(limit)
}
