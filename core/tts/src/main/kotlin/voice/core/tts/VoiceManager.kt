package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import voice.core.data.InstalledVoice
import voice.core.data.repo.VoiceRepo
import voice.core.logging.api.Logger
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

public data class AvailableVoice(
  val entry: VoiceCatalogEntry,
  val installed: Boolean,
)

public sealed interface InstallResult {
  public data object Success : InstallResult
  public data class Failure(val reason: String) : InstallResult
}

@Inject
public class VoiceManager
internal constructor(
  private val context: Context,
  private val downloader: TtsDownloader,
  private val voiceRepo: VoiceRepo,
  private val catalog: List<VoiceCatalogEntry>,
) {

  public suspend fun availableVoices(): List<AvailableVoice> {
    val installedIds = voiceRepo.installedVoices().map { it.voiceId }.toSet()
    return catalog.map { entry -> AvailableVoice(entry, installed = entry.voiceId in installedIds) }
  }

  public suspend fun install(voiceId: String): InstallResult {
    val entry = catalog.find { it.voiceId == voiceId }
      ?: return InstallResult.Failure("unknown voice: $voiceId")

    return withContext(Dispatchers.IO) {
      val downloaded = downloader.download(entry.downloadUrl)
        ?: return@withContext InstallResult.Failure("download failed")
      try {
        val actualChecksum = sha256(downloaded)
        if (!actualChecksum.equals(entry.sha256, ignoreCase = true)) {
          Logger.w("Checksum mismatch for voice=$voiceId")
          return@withContext InstallResult.Failure("checksum mismatch")
        }

        val voiceDir = File(context.filesDir, "ttsVoices/$voiceId")
        voiceDir.deleteRecursively()
        voiceDir.mkdirs()
        extractTarBz2(downloaded, voiceDir)

        val modelFile = File(voiceDir, "$voiceId.onnx")
        val tokensFile = File(voiceDir, "tokens.txt")
        val dataDir = File(voiceDir, "espeak-ng-data")
        if (!modelFile.isFile || !tokensFile.isFile || !dataDir.isDirectory) {
          voiceDir.deleteRecursively()
          return@withContext InstallResult.Failure("archive is missing expected files")
        }

        voiceRepo.upsert(
          InstalledVoice(
            voiceId = voiceId,
            name = entry.name,
            language = entry.language,
            modelFile = modelFile,
            tokensFile = tokensFile,
            dataDir = dataDir,
            installedAt = Instant.now(),
            sizeBytes = voiceDir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
          ),
        )
        InstallResult.Success
      } finally {
        downloaded.delete()
      }
    }
  }

  public suspend fun uninstall(voiceId: String) {
    withContext(Dispatchers.IO) {
      File(context.filesDir, "ttsVoices/$voiceId").deleteRecursively()
    }
    voiceRepo.delete(voiceId)
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream(), digest).use { stream ->
      val buffer = ByteArray(8192)
      while (stream.read(buffer) != -1) {
        // reading through the DigestInputStream drives digest.update()
      }
    }
    return HexFormat.of().formatHex(digest.digest())
  }

  private fun extractTarBz2(
    archive: File,
    destDir: File,
  ) {
    TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream())).use { tar ->
      while (true) {
        val entry = tar.nextEntry ?: break
        val relativePath = entry.name.substringAfter('/', missingDelimiterValue = "")
        if (relativePath.isEmpty()) continue
        val outFile = File(destDir, relativePath)
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          outFile.outputStream().use { output -> tar.copyTo(output) }
        }
      }
    }
  }
}
