package voice.core.tts

import android.content.Context
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import okio.sink
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import kotlin.uuid.Uuid

@Inject
internal class TtsDownloader(
  @TtsHttpClient private val client: OkHttpClient,
  private val context: Context,
) {

  internal suspend fun download(url: String): File? {
    val tempFolder = File(context.cacheDir, "ttsVoiceDownload").apply { mkdirs() }
    val request = Request.Builder().url(url).build()
    val response = try {
      client.newCall(request).executeAsync()
    } catch (e: IOException) {
      Logger.w(e, "Failed to download voice from $url")
      return null
    }
    if (!response.isSuccessful) {
      Logger.w("Failed to download voice from $url: HTTP ${response.code}")
      response.close()
      return null
    }
    return withContext(Dispatchers.IO) {
      val file = File(tempFolder, "${Uuid.random()}.tar.bz2")
      try {
        response.body.source().use { source ->
          file.sink().use { sink -> source.readAll(sink) }
        }
        file
      } catch (e: IOException) {
        Logger.w(e, "Failed to save voice download from $url")
        file.delete()
        null
      }
    }
  }
}
