package voice.core.data

import voice.core.documentfile.CachedDocumentFile

public fun CachedDocumentFile.isEpubFile(): Boolean {
  if (!isFile) return false
  val name = name ?: return false
  return name.substringAfterLast(".").lowercase() == "epub"
}
