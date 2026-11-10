package dev.instrum.ether.math

/**
 * A rigid transform composed of translation, rotation and scale.
 *
 * Instances are immutable. [toMatrix] converts the transform into a [Mat4] using the standard
 * `T * R * S` composition order.
 */
public data class Transform(
    public val translation: Vec3 = Vec3.ZERO,
    public val rotation: Quat = Quat.IDENTITY,
    public val scale: Vec3 = Vec3.ONE,
) {
    /** The identity transform: no translation, no rotation, unit scale. */
    public companion object {
        /** The identity transform. */
        public val IDENTITY: Transform = Transform()
    }

    /** This transform converted into a [Mat4]. */
    public fun toMatrix(): Mat4 =
        Mat4.translation(translation) * Mat4.fromQuat(rotation) * Mat4.scale(scale)

    /** The translation component expressed in the coordinate system of [parent]. */
    public fun localTranslationIn(parent: Transform): Vec3 = parent.toMatrix().inverse().transformPoint(translation)

    /**
     * Composes this transform with [child] so that the child is expressed in this transform's space.
     */
    public operator fun times(child: Transform): Transform = Transform(
        translation = translation + rotation.rotate(child.translation * scale),
        rotation = rotation * child.rotation,
        scale = scale * child.scale,
    )
}
