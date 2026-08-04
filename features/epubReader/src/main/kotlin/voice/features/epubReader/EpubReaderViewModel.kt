package voice.features.epubReader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.repo.EpubBookRepo
import voice.core.logging.api.Logger
import voice.core.playback.playstate.PlayStateManager
import java.time.Instant

@AssistedInject
public class EpubReaderViewModel(
  private val epubBookOpener: EpubBookOpener,
  private val epubPlaylistController: EpubPlaylistController,
  private val epubBookRepo: EpubBookRepo,
  private val bookRepository: BookRepository,
  private val playStateManager: PlayStateManager,
  dispatcherProvider: DispatcherProvider,
  @Assisted
  private val bookId: BookId,
) {

  private val scope = MainScope(dispatcherProvider)

  private sealed interface OpenState {
    data object Loading : OpenState
    data class Failed(val message: String) : OpenState
    data class Ready(
      val bookTitle: String,
      val chapters: List<EpubReaderViewState.ChapterEntry>,
      val sentences: List<String>,
    ) : OpenState
  }

  private val openState = MutableStateFlow<OpenState>(OpenState.Loading)
  private var voiceId: String? = null
  private var bookTitle: String? = null
  private var activeChapterIndex = 0

  init {
    Logger.i("DEBUGTRACE EpubReaderViewModel.init for bookId=$bookId, instance=${System.identityHashCode(this)}")
    scope.launch {
      when (val result = epubBookOpener.open(bookId)) {
        is EpubBookOpener.OpenResult.Ready -> {
          voiceId = result.voiceId
          val content = bookRepository.get(bookId)?.content
          val resolvedBookTitle = content?.name.orEmpty()
          bookTitle = resolvedBookTitle
          val resumeChapterIndex = content?.currentEpubChapterIndex ?: 0
          val resumeSentenceIndex = content?.currentEpubSentenceIndex ?: 0
          activeChapterIndex = resumeChapterIndex
          val chapters = result.chapters.map { EpubReaderViewState.ChapterEntry(index = it.index, title = it.title) }
          val sentences = epubBookRepo.sentences(bookId, chapterIndex = resumeChapterIndex).map { it.text }
          openState.value = OpenState.Ready(resolvedBookTitle, chapters, sentences)
          epubPlaylistController.start(
            bookId = bookId,
            voiceId = result.voiceId,
            bookTitle = resolvedBookTitle,
            chapterIndex = resumeChapterIndex,
            sentenceIndex = resumeSentenceIndex,
          )
        }
        is EpubBookOpener.OpenResult.Malformed -> {
          openState.value = OpenState.Failed(result.reason)
        }
        EpubBookOpener.OpenResult.DrmProtected -> {
          openState.value = OpenState.Failed("This book is DRM-protected and can't be read.")
        }
        is EpubBookOpener.OpenResult.VoiceInstallFailed -> {
          openState.value = OpenState.Failed(result.reason)
        }
      }
    }
    scope.launch {
      epubPlaylistController.currentSentenceFlow().collect { position ->
        if (position != null) {
          val (chapterIndex, sentenceIndex) = position
          if (chapterIndex != activeChapterIndex) {
            activeChapterIndex = chapterIndex
            updateSentencesForChapter(chapterIndex)
          }
          bookRepository.updateBook(bookId) {
            it.copy(
              currentEpubChapterIndex = chapterIndex,
              currentEpubSentenceIndex = sentenceIndex,
              lastPlayedAt = Instant.now(),
            )
          }
        }
      }
    }
  }

  public fun playPause() {
    epubPlaylistController.togglePlayPause()
  }

  public fun onChapterSelect(chapterIndex: Int) {
    val voiceId = voiceId ?: return
    val bookTitle = bookTitle ?: return
    activeChapterIndex = chapterIndex
    scope.launch {
      updateSentencesForChapter(chapterIndex)
      epubPlaylistController.start(bookId, voiceId, bookTitle, chapterIndex, sentenceIndex = 0)
    }
  }

  private suspend fun updateSentencesForChapter(chapterIndex: Int) {
    val current = openState.value
    if (current is OpenState.Ready) {
      openState.value = current.copy(sentences = epubBookRepo.sentences(bookId, chapterIndex).map { it.text })
    }
  }

  @Composable
  public fun viewState(): EpubReaderViewState {
    return when (val state = openState.collectAsState().value) {
      OpenState.Loading -> EpubReaderViewState.Loading
      is OpenState.Failed -> EpubReaderViewState.Error(state.message)
      is OpenState.Ready -> {
        val currentSentence = epubPlaylistController.currentSentenceFlow().collectAsState().value
        val playing = playStateManager.playStateFlow.collectAsState().value == PlayStateManager.PlayState.Playing
        EpubReaderViewState.Content(
          bookTitle = state.bookTitle,
          sentences = state.sentences,
          activeSentenceIndex = currentSentence?.second ?: 0,
          failedSentenceIndices = emptySet(),
          isPlaying = playing,
          chapters = state.chapters,
        )
      }
    }
  }

  @AssistedFactory
  public interface Factory {
    public fun create(bookId: BookId): EpubReaderViewModel
  }
}
