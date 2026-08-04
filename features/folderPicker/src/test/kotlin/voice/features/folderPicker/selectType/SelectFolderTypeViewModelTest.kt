package voice.features.folderPicker.selectType

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.common.DispatcherProvider
import voice.core.documentfile.FileBasedDocumentFactory
import voice.navigation.Origin
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class SelectFolderTypeViewModelTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun test() = runTest {
    val audiobookFolder = temporaryFolder.newFolder("audiobooks")
    with(temporaryFolder) {
      newFile("audiobooks/FirstBook.mp3")
      newFolder("audiobooks/SecondBook")
      newFile("audiobooks/SecondBook/1.mp3")
      newFile("audiobooks/SecondBook/2.mp3")
    }
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = audiobookFolder.toUri(),
      documentFile = DocumentFile.fromFile(audiobookFolder),
      origin = Origin.Default,
    )
    viewModel.setFolderMode(FolderMode.Audiobooks)

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      suspend fun expectItem(
        folderMode: FolderMode,
        vararg expectedBooks: SelectFolderTypeViewState.Book,
      ) {
        with(awaitItem()) {
          assertEquals(
            expected = expectedBooks.toList().sortedBy { it.name },
            actual = this.books.sortedBy { it.name },
          )
          assertEquals(expected = folderMode, actual = this.selectedFolderMode)
        }
      }
      expectItem(FolderMode.Audiobooks)

      expectItem(
        FolderMode.Audiobooks,
        SelectFolderTypeViewState.Book("FirstBook", 1),
        SelectFolderTypeViewState.Book("SecondBook", 2),
      )

      viewModel.setFolderMode(FolderMode.SingleBook)

      expectItem(
        FolderMode.SingleBook,
        SelectFolderTypeViewState.Book("FirstBook", 1),
        SelectFolderTypeViewState.Book("SecondBook", 2),
      )

      expectItem(
        FolderMode.SingleBook,
        SelectFolderTypeViewState.Book("audiobooks", 3),
      )
    }
  }

  @Test
  fun `folder of flat epub files auto-detects to Audiobooks mode with correct counts`() = runTest {
    val epubFolder = temporaryFolder.newFolder("epubs")
    with(temporaryFolder) {
      newFile("epubs/FirstBook.epub")
      newFile("epubs/SecondBook.epub")
    }
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = epubFolder.toUri(),
      documentFile = DocumentFile.fromFile(epubFolder),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.Audiobooks, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(
          SelectFolderTypeViewState.Book("FirstBook", 1),
          SelectFolderTypeViewState.Book("SecondBook", 1),
        ).sortedBy { it.name },
        actual = state.books.sortedBy { it.name },
      )
    }
  }

  @Test
  fun `single loose epub file shows a file count of 1, not 0`() = runTest {
    val file = temporaryFolder.newFile("LoneBook.epub")
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = file.toUri(),
      documentFile = DocumentFile.fromFile(file),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.SingleBook, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(SelectFolderTypeViewState.Book("LoneBook", 1)),
        actual = state.books,
      )
    }
  }

  @Test
  fun `folder containing a single epub file auto-detects to Audiobooks mode`() = runTest {
    val folder = temporaryFolder.newFolder("single-epub")
    temporaryFolder.newFile("single-epub/OnlyBook.epub")
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = folder.toUri(),
      documentFile = DocumentFile.fromFile(folder),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.Audiobooks, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(SelectFolderTypeViewState.Book("OnlyBook", 1)),
        actual = state.books,
      )
    }
  }

  @Test
  fun `mixed audio subfolder and flat epub files both count correctly`() = runTest {
    val mixedFolder = temporaryFolder.newFolder("mixed")
    with(temporaryFolder) {
      newFolder("mixed/AudioBook")
      newFile("mixed/AudioBook/1.mp3")
      newFile("mixed/EpubBook.epub")
      newFile("mixed/SecondEpubBook.epub")
    }
    val viewModel = SelectFolderTypeViewModel(
      dispatcherProvider = DispatcherProvider(coroutineContext, coroutineContext, coroutineContext),
      audiobookFolders = mockk(),
      navigator = mockk(),
      documentFileFactory = FileBasedDocumentFactory,
      uri = mixedFolder.toUri(),
      documentFile = DocumentFile.fromFile(mixedFolder),
      origin = Origin.Default,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.viewState()
    }.test {
      awaitItem() // loading
      val state = awaitItem()
      assertEquals(expected = FolderMode.Audiobooks, actual = state.selectedFolderMode)
      assertEquals(
        expected = listOf(
          SelectFolderTypeViewState.Book("AudioBook", 1),
          SelectFolderTypeViewState.Book("EpubBook", 1),
          SelectFolderTypeViewState.Book("SecondEpubBook", 1),
        ).sortedBy { it.name },
        actual = state.books.sortedBy { it.name },
      )
    }
  }
}
