package voice.core.tts

public object VoiceCatalog {
  public val entries: List<VoiceCatalogEntry> = listOf(
    VoiceCatalogEntry(
      voiceId = "en_US-amy-medium",
      name = "Amy (US English)",
      language = "en_US",
      downloadUrl =
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2",
      sizeBytes = 67_223_746L,
      sha256 = "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264",
    ),
    VoiceCatalogEntry(
      voiceId = "en_US-lessac-medium",
      name = "Lessac (US English)",
      language = "en_US",
      downloadUrl =
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
      sizeBytes = 67_230_653L,
      sha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
    ),
  )
}
