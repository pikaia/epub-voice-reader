package voice.core.epub

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import org.jsoup.Jsoup
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.text.BreakIterator
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

private val SENTENCE_LOCALE: Locale = Locale.US

// 20 MB per entry (a single EPUB chapter or OPF/container file)
private const val MAX_ENTRY_SIZE_BYTES = 20 * 1024 * 1024L

// 5 MB per cover image entry — generous for a cover, well under the text-entry cap above.
private const val MAX_COVER_SIZE_BYTES = 5 * 1024 * 1024L

@ContributesBinding(AppScope::class)
class DefaultEpubParser : EpubParser {

  private data class ManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val isCoverImage: Boolean,
  )

  override fun parse(file: File): EpubParseResult {
    return try {
      ZipFile(file).use { zip -> parseZip(zip) }
    } catch (e: Exception) {
      EpubParseResult.Malformed(e.message ?: e.javaClass.simpleName)
    }
  }

  private fun parseZip(zip: ZipFile): EpubParseResult {
    if (zip.getEntry("META-INF/encryption.xml") != null) {
      return EpubParseResult.DrmProtected
    }

    val containerXml = zip.readEntryOrNull("META-INF/container.xml")
      ?: return EpubParseResult.Malformed("missing META-INF/container.xml")
    val opfPath = parseOpfPath(containerXml)
      ?: return EpubParseResult.Malformed("no rootfile in container.xml")
    val opfXml = zip.readEntryOrNull(opfPath)
      ?: return EpubParseResult.Malformed("missing OPF file at $opfPath")
    val opfDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val opfDocument = parseXml(opfXml)
      ?: return EpubParseResult.Malformed("could not parse OPF at $opfPath")

    val manifestItems = parseManifestItems(opfDocument)
    val manifest = manifestItems.associate { it.id to it.href }
    val spineHrefs = parseSpineHrefs(opfDocument, manifest)
      ?: return EpubParseResult.Malformed("could not resolve manifest/spine")

    fun resolvePath(href: String) = if (opfDir.isEmpty()) href else "$opfDir/$href"

    val chapters = spineHrefs.mapIndexed { index, href ->
      val path = resolvePath(href)
      val html = zip.readEntryOrNull(path)
        ?: return EpubParseResult.Malformed("missing chapter file at $path")
      parseChapter(html, fallbackTitle = "Chapter ${index + 1}")
    }

    val coverHref = findCoverHref(opfDocument, manifestItems)
    val coverBytes = coverHref?.let { href -> zip.readEntryBytesOrNull(resolvePath(href)) }

    return EpubParseResult.Success(ParsedBook(chapters, coverBytes))
  }

  private fun parseChapter(
    html: String,
    fallbackTitle: String,
  ): ParsedChapter {
    val document = Jsoup.parse(html)
    val title = document.title().ifBlank { fallbackTitle }
    val sentences = splitSentences(document.body().text())
    return ParsedChapter(title = title, sentences = sentences)
  }

  private fun splitSentences(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(SENTENCE_LOCALE)
    iterator.setText(text)
    val sentences = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
      val sentence = text.substring(start, end).trim()
      if (sentence.isNotEmpty()) {
        sentences += sentence
      }
      start = end
      end = iterator.next()
    }
    return sentences
  }

  private fun parseOpfPath(containerXml: String): String? {
    val document = parseXml(containerXml) ?: return null
    val rootFiles = document.getElementsByTagName("rootfile")
    if (rootFiles.length == 0) return null
    val element = rootFiles.item(0) as? Element ?: return null
    return element.getAttribute("full-path").ifBlank { null }
  }

  private fun parseManifestItems(document: Document): List<ManifestItem> {
    val items = document.getElementsByTagName("item")
    val result = mutableListOf<ManifestItem>()
    for (i in 0 until items.length) {
      val item = items.item(i) as? Element ?: continue
      val id = item.getAttribute("id")
      val href = item.getAttribute("href")
      if (id.isBlank() || href.isBlank()) continue
      val mediaType = item.getAttribute("media-type")
      val properties = item.getAttribute("properties")
      val isCoverImage = properties.split(" ").any { it == "cover-image" }
      result += ManifestItem(id = id, href = href, mediaType = mediaType, isCoverImage = isCoverImage)
    }
    return result
  }

  private fun parseSpineHrefs(
    document: Document,
    manifest: Map<String, String>,
  ): List<String>? {
    val spine = mutableListOf<String>()
    val itemRefs = document.getElementsByTagName("itemref")
    for (i in 0 until itemRefs.length) {
      val itemRef = itemRefs.item(i) as? Element ?: continue
      val href = manifest[itemRef.getAttribute("idref")] ?: return null
      spine += href
    }
    return spine.ifEmpty { null }
  }

  private fun findCoverHref(
    document: Document,
    manifestItems: List<ManifestItem>,
  ): String? {
    manifestItems.firstOrNull { it.isCoverImage }?.let { return it.href }
    val coverMetaId = findCoverMetaContentId(document)
    if (coverMetaId != null) {
      manifestItems.firstOrNull { it.id == coverMetaId }?.let { return it.href }
    }
    return manifestItems.firstOrNull { it.mediaType.startsWith("image/") }?.href
  }

  private fun findCoverMetaContentId(document: Document): String? {
    val metas = document.getElementsByTagName("meta")
    for (i in 0 until metas.length) {
      val meta = metas.item(i) as? Element ?: continue
      if (meta.getAttribute("name") == "cover") {
        return meta.getAttribute("content").ifBlank { null }
      }
    }
    return null
  }

  private fun parseXml(xml: String): Document? {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setPropertyIfSupported { isXIncludeAware = false }
    factory.setPropertyIfSupported { isExpandEntityReferences = false }
    return factory.newDocumentBuilder().parse(xml.byteInputStream())
  }

  private inline fun DocumentBuilderFactory.setPropertyIfSupported(set: DocumentBuilderFactory.() -> Unit) {
    try {
      set()
    } catch (e: UnsupportedOperationException) {
      // Android's built-in DocumentBuilderFactory doesn't support configuring some of these
      // properties at all (even to set them to their already-default value) — a no-op there.
    }
  }

  private fun DocumentBuilderFactory.setFeatureIfSupported(
    name: String,
    value: Boolean,
  ) {
    try {
      setFeature(name, value)
    } catch (e: ParserConfigurationException) {
      // Android's built-in XML parser doesn't recognize Xerces-specific hardening features
      // (unlike the JVM's Xerces used in unit tests) — the other hardening calls in parseXml
      // still apply on every platform regardless of this feature's support.
    }
  }

  private fun ZipFile.readEntryOrNull(path: String): String? {
    val entry = getEntry(path) ?: return null
    if (entry.size < 0 || entry.size > MAX_ENTRY_SIZE_BYTES) return null
    return getInputStream(entry).bufferedReader().use { it.readText() }
  }

  private fun ZipFile.readEntryBytesOrNull(path: String): ByteArray? {
    val entry = getEntry(path) ?: return null
    if (entry.size < 0 || entry.size > MAX_COVER_SIZE_BYTES) return null
    return getInputStream(entry).use { it.readBytes() }
  }
}
