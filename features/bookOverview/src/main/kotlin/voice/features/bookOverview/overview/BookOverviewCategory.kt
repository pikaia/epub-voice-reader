package voice.features.bookOverview.overview

import androidx.annotation.StringRes
import voice.core.data.Book
import voice.core.data.BookComparator
import voice.core.data.BookSourceType
import java.util.concurrent.TimeUnit.SECONDS
import voice.core.strings.R as StringsR

enum class BookOverviewCategory(
  @StringRes val nameRes: Int,
  val comparator: Comparator<Book>,
) {
  CURRENT(
    nameRes = StringsR.string.library_category_current_title,
    comparator = BookComparator.ByLastPlayed,
  ),
  NOT_STARTED(
    nameRes = StringsR.string.library_category_not_started_title,
    comparator = BookComparator.ByName,
  ),
  FINISHED(
    nameRes = StringsR.string.library_category_completed_title,
    comparator = BookComparator.ByLastPlayed,
  ),
}

val Book.category: BookOverviewCategory
  get() {
    return when (content.sourceType) {
      BookSourceType.Audio -> audioCategory()
      BookSourceType.Epub -> epubCategory()
    }
  }

private fun Book.audioCategory(): BookOverviewCategory {
  return if (position == 0L) {
    BookOverviewCategory.NOT_STARTED
  } else {
    if (position >= duration - SECONDS.toMillis(5)) {
      BookOverviewCategory.FINISHED
    } else {
      BookOverviewCategory.CURRENT
    }
  }
}

private fun Book.epubCategory(): BookOverviewCategory {
  val chapterCount = content.epubChapterCount
  val chapterIndex = content.currentEpubChapterIndex
  val sentenceIndex = content.currentEpubSentenceIndex
  return when {
    chapterCount == 0 -> BookOverviewCategory.NOT_STARTED
    chapterIndex == 0 && sentenceIndex == 0 -> BookOverviewCategory.NOT_STARTED
    chapterIndex >= chapterCount - 1 && sentenceIndex >= content.epubLastChapterSentenceCount - 2 -> {
      BookOverviewCategory.FINISHED
    }
    else -> BookOverviewCategory.CURRENT
  }
}
