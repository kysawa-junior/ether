package dev.instrum.ether.render.api

/**
 * Texture coordinate wrapping mode.
 */
public enum class AddressMode {
    /** Clamp coordinates to the edge texel. */
    ClampToEdge,

    /** Repeat the texture. */
    Repeat,

    /** Repeat the texture, mirroring on each repetition. */
    MirrorRepeat,
}

/**
 * Magnification or minification filter.
 */
public enum class FilterMode {
    /** Nearest-neighbor sampling. */
    Nearest,

    /** Linear interpolation. */
    Linear,
}

/**
 * Mipmap selection filter.
 */
public enum class MipmapFilterMode {
    /** Nearest mip level. */
    Nearest,

    /** Linear interpolation between mip levels. */
    Linear,
}

/**
 * Describes a texture sampler.
 *
 * @property addressModeU wrapping mode on the U axis.
 * @property addressModeV wrapping mode on the V axis.
 * @property addressModeW wrapping mode on the W axis.
 * @property magFilter magnification filter.
 * @property minFilter minification filter.
 * @property mipmapFilter mipmap selection filter.
 * @property lodMinClamp smallest mip level of detail.
 * @property lodMaxClamp largest mip level of detail.
 * @property maxAnisotropy maximum anisotropy; 1 disables anisotropic filtering.
 * @property label an optional debug label.
 */
public data class SamplerDescriptor(
    public val addressModeU: AddressMode = AddressMode.ClampToEdge,
    public val addressModeV: AddressMode = AddressMode.ClampToEdge,
    public val addressModeW: AddressMode = AddressMode.ClampToEdge,
    public val magFilter: FilterMode = FilterMode.Linear,
    public val minFilter: FilterMode = FilterMode.Linear,
    public val mipmapFilter: MipmapFilterMode = MipmapFilterMode.Linear,
    public val lodMinClamp: Float = 0f,
    public val lodMaxClamp: Float = 32f,
    public val maxAnisotropy: Int = 1,
    public val label: String? = null,
) {
    init {
        require(lodMinClamp >= 0f) { "lodMinClamp must not be negative" }
        require(lodMaxClamp >= lodMinClamp) { "lodMaxClamp must not be smaller than lodMinClamp" }
        require(maxAnisotropy >= 1) { "maxAnisotropy must be at least 1" }
    }
}
