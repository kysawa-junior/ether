package dev.instrum.ether.mapdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract tests for the `urban-map-package` v1 module: validation rules, JSON decoding with
 * tolerate-unknown-keys semantics, and the untrusted-input size ceilings. The validation cases
 * mirror the compiler guarantees documented in the format spec (unique IDs, unique tile
 * coordinates, finite coordinates).
 */
class MapPackageTest {

    private companion object {
        /** A tile inside [1, 3] level bounds with well ordered bounds. */
        val TILE = TileReference(
            id = "12/123/456",
            level = 12,
            column = 123,
            row = 456,
            bounds = GeoBounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0),
            featureFile = "tiles/12/123/456.json",
            byteSize = 1024,
            checksum = "abc123",
        )

        fun manifest(
            tiles: List<TileReference> = emptyList(),
            minLevel: Int = 1,
            maxLevel: Int = 16,
        ): MapManifest = MapManifest(
            origin = MapOrigin(latitude = -22.21, longitude = -49.94),
            minLevel = minLevel,
            maxLevel = maxLevel,
            tiles = tiles,
        )

        /** A closed polygon feature; smaller rings keep the geometry type loose for tests. */
        fun building(
            id: String = "b1",
            featureId: String = id,
            type: String = "building",
            rings: List<List<GeoPoint>> = listOf(
                listOf(
                    GeoPoint(-22.2100, -49.9400),
                    GeoPoint(-22.2100, -49.9398),
                    GeoPoint(-22.2098, -49.9398),
                    GeoPoint(-22.2098, -49.9400),
                ),
            ),
        ): FeatureRecord = FeatureRecord(
            id = id,
            type = type,
            geometryType = "Polygon",
            properties = FeatureProperties(
                featureId = featureId,
                featureType = type,
                heightMeters = 12.0,
            ),
            rings = rings,
        )

