package voice.core.data.repo

import voice.core.data.BookId
import voice.core.data.EpubChapter
import voice.core.data.EpubSentence

public interface EpubBookRepo {

  public suspend fun replaceChapters(
    bookId: BookId,
    chapters: List<EpubChapter>,
    sentences: List<EpubSentence>,
  )

  public suspend fun chapters(bookId: BookId): List<EpubChapter>

  public suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence>
}
