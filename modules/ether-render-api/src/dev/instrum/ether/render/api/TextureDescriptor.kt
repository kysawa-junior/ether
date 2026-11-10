package dev.instrum.ether.render.api

/**
 * Texture dimensions supported by Ether.
 */
public enum class TextureDimension {
    /** One-dimensional texture. */
    D1,

    /** Two-dimensional texture, optionally arrayed. */
    D2,

    /** Three-dimensional texture. */
    D3,
}

/**
 * Texture formats supported by the portable render API.
 *
 * @property isDepthStencil whether the format is a depth or depth-stencil format.
 * @property isSrgb whether color values are sRGB encoded.
 * @property bytesPerPixel the uncompressed size of one pixel, in bytes.
 */
public enum class TextureFormat(
    public val isDepthStencil: Boolean,
    public val isSrgb: Boolean,
    public val bytesPerPixel: Int,
) {
    /** Single-channel 8-bit unsigned normalized. */
    R8Unorm(isDepthStencil = false, isSrgb = false, bytesPerPixel = 1),

    /** Two-channel 8-bit unsigned normalized. */
    Rg8Unorm(isDepthStencil = false, isSrgb = false, bytesPerPixel = 2),

    /** Four-channel 8-bit unsigned normalized. */
    Rgba8Unorm(isDepthStencil = false, isSrgb = false, bytesPerPixel = 4),

    /** Four-channel 8-bit sRGB. */
    Rgba8Srgb(isDepthStencil = false, isSrgb = true, bytesPerPixel = 4),

    /** Four-channel 8-bit BGRA, unsigned normalized. */
    Bgra8Unorm(isDepthStencil = false, isSrgb = false, bytesPerPixel = 4),

    /** Four-channel 8-bit BGRA, sRGB encoded. */
    Bgra8Srgb(isDepthStencil = false, isSrgb = true, bytesPerPixel = 4),

    /** Four-channel 16-bit floating point. */
    Rgba16Float(isDepthStencil = false, isSrgb = false, bytesPerPixel = 8),

    /** Four-channel 32-bit floating point. */
    Rgba32Float(isDepthStencil = false, isSrgb = false, bytesPerPixel = 16),

    /** 16-bit unsigned normalized depth. */
    Depth16Unorm(isDepthStencil = true, isSrgb = false, bytesPerPixel = 2),

    /** 24-bit depth with no stencil. */
    Depth24Plus(isDepthStencil = true, isSrgb = false, bytesPerPixel = 4),

    /** 24-bit depth plus 8-bit stencil. */
    Depth24PlusStencil8(isDepthStencil = true, isSrgb = false, bytesPerPixel = 4),

    /** 32-bit floating point depth. */
    Depth32Float(isDepthStencil = true, isSrgb = false, bytesPerPixel = 4),
}

/**
 * How a texture will be used during its lifetime.
 */
public enum class TextureUsage {
    /** Sampled by shaders. */
    Sampled,

    /** Used as a color attachment. */
    RenderTarget,

    /** Used as a depth or depth-stencil attachment. */
    DepthStencil,

    /** Used as a storage texture. */
    Storage,

    /** Source of a copy operation. */
    CopySource,

    /** Destination of a copy operation. */
    CopyDestination,
}

/**
 * Describes a GPU texture.
 *
 * @property width width in texels; must be positive.
 * @property height height in texels; must be positive.
 * @property depthOrLayers depth for [TextureDimension.D3], array layers for [TextureDimension.D2].
 * @property mipLevels number of mip levels, including the base level.
 * @property sampleCount samples per texel; a power of two between 1 and 64.
 * @property format the texel format.
 * @property usage the non-empty set of usages.
 * @property dimension the texture dimension.
 * @property label an optional debug label.
 */
public data class TextureDescriptor(
    public val width: Int,
    public val height: Int,
    public val depthOrLayers: Int = 1,
    public val mipLevels: Int = 1,
    public val sampleCount: Int = 1,
    public val format: TextureFormat,
    public val usage: Set<TextureUsage>,
    public val dimension: TextureDimension = TextureDimension.D2,
    public val label: String? = null,
) {
    init {
        require(width > 0) { "Texture width must be positive, was $width" }
        require(height > 0) { "Texture height must be positive, was $height" }
        require(depthOrLayers > 0) { "Texture depth or layer count must be positive" }
        require(mipLevels > 0) { "Texture mip level count must be positive" }
        require(sampleCount > 0) { "Texture sample count must be positive" }
        require(sampleCount <= 64) { "Texture sample count must not exceed 64" }
        require(sampleCount == 1 || (sampleCount and (sampleCount - 1)) == 0) {
            "Texture sample count must be a power of two, was $sampleCount"
        }
        require(usage.isNotEmpty()) { "Texture usage must not be empty" }
        val mipExtent = if (dimension == TextureDimension.D3) {
            maxOf(width, height, depthOrLayers)
        } else {
            maxOf(width, height)
        }
        require(mipLevels <= maxMipLevels(mipExtent)) {
            "Texture mip level count $mipLevels exceeds the maximum for ${width}x${height}x$depthOrLayers"
        }

        when (dimension) {
            TextureDimension.D1 -> {
                require(height == 1 && depthOrLayers == 1) {
                    "1D textures must have height 1 and depth 1"
                }
            }

            TextureDimension.D2 -> Unit

            TextureDimension.D3 -> {
                require(depthOrLayers > 1) {
                    "3D textures must have more than one depth slice"
                }
            }
        }

        require(!(TextureUsage.RenderTarget in usage && TextureUsage.DepthStencil in usage)) {
            "A texture cannot be both a color and depth/stencil attachment"
        }
        if (format.isDepthStencil) {
            require(TextureUsage.RenderTarget !in usage) {
                "Depth formats cannot be used as color render targets"
            }
        } else {
            require(TextureUsage.DepthStencil !in usage) {
                "Color formats cannot be used as depth/stencil attachments"
            }
        }
    }
}

private fun maxMipLevels(extent: Int): Int {
    var largest = extent
    var levels = 1
    while (largest > 1) {
        largest /= 2
        levels++
    }
    return levels
}
