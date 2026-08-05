package voice.features.epubReader

import voice.core.data.estimatedEpubCharacterCount
import voice.core.data.estimatedEpubDurationMs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class ChapterProgress(
  val position: Duration,
  val duration: Duration,
)

/**
 * Estimated position/duration within the current chapter, from the character lengths of its
 * already-loaded sentence texts. Sentence-precise (unlike the library card's chapter-granular
 * estimate), since only one chapter's worth of text is ever in memory here.
 */
internal fun chapterProgress(
  sentences: List<String>,
  activeSentenceIndex: Int,
): ChapterProgress {
  val totalChars = sentences.sumOf { it.length }
  val elapsedChars = sentences.take(activeSentenceIndex).sumOf { it.length }
  return ChapterProgress(
    position = estimatedEpubDurationMs(elapsedChars).milliseconds,
    duration = estimatedEpubDurationMs(totalChars).milliseconds,
  )
}

/**
 * Converts a scrubber seek target (an absolute position within the current chapter) into the
 * sentence index to resume playback from. A target landing exactly on a sentence boundary
 * resolves to the start of the following sentence.
 */
internal fun sentenceIndexForSeekPosition(
  sentences: List<String>,
  targetPosition: Duration,
): Int {
  if (sentences.isEmpty()) return 0
  val targetChars = estimatedEpubCharacterCount(targetPosition.inWholeMilliseconds)
  var cumulative = 0
  sentences.forEachIndexed { index, sentence ->
    cumulative += sentence.length
    if (cumulative > targetChars) return index
  }
  return sentences.lastIndex
}
