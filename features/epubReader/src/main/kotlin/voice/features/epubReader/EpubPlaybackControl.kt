package voice.features.epubReader

import androidx.media3.common.MediaItem
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import voice.core.playback.PlayerController

public interface EpubPlaybackControl {
  public fun setPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  )

  public fun currentMediaItemIndexFlow(): Flow<Int>

  public fun togglePlayPause()
}

@Inject
@ContributesBinding(AppScope::class)
public class RealEpubPlaybackControl(private val playerController: PlayerController) : EpubPlaybackControl {

  override fun setPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    playerController.setEpubPlaylist(mediaItems, startIndex, startPositionMs)
  }

  override fun currentMediaItemIndexFlow(): Flow<Int> = playerController.currentMediaItemIndexFlow()

  override fun togglePlayPause() {
    playerController.toggleEpubPlayPause()
  }
}
