package voice.features.bookOverview.overview

import androidx.datastore.core.DataStore
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.GridMode
import voice.core.data.KioskModeDemoData
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.internals.dao.RecentBookSearchDao
import voice.core.featureflag.MemoryFeatureFlag
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.scanner.DeviceHasStoragePermissionBug
import voice.core.scanner.MediaScanTrigger
import voice.core.search.BookSearch
import voice.core.ui.GridCount
import voice.features.bookOverview.book
import voice.navigation.Destination
import voice.navigation.Navigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BookOverviewViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val dispatcherProvider = DispatcherProvider(testDispatcher, testDispatcher, testDispatcher)

  @Test
  fun `state updates the current book item from live playback`() = runTest {
    val currentBook = book(name = "Current", time = 1_000)
    val otherBook = book(name = "Other", time = 2_000)
    val livePlaybackFlow = MutableStateFlow<LivePlaybackState?>(null)
    val viewModel = BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(listOf(currentBook, otherBook))
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = mockk<PlayerController> {
        every { livePlaybackStateFlow(currentBook.id) } returns livePlaybackFlow
      },
      currentBookStoreDataStore = MemoryDataStore(currentBook.id),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = mockk<Navigator>(),
      appInfoProvider = appInfoProvider(),
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = mockk<BookContentRepo>(),
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(true),
      kioskModeFeatureFlag = MemoryFeatureFlag(false),
      dispatcherProvider = dispatcherProvider,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      val initial = awaitItem()
      val initialCurrentItem = initial.currentBook(currentBook.id)
      val initialOtherItem = initial.currentBook(otherBook.id)
      val initialKeys = initial.books.getValue(BookOverviewCategory.CURRENT).keys.toList()

      assertEquals(expected = currentBook.toItemViewState(), actual = initialCurrentItem)
      assertEquals(expected = otherBook.toItemViewState(), actual = initialOtherItem)

      val livePlaybackState = LivePlaybackState(
        bookId = currentBook.id,
        chapterId = currentBook.chapters.first().id,
        positionMs = 6_000,
        isPlaying = true,
        playbackSpeed = 1F,
      )
      livePlaybackFlow.value = livePlaybackState
      yield()

      assertEquals(expected = initialKeys, actual = initial.books.getValue(BookOverviewCategory.CURRENT).keys.toList())
      assertEquals(expected = currentBook.overlay(livePlaybackState).toItemViewState(), actual = initial.currentBook(currentBook.id))
      assertEquals(expected = initialOtherItem, actual = initial.currentBook(otherBook.id))
      expectNoEvents()
    }
  }

  @Test
  fun `state uses demo books in kiosk mode`() = runTest {
    val viewModel = BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(emptyList())
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = mockk(),
      currentBookStoreDataStore = MemoryDataStore(null),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = mockk<Navigator>(),
      appInfoProvider = appInfoProvider(),
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = mockk<BookContentRepo>(),
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      kioskModeFeatureFlag = MemoryFeatureFlag(true),
      dispatcherProvider = dispatcherProvider,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      val state = awaitItem()
      assertEquals(
        expected = KioskModeDemoData.demoAudiobooks.map {
          it.id
        },
        actual = state.books.getValue(BookOverviewCategory.CURRENT).keys.toList(),
      )
      assertEquals(expected = "Echoes of Tomorrow", actual = state.currentBook(KioskModeDemoData.currentlyPlaying.id).name)
    }
  }

  @Test
  fun `folder picker icon is hidden when folder picker in settings flag is true`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(true),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = false, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker icon is shown once when flag is false`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = true, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker icon is hidden when moved dialog was shown`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(true),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = false, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker icon is hidden for installs on migration cutoff date`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
      appInfoProvider = appInfoProvider(installTime = Instant.parse("2026-06-17T00:00:00Z")),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = false, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker click shows moved dialog instead of navigating`() = runTest {
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = null, actual = awaitItem().dialog)

      viewModel.onBookFolderClick()

      assertEquals(expected = BookOverviewViewState.Dialog.FolderPickerMovedToSettings, actual = awaitItem().dialog)
      verify(exactly = 0) {
        navigator.goTo(Destination.FolderPicker)
      }
    }
  }

  @Test
  fun `dismissing moved dialog marks it shown and hides folder picker icon`() = runTest {
    val folderPickerMovedDialogShownStore = MemoryDataStore(false)
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = folderPickerMovedDialogShownStore,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = true, actual = awaitItem().showFolderPickerIcon)

      viewModel.onBookFolderClick()
      assertEquals(expected = BookOverviewViewState.Dialog.FolderPickerMovedToSettings, actual = awaitItem().dialog)

      viewModel.onFolderPickerMovedDialogDismiss()

      val dismissed = awaitItem()
      assertEquals(expected = null, actual = dismissed.dialog)
      if (dismissed.showFolderPickerIcon) {
        assertEquals(expected = false, actual = awaitItem().showFolderPickerIcon)
      } else {
        assertEquals(expected = false, actual = dismissed.showFolderPickerIcon)
      }
    }
  }

  @Test
  fun `onBookClick routes epub books to the epub reader`() = runTest {
    val bookId = BookId("content://epub-book")
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      contentRepo = mockk {
        coEvery { get(bookId) } returns mockk { every { sourceType } returns BookSourceType.Epub }
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.onBookClick(bookId)

    verify { navigator.goTo(Destination.EpubReader(bookId)) }
  }

  @Test
  fun `onBookClick routes audio books to the playback screen`() = runTest {
    val bookId = BookId("content://audio-book")
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      contentRepo = mockk {
        coEvery { get(bookId) } returns mockk { every { sourceType } returns BookSourceType.Audio }
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.onBookClick(bookId)

    verify { navigator.goTo(Destination.Playback(bookId)) }
  }

  @Test
  fun `playPause routes to the epub reader when the epub is more recently played than the audiobook`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { isActive } returns true
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { navigator.goTo(Destination.EpubReader(epubId)) }
    verify(exactly = 0) { playerController.playPause() }
  }

  @Test
  fun `playPause defers to the player controller when the audiobook is more recently played than the epub`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { isActive } returns true
            every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
    verify(exactly = 0) { navigator.goTo(Destination.EpubReader(epubId)) }
  }

  @Test
  fun `playPause routes to the epub reader when no audiobook has ever been played`() = runTest {
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(null),
      contentRepo = mockk {
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { isActive } returns true
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { navigator.goTo(Destination.EpubReader(epubId)) }
    verify(exactly = 0) { playerController.playPause() }
  }

  @Test
  fun `playPause pauses the currently playing session instead of navigating away from it`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val playStateManager = PlayStateManager().apply { playState = PlayStateManager.PlayState.Playing }
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      playStateManager = playStateManager,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { isActive } returns true
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
    verify(exactly = 0) { navigator.goTo(Destination.EpubReader(epubId)) }
  }

  @Test
  fun `playPause ignores an epub that is no longer active in the library`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val epubId = BookId("content://epub")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns listOf(
          mockk {
            every { id } returns epubId
            every { sourceType } returns BookSourceType.Epub
            every { isActive } returns false
            every { lastPlayedAt } returns java.time.Instant.parse("2026-06-01T00:00:00Z")
          },
        )
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
    verify(exactly = 0) { navigator.goTo(Destination.EpubReader(epubId)) }
  }

  @Test
  fun `playPause defers to the player controller when no epub exists`() = runTest {
    val audiobookId = BookId("content://audiobook")
    val navigator = mockk<Navigator>(relaxed = true)
    val playerController = mockk<PlayerController>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      playerController = playerController,
      currentBookStoreDataStore = MemoryDataStore(audiobookId),
      contentRepo = mockk {
        coEvery { get(audiobookId) } returns mockk {
          every { lastPlayedAt } returns java.time.Instant.parse("2026-01-01T00:00:00Z")
        }
        coEvery { all() } returns emptyList()
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      folderPickerMovedDialogShownStore = MemoryDataStore(false),
    )

    viewModel.playPause()

    verify { playerController.playPause() }
  }

  private fun BookOverviewViewState.currentBook(bookId: BookId): BookOverviewItemViewState {
    return books.getValue(BookOverviewCategory.CURRENT).getValue(bookId).value
  }

  private fun viewModel(
    folderPickerInSettingsFeatureFlag: MemoryFeatureFlag<Boolean>,
    folderPickerMovedDialogShownStore: DataStore<Boolean>,
    navigator: Navigator = mockk(),
    appInfoProvider: AppInfoProvider = appInfoProvider(),
    contentRepo: BookContentRepo = mockk(),
    currentBookStoreDataStore: DataStore<BookId?> = MemoryDataStore(null),
    playerController: PlayerController = mockk(relaxed = true),
    playStateManager: PlayStateManager = PlayStateManager(),
  ): BookOverviewViewModel {
    return BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(emptyList())
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { scan(any()) } just Runs
      },
      playStateManager = playStateManager,
      playerController = playerController,
      currentBookStoreDataStore = currentBookStoreDataStore,
      folderPickerMovedDialogShownStore = folderPickerMovedDialogShownStore,
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = navigator,
      appInfoProvider = appInfoProvider,
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = contentRepo,
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = folderPickerInSettingsFeatureFlag,
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      kioskModeFeatureFlag = MemoryFeatureFlag(false),
      dispatcherProvider = dispatcherProvider,
    )
  }

  private fun appInfoProvider(installTime: Instant = Instant.parse("2026-06-16T00:00:00Z")): AppInfoProvider {
    return mockk {
      every { this@mockk.installTime } returns installTime
    }
  }
}

private class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val value = MutableStateFlow(initial)

  override val data: Flow<T> get() = value

  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}
