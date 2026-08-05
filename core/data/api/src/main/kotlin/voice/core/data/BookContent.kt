package voice.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import java.io.File
import java.time.Instant

@Entity(tableName = "content2")
public data class BookContent(
  @PrimaryKey
  val id: BookId,
  val playbackSpeed: Float,
  val skipSilence: Boolean,
  val isActive: Boolean,
  val lastPlayedAt: Instant,
  val author: String?,
  val name: String,
  val addedAt: Instant,
  val chapters: List<ChapterId>,
  val currentChapter: ChapterId,
  val positionInChapter: Long,
  val cover: File?,
  @ColumnInfo(defaultValue = "0")
  val gain: Float,
  val genre: String?,
  val narrator: String?,
  val series: String?,
  val part: String?,
  @ColumnInfo(defaultValue = "'Audio'")
  val sourceType: BookSourceType = BookSourceType.Audio,
  val voiceId: String? = null,
  @ColumnInfo(defaultValue = "0")
  val currentEpubChapterIndex: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val currentEpubSentenceIndex: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubChapterCount: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubLastChapterSentenceCount: Int = 0,
  @ColumnInfo(defaultValue = "0")
  val epubTotalCharacterCount: Int = 0,
) {

  @Ignore
  val currentChapterIndex: Int = chapters.indexOf(currentChapter)

  val coverUrl: String? get() = cover?.toURI()?.toString()

  init {
    require(currentChapter in chapters && positionInChapter >= 0) {
      "invalid data in $this"
    }
    require(currentEpubChapterIndex >= 0 && currentEpubSentenceIndex >= 0) {
      "invalid epub position in $this"
    }
    require(epubChapterCount >= 0 && epubLastChapterSentenceCount >= 0 && epubTotalCharacterCount >= 0) {
      "invalid epub progress cache in $this"
    }
  }
}
