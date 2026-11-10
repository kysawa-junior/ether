package dev.instrum.ether.mapdata

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned contract shared by the map compiler and every Ether/TypeScript runtime. */
public object MapFormat {
    public const val ID: String = "urban-map-package"
    public const val VERSION: Int = 1
    public const val MANIFEST: String = "manifest.json"
    public const val FEATURES: String = "features.geojson"
    public const val GEOMETRY: String = "geometry.glb"
}

/** A world location in WGS84 degrees; altitude is meters above sea level. */
@Serializable
public data class GeoPoint(
    public val longitude: Double,
    public val latitude: Double,
    public val altitude: Double = 0.0,
)

/** A geographic rectangle expressed by its west/south/east/north cardinal limits. */
@Serializable
public data class GeoBounds(
    public val west: Double,
    public val south: Double,
    public val east: Double,
    public val north: Double,
)

/** The anchor every runtime coordinate is expressed relative to, in WGS84 degrees. */
@Serializable
public data class MapOrigin(
    public val latitude: Double,
    public val longitude: Double,
    public val altitude: Double = 0.0,
)

/**
 * A streamed XYZ tile referenced by the manifest.
 *
 * [featureFile] and [geometryFile] are package relative paths; [byteSize] and [checksum] describe
 * the tile content so streaming code can pre-filter downloads and detect corruption.
 */
@Serializable
public data class TileReference(
    public val id: String,
    public val level: Int,
    public val column: Int,
    public val row: Int,
    public val bounds: GeoBounds,
    public val featureFile: String? = null,
    public val geometryFile: String? = null,
    public val byteSize: Long = 0,
    public val checksum: String? = null,
)

/** The root record of `manifest.json`. */
@Serializable
public data class MapManifest(
    public val format: String = MapFormat.ID,
    public val version: Int = MapFormat.VERSION,
    public val crs: String = "EPSG:4326",
    public val origin: MapOrigin,
    public val minLevel: Int,
    public val maxLevel: Int,
    public val tiles: List<TileReference>,
    public val metadata: Map<String, String> = emptyMap(),
)

/** Persistent cartographic identity carried by every feature record. */
@Serializable
public data class FeatureProperties(
    public val featureId: String,
    public val featureType: String,
    public val name: String? = null,
    public val heightMeters: Double? = null,
    public val widthMeters: Double? = null,
    public val classification: String? = null,
    public val source: String? = null,
    public val extra: Map<String, String> = emptyMap(),
)

/**
 * One geographic feature with its GeoJSON-like rings.
 *
 * [rings] remain WGS84 coordinates; render meshes are a separate package artifact.
 */
@Serializable
public data class FeatureRecord(
    public val id: String,
    public val type: String,
    public val geometryType: String,
    public val properties: FeatureProperties,
    public val rings: List<List<GeoPoint>> = emptyList(),
)

/** A `features.geojson` collection record. */
@Serializable
public data class FeatureCollection(
    public val type: String = "FeatureCollection",
    public val features: List<FeatureRecord> = emptyList(),
)

/**
 * Problems found while validating a package.
 *
 * The sealed interface keeps the hierarchy closed and `when` expressions exhaustive, including
 * for future native consumers.
 */
public sealed interface MapValidationIssue {
    public val message: String

    public data class Error(override val message: String) : MapValidationIssue

    public data class Warning(override val message: String) : MapValidationIssue
}

/** The result of [MapPackage.validate]; [valid] stays true while no error is present. */
public data class MapValidationResult(
    public val issues: List<MapValidationIssue>,
) {
    public val errors: List<MapValidationIssue.Error>
        get() = issues.filterIsInstance<MapValidationIssue.Error>()
    public val warnings: List<MapValidationIssue.Warning>
        get() = issues.filterIsInstance<MapValidationIssue.Warning>()
    public val valid: Boolean
        get() = errors.isEmpty()
}

/**
 * A decoded cartographic package: manifest plus feature collection.
 *
 * Equality and hashing derive from the data class fields, so two packages with the same content
 * compare equal.
 */
