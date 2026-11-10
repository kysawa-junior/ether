package dev.instrum.ether.map.scene

import dev.instrum.ether.map.TestScenes
import dev.instrum.ether.mapdata.FeatureCollection
import dev.instrum.ether.mapdata.FeatureProperties
import dev.instrum.ether.mapdata.FeatureRecord
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.mapdata.MapManifest
import dev.instrum.ether.mapdata.MapOrigin
import dev.instrum.ether.mapdata.MapPackage
import dev.instrum.ether.mapdata.TileReference
import dev.instrum.ether.mapdata.GeoBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Scene assembly from both sources (cartographic package and OSM store), inheriting the paint
 * order and the stats contract of the removed engine. */
class SceneTest {

    @Test
    fun `pacote cartografico gera cena com edificios e vias`() {
        val building = FeatureRecord(
            id = "b1",
            type = "building",
            geometryType = "Polygon",
            properties = FeatureProperties(featureId = "b1", featureType = "building", heightMeters = 12.0),
            rings = listOf(
                listOf(
                    GeoPoint(longitude = -49.9400, latitude = -22.2100),
                    GeoPoint(longitude = -49.9398, latitude = -22.2100),
                    GeoPoint(longitude = -49.9398, latitude = -22.2098),
                    GeoPoint(longitude = -49.9400, latitude = -22.2098),
                ),
            ),
        )
        val road = FeatureRecord(
            id = "r1",
            type = "road",
            geometryType = "LineString",
            properties = FeatureProperties(featureId = "r1", featureType = "road", widthMeters = 8.0),
            rings = listOf(
                listOf(
                    GeoPoint(longitude = -49.9400, latitude = -22.2100),
                    GeoPoint(longitude = -49.9390, latitude = -22.2100),
                ),
            ),
        )
        val packageModel = MapPackage(
            manifest = MapManifest(
                origin = MapOrigin(latitude = -22.2100, longitude = -49.9400),
                minLevel = 1,
                maxLevel = 2,
                tiles = emptyList(),
            ),
            features = FeatureCollection(features = listOf(building, road)),
        )
        assertTrue(packageModel.validate().valid)

        val scene = Scene.fromPackage(packageModel)
        assertEquals(1, scene.stats.buildings)
        assertEquals(1, scene.stats.roads)
        assertTrue(scene.stats.triangles > 0)
        assertEquals(2, scene.entities.size)
        assertTrue(scene.worldBounds.max.y > 10f, "12 m de prédio dominam o bounds")
    }

    @Test
    fun `pacote do manifesto embute cena para streaming`() {
        // Package with a manifest tile must not affect the scene build (visual geometry comes from
        // the rings; the GLB artifact is optional).
        val packageModel = MapPackage(
            manifest = MapManifest(
                origin = MapOrigin(latitude = -22.21, longitude = -49.94),
                minLevel = 1,
                maxLevel = 2,
                tiles = listOf(
                    TileReference(
                        id = "2/2/2",
                        level = 2,
                        column = 2,
                        row = 2,
                        bounds = GeoBounds(west = 0.0, south = 0.0, east = 1.0, north = 1.0),
                        featureFile = "tiles/2/2/2.json",
                    ),
                ),
            ),
            features = FeatureCollection(),
        )
        assertTrue(packageModel.validate().valid)
        val scene = Scene.fromPackage(packageModel)
        assertEquals(0, scene.entities.size)
        assertEquals(0, scene.stats.triangles)
    }

    @Test
    fun `store osm gera via area e entidades com bounds`() {
        val scene = TestScenes.sceneWithAreaAndRoad()
        assertEquals(6, scene.stats.nodeCount)
        assertEquals(2, scene.stats.wayCount)
        assertEquals(1, scene.stats.roads)
        assertEquals(1, scene.stats.areas)
        assertEquals(2, scene.entities.size)
        val kinds = scene.entities.map { it.kind }.toSet()
        assertTrue(SceneEntity.EntityKind.Road in kinds)
        assertTrue(SceneEntity.EntityKind.Area in kinds)
        assertTrue(scene.worldBounds.max.x > scene.worldBounds.min.x)
        assertTrue(scene.worldBounds.max.z > scene.worldBounds.min.z)
        // Interleaved layout: 6 floats per vertex (position xyz + color rgb).
        assertEquals(scene.stats.vertices * 6, scene.interleaved.size)
        assertTrue(scene.cities.isNotEmpty(), "catálogo regiona l está embutido no builder")
    }

    @Test
    fun `building fechiado vindora do mapper e extrudada com altura`() {
        val scene = TestScenes.sceneWithOneBuilding()
        assertEquals(1, scene.stats.buildings)
        assertEquals(12f, scene.worldBounds.max.y, 1e-3f, "altura informada no tag height")
        val building = scene.entities.first()
        assertEquals("way/20", building.id)
        assertEquals(SceneEntity.EntityKind.Building, building.kind)
    }
}
