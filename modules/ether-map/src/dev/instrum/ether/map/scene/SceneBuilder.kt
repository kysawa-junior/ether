package dev.instrum.ether.map.scene

import dev.instrum.ether.mapdata.GeoBounds
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.map.mesh.BuildingMeshBuilder
import dev.instrum.ether.map.mesh.MeshData
import dev.instrum.ether.map.mesh.RoadMeshBuilder
import dev.instrum.ether.map.mesh.Style
import dev.instrum.ether.map.osm.OsmDataStore
import dev.instrum.ether.map.osm.OsmFeatureMapper
import dev.instrum.ether.mapdata.FeatureRecord
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3
import kotlin.math.abs

/**
 * 3D scene construction: areas -> water -> roads -> buildings (paint order), triangulation and
 * buffer aggregation.
 *
 * Two sources are supported: an OSM store (compatibility with the packaged extract) and the
 * features of the `urban-map-package` v1 cartographic package shared with the web runtime.
 */
public object SceneBuilder {
    /** Regional catalog: three cities (generous urban bboxes). */
    private val CITY_GEO: List<Triple<String, String, GeoBounds>> = listOf(
        Triple("marilia", "Marília-SP", GeoBounds(-49.975, -22.235, -49.925, -22.200)),
        Triple("veracruz", "Vera Cruz-SP", GeoBounds(-49.849, -22.236, -49.799, -22.206)),
        Triple("garca", "Garça-SP", GeoBounds(-49.684, -22.245, -49.634, -22.210)),
    )

    public fun build(store: OsmDataStore): Scene {
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        var any = false
        for (way in store.allWays()) {
            for (point in store.wayCoordinates(way)) {
                any = true
                if (point.lat < minLat) minLat = point.lat
                if (point.lat > maxLat) maxLat = point.lat
                if (point.lon < minLon) minLon = point.lon
                if (point.lon > maxLon) maxLon = point.lon
            }
        }
        if (!any) {
            minLat = 0.0; maxLat = 0.0; minLon = 0.0; maxLon = 0.0
        }
        val projector = LocalEnuTransform(GeoPoint((minLon + maxLon) / 2, (minLat + maxLat) / 2))

        val assembler = SceneAssembler()
        var roadCount = 0
        var buildingCount = 0
        var areaCount = 0
        var waterwayCount = 0

        // 1. Areas first (background); 2. water; 3. roads; 4. buildings.
        for (way in store.allWays()) {
            val area = OsmFeatureMapper.areaOf(way) ?: continue
            val coordinates = store.wayCoordinates(way)
            if (coordinates.size < 4) continue
            val ring = coordinates.map { projector.forward(GeoPoint(it.lon, it.lat, it.elev ?: 0.0)) }
            val color = area.kind
            val flat = flatPolygon(ring, emptyList(), color.r.toFloat(), color.g.toFloat(), color.b.toFloat(), -0.02f)
            if (flat != null) {
                assembler.addMesh(flat, area.id, SceneEntity.EntityKind.Area, area.kind.name.lowercase())
                areaCount++
            }
        }
        for (way in store.allWays()) {
            val water = OsmFeatureMapper.waterwayOf(way) ?: continue
            val coordinates = store.wayCoordinates(way)
            if (coordinates.size < 2) continue
            val line = coordinates.map { projector.forward(GeoPoint(it.lon, it.lat, it.elev ?: 0.0)) }
            val mesh = RoadMeshBuilder.build(line, water.widthMeters.toFloat(), -0.01f)
            if (mesh != null) {
                paint(mesh, 0.06f, 0.10f, 0.18f)
                assembler.addMesh(mesh, water.id, SceneEntity.EntityKind.Waterway, "waterway")
                waterwayCount++
            }
        }
        for (way in store.allWays()) {
            val road = OsmFeatureMapper.roadOf(way) ?: continue
            val coordinates = store.wayCoordinates(way)
            if (coordinates.size < 2) continue
            val line = coordinates.map { projector.forward(GeoPoint(it.lon, it.lat, it.elev ?: 0.0)) }
            val mesh = RoadMeshBuilder.build(line, road.widthMeters.toFloat(), road.liftMeters.toFloat()) ?: continue
            val color = Palette.road()
            paint(mesh, color[0], color[1], color[2])
            assembler.addMesh(mesh, road.id, SceneEntity.EntityKind.Road, road.name.ifEmpty { "via" })
            roadCount++
        }
        for (way in store.allWays()) {
            val building = OsmFeatureMapper.buildingOf(way) ?: continue
            val coordinates = store.wayCoordinates(way)
            if (coordinates.size < 4) continue
            val ring = coordinates.map { projector.forward(GeoPoint(it.lon, it.lat, it.elev ?: 0.0)) }
            val color = Palette.BUILDING
            val mesh = BuildingMeshBuilder.build(
                listOf(ring),
                building.heightMeters.toFloat(),
                building.minHeightMeters?.toFloat(),
                color[0], color[1], color[2],
            ) ?: continue
            assembler.addMesh(mesh, building.id, SceneEntity.EntityKind.Building, buildingLabel(building))
            buildingCount++
        }

        val citiesFixed = CITY_GEO.map { (id, name, bounds) ->
            val sw = projector.forward(GeoPoint(bounds.west, bounds.south))
            val ne = projector.forward(GeoPoint(bounds.east, bounds.north))
            CityAnchor(
                id, name,
                Aabb(
                    min = Vec3(minOf(sw.x, ne.x), -10f, minOf(sw.z, ne.z)),
                    max = Vec3(maxOf(sw.x, ne.x), 300f, maxOf(sw.z, ne.z)),
                ),
            )
        }

        val stats = SceneStats(
            nodeCount = store.nodeCount,
            wayCount = store.wayCount,
            roads = roadCount,
            buildings = buildingCount,
            areas = areaCount,
            waterways = waterwayCount,
            triangles = assembler.triangleCount,
            vertices = assembler.vertexCount,
            issues = store.issues.size,
        )
        return assembler.toScene(projector, citiesFixed, stats)
    }

