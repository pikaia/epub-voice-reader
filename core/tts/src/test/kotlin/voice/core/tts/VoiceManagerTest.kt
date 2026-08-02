package voice.core.tts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.repo.VoiceRepoImpl
import voice.core.data.repo.internals.AppDb
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class VoiceManagerTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val db = Room.inMemoryDatabaseBuilder(context, AppDb::class.java)
    .allowMainThreadQueries()
    .build()
  private val voiceRepo = VoiceRepoImpl(dao = db.installedVoiceDao())
  private val downloader = mockk<TtsDownloader>()

  private fun buildVoiceArchive(): File {
    val archive = File(testFolder.newFolder(), "voice.tar.bz2")
    BZip2CompressorOutputStream(archive.outputStream()).use { bzip2 ->
      TarArchiveOutputStream(bzip2).use { tar ->
        fun addFile(
          name: String,
          content: String,
        ) {
          val entry = TarArchiveEntry("vits-piper-test-voice/$name")
          entry.size = content.toByteArray().size.toLong()
          tar.putArchiveEntry(entry)
          tar.write(content.toByteArray())
          tar.closeArchiveEntry()
        }
        addFile("test-voice.onnx", "fake model bytes")
        addFile("tokens.txt", "fake tokens")
        addFile("espeak-ng-data/en_dict", "fake dict")
      }
    }
    return archive
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(file.readBytes())
    return HexFormat.of().formatHex(digest.digest())
  }

  private fun catalogEntry(archive: File) = VoiceCatalogEntry(
    voiceId = "test-voice",
    name = "Test Voice",
    language = "en_US",
    downloadUrl = "https://example.test/test-voice.tar.bz2",
    sizeBytes = archive.length(),
    sha256 = sha256(archive),
  )

  @Test
  fun availableVoicesReportsInstalledStatus() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val before = manager.availableVoices()
    assertEquals(expected = listOf(false), actual = before.map { it.installed })

    assertIs<InstallResult.Success>(manager.install(entry.voiceId))

    val after = manager.availableVoices()
    assertEquals(expected = listOf(true), actual = after.map { it.installed })
  }

  @Test
  fun installExtractsArchiveAndRecordsInstalledVoice() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Success>(result)
    val installed = voiceRepo.installedVoice(entry.voiceId)
    assertEquals(expected = "test-voice.onnx", actual = installed?.modelFile?.name)
    assertEquals(expected = "fake model bytes", actual = installed?.modelFile?.readText())
    assertEquals(expected = "tokens.txt", actual = installed?.tokensFile?.name)
    assertEquals(expected = "espeak-ng-data", actual = installed?.dataDir?.name)
  }

  @Test
  fun installFailsOnChecksumMismatch() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive).copy(sha256 = "0".repeat(64))
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Failure>(result)
    assertNull(voiceRepo.installedVoice(entry.voiceId))
  }

  @Test
  fun installFailsGracefullyWhenArchiveIsCorrupt() = runTest {
    val corruptArchive = File(testFolder.newFolder(), "corrupt.tar.bz2")
    corruptArchive.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
    val entry = catalogEntry(corruptArchive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns corruptArchive

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Failure>(result)
    assertNull(voiceRepo.installedVoice(entry.voiceId))
    val voiceDir = File(context.filesDir, "ttsVoices/${entry.voiceId}")
    assertEquals(expected = false, actual = voiceDir.exists())
  }

  @Test
  fun installFailsWhenDownloadFails() = runTest {
    val entry = catalogEntry(buildVoiceArchive())
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns null

    val result = manager.install(entry.voiceId)

    assertIs<InstallResult.Failure>(result)
  }

  @Test
  fun uninstallDeletesFilesAndRow() = runTest {
    val archive = buildVoiceArchive()
    val entry = catalogEntry(archive)
    val manager = VoiceManager(context, downloader, voiceRepo, listOf(entry))
    coEvery { downloader.download(entry.downloadUrl) } returns archive
    assertIs<InstallResult.Success>(manager.install(entry.voiceId))
    val installedDir = voiceRepo.installedVoice(entry.voiceId)?.modelFile?.parentFile!!

    manager.uninstall(entry.voiceId)

    assertNull(voiceRepo.installedVoice(entry.voiceId))
    assertEquals(expected = false, actual = installedDir.exists())
  }
}
