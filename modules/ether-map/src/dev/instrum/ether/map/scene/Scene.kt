package dev.instrum.ether.map.scene

import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.mapdata.MapPackage
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3

/** Selectable scene entity (CPU raycast over its own mesh). */
public class SceneEntity(
    public val id: String,
    public val kind: EntityKind,
    public val label: String,
    public val trianglesWorld: FloatArray,
    public val indices: IntArray,
    public val bounds: Aabb,
) {
    public enum class EntityKind { Road, Building, Area, Waterway }
}

/** Regional catalog city anchor (camera + navigation). */
public data class CityAnchor(
    public val id: String,
    public val name: String,
    public val bounds: Aabb,
)

/** Import statistics shown in the UI. */
public data class SceneStats(
    public val nodeCount: Int,
    public val wayCount: Int,
    public val roads: Int,
    public val buildings: Int,
    public val areas: Int,
    public val waterways: Int,
    public val triangles: Int,
    public val vertices: Int,
    public val issues: Int,
)

/**
 * Upload-ready 3D scene: static interleaved buffers (position xyz + color rgb) plus indexed
 * entities for picking.
 */
public class Scene(
    public val interleaved: FloatArray,
    public val indices: IntArray,
    public val entities: List<SceneEntity>,
    public val worldBounds: Aabb,
    public val projector: LocalEnuTransform,
    public val cities: List<CityAnchor>,
    public val stats: SceneStats,
) {
    public companion object {
        /** Builds the scene from an already parsed OSM store. */
        public fun build(store: dev.instrum.ether.map.osm.OsmDataStore): Scene =
            SceneBuilder.build(store)

        /** Builds the scene from the `urban-map-package` v1 cartographic package. */
        public fun fromPackage(mapPackage: MapPackage): Scene = SceneBuilder.buildFromFeatures(
            mapPackage.features.features,
            dev.instrum.ether.mapdata.GeoPoint(
                mapPackage.manifest.origin.longitude,
                mapPackage.manifest.origin.latitude,
                mapPackage.manifest.origin.altitude,
            ),
        )
    }
}
