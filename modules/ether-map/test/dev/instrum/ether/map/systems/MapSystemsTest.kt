package dev.instrum.ether.map.systems

import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.mapdata.FeatureProperties
import dev.instrum.ether.mapdata.FeatureRecord
import dev.instrum.ether.mapdata.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Terrain, buildings, roads, traffic and furniture built over locally projected features. */
class MapSystemsTest {

    private val transform = LocalEnuTransform(GeoPoint(longitude = -49.94, latitude = -22.21))

    @Test
    fun `heightmap 3x3 gera malha indexada`() {
        val heightmap = Heightmap(
            width = 3,
            height = 3,
            cellSizeMeters = 2f,
            elevations = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            origin = GeoPoint(longitude = -49.94, latitude = -22.21),
        )
        val mesh = TerrainSystem.generateMesh(heightmap)
        assertEquals(27, mesh.positions.size, "9 vértices xyz")
        assertEquals(24, mesh.indices.size, "2x2 células x 6 índices")
        assertEquals(8, mesh.indices.max())
        assertNotNull(mesh.normals)
        assertEquals(0f, mesh.bounds.min.y, 1e-6f)
        assertEquals(8f, mesh.bounds.max.y, 1e-6f)
        assertTrue(mesh.isValid)
    }

    @Test
    fun `sample de heightmap le a celula e satura na borda`() {
        val heightmap = Heightmap(
            width = 3,
            height = 3,
            cellSizeMeters = 2f,
            elevations = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f),
            origin = GeoPoint(longitude = -49.94, latitude = -22.21),
        )
        // 5 m east -> column 2; 1 m south -> row 0.
        assertEquals(2f, TerrainSystem.sample(heightmap, 5f, 1f))
        assertEquals(0f, TerrainSystem.sample(heightmap, 0.5f, 0.5f))
        assertEquals(8f, TerrainSystem.sample(heightmap, 100f, 100f), "fora do mapa satura na borda")
    }

    @Test
    fun `heightmap rejeita dimensoes invalidas`() {
        assertFailsWith<IllegalArgumentException> {
            Heightmap(1, 3, 1f, FloatArray(3), GeoPoint(-49.94, -22.21))
        }
        assertFailsWith<IllegalArgumentException> {
            Heightmap(3, 3, 0f, FloatArray(9), GeoPoint(-49.94, -22.21))
        }
        assertFailsWith<IllegalArgumentException> {
            Heightmap(3, 3, 1f, FloatArray(8), GeoPoint(-49.94, -22.21))
        }
        assertFailsWith<IllegalArgumentException> {
            Heightmap(3, 3, 1f, floatArrayOf(0f, 1f, Float.NaN, 0f, 1f, 2f, 0f, 1f, 2f), GeoPoint(-49.94, -22.21))
        }
    }

    @Test
    fun `sistema de vias classifica e extruda feature local`() {
        val road = FeatureRecord(
            id = "r1",
            type = "road",
            geometryType = "LineString",
            properties = FeatureProperties(
                featureId = "r1",
                featureType = "road",
                classification = "Primary",
            ),
            rings = listOf(
                listOf(
                    GeoPoint(-49.9405, -22.2105),
                    GeoPoint(-49.9400, -22.2100),
                ),
            ),
        )
        val instance = RoadSystem(transform).build(road)
        assertNotNull(instance)
        assertEquals(RoadClassification.Primary, instance.classification)
        assertEquals(10.0, instance.widthMeters, "largura padrão da categoria")
        assertTrue(instance.mesh.isValid)
        // The road pad floats FLAT_PAD_LIFT_METERS above the ground.
        val maxY = (0 until instance.mesh.vertexCount).maxOf { instance.mesh.positions[it * 3 + 1] }
        assertEquals(0.06f, maxY, 1e-5f)

        // Explicit width and unclassified fallback.
        val narrow = RoadSystem(transform).build(
            road.copy(
                id = "r2",
                properties = FeatureProperties(featureId = "r2", featureType = "road", widthMeters = 3.5),
            ),
        )!!
        assertEquals(RoadClassification.Residential, narrow.classification, "sem classificação: Residential")
        assertEquals(3.5, narrow.widthMeters)
    }

    @Test
    fun `sistema de predios extruda com altura da feature`() {
        val building = FeatureRecord(
            id = "b1",
            type = "building",
            geometryType = "Polygon",
            properties = FeatureProperties(
                featureId = "b1",
                featureType = "building",
                heightMeters = 12.0,
            ),
            rings = listOf(
                listOf(
                    GeoPoint(-49.9400, -22.2100),
                    GeoPoint(-49.9391, -22.2100),
                    GeoPoint(-49.9391, -22.2091),
                    GeoPoint(-49.9400, -22.2091),
                    GeoPoint(-49.9400, -22.2100),
                ),
            ),
        )
        val instance = BuildingSystem(transform).build(building, level = 2)
        assertNotNull(instance)
        assertEquals("b1", instance.featureId)
        assertEquals(2, instance.level)
        assertEquals(12f, instance.heightMeters)
        val maxY = (0 until instance.mesh.vertexCount).maxOf { instance.mesh.positions[it * 3 + 1] }
        assertEquals(12f, maxY, 1e-4f)
        val minY = (0 until instance.mesh.vertexCount).minOf { instance.mesh.positions[it * 3 + 1] }
        assertEquals(0f, minY, 1e-5f)
        // The footprint straddles the ENU origin (first node = projection origin).
        val xs = (0 until instance.mesh.vertexCount).map { instance.mesh.positions[it * 3] }
        assertTrue(abs(xs.min()) < 1f, "anel projetado perto da origem: $xs")
        assertTrue(instance.mesh.isValid)

        // No height tag: category default.
        val defaultHeight = BuildingSystem(transform).build(
            building.copy(id = "b2", properties = FeatureProperties(featureId = "b2", featureType = "building")),
        )!!
        assertEquals(8.0f, defaultHeight.heightMeters, 1e-6f)

        // Degenerate ring: no mesh.
        assertNull(
            BuildingSystem(transform).build(
                building.copy(id = "b3", rings = listOf(emptyList())),
            ),
        )
    }

    @Test
    fun `sistema de trafico e mobiliario le propriedades`() {
        val traffic = FeatureRecord(
            id = "t1",
            type = "TrafficLight",
            geometryType = "Point",
            properties = FeatureProperties(
                featureId = "t1",
                featureType = "TrafficLight",
                extra = mapOf("rotationRadians" to "1.5"),
            ),
            rings = listOf(listOf(GeoPoint(-49.9400, -22.2100))),
        )
        val element = TrafficSystem(transform).build(traffic)!!
        assertEquals(TrafficElementType.TrafficLight, element.type)
        assertEquals(1.5f, element.rotationRadians)
        assertEquals(3.0f, element.heightMeters, "altura padrão de poste")
        assertEquals(0f, element.position.x, 1e-4f, "primeiro node cai na origem")

        val tree = FeatureRecord(
            id = "f1",
            type = "Tree",
            geometryType = "Point",
            properties = FeatureProperties(
                featureId = "f1",
                featureType = "Tree",
                extra = mapOf("scale" to "2.5"),
            ),
            rings = listOf(listOf(GeoPoint(-49.9395, -22.2095))),
        )
        val furniture = UrbanEnvironmentSystem(transform).build(tree)!!
        assertEquals(UrbanFurnitureType.Tree, furniture.type)
        assertEquals(2.5f, furniture.scale)

        // Unknown types fall back and unknown point features return nothing.
        assertEquals(
            TrafficElementType.TrafficSign,
            TrafficSystem(transform).build(
                traffic.copy(id = "t2", type = "weird", properties = FeatureProperties("t2", "weird")),
            )!!.type,
        )
        assertNull(
            UrbanEnvironmentSystem(transform).build(
                tree.copy(id = "f2", rings = listOf(emptyList())),
            ),
        )
    }
}
