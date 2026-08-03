package voice.features.epubReader.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import voice.features.epubReader.EpubReaderViewState

@Composable
public fun EpubReaderView(
  viewState: EpubReaderViewState,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  when (viewState) {
    EpubReaderViewState.Loading -> {
      Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is EpubReaderViewState.Error -> {
      Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(viewState.message)
      }
    }
    is EpubReaderViewState.Content -> {
      EpubReaderContent(
        viewState = viewState,
        onPlayPauseClick = onPlayPauseClick,
        onChapterSelect = onChapterSelect,
        modifier = modifier,
      )
    }
  }
}

@Composable
private fun EpubReaderContent(
  viewState: EpubReaderViewState.Content,
  onPlayPauseClick: () -> Unit,
  onChapterSelect: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var chapterMenuExpanded by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  LaunchedEffect(viewState.activeSentenceIndex) {
    listState.animateScrollToItem(viewState.activeSentenceIndex)
  }

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(viewState.bookTitle) },
        actions = {
          Box {
            Text(
              text = "Chapters",
              modifier = Modifier
                .clickable { chapterMenuExpanded = true }
                .padding(8.dp),
            )
            DropdownMenu(
              expanded = chapterMenuExpanded,
              onDismissRequest = { chapterMenuExpanded = false },
            ) {
              viewState.chapters.forEach { chapter ->
                DropdownMenuItem(
                  text = { Text(chapter.title) },
                  onClick = {
                    chapterMenuExpanded = false
                    onChapterSelect(chapter.index)
                  },
                )
              }
            }
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = onPlayPauseClick) {
        Text(if (viewState.isPlaying) "Pause" else "Play")
      }
    },
  ) { contentPadding ->
    LazyColumn(
      state = listState,
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      itemsIndexed(viewState.sentences) { index, sentence ->
        val isActive = index == viewState.activeSentenceIndex
        Text(
          text = sentence,
          modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .background(
              if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            ),
          color = if (isActive) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onBackground
          },
        )
      }
    }
  }
}
