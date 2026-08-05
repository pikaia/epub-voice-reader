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
    autoPlay: Boolean,
  )

  public fun currentMediaItemIndexFlow(): Flow<Int>

  public fun togglePlayPause()

  // Pauses whatever session (book or non-book) is currently loaded, without touching or
  // resolving currentBookStoreId. Called before an EPUB starts synthesizing its opening window,
  // so a previously-loaded session (e.g. a paused audiobook) can't still be sitting there —
  // audible, in principle, to any stray resume — during the seconds synthesis can take.
  public fun pauseCurrentSession()

  // True if the player's current item belongs to a book (carries a MediaId), meaning some other
  // session has taken over since this EPUB was last active.
  public suspend fun isCurrentSessionBook(): Boolean
}

@Inject
@ContributesBinding(AppScope::class)
public class RealEpubPlaybackControl(private val playerController: PlayerController) : EpubPlaybackControl {

  override fun setPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    autoPlay: Boolean,
  ) {
    playerController.setEpubPlaylist(mediaItems, startIndex, startPositionMs, autoPlay)
  }

  override fun currentMediaItemIndexFlow(): Flow<Int> = playerController.currentMediaItemIndexFlow()

  override fun togglePlayPause() {
    playerController.toggleEpubPlayPause()
  }

  override fun pauseCurrentSession() {
    playerController.pauseCurrentSession()
  }

  override suspend fun isCurrentSessionBook(): Boolean = playerController.isCurrentSessionBook()
}
