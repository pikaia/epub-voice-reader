package voice.core.tts

public data class VoiceCatalogEntry(
  val voiceId: String,
  val name: String,
  val language: String,
  val downloadUrl: String,
  val sizeBytes: Long,
  val sha256: String,
)
