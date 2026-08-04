package voice.features.bookOverview

import voice.core.data.BookSourceType
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.category
import kotlin.test.Test
import kotlin.test.assertEquals

class BookOverviewCategoryTest {

  @Test
  fun finished() {
    val book = book().let { book ->
      val lastChapter = book.chapters.last()
      book.copy(
        content = book.content.copy(
          currentChapter = lastChapter.id,
          positionInChapter = lastChapter.duration,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.FINISHED, actual = book.category)
  }

  @Test
  fun notStarted() {
    val book = book().let { book ->
      val firstChapter = book.chapters.first()
      book.copy(
        content = book.content.copy(
          currentChapter = firstChapter.id,
          positionInChapter = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun current() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          currentChapter = book.chapters.last().id,
          positionInChapter = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }

  @Test
  fun `epub not started when unparsed`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 0,
          currentEpubChapterIndex = 0,
          currentEpubSentenceIndex = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun `epub not started at the beginning`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 0,
          currentEpubSentenceIndex = 0,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.NOT_STARTED, actual = book.category)
  }

  @Test
  fun `epub current mid-book`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 1,
          currentEpubSentenceIndex = 5,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }

  @Test
  fun `epub finished at the last sentence`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 2,
          currentEpubSentenceIndex = 9,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.FINISHED, actual = book.category)
  }

  @Test
  fun `epub current near the end but outside the finished buffer`() {
    val book = book().let { book ->
      book.copy(
        content = book.content.copy(
          sourceType = BookSourceType.Epub,
          epubChapterCount = 3,
          epubLastChapterSentenceCount = 10,
          currentEpubChapterIndex = 2,
          currentEpubSentenceIndex = 5,
        ),
      )
    }
    assertEquals(expected = BookOverviewCategory.CURRENT, actual = book.category)
  }
}
