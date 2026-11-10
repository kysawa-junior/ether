package dev.instrum.ether.map.geospatial

import dev.instrum.ether.mapdata.GeoBounds
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.map.geom.PolygonTriangulator
import dev.instrum.ether.math.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Local ENU projection (meters) - sub-metric error across the Marilia-Garca corridor (~40 km).
 * x = east, z = south, y = elevation.
 *
 * Replaces the duplicated `CoordinateProjector`/`LocalEnuTransform` pair of the removed engine with
 * a single implementation over the shared `ether-map-data` WGS84 types.
 */
public class LocalEnuTransform(public val origin: GeoPoint) {
    private val metersPerDegreeLatitude = PI / 180.0 * EARTH_RADIUS_METERS
    private val metersPerDegreeLongitude =
        PI / 180.0 * EARTH_RADIUS_METERS * cos(toRadians(origin.latitude))

    /** Projects a WGS84 point into local meters. */
    public fun forward(point: GeoPoint): Vec3 = Vec3(
        ((point.longitude - origin.longitude) * metersPerDegreeLongitude).toFloat(),
        (point.altitude - origin.altitude).toFloat(),
        ((origin.latitude - point.latitude) * metersPerDegreeLatitude).toFloat(),
    )

    /** Inverse projection from local meters back to WGS84. */
    public fun inverse(point: Vec3): GeoPoint = GeoPoint(
        longitude = origin.longitude + point.x / metersPerDegreeLongitude,
        latitude = origin.latitude - point.z / metersPerDegreeLatitude,
        altitude = origin.altitude + point.y,
    )
}

/** GeoJSON-like geometry sealed hierarchy over the shared `ether-map-data` types. */
public sealed interface GeographicGeometry {
    public val bounds: GeoBounds
}

/** A single WGS84 location. */
public data class PointGeometry(public val point: GeoPoint) : GeographicGeometry {
    override val bounds: GeoBounds
        get() = GeoBounds(point.longitude, point.latitude, point.longitude, point.latitude)
}

/** A WGS84 polyline. */
public data class LineStringGeometry(public val points: List<GeoPoint>) : GeographicGeometry {
    override val bounds: GeoBounds = boundsOf(points)
}

/** A WGS84 polygon with an optional list of holes. */
public data class PolygonGeometry(
    public val outerRing: List<GeoPoint>,
    public val holes: List<List<GeoPoint>> = emptyList(),
) : GeographicGeometry {
    override val bounds: GeoBounds = boundsOf(outerRing + holes.flatten())
}

/** A list of polygons forming one geographic object. */
public data class MultiPolygonGeometry(public val polygons: List<PolygonGeometry>) : GeographicGeometry {
    override val bounds: GeoBounds = polygons.map { it.bounds }
        .reduceOrNull { a, b -> union(a, b) }
        // A MultiPolygonGeometry cannot be constructed empty without tripping this message; guard
        // anyway for deserialized shapes.
        ?.let { if (it.west > it.east) throw IllegalArgumentException("multipolígono vazio") else it }
        ?: throw IllegalArgumentException("multipolígono vazio")
}

/** Geometry validity checks; messages mirror the compiler reports (PT-BR). */
public object GeospatialValidation {
    public fun validate(geometry: GeographicGeometry): List<String> {
        val issues = mutableListOf<String>()
        when (geometry) {
            is PointGeometry -> if (!geometry.point.longitude.isFinite()) issues.add("ponto não finito")
            is LineStringGeometry -> {
                if (geometry.points.size < 2) issues.add("linha precisa de ao menos dois pontos")
                issues.addAll(invalidPoints(geometry.points, "linha"))
            }
            is PolygonGeometry -> {
                if (geometry.outerRing.size < 3) issues.add("anel externo precisa de ao menos três pontos")
                if (!isClosed(geometry.outerRing)) issues.add("anel externo não é fechado")
                if (GeospatialOperations.signedArea(geometry.outerRing) == 0.0) {
                    issues.add("anel externo tem área zero")
                }
                geometry.holes.forEachIndexed { index, hole ->
                    if (hole.size < 3) issues.add("buraco $index precisa de ao menos três pontos")
                    if (!isClosed(hole)) issues.add("buraco $index não é fechado")
                    issues.addAll(invalidPoints(hole, "buraco $index"))
                }
            }
            is MultiPolygonGeometry -> {
                if (geometry.polygons.isEmpty()) issues.add("multipolígono vazio")
                geometry.polygons.forEachIndexed { index, polygon ->
                    issues.addAll(validate(polygon).map { "polígono $index: $it" })
                }
            }
        }
        return issues
    }

    private fun invalidPoints(points: List<GeoPoint>, label: String): List<String> =
        points.mapNotNull { point ->
            if (!point.longitude.isFinite() || !point.latitude.isFinite() || !point.altitude.isFinite()) {
                "$label contém coordenada não finita"
            } else {
                null
            }
        }.distinct()
}