public data class MapPackage(
    public val manifest: MapManifest,
    public val features: FeatureCollection,
) {
    public fun validate(): MapValidationResult {
        val issues = mutableListOf<MapValidationIssue>()
        if (manifest.format != MapFormat.ID) issues += MapValidationIssue.Error("formato desconhecido: ${manifest.format}")
        if (manifest.version != MapFormat.VERSION) issues += MapValidationIssue.Error("versão não suportada: ${manifest.version}")
        if (manifest.minLevel > manifest.maxLevel) issues += MapValidationIssue.Error("minLevel maior que maxLevel")

        val tileIds = mutableSetOf<String>()
        val tileKeys = mutableSetOf<String>()
        for (tile in manifest.tiles) {
            if (!tileIds.add(tile.id)) issues += MapValidationIssue.Error("tile duplicado: ${tile.id}")
            if (!tileKeys.add("${tile.level}/${tile.column}/${tile.row}")) {
                issues += MapValidationIssue.Error("coordenada de tile duplicada: ${tile.level}/${tile.column}/${tile.row}")
            }
            if (tile.bounds.west > tile.bounds.east || tile.bounds.south > tile.bounds.north) {
                issues += MapValidationIssue.Error("bounds inválido no tile ${tile.id}")
            }
            if (tile.level !in manifest.minLevel..manifest.maxLevel) {
                issues += MapValidationIssue.Error("nível fora do manifesto no tile ${tile.id}")
            }
        }

        val featureIds = mutableSetOf<String>()
        for (feature in features.features) {
            if (!featureIds.add(feature.id)) issues += MapValidationIssue.Error("feature duplicada: ${feature.id}")
            if (feature.properties.featureId != feature.id) {
                issues += MapValidationIssue.Error("feature ${feature.id} possui id persistente divergente")
            }
            if (feature.type.isBlank()) issues += MapValidationIssue.Error("feature ${feature.id} sem tipo")
            if (feature.rings.all { it.size < 2 }) issues += MapValidationIssue.Warning("feature ${feature.id} sem geometria útil")
            for (ring in feature.rings) {
                if (ring.any { !it.longitude.isFinite() || !it.latitude.isFinite() || !it.altitude.isFinite() }) {
                    issues += MapValidationIssue.Error("feature ${feature.id} contém coordenada não finita")
                    break
                }
            }
        }
        return MapValidationResult(issues)
    }
}

/**
 * Decodes a cartographic package.
 *
 * The API is String/ByteArray only, deliberately without stream types: this module is the
 * contract shared by every runtime, and streams would pin it to the JVM. The size ceiling is
 * enforced before the text is materialized, so an untrusted package cannot cause an OOM.
 */
public object MapPackageLoader {
    /** Default per-JSON-file ceiling of a package (32 MiB). */
    public const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Decodes the package from the manifest and feature texts. */
    public fun load(
        manifestText: String,
        featuresText: String? = null,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): MapPackage = MapPackage(
        manifest = decodeManifest(manifestText, maxBytes),
        features = featuresText?.let { decodeFeatures(it, maxBytes) } ?: FeatureCollection(),
    )

    public fun decodeFeatures(text: String, maxBytes: Int = DEFAULT_MAX_BYTES): FeatureCollection =
        json.decodeFromString(checkSize(text, maxBytes))

    public fun decodeManifest(text: String, maxBytes: Int = DEFAULT_MAX_BYTES): MapManifest =
        json.decodeFromString(checkSize(text, maxBytes))

    public fun encodeManifest(manifest: MapManifest): String = json.encodeToString(manifest)

    /** Decodes features from raw bytes, without an intermediate String pass. */
    public fun decodeFeatures(bytes: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): FeatureCollection {
        require(bytes.size <= maxBytes) {
            "arquivo de features excede $maxBytes bytes (${bytes.size})."
        }
        return json.decodeFromString(bytes.decodeToString())
    }

    private fun checkSize(text: String, maxBytes: Int): String {
        require(text.length <= maxBytes) {
            "arquivo JSON do pacote excede $maxBytes caracteres (${text.length})."
        }
        return text
    }
}
