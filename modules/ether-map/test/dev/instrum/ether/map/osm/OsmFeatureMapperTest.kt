package dev.instrum.ether.map.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OSM semantic interpretation: estimation precedence (explicit > derived > category), road
 * width/lift rules and the area classification inherited from F.O.G.O. */
class OsmFeatureMapperTest {

    private companion object {
        fun closedWay(id: Long, tags: Map<String, String>) = OsmWay(id, longArrayOf(1, 2, 3, 1), tags)

        fun openWay(id: Long, tags: Map<String, String>) = OsmWay(id, longArrayOf(1, 2, 3), tags)
    }

    @Test
    fun `estima altura de edificio por niveis teto e categoria`() {
        assertEquals(
            22.4,
            OsmFeatureMapper.estimateBuildingHeight(
                mapOf("building" to "yes", "building:levels" to "5", "roof:levels" to "2"),
            ),
            1e-9,
            "(níveis + teto) x 3.2",
        )
        assertEquals(
            15.8,
            OsmFeatureMapper.estimateBuildingHeight(
                mapOf("building" to "apartments", "building:levels" to "4", "roof:height" to "3"),
            ),
            1e-9,
            "niveis x 3.2 + roof:height",
        )
        assertEquals(
            7.5,
            OsmFeatureMapper.estimateBuildingHeight(mapOf("building" to "house", "height" to "7.5")),
            1e-9,
            "explícito vence derivado",
        )
        assertEquals(
            9.0,
            OsmFeatureMapper.estimateBuildingHeight(mapOf("building" to "yes")),
            1e-9,
            "categoria `yes` duração 9.0",
        )
        assertEquals(
            6.0,
            OsmFeatureMapper.estimateBuildingHeight(mapOf("building" to "house")),
            1e-9,
            "categoria casa",
        )
    }

    @Test
    fun `building rejeita anel aberto e limita altura`() {
        assertNull(OsmFeatureMapper.buildingOf(openWay(5, mapOf("building" to "yes"))))
        assertNull(OsmFeatureMapper.buildingOf(closedWay(6, mapOf("building" to "no"))))

        val clamped = OsmFeatureMapper.buildingOf(
            closedWay(7, mapOf("building" to "house", "height" to "0.5")),
        )!!
        assertEquals(
            2.5,
            clamped.heightMeters,
            1e-9,
            "altura trunca no piso do estilo",
        )
        val minHeight = OsmFeatureMapper.buildingOf(
            closedWay(8, mapOf("building" to "yes", "height" to "20", "min_height" to "4")),
        )!!
        assertEquals(4.0, minHeight.minHeightMeters!!, 1e-9)
    }

    @Test
    fun `estima largura de via com faixas c subtipos`() {
        assertEquals(
            3.0,
            OsmFeatureMapper.estimateRoadWidth(
                mapOf("highway" to "service", "service" to "alley"),
                OsmFeatureMapper.RoadType.Service,
            ),
            1e-9,
            "subtipo de serviço em beco",
        )
        assertEquals(
            12.8,
            OsmFeatureMapper.estimateRoadWidth(
                mapOf("highway" to "secondary", "lanes" to "4"),
                OsmFeatureMapper.RoadType.Secondary,
            ),
            1e-9,
            "faixas x 3.2 dentro da faixa da categoria",
        )
        assertEquals(
            14.5,
            OsmFeatureMapper.estimateRoadWidth(
                mapOf("highway" to "residential", "width" to "14.5"),
                OsmFeatureMapper.RoadType.Residential,
            ),
            1e-9,
            "tag width explícita vence",
        )
        // *_link inherits the base category.
        val ramp = OsmFeatureMapper.roadOf(
            openWay(9, mapOf("highway" to "primary_link")),
        )!!
        assertEquals(OsmFeatureMapper.RoadType.Primary, ramp.type)
    }

    @Test
    fun `lift de via usa layer bridge e tunnel`() {
        assertEquals(8.0, OsmFeatureMapper.roadOf(openWay(10, mapOf("highway" to "primary", "layer" to "2")))!!.liftMeters)
        assertEquals(4.0, OsmFeatureMapper.roadOf(openWay(11, mapOf("highway" to "primary", "bridge" to "yes")))!!.liftMeters)
        assertEquals(-4.0, OsmFeatureMapper.roadOf(openWay(12, mapOf("highway" to "primary", "tunnel" to "yes")))!!.liftMeters)
        assertEquals(0.0, OsmFeatureMapper.roadOf(openWay(13, mapOf("highway" to "primary")))!!.liftMeters)
    }

    @Test
    fun `areas classificam parque agua residencial e estacionamento`() {
        assertEquals(
            OsmFeatureMapper.LandKind.Park,
            OsmFeatureMapper.areaOf(closedWay(20, mapOf("leisure" to "park")))!!.kind,
        )
        assertEquals(
            OsmFeatureMapper.LandKind.Water,
            OsmFeatureMapper.areaOf(closedWay(21, mapOf("natural" to "water")))!!.kind,
        )
        assertEquals(
            OsmFeatureMapper.LandKind.Residential,
            OsmFeatureMapper.areaOf(closedWay(22, mapOf("landuse" to "residential")))!!.kind,
        )
        assertEquals(
            OsmFeatureMapper.LandKind.Parking,
            OsmFeatureMapper.areaOf(closedWay(23, mapOf("amenity" to "parking")))!!.kind,
        )
        assertNull(OsmFeatureMapper.areaOf(openWay(24, mapOf("leisure" to "park"))), "área exige anel fechado")
    }

    @Test
    fun `waterway classifica tipologia e abre anel`() {
        assertEquals(8.0, OsmFeatureMapper.waterwayOf(openWay(30, mapOf("waterway" to "river")))!!.widthMeters)
        assertEquals(3.0, OsmFeatureMapper.waterwayOf(openWay(31, mapOf("waterway" to "stream")))!!.widthMeters)
        assertEquals(6.0, OsmFeatureMapper.waterwayOf(openWay(32, mapOf("waterway" to "canal", "width" to "6")))!!.widthMeters)
        assertNull(OsmFeatureMapper.waterwayOf(openWay(33, mapOf("waterway" to "ditch"))), "tipologia não mapeada")
        assertNull(OsmFeatureMapper.waterwayOf(closedWay(34, mapOf("waterway" to "river"))), "rio fechado é riverbank (área)")
        assertTrue(OsmFeatureMapper.waterwayOf(openWay(30, mapOf("waterway" to "river")))!!.id.endsWith("30"))
    }
}
