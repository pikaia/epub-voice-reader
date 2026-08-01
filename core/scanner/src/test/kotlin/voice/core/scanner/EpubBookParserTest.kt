package voice.core.scanner

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.BookSourceType
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.ChapterRepo
import voice.core.documentfile.FileBasedDocumentFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class EpubBookParserTest {

  @get:Rule
  val testFolder = TemporaryFolder()

  private val contentRepo = FakeBookContentRepo()
  private val chapterRepo = FakeChapterRepo()
  private val parser = EpubBookParser(contentRepo, chapterRepo)

  @Test
  fun createsBookContentWithEpubSourceTypeAndFileNameAsTitle() = runTest {
    val file = testFolder.newFile("My Book.epub")

    val content = parser.parseAndStore(FileBasedDocumentFile(file))

    assertEquals(expected = "My Book", actual = content.name)
    assertEquals(expected = BookSourceType.Epub, actual = content.sourceType)
    assertEquals(expected = listOf(ChapterId(file.toUri())), actual = content.chapters)
    assertTrue(content.isActive)
  }

  @Test
  fun reactivatesAnExistingBookOnRescan() = runTest {
    val file = testFolder.newFile("My Book.epub")

    val firstScan = parser.parseAndStore(FileBasedDocumentFile(file))
    contentRepo.put(firstScan.copy(isActive = false))

    val secondScan = parser.parseAndStore(FileBasedDocumentFile(file))

    assertTrue(secondScan.isActive)
  }

  private class FakeBookContentRepo : BookContentRepo {
    private val content = MutableStateFlow<Map<BookId, BookContent>>(emptyMap())

    override fun flow(): Flow<List<BookContent>> = content.map { it.values.toList() }
    override suspend fun all(): List<BookContent> = content.value.values.toList()
    override fun flow(id: BookId): Flow<BookContent?> = content.map { it[id] }
    override suspend fun get(id: BookId): BookContent? = content.value[id]
    override suspend fun setAllInactiveExcept(ids: List<BookId>) {}
    override suspend fun put(content: BookContent) {
      this.content.value = this.content.value + (content.id to content)
    }
  }

  private class FakeChapterRepo : ChapterRepo {
    private val chapters = mutableMapOf<ChapterId, Chapter>()
    override suspend fun get(id: ChapterId): Chapter? = chapters[id]
    override suspend fun put(chapter: Chapter) {
      chapters[chapter.id] = chapter
    }
  }
}
