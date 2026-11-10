package dev.instrum.ether.map.streaming

import dev.instrum.ether.mapdata.MapManifest
import dev.instrum.ether.mapdata.MapPackageLoader

/**
 * Loads tiles from a cartographic package.
 *
 * Divergence from the removed engine: instead of `java.io.File`, a suspended reader lambda is
 * injected, so the loader works on any filesystem (JVM, native, browser `fetch`); the byte budget
 * is enforced by the reader implementation before materializing the content.
 */
public class MapPackageTileLoader(
    manifest: MapManifest,
    private val readTileFile: suspend (relativePath: String) -> ByteArray,
) : TileLoader {
    private val tiles = manifest.tiles.associateBy { "${it.level}/${it.column}/${it.row}" }

    override suspend fun load(id: TileId): TileContent {
        val reference = tiles["${id.level}/${id.column}/${id.row}"]
            ?: error("tile ausente no manifesto: ${id.level}/${id.column}/${id.row}")
        val relative = reference.featureFile
            ?: error("tile ${reference.id} não possui arquivo de features")
        val bytes = readTileFile(relative)
        // Ceiling before parsing: an untrusted package does not materialize the file whole.
        check(bytes.size <= MapPackageLoader.DEFAULT_MAX_BYTES) {
            "tile excede ${MapPackageLoader.DEFAULT_MAX_BYTES} bytes: $relative (${bytes.size})"
        }
        val features = MapPackageLoader.decodeFeatures(bytes)
        return TileContent(id, features, bytes.size.toLong())
    }
}
