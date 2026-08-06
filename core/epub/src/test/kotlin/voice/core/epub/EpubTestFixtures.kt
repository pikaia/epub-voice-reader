package voice.core.epub

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class TestEpubChapter(
  val title: String?,
  val bodyHtml: String,
)

internal data class TestManifestImage(
  val id: String,
  val href: String,
  val mediaType: String,
  val content: ByteArray,
  val coverImageProperty: Boolean = false,
)

internal fun buildTestEpub(
  file: File,
  chapters: List<TestEpubChapter>,
  encrypted: Boolean = false,
  images: List<TestManifestImage> = emptyList(),
  coverMetaContentId: String? = null,
): File {
  ZipOutputStream(file.outputStream()).use { zip ->
    fun writeEntry(
      name: String,
      content: String,
    ) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(content.toByteArray())
      zip.closeEntry()
    }
    fun writeBinaryEntry(
      name: String,
      content: ByteArray,
    ) {
      zip.putNextEntry(ZipEntry(name))
      zip.write(content)
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

    val chapterManifestItems = chapters.indices.joinToString("\n          ") { index ->
      """<item id="chapter$index" href="chapter$index.xhtml" media-type="application/xhtml+xml"/>"""
    }
    val imageManifestItems = images.joinToString("") { image ->
      val properties = if (image.coverImageProperty) " properties=\"cover-image\"" else ""
      "\n          " +
        """<item id="${image.id}" href="${image.href}" media-type="${image.mediaType}"$properties/>"""
    }
    val spineItems = chapters.indices.joinToString("\n          ") { index ->
      """<itemref idref="chapter$index"/>"""
    }
    val metadata = if (coverMetaContentId != null) {
      """<meta name="cover" content="$coverMetaContentId"/>"""
    } else {
      ""
    }
    writeEntry(
      "OEBPS/content.opf",
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
        <metadata>$metadata</metadata>
        <manifest>
          $chapterManifestItems$imageManifestItems
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

    images.forEach { image ->
      writeBinaryEntry("OEBPS/${image.href}", image.content)
    }
  }
  return file
}
