package voice.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.logging.api.Logger
import voice.core.playback.misc.Decibel
import voice.core.playback.session.CustomCommand
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.PlaybackService
import voice.core.playback.session.bookId
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.sendCustomCommand
import voice.core.playback.session.toMediaIdOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Inject
class PlayerController(
  private val context: Context,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  private val mediaItemProvider: MediaItemProvider,
) {

  private var _controller: Deferred<MediaController> = newControllerAsync()

  private fun newControllerAsync() = MediaController
    .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
    .buildAsync()
    .asDeferred()

  private val controller: Deferred<MediaController>
    get() {
      if (_controller.isCompleted) {
        val completedController = _controller.getCompleted()
        if (!completedController.isConnected) {
          completedController.release()
          _controller = newControllerAsync()
        }
      }
      return _controller
    }
  private val scope = CoroutineScope(Dispatchers.Main.immediate)

  // Tracks the user's last explicit play/pause intent for the active non-book (EPUB) session,
  // independent of the player's own ambient state. Necessary because a synthesis-window reload
  // (see setEpubPlaylist) can outrun playback, leaving the player at STATE_ENDED/IDLE with its
  // playWhenReady flag still stuck true — at that point controller.isPlaying and PlayStateManager
  // both read "paused" even though nothing ever explicitly paused it, so neither can be trusted to
  // tell "user paused" apart from "momentarily ran out of synthesized audio."
  @Volatile
  private var nonBookSessionShouldBePlaying = false

  fun setPosition(
    time: Long,
    id: ChapterId,
  ) = executeAfterPrepare { controller ->
    val bookId = currentBookStoreId.data.first() ?: return@executeAfterPrepare
    val book = bookRepository.get(bookId) ?: return@executeAfterPrepare
    val playbackItem = book.playbackItemForPosition(
      chapterId = id,
      positionInChapterMs = time,
    )
    if (playbackItem != null) {
      controller.seekTo(playbackItem.index, playbackItem.positionInMediaItem(time))
    }
  }

  fun pauseIfCurrentBookDifferentFrom(id: BookId) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      val currentItemIsBook = controller.currentMediaItem?.mediaId?.toMediaIdOrNull() != null
      if (!currentItemIsBook && controller.mediaItemCount > 0) {
        // A non-book (e.g. EPUB) session is active. It's always different from the audiobook
        // being opened here, since currentBookId() below only recognizes book MediaIds and would
        // otherwise read null and skip the pause entirely.
        nonBookSessionShouldBePlaying = false
        controller.pause()
        return@launch
      }
      val currentBookId = controller.currentBookId()
      if (currentBookId != null && currentBookId != id) {
        controller.pause()
      }
    }
  }

  suspend fun isCurrentSessionBook(): Boolean {
    val controller = awaitConnect() ?: return false
    return controller.currentMediaItem?.mediaId?.toMediaIdOrNull() != null
  }

  fun pauseCurrentSession() {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (controller.mediaItemCount > 0) {
        nonBookSessionShouldBePlaying = false
        controller.pause()
      }
    }
  }

  fun skipSilence(skip: Boolean) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetSkipSilence(skip))
  }

  fun fastForward() = executeAfterPrepare { controller ->
    controller.seekForward()
  }

  fun rewind() = executeAfterPrepare { controller ->
    controller.seekBack()
  }

  fun previous() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToPrevious)
  }

  fun next() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToNext)
  }

  fun play() = executeAfterPrepare { controller ->
    controller.play()
  }

  fun playPause() {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      val currentItemIsBook = controller.currentMediaItem?.mediaId?.toMediaIdOrNull() != null
      if (!currentItemIsBook && controller.mediaItemCount > 0) {
        // A non-book session is already loaded (e.g. an active EPUB queue, whose items carry no
        // MediaId) — toggle it directly. Falling through to maybePrepare() would try to resolve
        // via the audiobook-only currentBookStoreId, which either does nothing (if it's unset)
        // or replaces this queue with an unrelated book (if it's stale). This is the right call
        // for ambiguous, book-agnostic callers (the library FAB, the home-screen widget) that
        // don't know or care which book is active — but NOT for a caller that explicitly knows
        // which book it wants; those must use playPauseBook() instead.
        //
        // Toggle against the tracked intent, not controller.isPlaying — see
        // nonBookSessionShouldBePlaying's doc for why isPlaying alone isn't trustworthy here.
        if (nonBookSessionShouldBePlaying) {
          nonBookSessionShouldBePlaying = false
          controller.pause()
        } else {
          nonBookSessionShouldBePlaying = true
          controller.play()
        }
        return@launch
      }
      if (maybePrepare(controller)) {
        if (controller.isPlaying) {
          controller.pause()
        } else {
          controller.play()
        }
      }
    }
  }

  // For callers that already know exactly which book they want playing (e.g. a book-scoped
  // player screen). Unlike playPause(), this never defers to an already-loaded non-book (EPUB)
  // session — it always ensures this specific book is loaded first, since the caller's intent is
  // unambiguous here in a way the shared library FAB / widget's playPause() isn't.
  fun playPauseBook(bookId: BookId) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (maybePrepareBook(controller, bookId)) {
        if (controller.isPlaying) {
          controller.pause()
        } else {
          controller.play()
        }
      }
    }
  }

  private suspend fun maybePrepare(controller: MediaController): Boolean {
    val bookId = currentBookStoreId.data.first() ?: return false
    return maybePrepareBook(controller, bookId)
  }

  private suspend fun maybePrepareBook(
    controller: MediaController,
    bookId: BookId,
  ): Boolean {
    if (controller.currentBookId() == bookId &&
      controller.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
    ) {
      return true
    }
    val book = bookRepository.get(bookId) ?: return false
    // setMediaItems with an explicit startIndex (not setMediaItem(item), which implies an unset
    // index) — a single item at an unset start index/position is indistinguishable, on the wire,
    // from a passive/system-initiated resumption request, and LibrarySessionCallback intercepts
    // that shape to protect an active EPUB session from being silently hijacked by one. This is
    // OUR OWN deliberate, explicit request for a specific book, not an ambiguous passive one — it
    // must always win, so it needs a shape that guard doesn't recognize as ambiguous.
    controller.setMediaItems(listOf(mediaItemProvider.mediaItem(book)), 0, C.TIME_UNSET)
    controller.prepare()
    return true
  }

  private fun MediaController.currentBookId(): BookId? {
    val currentMediaItem = currentMediaItem ?: return null
    val mediaId = currentMediaItem.mediaId.toMediaIdOrNull() ?: return null
    return mediaId.bookId
  }

  fun pauseWithRewind(rewind: Duration) = executeAfterPrepare { controller ->
    controller.pause()
    controller.seekBackBy(
      rewind = rewind,
      crossMediaItems = false,
    )
  }

  private fun MediaController.seekBackBy(
    rewind: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?: return
    var remaining = rewind
    var mediaItemIndex = currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        seekTo(0)
        return
      }
      currentPosition = getMediaItemAt(previousMediaItemIndex).mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  fun setSpeed(speed: Float) = executeAfterPrepare { controller ->
    controller.setPlaybackSpeed(speed)
  }

  fun setGain(gain: Decibel) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetGain(gain))
  }

  fun setVolume(volume: Float) = executeAfterPrepare {
    require(volume in 0F..1F)
    it.volume = volume
  }

  fun setEpubPlaylist(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    autoPlay: Boolean,
  ) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      // A background window reload (autoPlay=false) must not resume playback the user paused
      // while the reload was in flight — and must not resume just because the window ran dry
      // before the reload landed (controller.isPlaying reads false in that case too, since the
      // player naturally reaches STATE_ENDED/IDLE, but playWhenReady is never cleared — so calling
      // prepare() below would otherwise auto-resume regardless of what we do next). Only an
      // explicit start (autoPlay=true) or a tracked "should be playing" intent should end up
      // playing after the new queue is set — and it must be applied explicitly (pause() too, not
      // just skipping play()), since a stale playWhenReady=true left over from the window running
      // dry would otherwise resume playback on its own once prepare() completes.
      val shouldPlay = if (autoPlay) {
        nonBookSessionShouldBePlaying = true
        true
      } else {
        nonBookSessionShouldBePlaying
      }
      controller.setMediaItems(mediaItems, startIndex, startPositionMs)
      controller.prepare()
      if (shouldPlay) {
        controller.play()
      } else {
        controller.pause()
      }
    }
  }

  fun currentMediaItemIndexFlow(): Flow<Int> = callbackFlow {
    val controller = awaitConnect()
    if (controller == null) {
      close()
      return@callbackFlow
    }

    fun emitIndex() {
      trySend(controller.currentMediaItemIndex)
    }

    val listener = object : Player.Listener {
      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
      ) {
        emitIndex()
      }
    }

    controller.addListener(listener)
    emitIndex()
    awaitClose {
      controller.removeListener(listener)
    }
  }

  fun toggleEpubPlayPause() {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      // Toggle against the tracked intent, not controller.isPlaying — see
      // nonBookSessionShouldBePlaying's doc for why isPlaying alone isn't trustworthy here.
      if (nonBookSessionShouldBePlaying) {
        nonBookSessionShouldBePlaying = false
        controller.pause()
      } else {
        nonBookSessionShouldBePlaying = true
        controller.play()
      }
    }
  }

  suspend fun livePlaybackState(bookId: BookId? = null): LivePlaybackState? {
    val controller = awaitConnect() ?: return null
    return controller.livePlaybackStateSnapshot(bookId)
  }

  fun livePlaybackStateFlow(bookId: BookId? = null): Flow<LivePlaybackState?> = callbackFlow {
    val controller = awaitConnect()
    if (controller == null) {
      trySend(null)
      close()
      return@callbackFlow
    }

    fun emitSnapshot() {
      trySend(controller.livePlaybackStateSnapshot(bookId))
    }

    var tickJob: Job? = null
    fun updateTicking() {
      if (!controller.isPlaying) {
        tickJob?.cancel()
        return
      }
      if (tickJob?.isActive == true) {
        return
      }
      tickJob = launch {
        while (isActive) {
          delay(250.milliseconds)
          emitSnapshot()
        }
      }
    }

    val listener = object : Player.Listener {
      override fun onEvents(
        player: Player,
        events: Player.Events,
      ) {
        if (events.containsAny(
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
          )
        ) {
          emitSnapshot()
          updateTicking()
        }
        if (events.containsAny(
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          )
        ) {
          emitSnapshot()
        }
      }
    }

    controller.addListener(listener)
    emitSnapshot()
    updateTicking()
    awaitClose {
      tickJob?.cancel()
      controller.removeListener(listener)
    }
  }

  private inline fun executeAfterPrepare(crossinline action: suspend (MediaController) -> Unit) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (maybePrepare(controller)) {
        action(controller)
      }
    }
  }

  @IgnorableReturnValue
  suspend fun awaitConnect(): MediaController? {
    return try {
      controller.await()
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      Logger.w(e, "Error while connecting to media controller")
      null
    }
  }
}
