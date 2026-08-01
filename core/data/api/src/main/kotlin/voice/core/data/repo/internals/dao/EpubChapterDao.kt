package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.BookId
import voice.core.data.EpubChapter

@Dao
public interface EpubChapterDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insertAll(chapters: List<EpubChapter>)

  @Query("SELECT * FROM epubChapter WHERE bookId = :bookId ORDER BY `index`")
  public suspend fun chapters(bookId: BookId): List<EpubChapter>

  @Query("DELETE FROM epubChapter WHERE bookId = :bookId")
  public suspend fun deleteForBook(bookId: BookId)
}
