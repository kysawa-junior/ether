package dev.instrum.ether.map.mesh

import dev.instrum.ether.math.Vec3

/**
 * Backend independent mesh: interleaved positions/colors plus indices.
 *
 * Colors are per vertex RGB already shaded against the entity base material (the fake directional
 * building facade shading lives in [Style]).
 */
public class MeshData(
    public val positions: FloatArray,
    public val colors: FloatArray,
    public val indices: IntArray,
) {
    public val vertexCount: Int
        get() = positions.size / 3

    public val triangleCount: Int
        get() = indices.size / 3

    public val isValid: Boolean
        get() {
            if (positions.size % 3 != 0 || colors.size != vertexCount * 3) return false
            for (value in positions) if (!value.isFinite()) return false
            for (index in indices) if (index < 0 || index >= vertexCount) return false
            return indices.size % 3 == 0
        }
}

/** Centralized visual style - the shading values live here. */
public object Style {
    public const val WALL_AMBIENT: Float = 0.55f
    public const val WALL_DIFFUSE: Float = 0.45f
    public const val ROOF_SHADE: Float = 1.0f
    public const val FLAT_PAD_LIFT_METERS: Float = 0.06f
    public const val MIN_BUILDING_HEIGHT: Float = 2.5f
    public const val MAX_BUILDING_HEIGHT: Float = 300.0f
    public const val POINT_EPSILON: Float = 0.01f
    public const val MITER_LIMIT: Float = 2.0f

    /** Fake light direction (points TOWARDS the light; northwest). */
    public val LIGHT: Vec3 = Vec3(-0.5f, 0.0f, -0.85f).normalized()
}
