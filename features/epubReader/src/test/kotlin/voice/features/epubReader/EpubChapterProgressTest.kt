package voice.features.epubReader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EpubChapterProgressTest {

  // Each sentence is exactly 15 characters -> exactly 1 second at 15 chars/sec. Chosen so every
  // char<->ms conversion in these tests is exact, with no integer-division truncation to reason
  // about (estimatedEpubDurationMs/estimatedEpubCharacterCount both truncate, so an arbitrary
  // sentence length would make "exactly on a boundary" tests fragile/ambiguous).
  private val sentences = listOf(
    "a".repeat(15),
    "b".repeat(15),
    "c".repeat(15),
  )
  // total 45 chars -> 3 seconds at 15 chars/sec

  @Test
  fun `chapterProgress at the first sentence has zero position`() {
    val progress = chapterProgress(sentences, activeSentenceIndex = 0)

    assertEquals(expected = 0.milliseconds, actual = progress.position)
    assertEquals(expected = 3.seconds, actual = progress.duration)
  }

  @Test
  fun `chapterProgress position is the estimated duration of sentences before the active one`() {
    val progress = chapterProgress(sentences, activeSentenceIndex = 2)

    assertEquals(expected = 2.seconds, actual = progress.position) // 2 full 1-second sentences before index 2
    assertEquals(expected = 3.seconds, actual = progress.duration)
  }

  @Test
  fun `chapterProgress for an empty chapter is zero duration and zero position`() {
    val progress = chapterProgress(emptyList(), activeSentenceIndex = 0)

    assertEquals(expected = 0.milliseconds, actual = progress.position)
    assertEquals(expected = 0.milliseconds, actual = progress.duration)
  }

  @Test
  fun `seeking to zero resolves to the first sentence`() {
    assertEquals(expected = 0, actual = sentenceIndexForSeekPosition(sentences, 0.milliseconds))
  }

  @Test
  fun `seeking to the full duration resolves to the last sentence`() {
    assertEquals(expected = 2, actual = sentenceIndexForSeekPosition(sentences, 3.seconds))
  }

  @Test
  fun `seeking into the middle of the second sentence resolves to that sentence`() {
    assertEquals(expected = 1, actual = sentenceIndexForSeekPosition(sentences, 1_500.milliseconds))
  }

  @Test
  fun `seeking exactly onto a sentence boundary resolves to the next sentence`() {
    // exactly the end of sentence 0 / start of sentence 1
    assertEquals(expected = 1, actual = sentenceIndexForSeekPosition(sentences, 1.seconds))
  }

  @Test
  fun `seeking an empty chapter resolves to the first index`() {
    assertEquals(expected = 0, actual = sentenceIndexForSeekPosition(emptyList(), 5.seconds))
  }
}
