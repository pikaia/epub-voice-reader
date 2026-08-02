package voice.core.tts

import kotlinx.coroutines.test.runTest
import voice.core.data.InstalledVoice
import java.io.File
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FakeSynthesisEngineTest {

  private val voice = InstalledVoice(
    voiceId = "en_US-amy-medium",
    name = "Amy",
    language = "en_US",
    modelFile = File("voices/model.onnx"),
    tokensFile = File("voices/tokens.txt"),
    dataDir = File("voices/espeak-ng-data"),
    installedAt = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes = 100L,
  )
  private val outputFile = File.createTempFile("fake-synthesis-engine-test", ".wav")

  @AfterTest
  fun cleanup() {
    outputFile.delete()
  }

  @Test
  fun writesConfiguredBytesAndReturnsSuccessByDefault() = runTest {
    val engine = FakeSynthesisEngine()

    val result = engine.synthesize("Hello.", voice, outputFile)

    assertEquals(expected = SynthesisResult.Success, actual = result)
    assertContentEquals(expected = byteArrayOf(1, 2, 3), actual = outputFile.readBytes())
    assertEquals(expected = listOf("Hello."), actual = engine.requestedTexts)
  }

  @Test
  fun returnsConfiguredFailureAndDoesNotWriteAFile() = runTest {
    val engine = FakeSynthesisEngine().apply {
      result = SynthesisResult.Failure("boom")
    }

    val result = engine.synthesize("Hello.", voice, outputFile)

    assertEquals(expected = SynthesisResult.Failure("boom"), actual = result)
    assertEquals(expected = 0L, actual = outputFile.length())
  }
}
