package voice.features.epubReader

import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
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

class EpubReaderViewModelTest {

  private val scope = TestScope()
  private val bookId = BookId("content://book1")
  private val currentSentenceFlow = MutableStateFlow<Pair<Int, Int>?>(null)
  private val epubPlaylistController = mockk<EpubPlaylistController> {
    coEvery { start(any(), any(), any(), any()) } just Runs
    every { currentSentenceFlow() } returns currentSentenceFlow
    every { togglePlayPause() } just Runs
  }
  private val epubBookRepo = mockk<EpubBookRepo> {
    coEvery { sentences(bookId, 0) } returns listOf(
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Hello."),
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "World."),
    )
  }
  private val bookRepository = mockk<BookRepository> {
    coEvery { get(bookId) } returns book()
    coEvery { updateBook(bookId, any()) } just Runs
  }
  private val playStateManager = PlayStateManager()

  private fun book() = Book(
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
  fun `playPause toggles playback through the playlist controller`() = scope.runTest {
    val viewModel = viewModel()

    viewModel.playPause()

    verify { epubPlaylistController.togglePlayPause() }
  }
}
