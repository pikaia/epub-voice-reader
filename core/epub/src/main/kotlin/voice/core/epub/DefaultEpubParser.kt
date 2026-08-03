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

@ContributesBinding(AppScope::class)
class DefaultEpubParser : EpubParser {

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
    val spineHrefs = parseSpineHrefs(opfXml)
      ?: return EpubParseResult.Malformed("could not resolve manifest/spine")

    val chapters = spineHrefs.mapIndexed { index, href ->
      val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
      val html = zip.readEntryOrNull(path)
        ?: return EpubParseResult.Malformed("missing chapter file at $path")
      parseChapter(html, fallbackTitle = "Chapter ${index + 1}")
    }
    return EpubParseResult.Success(ParsedBook(chapters))
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

  private fun parseSpineHrefs(opfXml: String): List<String>? {
    val document = parseXml(opfXml) ?: return null

    val manifest = mutableMapOf<String, String>()
    val items = document.getElementsByTagName("item")
    for (i in 0 until items.length) {
      val item = items.item(i) as? Element ?: continue
      val id = item.getAttribute("id")
      val href = item.getAttribute("href")
      if (id.isNotBlank() && href.isNotBlank()) {
        manifest[id] = href
      }
    }

    val spine = mutableListOf<String>()
    val itemRefs = document.getElementsByTagName("itemref")
    for (i in 0 until itemRefs.length) {
      val itemRef = itemRefs.item(i) as? Element ?: continue
      val href = manifest[itemRef.getAttribute("idref")] ?: return null
      spine += href
    }
    return spine.ifEmpty { null }
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
}
