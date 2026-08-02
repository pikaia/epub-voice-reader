package voice.core.tts

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import okhttp3.OkHttpClient

@Qualifier
public annotation class TtsHttpClient

@Qualifier
public annotation class MaxTtsCacheSizeBytes

@ContributesTo(AppScope::class)
public interface TtsModule {

  @Provides
  @SingleIn(AppScope::class)
  @TtsHttpClient
  public fun ttsHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

  @Provides
  public fun voiceCatalog(): List<VoiceCatalogEntry> = VoiceCatalog.entries

  @Provides
  @MaxTtsCacheSizeBytes
  public fun maxTtsCacheSizeBytes(): Long = 500L * 1024 * 1024
}
