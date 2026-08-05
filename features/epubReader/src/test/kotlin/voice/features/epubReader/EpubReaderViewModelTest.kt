package voice.features.epubReader

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.ChapterId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.BookRepository
import voice.core.data.repo.EpubBookRepo
import voice.core.playback.playstate.PlayStateManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

class EpubReaderViewModelTest {

  private val scope = TestScope()
  private val bookId = BookId("content://book1")
  private val currentSentenceFlow = MutableStateFlow<Pair<Int, Int>?>(null)
  private val epubPlaylistController = mockk<EpubPlaylistController> {
    coEvery { start(any(), any(), any(), any(), any()) } just Runs
    every { currentSentenceFlow() } returns currentSentenceFlow
    every { togglePlayPause() } just Runs
    every { pauseCurrentSession() } just Runs
  }
  private val epubBookRepo = mockk<EpubBookRepo> {
    coEvery { sentences(bookId, 0) } returns listOf(
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello."),
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "World."),
    )
    coEvery { sentences(bookId, 1) } returns listOf(
      EpubSentence(bookId = bookId, chapterIndex = 1, index = 0, text = "Second chapter."),
    )
  }
  private var bookFixture = book()
  private val bookRepository = mockk<BookRepository> {
    coEvery { get(bookId) } answers { bookFixture }
    coEvery { updateBook(bookId, any()) } answers {
      val update = secondArg<(BookContent) -> BookContent>()
      bookFixture = bookFixture.copy(content = update(bookFixture.content))
    }
  }
  private val playStateManager = PlayStateManager()

  private fun book(
    currentEpubChapterIndex: Int = 0,
    currentEpubSentenceIndex: Int = 0,
  ) = Book(
    content = BookContent(
      id = bookId,
      playbackSpeed = 1F,
      skipSilence = false,
      isActive = true,
      lastPlayedAt = Instant.EPOCH,
      author = null,
      name = "Test Book",
      addedAt = Instant.EPOCH,
      chapters = listOf(ChapterId(bookId.value)),
      currentChapter = ChapterId(bookId.value),
      positionInChapter = 0L,
      cover = null,
      gain = 0F,
      genre = null,
      narrator = null,
      series = null,
      part = null,
      sourceType = BookSourceType.Epub,
      voiceId = "voice-a",
      currentEpubChapterIndex = currentEpubChapterIndex,
      currentEpubSentenceIndex = currentEpubSentenceIndex,
    ),
    chapters = listOf(
      voice.core.data.Chapter(
        id = ChapterId(bookId.value),
        name = "Test Book",
        duration = 0L,
        fileLastModified = Instant.EPOCH,
        fileSize = 0L,
        markData = emptyList(),
      ),
    ),
  )

  private fun viewModel(
    openResult: EpubBookOpener.OpenResult = EpubBookOpener.OpenResult.Ready(
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Chapter One")),
      voiceId = "voice-a",
    ),
  ): EpubReaderViewModel {
    return EpubReaderViewModel(
      epubBookOpener = mockk { coEvery { open(bookId) } returns openResult },
      epubPlaylistController = epubPlaylistController,
      epubBookRepo = epubBookRepo,
      bookRepository = bookRepository,
      playStateManager = playStateManager,
      dispatcherProvider = DispatcherProvider(
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
        scope.backgroundScope.coroutineContext,
      ),
      bookId = bookId,
    )
  }

  @Test
  fun `starts loading then becomes content once the book opens`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      assertEquals(expected = EpubReaderViewState.Loading, actual = awaitItem())
      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      assertEquals(expected = "Test Book", actual = state.bookTitle)
      assertEquals(expected = listOf("Hello.", "World."), actual = state.sentences)
      assertEquals(expected = listOf(EpubReaderViewState.ChapterEntry(0, "Chapter One")), actual = state.chapters)
    }
    // Must pause whatever was previously playing (e.g. a different EPUB) immediately on opening
    // this screen — before opening/parsing this book, not just inside start() at the end of that
    // work — or the old session keeps audibly playing through the whole open() call.
    verify { epubPlaylistController.pauseCurrentSession() }
  }

  @Test
  fun `resumes from the persisted chapter and sentence position instead of restarting`() = scope.runTest {
    bookFixture = book(currentEpubChapterIndex = 1, currentEpubSentenceIndex = 0)
    val viewModel = viewModel(
      openResult = EpubBookOpener.OpenResult.Ready(
        chapters = listOf(
          EpubChapter(bookId = bookId, index = 0, title = "Chapter One"),
          EpubChapter(bookId = bookId, index = 1, title = "Chapter Two"),
        ),
        voiceId = "voice-a",
      ),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      assertEquals(expected = listOf("Second chapter."), actual = state.sentences)
    }
    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 1, sentenceIndex = 0) }
  }

  @Test
  fun `active sentence index follows the playlist controller's current sentence`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // initial Content, activeSentenceIndex = 0

      currentSentenceFlow.value = 0 to 1

      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      assertEquals(expected = 1, actual = state.activeSentenceIndex)
    }
  }

  @Test
  fun `chapter position and duration are estimated from the loaded sentence texts`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      val state = awaitItem()
      assertIs<EpubReaderViewState.Content>(state)
      // "Hello." (6 chars) + "World." (6 chars) = 12 chars -> 800ms at 15 chars/sec; position 0
      assertEquals(expected = 0.milliseconds, actual = state.chapterPosition)
      assertEquals(expected = 800.milliseconds, actual = state.chapterDuration)
    }
  }

  @Test
  fun `seekTo resumes playback at the sentence resolved from the seek position`() = scope.runTest {
    val viewModel = viewModel()
    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // Content
    }

    viewModel.seekTo(400.milliseconds) // into "World." (the second sentence, index 1)
    // seekTo dispatches epubPlaylistController.start asynchronously via scope.launch; runCurrent()
    // drives the test dispatcher's queue so that call has actually happened before we verify it.
    testScheduler.runCurrent()

    coVerify { epubPlaylistController.start(bookId, "voice-a", "Test Book", chapterIndex = 0, sentenceIndex = 1) }
  }

  @Test
  fun `malformed open result produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.Malformed("bad epub"))

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertEquals(expected = EpubReaderViewState.Error("bad epub"), actual = awaitItem())
    }
  }

  @Test
  fun `drm protected open result produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.DrmProtected)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertIs<EpubReaderViewState.Error>(awaitItem())
    }
  }

  @Test
  fun `voice install failure produces an error state`() = scope.runTest {
    val viewModel = viewModel(openResult = EpubBookOpener.OpenResult.VoiceInstallFailed("network error"))

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      assertEquals(expected = EpubReaderViewState.Error("network error"), actual = awaitItem())
    }
  }

  @Test
  fun `updates lastPlayedAt when the sentence position changes`() = scope.runTest {
    val viewModel = viewModel()

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // Loading
      awaitItem() // initial Content

      currentSentenceFlow.value = 0 to 1
      awaitItem()
    }

    assertEquals(expected = true, actual = bookFixture.content.lastPlayedAt.isAfter(Instant.EPOCH))
  }

  @Test
  fun `playPause toggles playback through the playlist controller`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.playPause()

    verify { epubPlaylistController.togglePlayPause() }
  }
}
