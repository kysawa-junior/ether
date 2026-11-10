package dev.instrum.ether.map.camera

import dev.instrum.ether.math.Mat4
import dev.instrum.ether.math.Vec3
import kotlin.math.tan

public enum class CameraProjection {
    Perspective,
    Orthographic,
}

/**
 * Plane in the `ax + by + cz + dw = 0` form; [distance] is positive on the interior side of the
 * frustum planes.
 */
public data class Plane(
    public val x: Float,
    public val y: Float,
    public val z: Float,
    public val w: Float,
) {
    public fun distance(point: Vec3): Float = x * point.x + y * point.y + z * point.z + w
}

/**
 * View frustum as six planes. Culling is conservative: a sphere or box touching a plane counts as
 * intersecting.
 */
public data class Frustum(
    public val left: Plane,
    public val right: Plane,
    public val bottom: Plane,
    public val top: Plane,
    public val near: Plane,
    public val far: Plane,
) {
    public fun intersectsSphere(center: Vec3, radius: Float): Boolean =
        left.distance(center) >= -radius &&
            right.distance(center) >= -radius &&
            bottom.distance(center) >= -radius &&
            top.distance(center) >= -radius &&
            near.distance(center) >= -radius &&
            far.distance(center) >= -radius

    public fun intersectsBox(min: Vec3, max: Vec3): Boolean {
        val center = (min + max) * 0.5f
        val radius = (max - min).length * 0.5f
        return intersectsSphere(center, radius)
    }
}

/** Camera abstraction shared by the orthographic 2D and perspective 3D paths. */
public interface Camera {
    public val position: Vec3
    public val projection: CameraProjection
    public fun viewMatrix(): Mat4
    public fun projectionMatrix(aspect: Float): Mat4
    public fun viewProjection(aspect: Float): Mat4 = projectionMatrix(aspect) * viewMatrix()
}

public class PerspectiveCamera(
    public override var position: Vec3 = Vec3(0f, 100f, 100f),
    public var target: Vec3 = Vec3.ZERO,
    public var up: Vec3 = Vec3(0f, 1f, 0f),
    public var fovYRadians: Float = 1.0471976f, // 60 degrees.
    public var near: Float = 0.1f,
    public var far: Float = 10_000f,
) : Camera {
    override val projection: CameraProjection
        get() = CameraProjection.Perspective

    override fun viewMatrix(): Mat4 = Mat4.lookAt(position, target, up)

    override fun projectionMatrix(aspect: Float): Mat4 =
        VulkanProjections.perspective(fovYRadians, aspect, near, far)
}

public class OrthographicCamera(
    public override var position: Vec3 = Vec3(0f, 100f, 0f),
    public var target: Vec3 = Vec3.ZERO,
    public var up: Vec3 = Vec3(0f, 0f, -1f),
    public var heightMeters: Float = 100f,
    public var near: Float = 0.1f,
    public var far: Float = 10_000f,
) : Camera {
    override val projection: CameraProjection
        get() = CameraProjection.Orthographic

    override fun viewMatrix(): Mat4 = Mat4.lookAt(position, target, up)

    override fun projectionMatrix(aspect: Float): Mat4 =
        VulkanProjections.orthographic(heightMeters, aspect, near, far)
}

/**
 * Projections with the Vulkan conventions inherited from the removed engine: **Y is flipped**
 * (image row 0 is NDC y = -1, so readbacks are upright) and depth spans **0..1**.
 *
 * Local implementation instead of the `ether-math` factories because `Mat4.perspective`/
 * `Mat4.orthographic` there use GL-style Y (no flip), and the map pipeline relies on the flipped
 * variant end to end (screen picking, frustum culling, CPU rasterization comparisons).
 */
public object VulkanProjections {
    /**
     * Centered orthographic projection; the eye-space view volume is
     * `[-height/2, +height/2] x [-height*aspect/2, +height*aspect/2] x [-far, -near]`.
     */
    public fun orthographic(heightMeters: Float, aspect: Float, near: Float, far: Float): Mat4 {
        require(heightMeters > 0f && aspect > 0f && far > near) { "projeção ortográfica inválida" }
        val halfHeight = heightMeters * 0.5f
        val halfWidth = halfHeight * aspect
        return Mat4.fromColumnMajor(
            floatArrayOf(
                1f / halfWidth, 0f, 0f, 0f,
                0f, -1f / halfHeight, 0f, 0f,
                0f, 0f, -1f / (far - near), 0f,
                0f, 0f, -near / (far - near), 1f,
            ),
        )
    }

    /** Perspective projection with flipped Y and `[0, 1]` depth (Vulkan). */
    public fun perspective(fovYRadians: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(fovYRadians * 0.5f)
        val nf = 1f / (near - far)
        return Mat4.fromColumnMajor(
            floatArrayOf(
                f / aspect, 0f, 0f, 0f,
                0f, -f, 0f, 0f,
                0f, 0f, far * nf, -1f,
                0f, 0f, far * near * nf, 0f,
            ),
        )
    }
}

/** Extracts the six frustum planes from a view-projection matrix (Gribb/Hartmann). */
public object FrustumExtractor {
    public fun extract(viewProjection: Mat4): Frustum {
        fun row(index: Int): FloatArray = floatArrayOf(
            viewProjection[index, 0],
            viewProjection[index, 1],
            viewProjection[index, 2],
            viewProjection[index, 3],
        )
        val r0 = row(0)
        val r1 = row(1)
        val r2 = row(2)
        val r3 = row(3)
        fun plane(sign: Float, a: FloatArray): Plane = Plane(
            r3[0] + sign * a[0],
            r3[1] + sign * a[1],
            r3[2] + sign * a[2],
            r3[3] + sign * a[3],
        ).normalized()
        return Frustum(
            plane(1f, r0),
            plane(-1f, r0),
            plane(1f, r1),
            plane(-1f, r1),
            plane(1f, r2),
            plane(-1f, r2),
        )
    }

    private fun Plane.normalized(): Plane {
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        return if (length < 1e-12f) this else Plane(x / length, y / length, z / length, w / length)
    }
}
