package voice.core.data

import androidx.room.Entity

@Entity(tableName = "epubChapter", primaryKeys = ["bookId", "index"])
public data class EpubChapter(
  val bookId: BookId,
  val index: Int,
  val title: String,
)
