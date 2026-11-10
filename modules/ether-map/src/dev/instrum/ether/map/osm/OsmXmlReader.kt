package dev.instrum.ether.map.osm

/**
 * Streaming reader of `.osm` XML extracts into an [OsmDataStore], without building a DOM.
 *
 * Divergences from the removed JVM-only `OsmXmlReader`, documented for the port:
 * - **Input is `String` or UTF-8 `ByteArray` only.** Stream types (`InputStream`, `File`) and gzip
 *   auto-detection are JVM-only; decompressing `.osm.gz` is now the caller's responsibility.
 * - DTDs and external entities are never processed (the common parser has no support), which keeps
 *   the old "DTD off for security" behavior by construction.
 */
public object OsmXmlReader {
    public fun read(text: String): OsmDataStore {
        val store = OsmDataStore()
        val scanner = XmlScanner(text)
        var currentWayId = 0L
        var currentRefs = LongArray(64)
        var refCount = 0
        var currentTags: HashMap<String, String>? = null

        while (true) {
            val event = scanner.next() ?: break
            when (event) {
                is XmlEvent.Start -> when (event.name) {
                    "node" -> {
                        val id = event.attributes["id"]?.toLongOrNull()
                        val lat = event.attributes["lat"]?.toDoubleOrNull()
                        val lon = event.attributes["lon"]?.toDoubleOrNull()
                        if (id == null || lat == null || lon == null) continue
                        val tags = if (event.selfClosing) {
                            emptyMap()
                        } else {
                            readTagsUntilEnd(scanner, "node")
                        }
                        store.addNode(OsmNode(id, lat, lon, tags["ele"]?.toDoubleOrNull(), tags))
                    }
                    "way" -> {
                        currentWayId = event.attributes["id"]?.toLongOrNull() ?: continue
                        currentTags = HashMap()
                        refCount = 0
                    }
                    "nd" -> {
                        val ref = event.attributes["ref"]?.toLongOrNull() ?: continue
                        if (refCount == currentRefs.size) currentRefs = currentRefs.copyOf(currentRefs.size * 2)
                        currentRefs[refCount++] = ref
                    }
                    "tag" -> {
                        val key = event.attributes["k"] ?: continue
                        currentTags?.put(key, event.attributes["v"] ?: "")
                    }
                }
                is XmlEvent.End -> if (event.name == "way" && currentWayId != 0L) {
                    store.addWay(OsmWay(currentWayId, currentRefs.copyOf(refCount), currentTags ?: emptyMap()))
                    currentWayId = 0
                    currentTags = null
                    refCount = 0
                }
            }
        }
        return store
    }

    public fun read(bytes: ByteArray): OsmDataStore {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            throw IllegalArgumentException(
                "osm.gz recebido comprimido; descomprima antes de chamar OsmXmlReader (o parser comum não descomprime)",
            )
        }
        return read(bytes.decodeToString())
    }

    /** Consumes the children of an element until its END (nodes only contain tags). */
    private fun readTagsUntilEnd(scanner: XmlScanner, element: String): Map<String, String> {
        val tags = HashMap<String, String>()
        while (true) {
            val event = scanner.next() ?: break
            when (event) {
                is XmlEvent.Start -> if (event.name == "tag") {
                    // Self-closing <tag/> still carries the k/v attributes in its Start event.
                    val key = event.attributes["k"]
                    if (key != null) tags[key] = event.attributes["v"] ?: ""
                }
                is XmlEvent.End -> {
                    if (event.name == element) break
                }
            }
        }
        return tags
    }
}

/** Minimal SAX-like XML event. */
internal sealed interface XmlEvent {
    public data class Start(
        public val name: String,
        public val attributes: Map<String, String>,
        public val selfClosing: Boolean,
    ) : XmlEvent

    public data class End(public val name: String) : XmlEvent
}

