package voice.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.File
import java.time.Instant

@Entity(tableName = "installedVoice")
public data class InstalledVoice(
  @PrimaryKey
  val voiceId: String,
  val name: String,
  val language: String,
  val modelFile: File,
  val tokensFile: File,
  val dataDir: File,
  val installedAt: Instant,
  val sizeBytes: Long,
)
