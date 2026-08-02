package voice.core.data.repo

import voice.core.data.InstalledVoice

public interface VoiceRepo {

  public suspend fun upsert(voice: InstalledVoice)

  public suspend fun installedVoices(): List<InstalledVoice>

  public suspend fun installedVoice(voiceId: String): InstalledVoice?

  public suspend fun delete(voiceId: String)
}
