package voice.core.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.InstalledVoice
import voice.core.logging.api.Logger
import java.io.File

@Inject
@ContributesBinding(AppScope::class)
public class SherpaOnnxSynthesisEngine : SynthesisEngine {

  override suspend fun synthesize(
    text: String,
    voice: InstalledVoice,
    outputFile: File,
  ): SynthesisResult {
    return withContext(Dispatchers.IO) {
      val tts = try {
        OfflineTts(
          config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
              vits = OfflineTtsVitsModelConfig(
                model = voice.modelFile.absolutePath,
                tokens = voice.tokensFile.absolutePath,
                dataDir = voice.dataDir.absolutePath,
              ),
              numThreads = 2,
              provider = "cpu",
            ),
          ),
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Failed to load TTS model for voice=${voice.voiceId}")
        return@withContext SynthesisResult.Failure("failed to load model for voice=${voice.voiceId}: ${e.message}")
      }

      try {
        val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
        if (audio.save(outputFile.absolutePath)) {
          SynthesisResult.Success
        } else {
          SynthesisResult.Failure("failed to write WAV to $outputFile")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Synthesis failed for voice=${voice.voiceId}")
        SynthesisResult.Failure("synthesis error: ${e.message}")
      } finally {
        tts.release()
      }
    }
  }
}
