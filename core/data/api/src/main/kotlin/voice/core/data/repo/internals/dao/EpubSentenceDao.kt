package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.EpubSentence

@Dao
public interface EpubSentenceDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insertAll(sentences: List<EpubSentence>)

  @Query("SELECT * FROM epubSentence WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY `index`")
  public suspend fun sentences(
    bookId: BookId,
    chapterIndex: Int,
  ): List<EpubSentence>

  @Query("DELETE FROM epubSentence WHERE bookId = :bookId")
  public suspend fun deleteForBook(bookId: BookId)
}
