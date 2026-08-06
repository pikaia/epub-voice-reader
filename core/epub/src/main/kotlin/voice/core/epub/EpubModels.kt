package voice.core.epub

data class ParsedBook(
  val chapters: List<ParsedChapter>,
  val coverBytes: ByteArray? = null,
)

data class ParsedChapter(
  val title: String,
  val sentences: List<String>,
)

sealed interface EpubParseResult {
  data class Success(val book: ParsedBook) : EpubParseResult
  data object DrmProtected : EpubParseResult
  data class Malformed(val reason: String) : EpubParseResult
}
