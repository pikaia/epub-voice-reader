package voice.features.bookOverview.overview

import voice.core.data.BookSourceType
import voice.features.bookOverview.book
import kotlin.test.Test
import kotlin.test.assertEquals

class BookOverviewItemViewStateTest {

  @Test
  fun `audiobook progress and remaining time are unchanged`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          currentChapter = book.chapters.first().id,
          positionInChapter = book.chapters.first().duration / 2,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0.25F, actual = state.progress)
    assertEquals(expected = "0:15", actual = state.remainingTime)
  }

  @Test
  fun `unparsed epub shows zero progress and zero remaining time`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 0,
          epubTotalCharacterCount = 0,
          currentEpubChapterIndex = 0,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0F, actual = state.progress)
    assertEquals(expected = "0:00", actual = state.remainingTime)
  }

  @Test
  fun `unstarted but parsed epub shows its full estimated duration`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 4,
          epubTotalCharacterCount = 15 * 60, // 60 seconds at 15 chars/sec
          currentEpubChapterIndex = 0,
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0F, actual = state.progress)
    assertEquals(expected = "1:00", actual = state.remainingTime)
  }

  @Test
  fun `epub progress advances by chapter fraction, not sentence position`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 4,
          epubTotalCharacterCount = 15 * 60,
          currentEpubChapterIndex = 1, // 1 of 4 chapters in -> 25%
          currentEpubSentenceIndex = 999, // irrelevant to the library-card estimate
        ),
      )
    }
    val state = book.toItemViewState()

    assertEquals(expected = 0.25F, actual = state.progress)
    assertEquals(expected = "0:45", actual = state.remainingTime)
  }
}
