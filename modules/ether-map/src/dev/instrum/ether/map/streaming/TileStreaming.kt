package dev.instrum.ether.map.streaming

import dev.instrum.ether.map.camera.Camera
import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.map.geospatial.TileCoordinates
import dev.instrum.ether.mapdata.FeatureCollection
import dev.instrum.ether.mapdata.GeoBounds
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.mapdata.TileReference
import dev.instrum.ether.math.Vec3
import kotlin.math.ln
import kotlin.math.sqrt

public data class TileId(public val level: Int, public val column: Int, public val row: Int) {
    public fun toReference(): TileReference {
        val coordinates = TileCoordinates(level, column, row)
        val bounds = coordinates.geographicBounds()
        return TileReference(
            id = "$level/$column/$row",
            level = level,
            column = column,
            row = row,
            bounds = GeoBounds(bounds.west, bounds.south, bounds.east, bounds.north),
        )
    }
}

public data class TileContent(
    public val id: TileId,
    public val features: FeatureCollection,
    public val byteSize: Long,
    public val geometryVersion: Long = 0,
)

public fun interface TileLoader {
    public suspend fun load(id: TileId): TileContent
}

public data class TilePriority(
    public val id: TileId,
    public val priority: Double,
    public val distanceMeters: Double,
)

public data class StreamingStats(
    public val loadedTiles: Int,
    public val cachedBytes: Long,
    public val pendingLoads: Int,
    public val evictedTiles: Int,
)

/** LRU cache of tile contents bounded by a byte budget. */
public class TileCache(private val memoryBudgetBytes: Long) {
    private val entries = linkedMapOf<TileId, TileContent>()
    private val lastUse = mutableMapOf<TileId, Long>()
    private var clock = 0L

    public var evictedTiles: Int = 0
        private set

    public val byteSize: Long
        get() = entries.values.sumOf { it.byteSize }

    public val size: Int
        get() = entries.size

    public fun get(id: TileId): TileContent? {
        val content = entries[id] ?: return null
        lastUse[id] = ++clock
        return content
    }

    public fun put(content: TileContent) {
        entries[content.id] = content
        lastUse[content.id] = ++clock
        evictIfNeeded()
    }

    public fun remove(id: TileId): Boolean = entries.remove(id) != null

    public fun clear() {
        entries.clear()
        lastUse.clear()
    }

    private fun evictIfNeeded() {
        while (byteSize > memoryBudgetBytes && entries.isNotEmpty()) {
            val oldest = entries.keys.minByOrNull { lastUse[it] ?: 0L } ?: break
            entries.remove(oldest)
            lastUse.remove(oldest)
            evictedTiles++
        }
    }
}

/**
 * Tile streaming coordinator: prioritizes the neighborhood tiles of the camera, enqueues loads and
 * keeps them in the LRU cache.
 */
public class TileStreamingManager(
    private val loader: TileLoader,
    private val transform: LocalEnuTransform,
    memoryBudgetBytes: Long = 256L * 1024L * 1024L,
    public val minLevel: Int = 8,
    public val maxLevel: Int = 16,
) {
    public val cache: TileCache = TileCache(memoryBudgetBytes)
    private val pending = linkedMapOf<TileId, TilePriority>()
    private val failed = mutableMapOf<TileId, String>()

    public val pendingCount: Int
        get() = pending.size

    public val stats: StreamingStats
        get() = StreamingStats(cache.size, cache.byteSize, pending.size, cache.evictedTiles)

    /** Prioritizes by distance and visibility. */
    public fun prioritize(camera: Camera, geographicBounds: GeoBounds): List<TilePriority> {
        val level = levelForBounds(geographicBounds)
        val center = GeoPoint(
            (geographicBounds.west + geographicBounds.east) / 2,
            (geographicBounds.south + geographicBounds.north) / 2,
        )
        val tile = TileCoordinates.fromGeographic(center, level)
        val result = mutableListOf<TilePriority>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val column = tile.column + dx
                val row = tile.row + dy
                val grid = 1 shl level
                if (column !in 0 until grid || row !in 0 until grid) continue
                val id = TileId(level, column, row)
                val tileCenter = TileCoordinates(level, column, row).geographicBounds()
                val centerPoint = GeoPoint(
                    (tileCenter.west + tileCenter.east) / 2,
                    (tileCenter.south + tileCenter.north) / 2,
                )
                val distance = roughDistanceMeters(camera.position, centerPoint)
                result += TilePriority(id, 1.0 / (1.0 + distance), distance)
            }
        }
        return result.sortedByDescending { it.priority }
    }

    /** Enqueues tiles not yet resident; cached tiles are not reloaded. */
    public fun enqueue(priorities: List<TilePriority>) {
        for (priority in priorities) {
            if (cache.get(priority.id) != null) continue
            if (failed.containsKey(priority.id)) continue
            pending[priority.id] = priority
        }
    }

    /**
     * Loads the next pending tile. Failures are recorded ([failure]) and skipped, so the returned
     * `null` only means the queue is exhausted — a caller that drains until `null` gets every
     * loadable tile.
     */
    public suspend fun loadNext(): TileContent? {
        while (true) {
            // Take the key first and remove before touching anything else: holding an entry of
            // the backing LinkedHashMap across a mutation would trip its strict modification
            // check on native.
            val firstId = pending.keys.firstOrNull() ?: return null
            val priority = pending.remove(firstId) ?: return null
            try {
                val content = loader.load(priority.id)
                cache.put(content)
                failed.remove(priority.id)
                return content
            } catch (error: Throwable) {
                failed[priority.id] = error.message ?: error::class.simpleName ?: "erro desconhecido"
            }
        }
    }

    public suspend fun loadAll(maxLoads: Int = Int.MAX_VALUE): List<TileContent> {
        val loaded = mutableListOf<TileContent>()
        var count = 0
        while (count < maxLoads) {
            val content = loadNext() ?: break
            loaded += content
            count++
        }
        return loaded
    }

    public fun failure(id: TileId): String? = failed[id]

    public fun levelForBounds(bounds: GeoBounds): Int {
        val span = maxOf(bounds.east - bounds.west, bounds.north - bounds.south).coerceAtLeast(1e-9)
        val level = ln(360.0 / span) / ln(2.0)
        return level.toInt().coerceIn(minLevel, maxLevel)
    }

    /**
     * Camera distance to a geographic center, both through the shared ENU projection.
     *
     * Divergence from the removed engine: its comparison mixed a local ENU x with
     * `longitude * 111320`, which is inconsistent; here both sides are projected into the same
     * local meters frame, fixing the priority ordering near the projection origin.
     */
    private fun roughDistanceMeters(cameraPosition: Vec3, center: GeoPoint): Double {
        val local = transform.forward(center)
        val dx = cameraPosition.x - local.x
        val dz = cameraPosition.z - local.z
        return sqrt((dx * dx + dz * dz).toDouble())
    }
}
