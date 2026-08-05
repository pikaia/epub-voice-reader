package voice.features.epubReader

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.data.EpubSentence
import voice.core.data.repo.EpubBookRepo
import voice.core.logging.api.Logger
import voice.core.tts.ClipResult
import voice.core.tts.SentenceClipCache

@Inject
public class EpubPlaylistController(
  private val epubBookRepo: EpubBookRepo,
  private val sentenceClipCache: SentenceClipCache,
  private val playbackControl: EpubPlaybackControl,
  dispatcherProvider: DispatcherProvider,
) {

  private data class WindowEntry(
    val chapterIndex: Int,
    val sentenceIndex: Int,
  )

  private val scope = MainScope(dispatcherProvider)
  private var indexCollectionJob: Job? = null

  private var bookId: BookId? = null
  private var voiceId: String? = null
  private var bookTitle: String? = null
  private var window: List<WindowEntry> = emptyList()

  private val currentSentence = MutableStateFlow<Pair<Int, Int>?>(null)

  public fun currentSentenceFlow(): StateFlow<Pair<Int, Int>?> = currentSentence

  // Pauses whatever session is currently loaded (book or non-book — a different EPUB included).
  // Exposed separately from start() so callers can pause immediately on entering the reader
  // screen, before any of the slower work (opening/parsing the book, synthesizing its first
  // window) that start() itself does — otherwise a previously-playing session keeps audibly
  // playing through all of that work, since start()'s own pauseCurrentSession() call doesn't run
  // until the end of it.
  public fun pauseCurrentSession() {
    playbackControl.pauseCurrentSession()
  }

  public suspend fun start(
    bookId: BookId,
    voiceId: String,
    bookTitle: String,
    chapterIndex: Int,
    sentenceIndex: Int,
  ) {
    indexCollectionJob?.cancel()
    playbackControl.pauseCurrentSession()
    this.bookId = bookId
    this.voiceId = voiceId
    this.bookTitle = bookTitle
    val (mediaItems, entries) = loadWindow(bookId, voiceId, bookTitle, chapterIndex, sentenceIndex)
    window = entries
    currentSentence.value = entries.firstOrNull()?.let { it.chapterIndex to it.sentenceIndex }
    playbackControl.setPlaylist(mediaItems, startIndex = 0, startPositionMs = 0, autoPlay = true)
    indexCollectionJob = scope.launch {
      playbackControl.currentMediaItemIndexFlow().collect { index ->
        onCurrentMediaItemIndexChanged(index)
      }
    }
  }

  public fun togglePlayPause() {
    playbackControl.togglePlayPause()
  }

  public suspend fun onCurrentMediaItemIndexChanged(index: Int) {
    if (playbackControl.isCurrentSessionBook()) {
      // A book has taken over the player since this EPUB was last active (e.g. the user opened a
      // different audiobook). This index change belongs to that book's own queue, not this EPUB's
      // window — stop listening, or reload() would misfire off the book's item indices and
      // overwrite its queue with EPUB clips.
      indexCollectionJob?.cancel()
      return
    }
    val entry = window.getOrNull(index) ?: return
    currentSentence.value = entry.chapterIndex to entry.sentenceIndex
    if (index >= window.size - RELOAD_MARGIN) {
      reload(entry)
    }
  }

  private suspend fun reload(from: WindowEntry) {
    val bookId = bookId ?: return
    val voiceId = voiceId ?: return
    val bookTitle = bookTitle ?: return
    val next = nextPosition(from) ?: return
    val (mediaItems, entries) = loadWindow(bookId, voiceId, bookTitle, next.chapterIndex, next.sentenceIndex)
    if (entries.isEmpty()) return
    window = entries
    playbackControl.setPlaylist(mediaItems, startIndex = 0, startPositionMs = 0, autoPlay = false)
  }

  private fun nextPosition(entry: WindowEntry): WindowEntry? {
    return WindowEntry(entry.chapterIndex, entry.sentenceIndex + 1)
  }

  private suspend fun loadWindow(
    bookId: BookId,
    voiceId: String,
    bookTitle: String,
    startChapterIndex: Int,
    startSentenceIndex: Int,
  ): Pair<List<MediaItem>, List<WindowEntry>> {
    val mediaItems = mutableListOf<MediaItem>()
    val entries = mutableListOf<WindowEntry>()
    var chapterIndex = startChapterIndex
    var sentenceIndex = startSentenceIndex
    var sentences = epubBookRepo.sentences(bookId, chapterIndex)
    val mediaMetadata = MediaMetadata.Builder().setTitle(bookTitle).build()

    while (entries.size < WINDOW_SIZE) {
      if (sentenceIndex >= sentences.size) {
        chapterIndex += 1
        sentenceIndex = 0
        sentences = epubBookRepo.sentences(bookId, chapterIndex)
        if (sentences.isEmpty()) break
        continue
      }
      val sentence: EpubSentence = sentences[sentenceIndex]
      when (val result = sentenceClipCache.getOrSynthesize(bookId, voiceId, chapterIndex, sentenceIndex, sentence.text)) {
        is ClipResult.Success -> {
          mediaItems += MediaItem.Builder()
            .setUri(result.file.toURI().toString())
            .setMediaMetadata(mediaMetadata)
            .build()
          entries += WindowEntry(chapterIndex, sentenceIndex)
        }
        is ClipResult.Failure -> {
          Logger.w("Skipping sentence chapter=$chapterIndex sentence=$sentenceIndex: ${result.reason}")
        }
      }
      sentenceIndex += 1
    }

    return mediaItems to entries
  }

  private companion object {
    const val WINDOW_SIZE = 30
    const val RELOAD_MARGIN = 5
  }
}
