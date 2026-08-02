package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.SentenceClip
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class SentenceClipRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = SentenceClipRepoImpl(dao = db.sentenceClipDao())
  private val bookId = BookId("content://book1")

  private fun clip(
    sentenceIndex: Int,
    lastAccessedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes: Long = 100L,
  ) = SentenceClip(
    bookId = bookId,
    voiceId = "en_US-amy-medium",
    chapterIndex = 0,
    sentenceIndex = sentenceIndex,
    file = File("clips/$sentenceIndex.wav").absoluteFile,
    sizeBytes = sizeBytes,
    lastAccessedAt = lastAccessedAt,
  )

  @Test
  fun getReturnsNullOnMiss() = runTest {
    assertNull(repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0))
  }

  @Test
  fun upsertThenGetReturnsTheClip() = runTest {
    repo.upsert(clip(sentenceIndex = 0))

    val result = repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = File("clips/0.wav").absoluteFile, actual = result?.file)
  }

  @Test
  fun touchUpdatesLastAccessedAt() = runTest {
    repo.upsert(clip(sentenceIndex = 0, lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z")))

    repo.touch(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      at = Instant.parse("2026-02-01T00:00:00Z"),
    )

    val result = repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0)
    assertEquals(expected = Instant.parse("2026-02-01T00:00:00Z"), actual = result?.lastAccessedAt)
  }

  @Test
  fun totalSizeBytesSumsAllClips() = runTest {
    repo.upsert(clip(sentenceIndex = 0, sizeBytes = 100L))
    repo.upsert(clip(sentenceIndex = 1, sizeBytes = 200L))

    assertEquals(expected = 300L, actual = repo.totalSizeBytes())
  }

  @Test
  fun leastRecentlyAccessedOrdersByLastAccessedAtAscending() = runTest {
    repo.upsert(clip(sentenceIndex = 0, lastAccessedAt = Instant.parse("2026-01-03T00:00:00Z")))
    repo.upsert(clip(sentenceIndex = 1, lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z")))
    repo.upsert(clip(sentenceIndex = 2, lastAccessedAt = Instant.parse("2026-01-02T00:00:00Z")))

    val result = repo.leastRecentlyAccessed(limit = 2)

    assertEquals(expected = listOf(1, 2), actual = result.map { it.sentenceIndex })
  }

  @Test
  fun deleteRemovesTheClip() = runTest {
    repo.upsert(clip(sentenceIndex = 0))

    repo.delete(clip(sentenceIndex = 0))

    assertNull(repo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0))
  }
}
