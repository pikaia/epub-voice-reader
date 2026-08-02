package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import voice.core.data.InstalledVoice
import voice.core.data.repo.internals.dao.InstalledVoiceDao

@ContributesBinding(AppScope::class)
public class VoiceRepoImpl(private val dao: InstalledVoiceDao) : VoiceRepo {

  override suspend fun upsert(voice: InstalledVoice) {
    dao.insert(voice)
  }

  override suspend fun installedVoices(): List<InstalledVoice> = dao.all()

  override suspend fun installedVoice(voiceId: String): InstalledVoice? = dao.get(voiceId)

  override suspend fun delete(voiceId: String) {
    dao.delete(voiceId)
  }
}
