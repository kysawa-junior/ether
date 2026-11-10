package dev.instrum.ether.map

import dev.instrum.ether.map.osm.OsmDataStore
import dev.instrum.ether.map.osm.OsmNode
import dev.instrum.ether.map.osm.OsmWay
import dev.instrum.ether.map.scene.Scene

/** Compact OSM-fixtures shared by the scene, runtime and renderer tests. */
internal object TestScenes {

    /** One straight 20 m wide-guess residential road going roughly north for ~220 m. */
    internal fun storeWithOneRoad(): OsmDataStore {
        val store = OsmDataStore()
        store.addNode(OsmNode(id = 1, lat = -22.2105, lon = -49.9405, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 2, lat = -22.2085, lon = -49.9405, elevation = null, tags = emptyMap()))
        store.addWay(
            OsmWay(id = 10, nodeRefs = longArrayOf(1, 2), tags = mapOf("highway" to "residential")),
        )
        return store
    }

    internal fun sceneWithOneRoad(): Scene = Scene.build(storeWithOneRoad())

    /** Closed 80 m x 80 m building footprint with an explicit 12 m height. */
    internal fun storeWithOneBuilding(): OsmDataStore {
        val store = OsmDataStore()
        val ring = longArrayOf(1, 2, 3, 4, 1)
        store.addNode(OsmNode(id = 1, lat = -22.2100, lon = -49.9400, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 2, lat = -22.2100, lon = -49.9392, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 3, lat = -22.2092, lon = -49.9392, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 4, lat = -22.2092, lon = -49.9400, elevation = null, tags = emptyMap()))
        store.addWay(
            OsmWay(
                id = 20,
                nodeRefs = ring,
                tags = mapOf("building" to "yes", "height" to "12"),
            ),
        )
        return store
    }

    internal fun sceneWithOneBuilding(): Scene = Scene.build(storeWithOneBuilding())

    /** Park and road straddling the projection origin so the bounds center hits both. */
    internal fun storeWithAreaAndRoad(): OsmDataStore {
        val store = OsmDataStore()
        store.addNode(OsmNode(id = 1, lat = -22.2095, lon = -49.9405, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 2, lat = -22.2095, lon = -49.9390, elevation = null, tags = emptyMap()))
        store.addWay(
            OsmWay(id = 10, nodeRefs = longArrayOf(1, 2), tags = mapOf("highway" to "residential")),
        )
        store.addNode(OsmNode(id = 5, lat = -22.2096, lon = -49.9399, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 6, lat = -22.2096, lon = -49.9393, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 7, lat = -22.2094, lon = -49.9393, elevation = null, tags = emptyMap()))
        store.addNode(OsmNode(id = 8, lat = -22.2094, lon = -49.9399, elevation = null, tags = emptyMap()))
        store.addWay(
            OsmWay(
                id = 30,
                nodeRefs = longArrayOf(5, 6, 7, 8, 5),
                tags = mapOf("leisure" to "park"),
            ),
        )
        return store
    }

    internal fun sceneWithAreaAndRoad(): Scene = Scene.build(storeWithAreaAndRoad())
}
