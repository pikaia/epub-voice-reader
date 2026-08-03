package voice.features.epubReader

import androidx.media3.common.MediaItem
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.tts.ClipResult
import voice.core.tts.SentenceClipCache
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class EpubPlaylistControllerTest {

  private val scope = TestScope()
  private val bookId = BookId("content://book1")
  private val voiceId = "en_US-amy-medium"
  private val bookTitle = "Test Book"
  private val epubBookRepo = FakeEpubBookRepo()
  private val failingSentences = mutableSetOf<Pair<Int, Int>>()
  private val sentenceClipCache = mockk<SentenceClipCache> {
    coEvery { getOrSynthesize(any(), any(), any(), any(), any()) } answers {
      val chapterIndex = thirdArg<Int>()
      val sentenceIndex = invocation.args[3] as Int
      if (chapterIndex to sentenceIndex in failingSentences) {
        ClipResult.Failure("synthesis failed")
      } else {
        ClipResult.Success(File("clips/$chapterIndex-$sentenceIndex.wav"))
      }
    }
  }

  private val playbackControl = FakePlaybackControl()
  private val controller = EpubPlaylistController(
    epubBookRepo = epubBookRepo,
    sentenceClipCache = sentenceClipCache,
    playbackControl = playbackControl,
    dispatcherProvider = DispatcherProvider(
      scope.backgroundScope.coroutineContext,
      scope.backgroundScope.coroutineContext,
      scope.backgroundScope.coroutineContext,
    ),
  )

  @Test
  fun `start synthesizes the initial window and sets it as the playlist`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 5)

    controller.start(bookId, voiceId, bookTitle, chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = 5, actual = playbackControl.lastPlaylist?.size)
    assertEquals(expected = 0, actual = playbackControl.lastStartIndex)
  }

  @Test
  fun `currentSentenceFlow maps the media item index back to chapter and sentence`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 5)
    controller.start(bookId, voiceId, bookTitle, chapterIndex = 0, sentenceIndex = 0)

    playbackControl.currentIndex.value = 2
    runCurrent()

    assertEquals(expected = 0 to 2, actual = controller.currentSentenceFlow().value)
  }

  @Test
  fun `skips a sentence that fails to synthesize without breaking the window`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 3)
    failingSentences += 0 to 1

    controller.start(bookId, voiceId, bookTitle, chapterIndex = 0, sentenceIndex = 0)

    assertEquals(expected = 2, actual = playbackControl.lastPlaylist?.size)
  }

  @Test
  fun `reload is not triggered before reaching the window's reload margin`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 30)
    controller.start(bookId, voiceId, bookTitle, chapterIndex = 0, sentenceIndex = 0)
    val setPlaylistCallsBefore = playbackControl.setPlaylistCallCount

    playbackControl.currentIndex.value = 10 // well before index 25 (window size 30 - reload margin 5)
    runCurrent()

    assertEquals(expected = setPlaylistCallsBefore, actual = playbackControl.setPlaylistCallCount)
  }

  @Test
  fun `reloads the window once playback nears its end`() = scope.runTest {
    epubBookRepo.seed(chapterIndex = 0, sentenceCount = 30)
    epubBookRepo.seed(chapterIndex = 1, sentenceCount = 10)
    controller.start(bookId, voiceId, bookTitle, chapterIndex = 0, sentenceIndex = 0)
    val setPlaylistCallsBefore = playbackControl.setPlaylistCallCount

    playbackControl.currentIndex.value = 26 // >= 25 (window size 30 - reload margin 5), triggers a reload
    runCurrent()

    assertEquals(expected = setPlaylistCallsBefore + 1, actual = playbackControl.setPlaylistCallCount)
  }

  private class FakeEpubBookRepo : EpubBookRepo {
    private val bySentenceIndex = mutableMapOf<Int, List<EpubSentence>>()

    fun seed(
      chapterIndex: Int,
      sentenceCount: Int,
    ) {
      bySentenceIndex[chapterIndex] = (0 until sentenceCount).map { index ->
        EpubSentence(bookId = BookId("content://book1"), chapterIndex = chapterIndex, index = index, text = "Sentence $index.")
      }
    }

    override suspend fun replaceChapters(
      bookId: BookId,
      chapters: List<voice.core.data.EpubChapter>,
      sentences: List<EpubSentence>,
    ) = error("not used in this test")

    override suspend fun chapters(bookId: BookId) = error("not used in this test")

    override suspend fun sentences(
      bookId: BookId,
      chapterIndex: Int,
    ): List<EpubSentence> = bySentenceIndex[chapterIndex].orEmpty()
  }

  private class FakePlaybackControl : EpubPlaybackControl {
    var lastPlaylist: List<MediaItem>? = null
    var lastStartIndex: Int? = null
    var setPlaylistCallCount = 0
    var togglePlayPauseCallCount = 0
    val currentIndex = MutableStateFlow(0)

    override fun setPlaylist(
      mediaItems: List<MediaItem>,
      startIndex: Int,
      startPositionMs: Long,
    ) {
      lastPlaylist = mediaItems
      lastStartIndex = startIndex
      setPlaylistCallCount++
    }

    override fun currentMediaItemIndexFlow() = currentIndex

    override fun togglePlayPause() {
      togglePlayPauseCallCount++
    }
  }
}
