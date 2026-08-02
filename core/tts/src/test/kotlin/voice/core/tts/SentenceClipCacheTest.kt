package voice.core.tts

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.InstalledVoice
import voice.core.data.repo.SentenceClipRepo
import voice.core.data.repo.SentenceClipRepoImpl
import voice.core.data.repo.VoiceRepoImpl
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SentenceClipCacheTest {

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
    .allowMainThreadQueries()
    .build()
  private val sentenceClipRepo = SentenceClipRepoImpl(dao = db.sentenceClipDao())
  private val voiceRepo = VoiceRepoImpl(dao = db.installedVoiceDao())
  private val synthesisEngine = FakeSynthesisEngine()
  private val cache = SentenceClipCache(
    context = context,
    synthesisEngine = synthesisEngine,
    sentenceClipRepo = sentenceClipRepo,
    voiceRepo = voiceRepo,
    maxCacheSizeBytes = 300L,
  )
  private val bookId = BookId("content://book1")

  private suspend fun installVoice(voiceId: String = "en_US-amy-medium") {
    voiceRepo.upsert(
      InstalledVoice(
        voiceId = voiceId,
        name = "Amy",
        language = "en_US",
        modelFile = File("voices/$voiceId/model.onnx"),
        tokensFile = File("voices/$voiceId/tokens.txt"),
        dataDir = File("voices/$voiceId/espeak-ng-data"),
        installedAt = Instant.parse("2026-01-01T00:00:00Z"),
        sizeBytes = 100L,
      ),
    )
  }

  @Test
  fun synthesizesOnCacheMissAndPersistsTheClip() = runTest {
    installVoice()

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Success>(result)
    assertTrue(result.file.exists())
    assertEquals(expected = listOf("Hello."), actual = synthesisEngine.requestedTexts)
  }

  @Test
  fun cacheHitReturnsExistingClipWithoutCallingTheEngineAgain() = runTest {
    installVoice()
    val first = cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0, text = "Hello.")
    assertIs<ClipResult.Success>(first)

    val second = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Success>(second)
    assertEquals(expected = 1, actual = synthesisEngine.requestedTexts.size)
  }

  @Test
  fun returnsFailureWhenTheVoiceIsNotInstalled() = runTest {
    val result = cache.getOrSynthesize(
      bookId,
      "missing-voice",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Failure>(result)
    assertEquals(expected = 0, actual = synthesisEngine.requestedTexts.size)
  }

  @Test
  fun returnsFailureAndDeletesTheOutputFileWhenSynthesisFails() = runTest {
    installVoice()
    synthesisEngine.result = SynthesisResult.Failure("boom")

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Failure>(result)
    assertEquals(expected = "boom", actual = result.reason)
  }

  @Test
  fun evictsLeastRecentlyAccessedClipsWhenOverTheSizeCap() = runTest {
    installVoice()
    synthesisEngine.writeBytes = ByteArray(150)
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0, text = "One."),
    )
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 1, text = "Two."),
    )

    // total is now 300 bytes, exactly at the cap; a third 150-byte clip forces eviction of sentence 0
    // (the least recently accessed clip)
    assertIs<ClipResult.Success>(
      cache.getOrSynthesize(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 2, text = "Three."),
    )

    assertEquals(
      expected = null,
      actual = sentenceClipRepo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 0),
    )
    assertTrue(sentenceClipRepo.get(bookId, "en_US-amy-medium", chapterIndex = 0, sentenceIndex = 2) != null)
  }

  @Test
  fun returnsFailureWhenASingleClipExceedsTheEntireCap() = runTest {
    installVoice()
    synthesisEngine.writeBytes = ByteArray(400)

    val result = cache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Too big.",
    )

    assertIs<ClipResult.Failure>(result)
  }

  @Test
  fun returnsFailureAndDeletesTheOutputFileWhenARoomCallThrows() = runTest {
    installVoice()
    val throwingRepo = mockk<SentenceClipRepo>()
    coEvery { throwingRepo.get(any(), any(), any(), any()) } returns null
    coEvery { throwingRepo.totalSizeBytes() } throws SQLiteFullException("disk full")
    val throwingCache = SentenceClipCache(
      context = context,
      synthesisEngine = synthesisEngine,
      sentenceClipRepo = throwingRepo,
      voiceRepo = voiceRepo,
      maxCacheSizeBytes = 300L,
    )
    val clipsDir = File(context.filesDir, "ttsClips")

    val result = throwingCache.getOrSynthesize(
      bookId,
      "en_US-amy-medium",
      chapterIndex = 0,
      sentenceIndex = 0,
      text = "Hello.",
    )

    assertIs<ClipResult.Failure>(result)
    // the clip was synthesized (writing a file into clipsDir) before totalSizeBytes() threw inside
    // makeRoomFor; the catch block in getOrSynthesize must delete that orphaned file.
    assertEquals(expected = emptyList(), actual = clipsDir.listFiles()?.toList().orEmpty())
  }
}
