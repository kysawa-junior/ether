package dev.instrum.ether.map.osm

/**
 * Minimal OSM semantic interpretation: roads, buildings, areas and water. Estimates (width/height)
 * inherited and documented from F.O.G.O.: explicit value > estimated from a derived tag > default
 * by category.
 */
public object OsmFeatureMapper {
    // ── Roads ────────────────────────────────────────────────────────────

    public enum class RoadType(public val widthMeters: Double) {
        Motorway(14.0), Trunk(12.0), Primary(10.0), Secondary(9.0),
        Tertiary(8.0), Unclassified(6.5), Residential(6.5), LivingStreet(6.0),
        Service(4.0), Other(5.0);
    }

    private val ROAD_TYPES = mapOf(
        "motorway" to RoadType.Motorway, "trunk" to RoadType.Trunk,
        "primary" to RoadType.Primary, "secondary" to RoadType.Secondary,
        "tertiary" to RoadType.Tertiary, "unclassified" to RoadType.Unclassified,
        "residential" to RoadType.Residential, "living_street" to RoadType.LivingStreet,
        "service" to RoadType.Service,
    )

    /** Narrower `highway=service` subtypes. */
    private val SERVICE_WIDTHS = mapOf(
        "alley" to 3.0, "driveway" to 2.5, "parking_aisle" to 3.5,
        "drive-through" to 3.5, "emergency_access" to 5.0,
    )

    public data class RoadFeature(
        public val id: String,
        public val name: String,
        public val type: RoadType,
        public val widthMeters: Double,
        /** Vertical lift (layer*4 m; bridge +4; tunnel -4). */
        public val liftMeters: Double,
        public val isRing: Boolean,
    )

    /** Width: `width` > lanes estimate (0.5x-1.5x of the default) > default. */
    public fun estimateRoadWidth(tags: Map<String, String>, type: RoadType): Double {
        tags["width"]?.toDoubleOrNull()?.let { width -> if (width in 0.001..100.0) return width }
        val base = if (type == RoadType.Service) {
            SERVICE_WIDTHS[tags["service"]] ?: type.widthMeters
        } else {
            type.widthMeters
        }
        val lanes = tags["lanes"]?.toIntOrNull()
        if (lanes != null && lanes in 1..12) {
            return ((lanes * 3.2).coerceIn(base * 0.5, base * 1.5)).coerceIn(2.0, 60.0)
        }
        return base
    }

    private fun liftOf(tags: Map<String, String>): Double {
        val layer = tags["layer"]?.toIntOrNull() ?: 0
        val base = layer * 4.0
        return when {
            base != 0.0 -> base
            tags["bridge"] != null && tags["bridge"] != "no" -> 4.0
            tags["tunnel"] != null && tags["tunnel"] != "no" -> -4.0
            else -> 0.0
        }
    }

    public fun roadOf(way: OsmWay): RoadFeature? {
        val highway = way.tags["highway"] ?: return null
        // `*_link` inherits the base type (ramp at its own layer level).
        val type = ROAD_TYPES[highway.removeSuffix("_link")] ?: return null
        val name = way.tags["name"] ?: ""
        return RoadFeature(
            id = "way/${way.id}",
            name = name,
            type = type,
            widthMeters = estimateRoadWidth(way.tags, type),
            liftMeters = liftOf(way.tags),
            isRing = way.isClosed,
        )
    }

    // ── Buildings ────────────────────────────────────────────────────────

    /**
     * Default heights per `building=*` category - calibrated with the real categories of the
     * region (Marília/Vera Cruz/Garça).
     */
    private val BUILDING_HEIGHTS = mapOf(
        "yes" to 9.0, "house" to 6.0, "residential" to 11.0, "apartments" to 16.0,
        "dormitory" to 16.0, "hotel" to 17.0, "commercial" to 13.0, "retail" to 9.5,
        "supermarket" to 10.5, "office" to 14.5, "public" to 13.0, "kiosk" to 3.5,
        "school" to 10.5, "kindergarten" to 7.5, "university" to 11.5, "college" to 11.5,
        "hospital" to 15.5, "train_station" to 11.5, "fire_station" to 11.5,
        "stadium" to 18.0, "grandstand" to 10.0, "church" to 18.0, "chapel" to 10.0,
        "cathedral" to 26.0, "mosque" to 15.0, "temple" to 15.0, "industrial" to 11.0,
        "warehouse" to 11.0, "hangar" to 13.5, "factory" to 13.5, "barn" to 6.0,
        "shed" to 3.5, "greenhouse" to 5.0, "garage" to 3.5, "garages" to 3.5,
        "carport" to 3.0, "roof" to 3.5, "ruins" to 3.5, "hut" to 3.0, "construction" to 9.0,
    )

