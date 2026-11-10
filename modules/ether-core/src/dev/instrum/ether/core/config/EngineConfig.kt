package dev.instrum.ether.core.config

import dev.instrum.ether.core.log.LogLevel

/**
 * The configuration for an engine instance.
 *
 * Instances are immutable; use [copy] to derive a modified configuration.
 *
 * @property applicationName the human-readable name of the application using the engine.
 * @property logLevel the minimum severity emitted by the engine's default logger.
 * @property targetFramesPerSecond the frame-rate cap, or `0` to run uncapped.
 * @property maximumDeltaSeconds the largest frame delta the engine will report, guarding against
 *   the "spiral of death" after a long stall. Longer frames are clamped to this value.
 * @property enableValidation whether the engine enables extra runtime validation in debug builds.
 */
public data class EngineConfig(
    public val applicationName: String = "Ether Application",
    public val logLevel: LogLevel = LogLevel.Info,
    public val targetFramesPerSecond: Int = 0,
    public val maximumDeltaSeconds: Float = 0.25f,
    public val enableValidation: Boolean = true,
) {
    init {
        require(targetFramesPerSecond >= 0) {
            "targetFramesPerSecond must not be negative, got $targetFramesPerSecond"
        }
        require(maximumDeltaSeconds > 0f) {
            "maximumDeltaSeconds must be positive, got $maximumDeltaSeconds"
        }
    }

    /** The target frame interval in nanoseconds, or `null` when the engine runs uncapped. */
    public val targetFrameIntervalNanos: Long?
        get() = if (targetFramesPerSecond <= 0) {
            null
        } else {
            1_000_000_000L / targetFramesPerSecond
        }
}
