package dev.instrum.ether.render.api

/**
 * Optional GPU features that a backend may support.
 *
 * Engine code must gate features on [GraphicsCapabilities.features] rather than checking the
 * backend type. A backend reports a feature only when it can execute it.
 */
public enum class GraphicsFeature {
    /** Compute shader pipelines and dispatch commands. */
    ComputeShaders,

    /** GPU timestamp queries. */
    TimestampQueries,

    /** Indirect draw commands. */
    IndirectDraw,

    /** Multi-draw indirect commands. */
    MultiDrawIndirect,

    /** Instanced drawing. */
    InstancedDrawing,

    /** BC compressed texture formats. */
    TextureCompressionBc,

    /** ETC2 compressed texture formats. */
    TextureCompressionEtc2,

    /** ASTC compressed texture formats. */
    TextureCompressionAstc,

    /** 16-bit floating point shader arithmetic. */
    Float16Shader,

    /** Filtering of 32-bit floating point textures. */
    Float32Filterable,

    /** Dual-source blending. */
    DualSourceBlending,

    /** Storage buffers. */
    StorageBuffers,

    /** Storage textures. */
    StorageTextures,

    /** Rendering into 3D texture slices. */
    RenderTo3DTexture,

    /** Multisample resolve operations. */
    MultisampleResolve,

    /** Anisotropic texture filtering. */
    AnisotropicFiltering,

    /** Depth clamping. */
    DepthClamp,

    /** User clip distances. */
    ClipDistances,

    /** Wireframe rasterization. */
    WireframeFill,
}

/**
 * Numeric limits reported by a rendering backend.
 *
 * Limits with a value of zero mean the corresponding resource or operation is unsupported. Sizes
 * are in bytes unless the property name says otherwise.
 */
