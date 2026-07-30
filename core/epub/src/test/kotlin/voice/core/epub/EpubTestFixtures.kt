package voice.core.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class TestEpubChapter(
  val title: String?,
  val bodyHtml: String,
)

internal fun buildTestEpub(
  file: File,
  chapters: List<TestEpubChapter>,
  encrypted: Boolean = false,
): File {
  ZipOutputStream(file.outputStream()).use { zip ->
    fun writeEntry(name: String, content: String) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(content.toByteArray())
      zip.closeEntry()
    }

    writeEntry("mimetype", "application/epub+zip")
    writeEntry(
      "META-INF/container.xml",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles>
          <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
        </rootfiles>
      </container>
      """.trimIndent(),
    )
    if (encrypted) {
      writeEntry(
        "META-INF/encryption.xml",
        """<?xml version="1.0" encoding="UTF-8"?><encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"/>""",
      )
    }

    val manifestItems = chapters.indices.joinToString("\n          ") { index ->
      """<item id="chapter$index" href="chapter$index.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val spineItems = chapters.indices.joinToString("\n          ") { index ->
      """<itemref idref="chapter$index"/>"""
    }
    writeEntry(
      "OEBPS/content.opf",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata/>
        <manifest>
          $manifestItems
        </manifest>
        <spine>
          $spineItems
        </spine>
      </package>
      """.trimIndent(),
    )

    chapters.forEachIndexed { index, chapter ->
      val head = if (chapter.title != null) "<head><title>${chapter.title}</title></head>" else "<head></head>"
      writeEntry(
        "OEBPS/chapter$index.xhtml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml">
          $head
          <body>${chapter.bodyHtml}</body>
        </html>
        """.trimIndent(),
      )
    }
  }
  return file
}
