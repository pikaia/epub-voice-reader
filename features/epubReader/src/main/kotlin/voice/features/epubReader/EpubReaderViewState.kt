package voice.features.epubReader

import kotlin.time.Duration

public sealed interface EpubReaderViewState {
  public data object Loading : EpubReaderViewState

  public data class Error(val message: String) : EpubReaderViewState

  public data class Content(
    val bookTitle: String,
    val sentences: List<String>,
    val activeSentenceIndex: Int,
    val failedSentenceIndices: Set<Int>,
    val isPlaying: Boolean,
    val chapters: List<ChapterEntry>,
    val chapterPosition: Duration,
    val chapterDuration: Duration,
  ) : EpubReaderViewState

  public data class ChapterEntry(
    val index: Int,
    val title: String,
  )
}
