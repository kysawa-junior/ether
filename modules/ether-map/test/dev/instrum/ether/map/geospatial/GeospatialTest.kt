package dev.instrum.ether.map.geospatial

import dev.instrum.ether.mapdata.GeoBounds
import dev.instrum.ether.mapdata.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Geospatial contracts ported from the removed engine: ENU round trip, XYZ tiles (with the level
 * rejection the old test asserted through the tile constructor), geometry validation and the
 * spatial index de-duplication. The user decision "no GeoBounds.contains in the shared contract"
 * is asserted inline here.
 */
class GeospatialTest {

    @Test
    fun `transform local preserva posicao geodesica`() {
        val origin = GeoPoint(longitude = -49.94, latitude = -22.21)
        val transform = LocalEnuTransform(origin)
        val point = GeoPoint(longitude = -49.93, latitude = -22.20, altitude = 12.0)
        val local = transform.forward(point)
        val back = transform.inverse(local)
        assertTrue(kotlin.math.abs(back.latitude - point.latitude) < 1e-9)
        assertTrue(kotlin.math.abs(back.longitude - point.longitude) < 1e-9)
        assertEquals(12.0, back.altitude, 1e-9)
    }

    @Test
    fun `tile xyz cobre globo e rejeita nivel invalido`() {
        val tile = TileCoordinates.fromGeographic(GeoPoint(longitude = 0.0, latitude = 0.0), 2)
        assertEquals(2, tile.level)
        assertEquals(2, tile.column)
        assertEquals(2, tile.row)
        val bounds = tile.geographicBounds()
        val center = GeoPoint(longitude = 0.0, latitude = 0.0)
        // Inline containment (the shared GeoBounds contract has no contains method).
        assertTrue(
            center.longitude >= bounds.west && center.longitude <= bounds.east &&
                center.latitude >= bounds.south && center.latitude <= bounds.north,
            "tile 2/2/2 cobre o cruzamento do equador com Greenwich",
        )

        assertFailsWith<IllegalArgumentException> { TileCoordinates(-1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TileCoordinates(31, 0, 0) }
        assertFailsWith<IllegalArgumentException> { TileCoordinates(2, 4, 0) }
        assertFailsWith<IllegalArgumentException> { TileCoordinates(2, 0, 4) }
    }

    @Test
    fun `geometria valida e anel de area zero e detectado`() {
        val valid = PointGeometry(GeoPoint(longitude = 0.0, latitude = 0.0))
        assertTrue(GeospatialValidation.validate(valid).isEmpty())

        val closedCollinear = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(1.0, 0.0),
            GeoPoint(2.0, 0.0),
            GeoPoint(0.0, 0.0),
        )
        val polygon = PolygonGeometry(closedCollinear)
        val issues = GeospatialValidation.validate(polygon)
        assertTrue(
            issues.any { it.contains("área zero") },
            "anel colinear fechado reporta área zero: $issues",
        )
    }

    @Test
    fun `indice espacial deduplica item em varias celulas`() {
        val index = SpatialGridIndex<String>(cellSizeDegrees = 1.0)
        // Wide item spanning several grid cells.
        index.insert("wide", GeoBounds(west = -1.5, south = 0.0, east = 1.5, north = 0.2))
        index.insert("narrow", GeoBounds(west = 2.2, south = 0.0, east = 2.3, north = 0.1))

        val hits = index.query(GeoBounds(west = -0.5, south = 0.0, east = 0.5, north = 0.1))
        assertEquals(listOf("wide"), hits, "item em várias células retorna uma vez")
        assertEquals(listOf("narrow"), index.query(GeoBounds(west = 2.0, south = 0.0, east = 3.0, north = 1.0)))
        index.clear()
        assertTrue(index.query(GeoBounds(west = -3.0, south = -3.0, east = 3.0, north = 3.0)).isEmpty())
    }

    @Test
    fun `operacoes geoespaciais triangulam poligono local`() {
        val transform = LocalEnuTransform(GeoPoint(longitude = -49.94, latitude = -22.21))
        val polygon = PolygonGeometry(
            outerRing = listOf(
                GeoPoint(-49.94, -22.21),
                GeoPoint(-49.9398, -22.21),
                GeoPoint(-49.9398, -22.2098),
                GeoPoint(-49.94, -22.2098),
                GeoPoint(-49.94, -22.21),
            ),
        )
        val triangles = GeospatialOperations.triangulate(polygon, transform)!!
        assertEquals(2, triangles.size)
        triangles.forEach { assertEquals(3, it.size) }
    }

    @Test
    fun `tess de linhagens captura bounds corrretos`() {
        val line = LineStringGeometry(
            listOf(
                GeoPoint(-49.94, -22.21),
                GeoPoint(-49.9390, -22.2105),
            ),
        )
        val bounds = line.bounds
        assertEquals(-49.94, bounds.west, 1e-12)
        assertEquals(-49.9390, bounds.east, 1e-12)
        assertFailsWith<IllegalArgumentException> {
            MultiPolygonGeometry(emptyList())
        }
    }
}