/** Ring operations over WGS84 geometry. */
public object GeospatialOperations {
    /** Signed area of a WGS84 ring (lon/lat shoelace), or 0 for fewer than 3 points. */
    public fun signedArea(ring: List<GeoPoint>): Double {
        if (ring.size < 3) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.longitude * b.latitude - b.longitude * a.latitude
        }
        return sum * 0.5
    }

    /**
     * Triangulates [polygon] in local ENU meters; triangles return as vertex index triples into
     * a flattened vertex list order matching the input rings.
     */
    public fun triangulate(
        polygon: PolygonGeometry,
        transform: LocalEnuTransform,
    ): List<IntArray>? {
        val outer = polygon.outerRing.map { transform.forward(it) }
        val holes = polygon.holes.map { ring -> ring.map { transform.forward(it) } }
        val triangulation = PolygonTriangulator.triangulate(outer, holes) ?: return null
        val result = ArrayList<IntArray>(triangulation.triangles.size / 3)
        var i = 0
        while (i < triangulation.triangles.size) {
            result.add(intArrayOf(triangulation.triangles[i], triangulation.triangles[i + 1], triangulation.triangles[i + 2]))
            i += 3
        }
        return result
    }
}

/**
 * Slippy-map XYZ coordinate. Row grows towards the south; tile 0/0/0 covers the globe.
 */
public class TileCoordinates(
    public val level: Int,
    public val column: Int,
    public val row: Int,
) {
    init {
        require(level in 0..30) { "nível de tile inválido: $level" }
        val maximum = 1 shl level
        require(column in 0 until maximum && row in 0 until maximum) {
            "tile fora da grade: $level/$column/$row"
        }
    }

    public fun parent(): TileCoordinates = TileCoordinates(level - 1, column shr 1, row shr 1)

    /** The geographic rectangle covered by this tile. */
    public fun geographicBounds(): GeoBounds {
        val n = 1 shl level
        val west = longitude(column.toDouble() / n)
        val east = longitude((column + 1).toDouble() / n)
        val north = latitude(row.toDouble() / n)
        val south = latitude((row + 1).toDouble() / n)
        return GeoBounds(west, south, east, north)
    }

    public companion object {
        public fun fromGeographic(point: GeoPoint, level: Int): TileCoordinates {
            val n = 1 shl level
            val x = (point.longitude + 180.0) / 360.0 * n
            val latitudeRad = toRadians(point.latitude.coerceIn(-85.05112878, 85.05112878))
            val y = (1.0 - ln(tan(latitudeRad) + 1.0 / cos(latitudeRad)) / PI) / 2.0 * n
            return TileCoordinates(level, floor(x).toInt().coerceIn(0, n - 1), floor(y).toInt().coerceIn(0, n - 1))
        }

        public fun longitude(x: Double): Double = x * 360.0 - 180.0

        public fun latitude(y: Double): Double {
            val rad = PI * (1.0 - 2.0 * y)
            return 180.0 / PI * atan(sinh(rad))
        }
    }
}

/**
 * Uniform spatial index, adequate to small feature sets.
 *
 * Entries are stored per cell; queries de-duplicate by item identity, so an item spanning several
 * cells returns once.
 */
public class SpatialGridIndex<T : Any>(
    private val cellSizeDegrees: Double,
) {
    public data class Entry<T>(public val item: T, public val bounds: GeoBounds)

    private val cells = mutableMapOf<Long, MutableList<Entry<T>>>()

    public fun insert(item: T, bounds: GeoBounds) {
        val minX = floor(bounds.west / cellSizeDegrees).toLong()
        val maxX = floor(bounds.east / cellSizeDegrees).toLong()
        val minY = floor(bounds.south / cellSizeDegrees).toLong()
        val maxY = floor(bounds.north / cellSizeDegrees).toLong()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                cells.getOrPut(key(x, y)) { mutableListOf() }.add(Entry(item, bounds))
            }
        }
    }

    public fun query(bounds: GeoBounds): List<T> {
        val minX = floor(bounds.west / cellSizeDegrees).toLong()
        val maxX = floor(bounds.east / cellSizeDegrees).toLong()
        val minY = floor(bounds.south / cellSizeDegrees).toLong()
        val maxY = floor(bounds.north / cellSizeDegrees).toLong()
        val seen = LinkedHashSet<T>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                cells[key(x, y)].orEmpty().forEach { entry ->
                    if (entry.bounds.west <= bounds.east && entry.bounds.east >= bounds.west &&
                        entry.bounds.south <= bounds.north && entry.bounds.north >= bounds.south
                    ) {
                        seen.add(entry.item)
                    }
                }
            }
        }
        return seen.toList()
    }

    public fun clear() {
        cells.clear()
    }

    private fun key(x: Long, y: Long): Long =
        x * 521_560_423L xor (y * -7046029254386353131L)
}

private const val EARTH_RADIUS_METERS = 6_378_137.0

private fun boundsOf(points: List<GeoPoint>): GeoBounds {
    require(points.isNotEmpty()) { "geometria sem pontos" }
    var west = points[0].longitude
    var east = west
    var south = points[0].latitude
    var north = south
    for (point in points) {
        west = min(west, point.longitude)
        east = max(east, point.longitude)
        south = min(south, point.latitude)
        north = max(north, point.latitude)
    }
    return GeoBounds(west, south, east, north)
}

private fun union(a: GeoBounds, b: GeoBounds) = GeoBounds(
    min(a.west, b.west),
    min(a.south, b.south),
    max(a.east, b.east),
    max(a.north, b.north),
)

private fun isClosed(ring: List<GeoPoint>): Boolean =
    ring.size >= 4 && ring.first().longitude == ring.last().longitude &&
        ring.first().latitude == ring.last().latitude

/**
 * `Math.toRadians` is JVM-only in the Kotlin stdlib; this is its common replacement.
 */
private fun toRadians(degrees: Double): Double = degrees * (PI / 180.0)