    /**
     * Scene from the cartographic package (`urban-map-package` v1) features. The semantic
     * geometry comes from the geographic rings; the Blender GLB is visual geometry only and is
     * not required here.
     */
    public fun buildFromFeatures(features: List<FeatureRecord>, origin: GeoPoint): Scene {
        val projector = LocalEnuTransform(origin)
        val assembler = SceneAssembler()
        var roadCount = 0
        var buildingCount = 0
        var areaCount = 0
        var waterwayCount = 0

        for (feature in features) {
            val kind = feature.type.lowercase()
            val rings = feature.rings.map { ring ->
                ring.map { projector.forward(it) }
            }
            when {
                kind.contains("building") || kind.contains("predio") || kind.contains("edif") -> {
                    if (rings.isEmpty() || rings.first().size < 4) continue
                    val height = (feature.properties.heightMeters ?: 8.0).toFloat()
                    val color = Palette.BUILDING
                    val mesh = BuildingMeshBuilder.build(
                        rings,
                        height,
                        feature.properties.extra["baseHeightMeters"]?.toDoubleOrNull()?.toFloat(),
                        color[0], color[1], color[2],
                    ) ?: continue
                    assembler.addMesh(mesh, feature.id, SceneEntity.EntityKind.Building, feature.properties.name ?: "edifício")
                    buildingCount++
                }
                kind.contains("water") -> {
                    val line = rings.firstOrNull()?.takeIf { it.size >= 2 } ?: continue
                    val mesh = RoadMeshBuilder.build(line, (feature.properties.widthMeters ?: 8.0).toFloat(), -0.01f) ?: continue
                    paint(mesh, 0.06f, 0.10f, 0.18f)
                    assembler.addMesh(mesh, feature.id, SceneEntity.EntityKind.Waterway, "waterway")
                    waterwayCount++
                }
                kind.contains("road") || kind.contains("highway") || kind.contains("street") || kind.contains("via") -> {
                    val line = rings.firstOrNull()?.takeIf { it.size >= 2 } ?: continue
                    val mesh = RoadMeshBuilder.build(line, (feature.properties.widthMeters ?: 6.0).toFloat(), Style.FLAT_PAD_LIFT_METERS) ?: continue
                    val color = Palette.road()
                    paint(mesh, color[0], color[1], color[2])
                    assembler.addMesh(mesh, feature.id, SceneEntity.EntityKind.Road, feature.properties.name ?: "via")
                    roadCount++
                }
                else -> {
                    if (rings.isEmpty() || rings.first().size < 4) continue
                    val flat = flatPolygon(rings.first(), rings.drop(1), 0.10f, 0.16f, 0.12f, -0.02f) ?: continue
                    assembler.addMesh(flat, feature.id, SceneEntity.EntityKind.Area, kind)
                    areaCount++
                }
            }
        }

        val stats = SceneStats(
            nodeCount = 0,
            wayCount = features.size,
            roads = roadCount,
            buildings = buildingCount,
            areas = areaCount,
            waterways = waterwayCount,
            triangles = assembler.triangleCount,
            vertices = assembler.vertexCount,
            issues = 0,
        )
        return assembler.toScene(projector, emptyList(), stats)
    }

    /** Readable building label (category + height). */
    private fun buildingLabel(building: OsmFeatureMapper.BuildingFeature): String {
        val height = building.heightMeters
        val formatted = if (height >= 10) {
            height.toInt().toString()
        } else {
            (kotlin.math.round(height * 10) / 10).toString()
        }
        return "edifício $formatted m"
    }

