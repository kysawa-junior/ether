package dev.instrum.ether.map.scene

/**
 * Map cartographic palette (linear 0..1 colors) - identity inherited from F.O.G.O.: uniform
 * pavement, cold buildings, selection in active-surface blue.
 */
public object Palette {
    public val GROUND: FloatArray = floatArrayOf(0.05f, 0.06f, 0.08f)
    public fun road(): FloatArray = floatArrayOf(0.40f, 0.42f, 0.46f)
    public val BUILDING: FloatArray = floatArrayOf(0.16f, 0.18f, 0.22f)
    public val SELECTION: FloatArray = floatArrayOf(0.20f, 0.62f, 1.00f)
    public val POLICE_VEHICLE: FloatArray = floatArrayOf(0.30f, 0.55f, 0.90f)
    public val FIRE_VEHICLE: FloatArray = floatArrayOf(0.95f, 0.45f, 0.25f)
}
