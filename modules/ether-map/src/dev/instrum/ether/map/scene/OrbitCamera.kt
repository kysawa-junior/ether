package dev.instrum.ether.map.scene

import dev.instrum.ether.map.camera.Camera
import dev.instrum.ether.map.camera.OrthographicCamera
import dev.instrum.ether.map.camera.PerspectiveCamera
import dev.instrum.ether.map.runtime.MapMode
import dev.instrum.ether.map.runtime.MapModePolicy
import dev.instrum.ether.math.Aabb
import dev.instrum.ether.math.Mat4
import dev.instrum.ether.math.Vec3
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private val HALF_PI: Float = (PI / 2.0).toFloat()

/**
 * Map camera - orbit state by goals + exponential damping, with the user 2D/3D swap policy
 * ([MapModePolicy]) replacing the removed single `MAP_MODE_DISTANCE_METERS` threshold.
 *
 * Startup is Overhead2D: while the camera is far away the map renders flat top-down; only when it
 * comes closer than the close threshold does the perspective 3D tilt in. Between the close and far
 * thresholds the current mode is kept (hysteresis, no oscillation).
 */
public class OrbitCamera(
    public var target: Vec3 = Vec3.ZERO,
    public var azimuthRadians: Float = 0f,
    public var elevationRadians: Float = 0.9f,
    public var distanceMeters: Float = 1500f,
    public val minDistance: Float = 80f,
    public val maxDistance: Float = 25000f,
    public val smoothing: Float = 12f,
    public val modePolicy: MapModePolicy = MapModePolicy.Defaults,
) {
    public var goalTarget: Vec3 = target
    public var goalAzimuth: Float = azimuthRadians
    public var goalElevation: Float = elevationRadians
    public var goalDistance: Float = distanceMeters

    public var targetBounds: Aabb? = null

    /** Whether the zoom is currently in the flat 2D map mode (far away). */
    private var flat: Boolean = distanceMeters > modePolicy.farThresholdMeters

    /** The frame mode; resolved from [flat] (stable for one frame). */
    public val mode: MapMode
        get() = if (flat) MapMode.Overhead2D else MapMode.Street3D

    public val position: Vec3
        get() = Vec3(
            target.x + distanceMeters * cos(elevationRadians) * cos(azimuthRadians),
            target.y + distanceMeters * sin(elevationRadians),
            target.z + distanceMeters * cos(elevationRadians) * sin(azimuthRadians),
        )

    /** Advances the damping (call once per frame). */
    public fun tick(deltaSeconds: Float) {
        // Hysteresis: leave 2D only below the close threshold; return to 2D only above the far one.
        val flat = if (this.flat) {
            goalDistance >= modePolicy.closeThresholdMeters
        } else {
            goalDistance > modePolicy.farThresholdMeters
        }
        if (flat) {
            // 2D map: zenith (lookAt is still well defined with the epsilon).
            goalElevation = TOP_DOWN_ELEVATION
        } else if (this.flat) {
            // Came close enough: reveal the 3D with the default tilt.
            goalElevation = TILT_ELEVATION
        }
        this.flat = flat
        if (smoothing <= 0f || deltaSeconds <= 0f) {
            target = goalTarget
            azimuthRadians = goalAzimuth
            elevationRadians = goalElevation
            distanceMeters = goalDistance
            return
        }
        val t = 1f - exp(-smoothing * deltaSeconds)
        target = Vec3(
            target.x + (goalTarget.x - target.x) * t,
            target.y + (goalTarget.y - target.y) * t,
            target.z + (goalTarget.z - target.z) * t,
        )
        azimuthRadians += (goalAzimuth - azimuthRadians) * t
        elevationRadians += (goalElevation - elevationRadians) * t
        distanceMeters += (goalDistance - distanceMeters) * t
    }

    /** Orbital rotation: dx/dy in gesture pixels. */
    public fun rotate(dx: Float, dy: Float) {
        goalAzimuth += dx * 0.005f
        goalElevation = (goalElevation - dy * 0.005f).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
    }

    public fun zoom(factor: Float) {
        goalDistance = (goalDistance / factor).coerceIn(minDistance, maxDistance)
    }

    /** Zoom directed at the ground point under the cursor. */
    public fun zoomToward(factor: Float, groundPoint: Vec3) {
        val before = goalDistance
        zoom(factor)
        val applied = before / goalDistance
        val k = 1f - 1f / applied
        goalTarget = Vec3(
            goalTarget.x + (groundPoint.x - goalTarget.x) * k,
            goalTarget.y,
            goalTarget.z + (groundPoint.z - goalTarget.z) * k,
        )
        clampGoalTarget()
    }

    /** Pan: moves the target along the camera ground axes. */
    public fun pan(dx: Float, dy: Float, speed: Float = 0.0016f) {
        val k = goalDistance * speed
        val fwdX = -cos(goalAzimuth)
        val fwdZ = -sin(goalAzimuth)
        val rightX = -fwdZ
        val rightZ = fwdX
        goalTarget = Vec3(
            goalTarget.x + (-rightX * dx + fwdX * dy) * k,
            goalTarget.y,
            goalTarget.z + (-rightZ * dx + fwdZ * dy) * k,
        )
        clampGoalTarget()
    }

    /** Fits the scene/region bounds (long axis lying on the width). */
    public fun fit(bounds: Aabb, instant: Boolean = false) {
        goalTarget = Vec3(bounds.center.x, 0f, bounds.center.z)
        val dx = bounds.max.x - bounds.min.x
        val dz = bounds.max.z - bounds.min.z
        goalDistance = (sqrt(dx * dx + dz * dz) * 1.15f).coerceIn(minDistance, maxDistance)
        goalElevation = 0.9f
        goalAzimuth = if (dx >= dz) HALF_PI else 0f
        confine(bounds)
        if (instant) tick(1e9f)
    }

    /** Frames an entity, keeping the current angle. */
    public fun frameBounds(bounds: Aabb, margin: Float = 2.2f) {
        goalTarget = Vec3(bounds.center.x, 0f, bounds.center.z)
        val dx = bounds.max.x - bounds.min.x
        val dz = bounds.max.z - bounds.min.z
        val diagonal = maxOf(sqrt(dx * dx + dz * dz), 30f)
        goalDistance = (diagonal * margin).coerceIn(minDistance, maxDistance)
        clampGoalTarget()
    }

    /** Sets the target bounds (null removes) and confines the current target. */
    public fun confine(bounds: Aabb?) {
        targetBounds = bounds
        clampGoalTarget()
        if (bounds != null) {
            target = Vec3(
                target.x.coerceIn(bounds.min.x, bounds.max.x),
                target.y,
                target.z.coerceIn(bounds.min.z, bounds.max.z),
            )
        }
    }

    private fun clampGoalTarget() {
        val bounds = targetBounds ?: return
        goalTarget = Vec3(
            goalTarget.x.coerceIn(bounds.min.x, bounds.max.x),
            goalTarget.y,
            goalTarget.z.coerceIn(bounds.min.z, bounds.max.z),
        )
    }

    /** Intersection of the screen ray (origin = camera position) with the ground plane. */
    public fun groundPointAt(
        viewProjection: Mat4,
        cameraPosition: Vec3,
        pixelX: Float,
        pixelY: Float,
        width: Int,
        height: Int,
    ): Vec3 {
        val ray = Raycast.screenRay(viewProjection, cameraPosition, pixelX, pixelY, width, height)
        // t such that origin + dir*t has y = 0.
        if (ray.second.y >= -1e-6f) return Vec3(target.x, 0f, target.z)
        val t = -ray.first.y / ray.second.y
        return Vec3(ray.first.x + ray.second.x * t, 0f, ray.first.z + ray.second.z * t)
    }

    /** Camera for the current mode: orthographic zenith for 2D, perspective for 3D. */
    public fun camera(aspect: Float): Camera = when (mode) {
        MapMode.Overhead2D -> OrthographicCamera(
            position = Vec3(target.x, target.y + goalDistance, target.z),
            target = target,
            heightMeters = goalDistance,
            near = 1f,
            far = goalDistance * 2f + 1000f,
        )
        MapMode.Street3D -> PerspectiveCamera(
            position = position,
            target = target,
            fovYRadians = 0.9f,
            near = 80f,
            far = 25000f,
        )
    }

    public companion object {
        public const val MIN_ELEVATION: Float = 0.08f // ~4.6 degrees

        public val MAX_ELEVATION: Float = HALF_PI * 0.94f // ~85 degrees

        /** Zenith of the 2D map mode (epsilon so lookAt does not degenerate). */
        public val TOP_DOWN_ELEVATION: Float = HALF_PI - 1e-4f

        /** 3D tilt assumed when the zoom comes closer than the threshold. */
        public const val TILT_ELEVATION: Float = 0.9f
    }

    /** Orthographic height covering the perspective view at the current distance. */
    public fun orthographicHeightMeters(fovYRadians: Float = 0.9f): Float =
        goalDistance * 2f * kotlin.math.tan(fovYRadians * 0.5f)

    /** Shared projection helper so the runtime and camera agree on Vulkan conventions. */
    public fun viewProjection(aspect: Float): Mat4 = camera(aspect).viewProjection(aspect)
}
