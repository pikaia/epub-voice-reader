package voice.core.tts

import voice.core.data.InstalledVoice
import java.io.File

public interface SynthesisEngine {

  public suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult
}

public sealed interface SynthesisResult {
  public data object Success : SynthesisResult
  public data class Failure(val reason: String) : SynthesisResult
}
