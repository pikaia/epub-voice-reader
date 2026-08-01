package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.internals.AppDb
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class EpubBookRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = EpubBookRepoImpl(
    chapterDao = db.epubChapterDao(),
    sentenceDao = db.epubSentenceDao(),
    appDb = db,
  )

  @Test
  fun replaceChaptersStoresChaptersAndSentences() = runTest {
    val bookId = BookId("content://book1")
    val chapters = listOf(
      EpubChapter(bookId = bookId, index = 0, title = "Chapter One"),
      EpubChapter(bookId = bookId, index = 1, title = "Chapter Two"),
    )
    val sentences = listOf(
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "First sentence."),
      EpubSentence(bookId = bookId, chapterIndex = 0, index = 1, text = "Second sentence."),
      EpubSentence(bookId = bookId, chapterIndex = 1, index = 0, text = "Third sentence."),
    )

    repo.replaceChapters(bookId, chapters, sentences)

    assertEquals(expected = chapters, actual = repo.chapters(bookId))
    assertEquals(
      expected = listOf("First sentence.", "Second sentence."),
      actual = repo.sentences(bookId, chapterIndex = 0).map { it.text },
    )
  }

  @Test
  fun replaceChaptersReplacesPreviousData() = runTest {
    val bookId = BookId("content://book1")
    repo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "Old")),
      sentences = listOf(EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "Old sentence.")),
    )

    repo.replaceChapters(
      bookId,
      chapters = listOf(EpubChapter(bookId = bookId, index = 0, title = "New")),
      sentences = listOf(EpubSentence(bookId = bookId, chapterIndex = 0, index = 0, text = "New sentence.")),
    )

    assertEquals(expected = listOf("New"), actual = repo.chapters(bookId).map { it.title })
    assertEquals(expected = listOf("New sentence."), actual = repo.sentences(bookId, 0).map { it.text })
  }
}
