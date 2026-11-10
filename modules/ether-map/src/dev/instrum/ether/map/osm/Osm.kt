package dev.instrum.ether.map.osm

/** `<node id lat lon [ele]>` + tags. */
public class OsmNode(
    public val id: Long,
    public val lat: Double,
    public val lon: Double,
    public val elevation: Double?,
    public val tags: Map<String, String>,
)

/** `<way id> <nd ref.../> <tag.../>` - `nd` order preserved. */
public class OsmWay(
    public val id: Long,
    public val nodeRefs: LongArray,
    public val tags: Map<String, String>,
) {
    public val isClosed: Boolean
        get() = nodeRefs.size > 1 && nodeRefs.first() == nodeRefs.last()

    override fun equals(other: Any?): Boolean = other is OsmWay && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

/** A node coordinate with its optional elevation (never invented). */
public data class GeoPointElev(public val lat: Double, public val lon: Double, public val elev: Double?)

/** Raw OSM elements before interpretation; ids and tags intact. */
public class OsmDataStore {
    private val nodes = HashMap<Long, OsmNode>()
    private val ways = HashMap<Long, OsmWay>()
    public val issues: MutableList<String> = mutableListOf()

    public var nodeCount: Int = 0
        private set

    public var wayCount: Int = 0
        private set

    public fun addNode(node: OsmNode) {
        nodes[node.id] = node
        nodeCount++
    }

    public fun addWay(way: OsmWay) {
        ways[way.id] = way
        wayCount++
    }

    public fun nodeById(id: Long): OsmNode? = nodes[id]

    public fun allWays(): Sequence<OsmWay> = ways.values.asSequence()

    /** Resolved way geometry; missing nodes produce an issue and are skipped. */
    public fun wayCoordinates(way: OsmWay): List<GeoPointElev> {
        val out = ArrayList<GeoPointElev>(way.nodeRefs.size)
        for (ref in way.nodeRefs) {
            val node = nodes[ref]
            if (node == null) {
                if (issues.size < 50) issues.add("Nó $ref da via ${way.id} ausente no arquivo.")
                continue
            }
            out.add(GeoPointElev(node.lat, node.lon, node.elevation))
        }
        return out
    }
}
