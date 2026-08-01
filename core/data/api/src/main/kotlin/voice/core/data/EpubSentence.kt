package voice.core.data

import androidx.room.Entity

@Entity(tableName = "epubSentence", primaryKeys = ["bookId", "chapterIndex", "index"])
public data class EpubSentence(
  val bookId: BookId,
  val chapterIndex: Int,
  val index: Int,
  val text: String,
)
