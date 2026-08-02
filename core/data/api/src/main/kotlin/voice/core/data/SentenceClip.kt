package voice.core.data

import androidx.room.Entity
import java.io.File
import java.time.Instant

@Entity(tableName = "sentenceClip", primaryKeys = ["bookId", "voiceId", "chapterIndex", "sentenceIndex"])
public data class SentenceClip(
  val bookId: BookId,
  val voiceId: String,
  val chapterIndex: Int,
  val sentenceIndex: Int,
  val file: File,
  val sizeBytes: Long,
  val lastAccessedAt: Instant,
)