        fun features(vararg records: FeatureRecord): FeatureCollection =
            FeatureCollection(features = records.toList())
    }

    // ------------------------------------------------------------------
    // Validation: features
    // ------------------------------------------------------------------

    @Test
    fun packageRejectsDuplicateFeature() {
        val feature = building(id = "same")
        val mapPackage = MapPackage(manifest(), features(feature, feature))
        val result = mapPackage.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { "feature duplicada: same" == it.message })
    }

    @Test
    fun validCartographicPackageValidates() {
        val mapPackage = MapPackage(manifest(tiles = listOf(TILE)), features(building(), building(id = "b2")))
        val result = mapPackage.validate()
        assertTrue(result.valid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun packageFeatureIdDivergenceIsAnError() {
        val mapPackage = MapPackage(manifest(), features(building(featureId = "b2")))
        val result = mapPackage.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.message.contains("id persistente divergente") })
    }

    @Test
    fun packageBlankFeatureTypeIsAnError() {
        val mapPackage = MapPackage(manifest(), features(building(type = " ")))
        val result = mapPackage.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.message.contains("sem tipo") })
    }

    @Test
    fun packageSinglePointFeatureWarnsWithoutError() {
        val mapPackage = MapPackage(
            manifest(),
            features(building(rings = listOf(listOf(GeoPoint(0.0, 0.0, 0.0))))),
        )
        val result = mapPackage.validate()
        assertTrue(result.valid, "Warnings only do not invalidate the package")
        assertTrue(result.warnings.any { it.message.contains("sem geometria útil") })
    }

    @Test
    fun packageNonFiniteCoordinatesAreAnError() {
        val mapPackage = MapPackage(
            manifest(),
            features(building(rings = listOf(listOf(GeoPoint(Double.NaN, 0.0, 0.0))))),
        )
        val result = mapPackage.validate()
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.message.contains("coordenada não finita") })
    }

    // ------------------------------------------------------------------
    // Validation: manifest and tiles
    // ------------------------------------------------------------------

    @Test
    fun packageRejectsUnknownFormatAndVersion() {
        val wrongFormat = manifest().copy(format = "other-package")
        val wrongVersion = manifest().copy(version = 2)
        assertEquals(
            listOf("formato desconhecido: other-package"),
            MapPackage(wrongFormat, features()).validate().errors.map { it.message },
        )
        assertEquals(
            listOf("versão não suportada: 2"),
            MapPackage(wrongVersion, features()).validate().errors.map { it.message },
        )
    }

    @Test
    fun packageRejectsInvertedLevelBounds() {
        val result = MapPackage(manifest(minLevel = 4, maxLevel = 2), features()).validate()
        assertEquals(listOf("minLevel maior que maxLevel"), result.errors.map { it.message })
    }

    @Test
    fun packageRejectsDuplicateTileIdAndCoordinate() {
        val result = MapPackage(manifest(tiles = listOf(TILE, TILE.copy())), features()).validate()
        assertTrue(result.errors.any { it.message.contains("tile duplicado: 12/123/456") })
        assertTrue(result.errors.any { it.message.contains("coordenada de tile duplicada: 12/123/456") })
    }

    @Test
    fun packageRejectsInvalidTileBounds() {
        val tile = TILE.copy(bounds = GeoBounds(west = 2.0, south = -1.0, east = 1.0, north = 1.0))
        val result = MapPackage(manifest(tiles = listOf(tile)), features()).validate()
        assertTrue(result.errors.any { it.message.contains("bounds inválido no tile 12/123/456") })
    }

    @Test
    fun packageRejectsLevelOutsideManifestRange() {
        val tile = TILE.copy(level = 20)
        val result = MapPackage(manifest(tiles = listOf(tile)), features()).validate()
        assertTrue(result.errors.any { it.message.contains("nível fora do manifesto no tile 12/123/456") })
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    @Test
    fun manifestRoundTripPreservesTilesAndChecksums() {
        val original = manifest(tiles = listOf(TILE))
            .copy(metadata = mapOf("source" to "compiler"))
        val decoded = MapPackageLoader.decodeManifest(MapPackageLoader.encodeManifest(original))
        assertEquals(original, decoded)
        assertEquals("abc123", decoded.tiles.single().checksum)
        assertEquals(1024L, decoded.tiles.single().byteSize)
    }

    @Test
    fun decodeFeaturesIgnoresUnknownKeysAndMissingFields() {
        val text = """
            {
              "type": "FeatureCollection",
              "unknownExtension": true,
              "features": [
                {
                  "id": "b1",
                  "type": "building",
                  "geometryType": "Polygon",
                  "futureField": 42,
                  "properties": {
                    "featureId": "b1",
                    "featureType": "building",
                    "name": "Central",
                    "classification": null,
                    "extra": {}
                  }
                }
              ]
            }
        """.trimIndent()
        val features = MapPackageLoader.decodeFeatures(text)
        assertEquals(1, features.features.size)
        val record = features.features.single()
        assertEquals("b1", record.id)
        assertEquals("Central", record.properties.name)
        assertTrue(record.properties.heightMeters == null)
        assertTrue(MapPackage(manifest(), features(record)).validate().valid)
    }

    @Test
    fun decodeFeaturesBytesMatchesTextDecoding() {
        val text = """
            {"type":"FeatureCollection","features":[
              {"id":"b1","type":"building","geometryType":"Polygon",
               "properties":{"featureId":"b1","featureType":"building","heightMeters":12.0},
               "rings":[[{"longitude":-49.94,"latitude":-22.21,"altitude":0.0}]]}
            ]}
        """.trimIndent()
        val fromText = MapPackageLoader.decodeFeatures(text)
        val fromBytes = MapPackageLoader.decodeFeatures(text.encodeToByteArray())
        assertEquals(fromText, fromBytes)
        assertEquals("building", fromBytes.features.single().type)
    }

    @Test
    fun loadWithNullFeaturesDecodesToEmptyCollection() {
        val text = MapPackageLoader.encodeManifest(manifest(tiles = listOf(TILE)))
        val mapPackage = MapPackageLoader.load(text)
        assertTrue(mapPackage.features.features.isEmpty())
        assertTrue(mapPackage.validate().valid)
    }

    // ------------------------------------------------------------------
    // Untrusted input size ceilings
    // ------------------------------------------------------------------

    @Test
    fun loaderRejectsOversizedManifest() {
        val text = "x".repeat(64)
        val error = assertFailsWith<IllegalArgumentException> {
            MapPackageLoader.decodeManifest(text, maxBytes = 32)
        }
        assertTrue(
            error.message!!.contains("arquivo JSON do pacote excede 32 caracteres (64)."),
            "Actual: ${error.message}",
        )
    }

    @Test
    fun loaderRejectsOversizedFeaturesBytes() {
        val bytes = ByteArray(48)
        val error = assertFailsWith<IllegalArgumentException> {
            MapPackageLoader.decodeFeatures(bytes, maxBytes = 32)
        }
        assertTrue(
            error.message!!.contains("arquivo de features excede 32 bytes (48)."),
            "Actual: ${error.message}",
        )
    }
}