    /** Height: `height` > `building:levels`*3.2 (+roof) > default by category. */
    public fun estimateBuildingHeight(tags: Map<String, String>): Double {
        tags["height"]?.toDoubleOrNull()?.let { height -> if (height > 0) return height }
        val levels = tags["building:levels"]?.toDoubleOrNull()
        if (levels != null && levels > 0) {
            val roofLevels = tags["roof:levels"]?.toDoubleOrNull()
            val roofHeight = tags["roof:height"]?.toDoubleOrNull()
            return when {
                roofLevels != null && roofLevels > 0 -> (levels + roofLevels) * 3.2
                roofHeight != null && roofHeight > 0 -> levels * 3.2 + roofHeight
                else -> levels * 3.2
            }
        }
        val category = tags["building"] ?: "yes"
        return BUILDING_HEIGHTS[category] ?: BUILDING_HEIGHTS.getValue("yes")
    }

    public data class BuildingFeature(
        public val id: String,
        public val name: String,
        public val heightMeters: Double,
        public val minHeightMeters: Double?,
    )

    public fun buildingOf(way: OsmWay): BuildingFeature? {
        val building = way.tags["building"] ?: return null
        if (building == "no") return null
        if (!way.isClosed) return null
        val height = estimateBuildingHeight(way.tags).coerceIn(2.5, 300.0)
        return BuildingFeature(
            id = "way/${way.id}",
            name = way.tags["name"] ?: "",
            heightMeters = height,
            minHeightMeters = way.tags["min_height"]?.toDoubleOrNull(),
        )
    }

    // ── Areas and water ──────────────────────────────────────────────────

    public enum class LandKind(
        public val r: Double,
        public val g: Double,
        public val b: Double,
        public val a: Double = 1.0,
    ) {
        Park(0.10, 0.16, 0.10),
        Water(0.06, 0.10, 0.18),
        Forest(0.08, 0.13, 0.09),
        Farmland(0.13, 0.12, 0.08),
        Residential(0.07, 0.075, 0.09),
        Parking(0.10, 0.10, 0.12),
    }

    public data class AreaFeature(public val id: String, public val kind: LandKind)

    public fun areaOf(way: OsmWay): AreaFeature? {
        if (!way.isClosed) return null
        val kind = when {
            way.tags["leisure"] in setOf("park", "playground", "garden", "pitch") -> LandKind.Park
            way.tags["natural"] == "water" || way.tags["waterway"] == "riverbank" -> LandKind.Water
            way.tags["landuse"] == "forest" || way.tags["natural"] == "wood" -> LandKind.Forest
            way.tags["landuse"] == "farmland" || way.tags["landuse"] == "orchard" -> LandKind.Farmland
            way.tags["landuse"] == "residential" -> LandKind.Residential
            way.tags["amenity"] == "parking" -> LandKind.Parking
            else -> null
        } ?: return null
        return AreaFeature("way/${way.id}", kind)
    }

    public data class WaterwayFeature(public val id: String, public val widthMeters: Double)

    public fun waterwayOf(way: OsmWay): WaterwayFeature? {
        val type = way.tags["waterway"] ?: return null
        if (way.isClosed) return null
        val width = way.tags["width"]?.toDoubleOrNull() ?: when (type) {
            "river" -> 8.0
            "stream" -> 3.0
            "canal" -> 5.0
            else -> return null
        }
        return WaterwayFeature("way/${way.id}", width)
    }
}
