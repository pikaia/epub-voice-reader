package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.SentenceClip
import java.time.Instant

@Dao
public interface SentenceClipDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insert(clip: SentenceClip)

  @Query(
    "SELECT * FROM sentenceClip WHERE bookId = :bookId AND voiceId = :voiceId " +
      "AND chapterIndex = :chapterIndex AND sentenceIndex = :sentenceIndex",
  )
  public suspend fun get(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ): SentenceClip?

  @Query(
    "UPDATE sentenceClip SET lastAccessedAt = :lastAccessedAt WHERE bookId = :bookId AND voiceId = :voiceId " +
      "AND chapterIndex = :chapterIndex AND sentenceIndex = :sentenceIndex",
  )
  public suspend fun touch(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    lastAccessedAt: Instant,
  )

  @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM sentenceClip")
  public suspend fun totalSizeBytes(): Long

  @Query("SELECT * FROM sentenceClip ORDER BY lastAccessedAt ASC LIMIT :limit")
  public suspend fun leastRecentlyAccessed(limit: Int): List<SentenceClip>

  @Delete
  public suspend fun delete(clip: SentenceClip)
}
