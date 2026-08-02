package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import voice.core.data.BookId
import voice.core.data.InstalledVoice
import voice.core.data.SentenceClip
import voice.core.data.repo.SentenceClipRepo
import voice.core.data.repo.VoiceRepo
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
    val existing = sentenceClipRepo.get(bookId, voiceId, chapterIndex, sentenceIndex)
    if (existing != null) {
      sentenceClipRepo.touch(bookId, voiceId, chapterIndex, sentenceIndex, Instant.now())
      return ClipResult.Success(existing.file)
    }

    val voice: InstalledVoice = voiceRepo.installedVoice(voiceId)
      ?: return ClipResult.Failure("voice $voiceId is not installed")

    val clipsDir = File(context.filesDir, "ttsClips").apply { mkdirs() }
    val outputFile = File(clipsDir, "${Uuid.random()}.wav")

    return when (val result = synthesisEngine.synthesize(text, voice, outputFile)) {
      is SynthesisResult.Failure -> {
        outputFile.delete()
        ClipResult.Failure(result.reason)
      }
      SynthesisResult.Success -> {
        val sizeBytes = outputFile.length()
        if (!makeRoomFor(sizeBytes)) {
          outputFile.delete()
          ClipResult.Failure("not enough cache space for this clip")
        } else {
          sentenceClipRepo.upsert(
            SentenceClip(
              bookId = bookId,
              voiceId = voiceId,
              chapterIndex = chapterIndex,
              sentenceIndex = sentenceIndex,
              file = outputFile,
              sizeBytes = sizeBytes,
              lastAccessedAt = Instant.now(),
            ),
          )
          ClipResult.Success(outputFile)
        }
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
