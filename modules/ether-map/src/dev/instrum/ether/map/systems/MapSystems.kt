package dev.instrum.ether.map.systems

import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.map.geometry.Mesh
import dev.instrum.ether.map.geometry.MeshProcessor
import dev.instrum.ether.map.mesh.BuildingMeshBuilder
import dev.instrum.ether.map.mesh.RoadMeshBuilder
import dev.instrum.ether.map.mesh.Style
import dev.instrum.ether.mapdata.FeatureRecord
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Vec3

/** Terrain: regular heightmap in meters, converted into a local mesh. */
public data class Heightmap(
    public val width: Int,
    public val height: Int,
    public val cellSizeMeters: Float,
    public val elevations: FloatArray,
    public val origin: GeoPoint,
) {
    init {
        require(width >= 2 && height >= 2) { "heightmap requer pelo menos 2x2 células" }
        require(cellSizeMeters > 0f) { "cellSizeMeters inválido" }
        require(elevations.size == width * height) { "dimensões do heightmap não coincidem" }
        require(elevations.all { it.isFinite() }) { "heightmap contém elevação não finita" }
    }

    public fun elevation(column: Int, row: Int): Float = elevations[row * width + column]

    override fun equals(other: Any?): Boolean =
        other is Heightmap &&
            width == other.width &&
            height == other.height &&
            cellSizeMeters == other.cellSizeMeters &&
            elevations.contentEquals(other.elevations) &&
            origin == other.origin

    override fun hashCode(): Int = 31 * (31 * width + height) + elevations.contentHashCode()
}

public object TerrainSystem {
    public fun generateMesh(heightmap: Heightmap): Mesh {
        val positions = FloatArray(heightmap.width * heightmap.height * 3)
        for (row in 0 until heightmap.height) {
            for (column in 0 until heightmap.width) {
                val index = (row * heightmap.width + column) * 3
                positions[index] = column * heightmap.cellSizeMeters
                positions[index + 1] = heightmap.elevations[row * heightmap.width + column]
                positions[index + 2] = row * heightmap.cellSizeMeters
            }
        }
        val indices = IntArray((heightmap.width - 1) * (heightmap.height - 1) * 6)
        var target = 0
        for (row in 0 until heightmap.height - 1) {
            for (column in 0 until heightmap.width - 1) {
                val a = row * heightmap.width + column
                val b = a + 1
                val c = a + heightmap.width
                val d = c + 1
                indices[target++] = a; indices[target++] = c; indices[target++] = b
                indices[target++] = b; indices[target++] = c; indices[target++] = d
            }
        }
        return meshFrom(positions, indices, generateNormals = true)
    }

    public fun sample(heightmap: Heightmap, xMeters: Float, zMeters: Float): Float {
        val column = (xMeters / heightmap.cellSizeMeters).toInt().coerceIn(0, heightmap.width - 1)
        val row = (zMeters / heightmap.cellSizeMeters).toInt().coerceIn(0, heightmap.height - 1)
        return heightmap.elevation(column, row)
    }
}

public data class BuildingInstance(
    public val featureId: String,
    public val mesh: Mesh,
    public val heightMeters: Float,
    public val level: Int,
)

public class BuildingSystem(private val transform: LocalEnuTransform) {
    public fun build(feature: FeatureRecord, level: Int = 0): BuildingInstance? {
        if (feature.rings.isEmpty()) return null
        val rings = feature.rings.map { ring -> ring.map { transform.forward(it) } }
        val height = (feature.properties.heightMeters ?: 8.0).toFloat()
        val meshData = BuildingMeshBuilder.build(
            rings, height, feature.properties.extra["baseHeightMeters"]?.toDoubleOrNull()?.toFloat(),
            0.72f, 0.74f, 0.78f,
        ) ?: return null
        return BuildingInstance(feature.id, meshFrom(meshData), height, level)
    }
}

