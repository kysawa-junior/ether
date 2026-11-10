package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.ColorTargetState
import dev.instrum.ether.render.api.DepthStencilState
import dev.instrum.ether.render.api.FragmentState
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.LoadOperation
import dev.instrum.ether.render.api.ClearColor
import dev.instrum.ether.render.api.MultisampleState
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPassColorAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.RenderPipelineHandle
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderModuleHandle
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.StoreOp
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.VertexAttribute
import dev.instrum.ether.render.api.VertexBufferLayout
import dev.instrum.ether.render.api.VertexFormat
import dev.instrum.ether.render.api.VertexState
import dev.instrum.ether.render.api.VertexStepMode
import dev.instrum.ether.render.nullbackend.NullBackend
import dev.instrum.ether.render.nullbackend.NullBackendDevice
import dev.instrum.ether.render.nullbackend.NullCompletionMode
import dev.instrum.ether.render.nullbackend.defaultNullCapabilities

/** A null-backed device plus the backend device used to inspect tokens and submissions. */
internal class NullTestDevice(
    val device: GraphicsDevice,
    val backend: NullBackendDevice,
)

/** Creates a GraphicsDevice backed by a NullBackend with optional manual completion. */
internal fun createNullTestDevice(
    completionMode: NullCompletionMode = NullCompletionMode.Automatic,
    capabilities: GraphicsCapabilities = defaultNullCapabilities(),
): NullTestDevice {
    val backend = NullBackend(completionMode, capabilities)
    val backendDevice = backend.createDevice(BackendDeviceDescriptor("test")) as NullBackendDevice
    val fixedBackend = object : RenderBackend {
        override val kind: BackendKind
            get() = BackendKind.Null

        override fun isAvailable(): Boolean = true

        override fun capabilities(): GraphicsCapabilities = backend.capabilities()

        override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
            backendDevice
    }
    return NullTestDevice(
        device = GraphicsDevice.create(fixedBackend, BackendDeviceDescriptor("test")),
        backend = backendDevice,
    )
}

/** Builds capabilities for the null backend with overridden features, limits, or formats. */
internal fun testCapabilities(
    features: Set<GraphicsFeature> = GraphicsFeature.entries.toSet(),
    limits: GraphicsLimits = GraphicsLimits.Permissive,
    formats: Set<TextureFormat> = TextureFormat.entries.toSet(),
): GraphicsCapabilities = GraphicsCapabilities(
    backend = BackendKind.Null,
    features = features,
    limits = limits,
    supportedTextureFormats = formats,
    clipSpaceDepthZeroToOne = true,
    framebufferOriginBottomLeft = false,
    supportsMappableBuffers = true,
    supportsPersistentMappedBuffers = true,
)

internal fun GraphicsDevice.createTestTexture(
    width: Int = 64,
    height: Int = 64,
    format: TextureFormat = TextureFormat.Rgba8Unorm,
    usage: Set<TextureUsage> = setOf(TextureUsage.RenderTarget, TextureUsage.CopySource),
    sampleCount: Int = 1,
    mipLevels: Int = 1,
): TextureHandle = createTexture(
    TextureDescriptor(
        width = width,
        height = height,
        mipLevels = mipLevels,
        sampleCount = sampleCount,
        format = format,
        usage = usage,
    ),
)

internal fun GraphicsDevice.createTestShader(stage: ShaderStage): ShaderModuleHandle =
    createShaderModule(
        ShaderModuleDescriptor(
            code = byteArrayOf(1, 2, 3, 4),
            stage = stage,
            label = stage.name,
        ),
    )

internal fun GraphicsDevice.createTestPipeline(
    label: String = "test-pipeline",
    colorFormat: TextureFormat = TextureFormat.Rgba8Unorm,
    colorTargetCount: Int = 1,
    depthFormat: TextureFormat? = null,
    sampleCount: Int = 1,
    vertexLayouts: List<VertexBufferLayout> = emptyList(),
): RenderPipelineHandle {
    val vertexShader = createTestShader(ShaderStage.Vertex)
    val fragmentShader = createTestShader(ShaderStage.Fragment)
    return createRenderPipeline(
        RenderPipelineDescriptor(
            label = label,
            vertex = VertexState(vertexShader, vertexLayouts),
            fragment = FragmentState(
                shader = fragmentShader,
                targets = List(colorTargetCount) { ColorTargetState(colorFormat) },
            ),
            depthStencil = depthFormat?.let { DepthStencilState(it) },
            multisample = MultisampleState(sampleCount),
        ),
    )
}

internal fun positionLayout(): VertexBufferLayout = VertexBufferLayout(
    strideBytes = 12,
    stepMode = VertexStepMode.Vertex,
    attributes = listOf(VertexAttribute(0, VertexFormat.Float32x3, 0)),
)

internal fun colorPass(
    target: TextureHandle,
    label: String = "main",
    resolveTarget: TextureHandle? = null,
): RenderPassDescriptor = RenderPassDescriptor(
    label = label,
    colorAttachments = listOf(
        RenderPassColorAttachment(
            texture = target,
            resolveTarget = resolveTarget,
            load = LoadOperation.Clear(ClearColor.Black),
            store = StoreOp.Store,
        ),
    ),
)
