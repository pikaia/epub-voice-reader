package voice.features.bookOverview.overview

import androidx.compose.runtime.Immutable
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.estimatedEpubDurationMs
import voice.core.logging.api.Logger
import voice.core.ui.formatTime

@Immutable
data class BookOverviewItemViewState(
  val name: String,
  val author: String?,
  val cover: String?,
  val progress: Float,
  val id: BookId,
  val remainingTime: String,
)

internal fun Book.toItemViewState(): BookOverviewItemViewState {
  val (currentPosition, totalDuration) = when (content.sourceType) {
    BookSourceType.Audio -> position to duration
    BookSourceType.Epub -> epubPosition() to epubDuration()
  }
  return BookOverviewItemViewState(
    name = content.name,
    author = content.author,
    cover = content.coverUrl,
    id = id,
    progress = progressFraction(currentPosition, totalDuration),
    remainingTime = formatTime(totalDuration - currentPosition),
  )
}

private fun Book.epubDuration(): Long = estimatedEpubDurationMs(content.epubTotalCharacterCount)

// Chapter-granular, not character-precise: advances in per-chapter steps rather than
// continuously. Keeps this function pure (no repo access), which matters because it runs for
// every visible library card on every render — see the EPUB progress display design doc.
private fun Book.epubPosition(): Long {
  val chapterCount = content.epubChapterCount
  if (chapterCount == 0) return 0L
  // Matches BookOverviewCategory.kt's epubCategory() FINISHED threshold exactly (chapterIndex at
  // the last chapter, sentenceIndex within its last 2 sentences) — without this, a book the app
  // already categorizes as FINISHED would still show less than 100% here, since
  // currentEpubChapterIndex can never reach chapterCount (its max valid value is
  // chapterCount - 1), producing a visible contradiction between the "Completed" section header
  // and the percentage/remaining-time shown on the same card.
  val isFinished = content.currentEpubChapterIndex >= chapterCount - 1 &&
    content.currentEpubSentenceIndex >= content.epubLastChapterSentenceCount - 2
  if (isFinished) return epubDuration()
  val elapsedFraction = content.currentEpubChapterIndex.toFloat() / chapterCount.toFloat()
  return (epubDuration() * elapsedFraction).toLong()
}

private fun progressFraction(
  position: Long,
  duration: Long,
): Float {
  // An unparsed EPUB has duration == 0 (epubTotalCharacterCount defaults to 0 until first open —
  // see Task 2), which the old audiobook-only code never had to guard against since a real
  // audiobook's duration is never 0. Without this guard, position.toFloat() / duration.toFloat()
  // is 0F / 0F = NaN, and Float.coerceIn compares with < / >, both of which are false for NaN, so
  // NaN would pass through uncaught instead of coercing to 0F.
  if (duration == 0L) return 0F
  val progress = position.toFloat() / duration.toFloat()
  if (progress < 0F) {
    Logger.w("Couldn't determine progress for position=$position duration=$duration")
  }
  return progress.coerceIn(0F, 1F)
}