public enum class RoadClassification(public val defaultWidthMeters: Double) {
    Motorway(14.0), Trunk(12.0), Primary(10.0), Secondary(8.0), Residential(6.0), Service(4.0), Path(2.0),
}

public data class RoadInstance(
    public val featureId: String,
    public val mesh: Mesh,
    public val classification: RoadClassification,
    public val widthMeters: Double,
)

public class RoadSystem(private val transform: LocalEnuTransform) {
    public fun build(feature: FeatureRecord): RoadInstance? {
        val line = feature.rings.firstOrNull()?.map { transform.forward(it) } ?: return null
        val classification = RoadClassification.entries.firstOrNull {
            it.name.equals(feature.properties.classification, ignoreCase = true)
        } ?: RoadClassification.Residential
        val width = feature.properties.widthMeters ?: classification.defaultWidthMeters
        val meshData = RoadMeshBuilder.build(line, width.toFloat(), Style.FLAT_PAD_LIFT_METERS) ?: return null
        return RoadInstance(feature.id, meshFrom(meshData), classification, width)
    }
}

public enum class TrafficElementType { TrafficLight, TrafficSign, Pole, Crosswalk, Marking }

public data class TrafficElement(
    public val id: String,
    public val type: TrafficElementType,
    public val position: Vec3,
    public val rotationRadians: Float,
    public val widthMeters: Float,
    public val heightMeters: Float,
)

public class TrafficSystem(private val transform: LocalEnuTransform) {
    public fun build(feature: FeatureRecord): TrafficElement? {
        val point = feature.rings.firstOrNull()?.firstOrNull() ?: return null
        val type = TrafficElementType.entries.firstOrNull { it.name.equals(feature.type, ignoreCase = true) }
            ?: TrafficElementType.TrafficSign
        return TrafficElement(
            id = feature.id,
            type = type,
            position = transform.forward(point),
            rotationRadians = feature.properties.extra["rotationRadians"]?.toDoubleOrNull()?.toFloat() ?: 0f,
            widthMeters = (feature.properties.widthMeters ?: if (type == TrafficElementType.Crosswalk) 4.0 else 0.2).toFloat(),
            heightMeters = (feature.properties.heightMeters ?: if (type == TrafficElementType.Crosswalk) 0.02 else 3.0).toFloat(),
        )
    }
}

public enum class UrbanFurnitureType { Tree, Vegetation, StreetLight, Bench, BusStop }

public data class UrbanFurniture(
    public val id: String,
    public val type: UrbanFurnitureType,
    public val position: Vec3,
    public val scale: Float = 1f,
)

public class UrbanEnvironmentSystem(private val transform: LocalEnuTransform) {
    public fun build(feature: FeatureRecord): UrbanFurniture? {
        val point = feature.rings.firstOrNull()?.firstOrNull() ?: return null
        val type = UrbanFurnitureType.entries.firstOrNull { it.name.equals(feature.type, ignoreCase = true) }
            ?: UrbanFurnitureType.Vegetation
        return UrbanFurniture(
            feature.id,
            type,
            transform.forward(point),
            feature.properties.extra["scale"]?.toDoubleOrNull()?.toFloat() ?: 1f,
        )
    }
}

/** Builds a [Mesh] from raw positions with area weighted normals and computed bounds. */
internal fun meshFrom(
    positions: FloatArray,
    indices: IntArray,
    generateNormals: Boolean = true,
): Mesh {
    val normals = if (generateNormals) MeshProcessor.generateNormals(positions, indices) else null
    return Mesh(positions = positions, indices = indices, normals = normals)
}

/** Builds a [Mesh] from a [dev.instrum.ether.map.mesh.MeshData] (drops colors; the map pipeline has them per vertex already baked). */
internal fun meshFrom(meshData: dev.instrum.ether.map.mesh.MeshData): Mesh =
    meshFrom(meshData.positions, meshData.indices)
