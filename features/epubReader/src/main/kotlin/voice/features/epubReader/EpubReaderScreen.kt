package voice.features.epubReader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.features.epubReader.view.EpubReaderView
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@Composable
public fun EpubReaderScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<EpubReaderGraph>()
      .epubReaderViewModelFactory
      .create(bookId)
  }
  val viewState = viewModel.viewState()
  EpubReaderView(
    viewState = viewState,
    onPlayPauseClick = viewModel::playPause,
    onChapterSelect = viewModel::onChapterSelect,
  )
}

@ContributesTo(AppScope::class)
public interface EpubReaderGraph {
  public val epubReaderViewModelFactory: EpubReaderViewModel.Factory
}

@ContributesTo(AppScope::class)
public interface EpubReaderProvider {

  @Provides
  @IntoSet
  public fun epubReaderNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.EpubReader> { key ->
    NavEntry(key) {
      EpubReaderScreen(bookId = key.bookId)
    }
  }
}
