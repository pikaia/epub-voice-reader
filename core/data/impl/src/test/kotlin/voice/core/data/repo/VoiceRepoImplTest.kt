package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.InstalledVoice
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class VoiceRepoImplTest {

  private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
    .allowMainThreadQueries()
    .build()

  private val repo = VoiceRepoImpl(dao = db.installedVoiceDao())

  private fun voice(voiceId: String) = InstalledVoice(
    voiceId = voiceId,
    name = "Amy",
    language = "en_US",
    modelFile = File("voices/$voiceId/model.onnx"),
    tokensFile = File("voices/$voiceId/tokens.txt"),
    dataDir = File("voices/$voiceId/espeak-ng-data"),
    installedAt = Instant.parse("2026-01-01T00:00:00Z"),
    sizeBytes = 1_000L,
  )

  @Test
  fun upsertThenInstalledVoicesReturnsIt() = runTest {
    repo.upsert(voice("en_US-amy-medium"))

    assertEquals(expected = listOf("en_US-amy-medium"), actual = repo.installedVoices().map { it.voiceId })
  }

  @Test
  fun installedVoiceReturnsNullWhenNotInstalled() = runTest {
    assertNull(repo.installedVoice("missing"))
  }

  @Test
  fun deleteRemovesTheVoice() = runTest {
    repo.upsert(voice("en_US-amy-medium"))

    repo.delete("en_US-amy-medium")

    assertEquals(expected = emptyList(), actual = repo.installedVoices())
  }

  @Test
  fun upsertReplacesAnExistingRow() = runTest {
    repo.upsert(voice("en_US-amy-medium"))
    repo.upsert(voice("en_US-amy-medium").copy(sizeBytes = 2_000L))

    assertEquals(expected = 2_000L, actual = repo.installedVoice("en_US-amy-medium")?.sizeBytes)
  }
}
