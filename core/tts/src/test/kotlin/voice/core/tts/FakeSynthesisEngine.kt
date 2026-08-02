package voice.core.tts

import voice.core.data.InstalledVoice
import java.io.File

internal class FakeSynthesisEngine : SynthesisEngine {
  var result: SynthesisResult = SynthesisResult.Success
  var writeBytes: ByteArray? = byteArrayOf(1, 2, 3)
  val requestedTexts = mutableListOf<String>()

  override suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult {
    requestedTexts += text
    if (result is SynthesisResult.Success) {
      writeBytes?.let { outputFile.writeBytes(it) }
    }
    return result
  }
}
