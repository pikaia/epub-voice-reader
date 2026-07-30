package voice.core.epub

import java.io.File

interface EpubParser {
  fun parse(file: File): EpubParseResult
}