/**
 * Single pass XML tag scanner: emits [XmlEvent.Start] (with self-closing flag) and
 * [XmlEvent.End]; character data is skipped. It is not a general XML parser - it is exactly as
 * strict as the OSM XML shape needs.
 */
internal class XmlScanner(private val text: String) {
    private var position = 0

    public fun next(): XmlEvent? {
        while (true) {
            val open = text.indexOf('<', position)
            if (open < 0) return null
            position = open + 1
            if (position >= text.length) return null
            when (text[position]) {
                '?' -> { // <?xml ... ?>
                    position = text.indexOf('>', position)
                    if (position < 0) return null
                    position++
                }
                '!' -> { // <!-- comment -->, <![CDATA[...]]> or <!DOCTYPE...>
                    position = skipSpecial()
                    if (position < 0) return null
                }
                '/' -> { // </name>
                    val close = text.indexOf('>', position)
                    if (close < 0) return null
                    val name = text.substring(position + 1, close).trim()
                    position = close + 1
                    if (name.isNotEmpty()) return XmlEvent.End(name)
                }
                else -> { // <name attr="value" ...> or <name ... />
                    val close = text.indexOf('>', position)
                    if (close < 0) return null
                    val raw = text.substring(position, close)
                    position = close + 1
                    val selfClosing = raw.endsWith("/")
                    val body = if (selfClosing) raw.removeSuffix("/") else raw
                    val name = body.substringBefore(' ').trim()
                    if (name.isEmpty()) continue // malformed tag; skip
                    val attributes = parseAttributes(body.removePrefix(name))
                    return XmlEvent.Start(name, attributes, selfClosing)
                }
            }
        }
    }

    /** Skips comments, CDATA blocks and DOCTYPE declarations; returns the position after them. */
    private fun skipSpecial(): Int {
        if (text.startsWith("<!--", position)) {
            val end = text.indexOf("-->", position)
            return if (end < 0) -1 else end + 3
        }
        if (text.startsWith("<![CDATA[", position)) {
            val end = text.indexOf("]]>", position)
            return if (end < 0) -1 else end + 3
        }
        // <!DOCTYPE ...> (internal subsets with [] are not expected in OSM extracts)
        val end = text.indexOf('>', position)
        return if (end < 0) -1 else end + 1
    }

    private fun parseAttributes(body: String): Map<String, String> {
        val attributes = HashMap<String, String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length) break
            val equals = body.indexOf('=', index)
            if (equals < 0) break
            val key = body.substring(index, equals).trim()
            var cursor = equals + 1
            while (cursor < body.length && body[cursor].isWhitespace()) cursor++
            if (cursor >= body.length) break
            val quote = body[cursor]
            if (quote != '"' && quote != '\'') break
            val valueEnd = body.indexOf(quote, cursor + 1)
            if (valueEnd < 0) break
            attributes[key] = decodeEntities(body.substring(cursor + 1, valueEnd))
            index = valueEnd + 1
        }
        return attributes
    }

    private fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '&') {
                out.append(char)
                index++
                continue
            }
            val semicolon = value.indexOf(';', index)
            if (semicolon < 0 || semicolon - index > 10) {
                out.append(char)
                index++
                continue
            }
            when (val entity = value.substring(index + 1, semicolon)) {
                "amp" -> out.append('&')
                "lt" -> out.append('<')
                "gt" -> out.append('>')
                "quot" -> out.append('"')
                "apos" -> out.append('\'')
                else -> {
                    if (entity.startsWith("#x") || entity.startsWith("#X")) {
                        entity.substring(2).toIntOrNull(16)?.let { code -> out.append(code.toChar()) }
                    } else if (entity.startsWith("#")) {
                        entity.substring(1).toIntOrNull()?.let { code -> out.append(code.toChar()) }
                    } else {
                        out.append('&').append(entity).append(';')
                        index = semicolon + 1
                        continue
                    }
                }
            }
            index = semicolon + 1
        }
        return out.toString()
    }
}
