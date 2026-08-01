package voice.core.data.repo

import androidx.room.RoomDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence
import voice.core.data.repo.internals.dao.EpubChapterDao
import voice.core.data.repo.internals.dao.EpubSentenceDao
import voice.core.data.repo.internals.transaction

@ContributesBinding(AppScope::class)
public class EpubBookRepoImpl(
  private val chapterDao: EpubChapterDao,
  private val sentenceDao: EpubSentenceDao,
  private val appDb: RoomDatabase,
) : EpubBookRepo {

  override suspend fun replaceChapters(
    bookId: BookId,
    chapters: List<EpubChapter>,
    sentences: List<EpubSentence>,
  ) {
    appDb.transaction {
      chapterDao.deleteForBook(bookId)
      sentenceDao.deleteForBook(bookId)
      chapterDao.insertAll(chapters)
      sentenceDao.insertAll(sentences)
    }
  }

  override suspend fun chapters(bookId: BookId): List<EpubChapter> = chapterDao.chapters(bookId)

  override suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence> = sentenceDao.sentences(bookId, chapterIndex)
}