public data class GraphicsLimits(
    public val maxTexture1DSize: Int = 2_048,
    public val maxTexture2DSize: Int = 2_048,
    public val maxTexture3DSize: Int = 256,
    public val maxTextureCubeSize: Int = 2_048,
    public val maxTextureArrayLayers: Int = 256,
    public val maxColorAttachments: Int = 4,
    public val maxSampleCount: Int = 4,
    public val maxVertexBuffers: Int = 16,
    public val maxVertexAttributes: Int = 16,
    public val maxVertexBufferStride: Int = 2_048,
    public val maxBindGroups: Int = 4,
    public val maxBindingsPerGroup: Int = 16,
    public val maxSampledTexturesPerStage: Int = 16,
    public val maxSamplersPerStage: Int = 16,
    public val maxUniformBuffersPerStage: Int = 12,
    public val maxStorageBuffersPerStage: Int = 0,
    public val maxUniformBufferBindingSize: Long = 16_384L,
    public val maxStorageBufferBindingSize: Long = 0L,
    public val maxBufferSize: Long = 268_435_456L,
    public val maxPushConstantSize: Int = 0,
    public val maxComputeWorkgroupSizeX: Int = 0,
    public val maxComputeWorkgroupSizeY: Int = 0,
    public val maxComputeWorkgroupSizeZ: Int = 0,
    public val maxComputeInvocations: Int = 0,
    public val maxDrawCallsPerFrame: Int = 100_000,
    public val maxFramesInFlight: Int = 2,
    public val maxSamplerAnisotropy: Int = 16,
) {
    init {
        require(maxTexture1DSize > 0) { "maxTexture1DSize must be positive" }
        require(maxTexture2DSize > 0) { "maxTexture2DSize must be positive" }
        require(maxTexture3DSize > 0) { "maxTexture3DSize must be positive" }
        require(maxTextureCubeSize > 0) { "maxTextureCubeSize must be positive" }
        require(maxTextureArrayLayers > 0) { "maxTextureArrayLayers must be positive" }
        require(maxColorAttachments > 0) { "maxColorAttachments must be positive" }
        require(maxSampleCount > 0) { "maxSampleCount must be positive" }
        require(maxVertexBuffers > 0) { "maxVertexBuffers must be positive" }
        require(maxVertexAttributes > 0) { "maxVertexAttributes must be positive" }
        require(maxVertexBufferStride > 0) { "maxVertexBufferStride must be positive" }
        require(maxBufferSize > 0) { "maxBufferSize must be positive" }
        require(maxDrawCallsPerFrame > 0) { "maxDrawCallsPerFrame must be positive" }
        require(maxFramesInFlight > 0) { "maxFramesInFlight must be positive" }
        require(maxSamplerAnisotropy >= 1) { "maxSamplerAnisotropy must be at least 1" }
        require(maxBindGroups >= 0) { "maxBindGroups must not be negative" }
        require(maxBindingsPerGroup >= 0) { "maxBindingsPerGroup must not be negative" }
        require(maxSampledTexturesPerStage >= 0) { "maxSampledTexturesPerStage must not be negative" }
        require(maxSamplersPerStage >= 0) { "maxSamplersPerStage must not be negative" }
        require(maxUniformBuffersPerStage >= 0) { "maxUniformBuffersPerStage must not be negative" }
        require(maxStorageBuffersPerStage >= 0) { "maxStorageBuffersPerStage must not be negative" }
        require(maxUniformBufferBindingSize >= 0) { "maxUniformBufferBindingSize must not be negative" }
        require(maxStorageBufferBindingSize >= 0) { "maxStorageBufferBindingSize must not be negative" }
        require(maxPushConstantSize >= 0) { "maxPushConstantSize must not be negative" }
        require(maxComputeWorkgroupSizeX >= 0) { "maxComputeWorkgroupSizeX must not be negative" }
        require(maxComputeWorkgroupSizeY >= 0) { "maxComputeWorkgroupSizeY must not be negative" }
        require(maxComputeWorkgroupSizeZ >= 0) { "maxComputeWorkgroupSizeZ must not be negative" }
        require(maxComputeInvocations >= 0) { "maxComputeInvocations must not be negative" }
    }

    public companion object {
        /**
         * Limits suitable for a conservative web-class device.
         */
        public val Conservative: GraphicsLimits = GraphicsLimits()

        /**
         * Generous synthetic limits used by the null backend and capability tests.
         *
         * These numbers are not a hardware support claim.
         */
        public val Permissive: GraphicsLimits = GraphicsLimits(
            maxTexture1DSize = 16_384,
            maxTexture2DSize = 16_384,
            maxTexture3DSize = 2_048,
            maxTextureCubeSize = 16_384,
            maxTextureArrayLayers = 2_048,
            maxColorAttachments = 8,
            maxSampleCount = 8,
            maxVertexBuffers = 32,
            maxVertexAttributes = 32,
            maxVertexBufferStride = 4_096,
            maxBindGroups = 8,
            maxBindingsPerGroup = 32,
            maxSampledTexturesPerStage = 32,
            maxSamplersPerStage = 32,
            maxUniformBuffersPerStage = 16,
            maxStorageBuffersPerStage = 16,
            maxUniformBufferBindingSize = 65_536L,
            maxStorageBufferBindingSize = 1_073_741_824L,
            maxBufferSize = 2_147_483_648L,
            maxPushConstantSize = 128,
            maxComputeWorkgroupSizeX = 1_024,
            maxComputeWorkgroupSizeY = 1_024,
            maxComputeWorkgroupSizeZ = 64,
            maxComputeInvocations = 1_024,
            maxDrawCallsPerFrame = 1_000_000,
            maxFramesInFlight = 3,
            maxSamplerAnisotropy = 16,
        )
    }
}

/**
 * Everything a backend can do, reported as features, numeric limits, supported formats, and layout
 * conventions.
 *
 * @property backend the backend that produced this report.
 * @property features the optional features the backend can execute.
 * @property limits numeric limits and resource caps.
 * @property supportedTextureFormats texture formats the backend can create.
 * @property clipSpaceDepthZeroToOne whether clip-space depth is `[0, 1]` (otherwise `[-1, 1]`).
 * @property framebufferOriginBottomLeft whether framebuffer coordinates start at the bottom left.
 * @property supportsMappableBuffers whether buffers can be mapped for CPU access.
 * @property supportsPersistentMappedBuffers whether buffers can stay mapped across frames.
 */
public data class GraphicsCapabilities(
    public val backend: BackendKind,
    public val features: Set<GraphicsFeature>,
    public val limits: GraphicsLimits,
    public val supportedTextureFormats: Set<TextureFormat>,
    public val clipSpaceDepthZeroToOne: Boolean,
    public val framebufferOriginBottomLeft: Boolean,
    public val supportsMappableBuffers: Boolean,
    public val supportsPersistentMappedBuffers: Boolean,
) {
    init {
        require(supportedTextureFormats.isNotEmpty()) {
            "supportedTextureFormats must not be empty"
        }
    }

    /** Whether [feature] is reported by this backend. */
    public fun supports(feature: GraphicsFeature): Boolean = feature in features

    /** Whether [format] can be created by this backend. */
    public fun supports(format: TextureFormat): Boolean = format in supportedTextureFormats
}
