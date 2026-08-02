package voice.core.data.repo

import voice.core.data.BookId
import voice.core.data.SentenceClip
import java.time.Instant

public interface SentenceClipRepo {

  public suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip?

  public suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    at: Instant,
  )

  public suspend fun upsert(clip: SentenceClip)

  public suspend fun delete(clip: SentenceClip)

  public suspend fun totalSizeBytes(): Long

  public suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip>
}
