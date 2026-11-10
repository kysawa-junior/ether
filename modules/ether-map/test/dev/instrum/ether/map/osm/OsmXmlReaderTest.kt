package dev.instrum.ether.map.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Streaming XML reader contracts. Divergences documented against the removed JVM-only reader:
 * String/UTF-8 ByteArray inputs only, and `.osm.gz` rejection with an explicit message (the
 * caller decompresses now).
 */
class OsmXmlReaderTest {

    private companion object {
        val SAMPLE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE osm SYSTEM "osm.dtd">
            <osm version="0.6">
             <!-- <node id="99" lat="0.0" lon="0.0"/> -->
             <node id="1" lat="52.0" lon="13.0"/>
             <node id="2" lat="52.001" lon="13.001">
              <tag k="name" v="A&amp;B"/>
             </node>
             <node id="3" lat="52.002" lon="13.0">
              <tag k="ele" v="34"/>
              <tag k="payment" v="&#65;&#x42;"/>
             </node>
             <way id="10">
              <nd ref="1"/>
              <nd ref="2"/>
              <nd ref="3"/>
              <nd ref="1"/>
              <tag k="building" v="yes"/>
             </way>
             <way id="20">
              <nd ref="4"/>
              <nd ref="5"/>
              <tag k="leisure" v="park"/>
              <tag k="unknown-entity" v="A&amp;nbsp;B">
             </way>
            </osm>
        """.trimIndent()
    }

    @Test
    fun `le nodes ways tags e anel fechado`() {
        val store = OsmXmlReader.read(SAMPLE)
        assertEquals(3, store.nodeCount)
        assertEquals(2, store.wayCount)
        assertEquals(34.0, store.nodeById(3)!!.elevation)
        assertNull(store.nodeById(1)!!.elevation)
        assertEquals("A&B", store.nodeById(2)!!.tags["name"])

        val closed = store.allWays().first { it.id == 10L }
        assertTrue(closed.isClosed, "ref(s) primeiro == último fecha o anel")
        assertEquals(4, store.wayCoordinates(closed).size)
        assertEquals("yes", closed.tags["building"])

        // Nodes missing from the file become issues, not crashes.
        val broken = store.allWays().first { it.id == 20L }
        assertEquals(0, store.wayCoordinates(broken).size)
    }

    @Test
    fun `ele e tags numericos decodificam entidades`() {
        val store = OsmXmlReader.read(SAMPLE)
        assertEquals("AB", store.nodeById(3)!!.tags["payment"], "decimal e hex entities")
        // Unknown entities survive intact (not silently dropped).
        val broken = store.allWays().first { it.id == 20L }
        assertEquals("A&nbsp;B", broken.tags["unknown-entity"])
    }

    @Test
    fun `rejeita gzip pedindo descompressao do chamador`() {
        val gzipMagic = byteArrayOf(0x1f.toByte(), 0x8b.toByte(), 0x08.toByte(), 0x00.toByte())
        val error = assertFailsWith<IllegalArgumentException> { OsmXmlReader.read(gzipMagic) }
        assertTrue(error.message!!.contains("descomprima"), "mensagem orienta a descompressão")
    }

    @Test
    fun `bytes utf8 e texto produzem stores iguais`() {
        val fromText = OsmXmlReader.read(SAMPLE)
        val fromBytes = OsmXmlReader.read(SAMPLE.encodeToByteArray())
        assertEquals(fromText.nodeCount, fromBytes.nodeCount)
        assertEquals(fromText.wayCount, fromBytes.wayCount)
    }
}
