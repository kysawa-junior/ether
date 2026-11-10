package dev.instrum.ether.map.runtime

/**
 * The rendered map mode of a frame.
 *
 * [Overhead2D] is the orthographic top-down view shown while the camera is far away;
 * [Street3D] is the perspective view used when the camera is extremely close.
 */
public enum class MapMode {
    Overhead2D,
    Street3D,
}

/**
 * User configurable 2D/3D swap policy.
 *
 * The camera distance drives the mode: closer than [closeThresholdMeters] switches to Street3D;
 * farther than [farThresholdMeters] switches back to Overhead2D; in between, the current mode is
 * kept (hysteresis prevents oscillation at the boundary). Defaults follow the user decision:
 * 220 m close / 420 m far.
 */
public data class MapModePolicy(
    public val closeThresholdMeters: Float,
    public val farThresholdMeters: Float,
) {
    init {
        require(closeThresholdMeters > 0f) { "closeThresholdMeters deve ser positivo: $closeThresholdMeters" }
        require(farThresholdMeters > closeThresholdMeters) {
            "farThresholdMeters ($farThresholdMeters) deve ser maior que closeThresholdMeters ($closeThresholdMeters)"
        }
    }

    public companion object {
        /** User approved defaults: 220 m close, 420 m far. */
        public val Defaults: MapModePolicy = MapModePolicy(closeThresholdMeters = 220f, farThresholdMeters = 420f)
    }
}
