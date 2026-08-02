package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.InstalledVoice
import voice.core.data.SentenceClip
import voice.core.data.repo.SentenceClipRepo
import voice.core.data.repo.VoiceRepo
import voice.core.logging.api.Logger
import java.io.File
import java.time.Instant
import kotlin.uuid.Uuid

public sealed interface ClipResult {
  public data class Success(val file: File) : ClipResult
  public data class Failure(val reason: String) : ClipResult
}

@Inject
public class SentenceClipCache(
  private val context: Context,
  private val synthesisEngine: SynthesisEngine,
  private val sentenceClipRepo: SentenceClipRepo,
  private val voiceRepo: VoiceRepo,
  @MaxTtsCacheSizeBytes private val maxCacheSizeBytes: Long,
) {

  public suspend fun getOrSynthesize(
    bookId: BookId,
    voiceId: String,
    chapterIndex: Int,
    sentenceIndex: Int,
    text: String,
  ): ClipResult {
    return withContext(Dispatchers.IO) {
      var outputFile: File? = null
      try {
        val existing = sentenceClipRepo.get(bookId, voiceId, chapterIndex, sentenceIndex)
        if (existing != null) {
          if (existing.file.exists()) {
            sentenceClipRepo.touch(bookId, voiceId, chapterIndex, sentenceIndex, Instant.now())
            return@withContext ClipResult.Success(existing.file)
          }
          Logger.w(
            "Stale SentenceClip row for voice=$voiceId chapter=$chapterIndex sentence=$sentenceIndex: " +
              "file missing, re-synthesizing",
          )
          sentenceClipRepo.delete(existing)
        }

        val voice: InstalledVoice = voiceRepo.installedVoice(voiceId)
          ?: return@withContext ClipResult.Failure("voice $voiceId is not installed")

        val clipsDir = File(context.filesDir, "ttsClips").apply { mkdirs() }
        val file = File(clipsDir, "${Uuid.random()}.wav")
        outputFile = file

        when (val result = synthesisEngine.synthesize(text, voice, file)) {
          is SynthesisResult.Failure -> {
            file.delete()
            ClipResult.Failure(result.reason)
          }
          SynthesisResult.Success -> {
            val sizeBytes = file.length()
            if (!makeRoomFor(sizeBytes)) {
              file.delete()
              ClipResult.Failure("not enough cache space for this clip")
            } else {
              sentenceClipRepo.upsert(
                SentenceClip(
                  bookId = bookId,
                  voiceId = voiceId,
                  chapterIndex = chapterIndex,
                  sentenceIndex = sentenceIndex,
                  file = file,
                  sizeBytes = sizeBytes,
                  lastAccessedAt = Instant.now(),
                ),
              )
              ClipResult.Success(file)
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Failed to get or synthesize clip for voice=$voiceId chapter=$chapterIndex sentence=$sentenceIndex")
        outputFile?.delete()
        ClipResult.Failure("cache error: ${e.message}")
      }
    }
  }

  private suspend fun makeRoomFor(newClipBytes: Long): Boolean {
    if (newClipBytes > maxCacheSizeBytes) return false
    var total = sentenceClipRepo.totalSizeBytes()
    while (total + newClipBytes > maxCacheSizeBytes) {
      val victims = sentenceClipRepo.leastRecentlyAccessed(limit = EVICTION_BATCH_SIZE)
      if (victims.isEmpty()) return false
      for (victim in victims) {
        victim.file.delete()
        sentenceClipRepo.delete(victim)
        total -= victim.sizeBytes
        if (total + newClipBytes <= maxCacheSizeBytes) break
      }
    }
    return true
  }

  private companion object {
    const val EVICTION_BATCH_SIZE = 10
  }
}
