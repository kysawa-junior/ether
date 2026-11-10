package dev.instrum.ether.map.streaming

import dev.instrum.ether.map.camera.OrthographicCamera
import dev.instrum.ether.map.geospatial.LocalEnuTransform
import dev.instrum.ether.map.geospatial.TileCoordinates
import dev.instrum.ether.mapdata.FeatureCollection
import dev.instrum.ether.mapdata.GeoBounds
import dev.instrum.ether.mapdata.GeoPoint
import dev.instrum.ether.mapdata.MapManifest
import dev.instrum.ether.mapdata.MapOrigin
import dev.instrum.ether.mapdata.TileReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Streaming contracts: LRU cache bounds, distance-ordered prioritization (fixed by projecting the
 * tile centers through the same ENU transform), deferred load failures and the package-backed
 * loader.
 */
class TileStreamingTest {

    /** Synchronous bridge for suspend test blocks (loaders never actually suspend). */
    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        val completion = object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                result.exceptionOrNull()?.let { failure = it }
            }
        }
        block.startCoroutine(completion)
        failure?.let { throw it }
    }

    /** Always-succeed loader; configured ids fail on purpose. */
    private class ScriptedLoader(private val failures: Set<TileId> = emptySet()) : TileLoader {
        val loaded = mutableListOf<TileId>()

        override suspend fun load(id: TileId): TileContent {
            loaded += id
            if (id in failures) throw IllegalStateException("falha simulada")
            return TileContent(id, FeatureCollection(), 1L)
        }
    }

    @Test
    fun `tile cache respeita budget e remove menos recente`() {
        val cache = TileCache(10L)
        cache.put(TileContent(TileId(10, 1, 1), FeatureCollection(), 6L))
        assertNotNull(cache.get(TileId(10, 1, 1)), "get rewinda o LRU")
        cache.put(TileContent(TileId(10, 2, 2), FeatureCollection(), 6L))
        assertEquals(1, cache.size, "12 bytes em um budget de 10 expulsa o menos recente")
        assertEquals(1, cache.evictedTiles)
        assertNull(cache.get(TileId(10, 1, 1)), "o get antigo foi expulso")
        assertNotNull(cache.get(TileId(10, 2, 2)))
    }

    @Test
    fun `priorizacao usa nivel do bounds e ordena por distancia`() {
        val transform = LocalEnuTransform(GeoPoint(longitude = 0.0, latitude = 0.0))
        // Camera parked exactly on the center of tile 12/2048/2048 so the minimum is unique.
        val cameraTileCenter = GeoPoint(
            longitude = TileCoordinates.longitude(2048.5 / 4096.0),
            latitude = TileCoordinates.latitude(0.5 + 0.5 / 4096.0),
        )
        val camera = OrthographicCamera(position = transform.forward(cameraTileCenter), heightMeters = 1000f)
        val manager = TileStreamingManager(
            loader = ScriptedLoader(),
            transform = transform,
            minLevel = 8,
            maxLevel = 16,
        )
        val bounds = GeoBounds(west = -0.025, south = -0.025, east = 0.025, north = 0.025)

        assertEquals(
            12,
            manager.levelForBounds(bounds),
            "5 km de comprimento cai no nível 12",
        )
        assertEquals(
            8,
            manager.levelForBounds(GeoBounds(west = -180.0, south = -90.0, east = 180.0, north = 90.0)),
            "globo inteiro clampa no mínimo",
        )

        val priorities = manager.prioritize(camera, bounds)
        assertEquals(9, priorities.size, "vizinhança 3x3")
        assertEquals(TileId(12, 2048, 2048), priorities.first().id, "tile da câmera vem primeiro")
        assertTrue(
            priorities.first().distanceMeters < priorities.last().distanceMeters,
            "ordenado por distância crescente",
        )
    }

    @Test
    fun `manager enfileira carrega e registra falha`() {
        val transform = LocalEnuTransform(GeoPoint(longitude = 0.0, latitude = 0.0))
        // Camera parked exactly on the tile center: the queue starts with the (failing) center
        // tile, making the drain order deterministic.
        val cameraTileCenter = GeoPoint(
            longitude = TileCoordinates.longitude(2048.5 / 4096.0),
            latitude = TileCoordinates.latitude(0.5 + 0.5 / 4096.0),
        )
        val camera = OrthographicCamera(position = transform.forward(cameraTileCenter), heightMeters = 1000f)
        val centerTile = TileId(12, 2048, 2048)
        val manager = TileStreamingManager(
            loader = ScriptedLoader(failures = setOf(centerTile)),
            transform = transform,
        )
        val priorities = manager.prioritize(camera, GeoBounds(-0.025, -0.025, 0.025, 0.025))
        manager.enqueue(priorities)
        assertEquals(9, manager.pendingCount)
        manager.enqueue(priorities)
        assertEquals(9, manager.pendingCount, "enfileirar duas vezes não duplica")

        runSuspend {
            var loaded = 0
            while (true) {
                val content = manager.loadNext() ?: break
                assertEquals(1L, content.byteSize)
                loaded++
            }
            assertEquals(8, loaded, "o tile central falha, os demais carregam")
        }
        assertEquals(0, manager.pendingCount)
        assertEquals(8, manager.cache.size)
        assertEquals("falha simulada", manager.failure(centerTile))

        manager.enqueue(priorities)
        assertEquals(
            0,
            manager.pendingCount,
            "tiles em cache e em falha não reenfileiram",
        )
    }

    @Test
    fun `tile id referencia coordenadas do partagamento`() {
        val reference = TileId(10, 300, 500).toReference()
        assertEquals("10/300/500", reference.id)
        assertEquals(10, reference.level)
        assertTrue(reference.bounds.east > reference.bounds.west)
    }

    @Test
    fun `map package loader carrega tile e falha ausente`() {
        val manifest = MapManifest(
            origin = MapOrigin(latitude = -22.21, longitude = -49.94),
            minLevel = 1,
            maxLevel = 16,
            tiles = listOf(
                TileReference(
                    id = "10/300/500",
                    level = 10,
                    column = 300,
                    row = 500,
                    bounds = GeoBounds(west = 0.0, south = 0.0, east = 0.001, north = 0.001),
                    featureFile = "tiles/10/300/500.json",
                ),
                TileReference(
                    id = "10/301/500",
                    level = 10,
                    column = 301,
                    row = 500,
                    bounds = GeoBounds(west = 0.001, south = 0.0, east = 0.002, north = 0.001),
                ),
            ),
        )
        val tileBytes = """
            {"type":"FeatureCollection","features":[
              {"id":"x1","type":"building","geometryType":"Polygon",
               "properties":{"featureId":"x1","featureType":"building"},
               "rings":[[{"longitude":0.0,"latitude":0.0},{"longitude":0.0,"latitude":0.0001},{"longitude":0.0001,"latitude":0.0001}]]}
            ]}
        """.trimIndent().encodeToByteArray()
        val loader = MapPackageTileLoader(manifest) { relative ->
            if (relative == "tiles/10/300/500.json") tileBytes else error("arquivo inesperado: $relative")
        }

        runSuspend {
            val content = loader.load(TileId(10, 300, 500))
            assertEquals(1, content.features.features.size)
            assertEquals(tileBytes.size.toLong(), content.byteSize)
            assertEquals("building", content.features.features[0].type)

            val error = assertFailsWith<IllegalStateException> { loader.load(TileId(10, 302, 500)) }
            assertTrue(error.message!!.contains("manifesto"), "tile ausente no manifesto")

            val error2 = assertFailsWith<IllegalStateException> { loader.load(TileId(10, 301, 500)) }
            assertTrue(error2.message!!.contains("arquivo"), "tile sem featureFile")
        }
    }
}