    private fun paint(mesh: MeshData, r: Float, g: Float, b: Float) {
        for (vertex in 0 until mesh.vertexCount) {
            if (mesh.colors[vertex * 3] == 0f && mesh.colors[vertex * 3 + 1] == 0f && mesh.colors[vertex * 3 + 2] == 0f) {
                mesh.colors[vertex * 3] = r
                mesh.colors[vertex * 3 + 1] = g
                mesh.colors[vertex * 3 + 2] = b
            }
        }
    }

    private fun flatPolygon(
        ring: List<Vec3>,
        holes: List<List<Vec3>>,
        r: Float,
        g: Float,
        b: Float,
        yLift: Float,
    ): MeshData? {
        val deduped = ArrayList<Vec3>(ring.size)
        for (point in ring) {
            val last = deduped.lastOrNull()
            if (last != null && abs(last.x - point.x) < 1e-6f && abs(last.z - point.z) < 1e-6f) continue
            deduped.add(point)
        }
        if (deduped.size > 2) {
            val first = deduped.first()
            val last = deduped.last()
            if (abs(first.x - last.x) < 1e-6f && abs(first.z - last.z) < 1e-6f) deduped.removeAt(deduped.size - 1)
        }
        if (deduped.size < 3) return null
        val triangulation = dev.instrum.ether.map.geom.PolygonTriangulator.triangulate(
            deduped.map { Vec3(it.x, 0f, it.z) },
            holes.map { hole -> hole.map { Vec3(it.x, 0f, it.z) } },
        ) ?: return null
        val positions = FloatArray(triangulation.vertices.size * 3)
        val colors = FloatArray(triangulation.vertices.size * 3)
        for (vertex in triangulation.vertices.indices) {
            positions[vertex * 3] = triangulation.vertices[vertex].x
            positions[vertex * 3 + 1] = yLift + Style.FLAT_PAD_LIFT_METERS
            positions[vertex * 3 + 2] = triangulation.vertices[vertex].z
            colors[vertex * 3] = r; colors[vertex * 3 + 1] = g; colors[vertex * 3 + 2] = b
        }
        return MeshData(positions, colors, triangulation.triangles)
    }
}

/** Shared buffer/entity accumulator used by both scene sources. */
private class SceneAssembler {
    private val positions = ArrayList<Float>(1 shl 16)
    private val indices = ArrayList<Int>(1 shl 16)
    private val entities = ArrayList<SceneEntity>(1 shl 12)

    val triangleCount: Int
        get() = indices.size / 3

    val vertexCount: Int
        get() = positions.size / 6

    fun addMesh(mesh: MeshData, id: String, kind: SceneEntity.EntityKind, label: String) {
        if (!mesh.isValid || mesh.triangleCount == 0) return
        val base = positions.size / 6
        val trianglesWorld = FloatArray(mesh.vertexCount * 3)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (vertex in 0 until mesh.vertexCount) {
            val x = mesh.positions[vertex * 3]
            val y = mesh.positions[vertex * 3 + 1]
            val z = mesh.positions[vertex * 3 + 2]
            positions.add(x); positions.add(y); positions.add(z)
            positions.add(mesh.colors[vertex * 3]); positions.add(mesh.colors[vertex * 3 + 1]); positions.add(mesh.colors[vertex * 3 + 2])
            trianglesWorld[vertex * 3] = x; trianglesWorld[vertex * 3 + 1] = y; trianglesWorld[vertex * 3 + 2] = z
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
        }
        for (index in mesh.indices) indices.add(index + base)
        entities.add(
            SceneEntity(
                id, kind, label, trianglesWorld, mesh.indices.copyOf(),
                Aabb(min = Vec3(minX, minY, minZ), max = Vec3(maxX, maxY, maxZ)),
            ),
        )
    }

    fun toScene(projector: LocalEnuTransform, cities: List<CityAnchor>, stats: SceneStats): Scene {
        val interleaved = FloatArray(positions.size) { positions[it] }
        val sceneIndices = IntArray(indices.size) { indices[it] }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        if (entities.isEmpty()) {
            minX = 0f; minY = 0f; minZ = 0f; maxX = 0f; maxY = 0f; maxZ = 0f
        } else {
            for (entity in entities) {
                val bounds = entity.bounds
                minX = minOf(minX, bounds.min.x)
                maxX = maxOf(maxX, bounds.max.x)
                minY = minOf(minY, bounds.min.y)
                maxY = maxOf(maxY, bounds.max.y)
                minZ = minOf(minZ, bounds.min.z)
                maxZ = maxOf(maxZ, bounds.max.z)
            }
        }
        val sceneBounds = Aabb(min = Vec3(minX, minY, minZ), max = Vec3(maxX, maxY, maxZ))
        return Scene(interleaved, sceneIndices, entities, sceneBounds, projector, cities, stats)
    }
}
