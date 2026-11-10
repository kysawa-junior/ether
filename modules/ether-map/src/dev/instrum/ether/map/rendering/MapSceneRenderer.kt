package dev.instrum.ether.map.rendering

import dev.instrum.ether.map.scene.Scene
import dev.instrum.ether.math.Mat4
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferHandle
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.ClearColor
import dev.instrum.ether.render.api.ColorTargetState
import dev.instrum.ether.render.api.CompareFunction
import dev.instrum.ether.render.api.DepthLoadOperation
import dev.instrum.ether.render.api.DepthStencilState
import dev.instrum.ether.render.api.FragmentState
import dev.instrum.ether.render.api.IndexFormat
import dev.instrum.ether.render.api.LoadOperation
import dev.instrum.ether.render.api.RenderPassColorAttachment
import dev.instrum.ether.render.api.RenderPassDepthStencilAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.RenderPipelineHandle
import dev.instrum.ether.render.api.ScissorRect
import dev.instrum.ether.render.api.ShaderModuleDescriptor
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
import dev.instrum.ether.render.api.Viewport
import dev.instrum.ether.render.core.GraphicsDevice

/** Interleaved position xyz + color rgb vertex stride, in bytes. */
private const val VERTEX_STRIDE_BYTES = 24

/**
 * Recorder-backed frame renderer: uploads scene buffers to a [GraphicsDevice] and records one
 * validated render pass per frame (a Vulkan-configured color target with depth 0..1 and the
 * engine's Vulkan Y-flip conventions).
 *
 * The device is injected and stays owned by the caller; [close] releases only the resources this
 * renderer created.
 */
public class MapSceneRenderer(
    private val device: GraphicsDevice,
    width: Int,
    height: Int,
) : MapFrameRenderer {
    public override var renderWidth: Int = width.coerceAtLeast(1)
        private set

    public override var renderHeight: Int = height.coerceAtLeast(1)
        private set

    private var vertexBuffer: BufferHandle? = null
    private var indexBuffer: BufferHandle? = null
    private var indexCount = 0
    private var dynamicBuffer: BufferHandle? = null
    private var dynamicVertexCount = 0
    private var colorTexture: TextureHandle? = null
    private var depthTexture: TextureHandle? = null

    private val vertexShader = device.createShaderModule(
        ShaderModuleDescriptor(SPIRV_PLACEHOLDER, ShaderStage.Vertex, label = "ether-map vs"),
    )
    private val fragmentShader = device.createShaderModule(
        ShaderModuleDescriptor(SPIRV_PLACEHOLDER, ShaderStage.Fragment, label = "ether-map fs"),
    )

    private val pipeline: RenderPipelineHandle = device.createRenderPipeline(
        RenderPipelineDescriptor(
            label = "ether-map scene pipeline",
            vertex = VertexState(
                shader = vertexShader,
                buffers = listOf(
                    VertexBufferLayout(
                        strideBytes = VERTEX_STRIDE_BYTES,
                        stepMode = VertexStepMode.Vertex,
                        attributes = listOf(
                            VertexAttribute(location = 0, format = VertexFormat.Float32x3, offsetBytes = 0),
                            VertexAttribute(location = 1, format = VertexFormat.Float32x3, offsetBytes = 12),
                        ),
                    ),
                ),
            ),
            fragment = FragmentState(
                shader = fragmentShader,
                targets = listOf(ColorTargetState(format = TextureFormat.Rgba8Unorm)),
            ),
            depthStencil = DepthStencilState(
                format = TextureFormat.Depth24Plus,
                depthWriteEnabled = true,
                depthCompare = CompareFunction.Less,
            ),
        ),
    )

    init {
        recreateTargets()
    }

    public override fun uploadScene(scene: Scene) {
        vertexBuffer?.let(device::destroyBuffer)
        indexBuffer?.let(device::destroyBuffer)

        val vertexBytes = packFloats(scene.interleaved)
        val newVertexBuffer = device.createBuffer(
            BufferDescriptor(vertexBytes.size.toLong(), setOf(BufferUsage.Vertex), "ether-map scene vertices"),
        )
        device.writeBuffer(newVertexBuffer, 0, vertexBytes)

        val indexBytes = packInts(scene.indices)
        val newIndexBuffer = device.createBuffer(
            BufferDescriptor(indexBytes.size.toLong(), setOf(BufferUsage.Index), "ether-map scene indices"),
        )
        device.writeBuffer(newIndexBuffer, 0, indexBytes)

        vertexBuffer = newVertexBuffer
        indexBuffer = newIndexBuffer
        indexCount = scene.indices.size
    }

    public override fun uploadDynamic(mesh: FloatArray) {
        dynamicBuffer?.let(device::destroyBuffer)
        dynamicVertexCount = mesh.size / 6
        if (dynamicVertexCount == 0) {
            dynamicBuffer = null
            return
        }
        val bytes = packFloats(mesh)
        val newBuffer = device.createBuffer(
            BufferDescriptor(bytes.size.toLong(), setOf(BufferUsage.Vertex), "ether-map dynamic soup"),
        )
        device.writeBuffer(newBuffer, 0, bytes)
        dynamicBuffer = newBuffer
    }

    public override fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == renderWidth && height == renderHeight) return
        renderWidth = width
        renderHeight = height
        recreateTargets()
    }

    public override fun render(viewProjection: Mat4) {
        val sceneVertexBuffer = vertexBuffer ?: return
        val colorTarget = colorTexture ?: return
        val depthTarget = depthTexture ?: return

        val encoder = device.createCommandEncoder("ether-map frame")
        encoder.beginRenderPass(
            RenderPassDescriptor(
                label = "ether-map frame",
                colorAttachments = listOf(
                    RenderPassColorAttachment(
                        texture = colorTarget,
                        load = LoadOperation.Clear(ClearColor(0.05f, 0.06f, 0.08f, 1f)),
                        store = StoreOp.Store,
                    ),
                ),
                depthStencilAttachment = RenderPassDepthStencilAttachment(
                    texture = depthTarget,
                    depthLoad = DepthLoadOperation.Clear(1f),
                    depthStore = StoreOp.Store,
                ),
            ),
        )
        encoder.setPipeline(pipeline)
        encoder.setViewport(Viewport(0f, 0f, renderWidth.toFloat(), renderHeight.toFloat()))
        encoder.setScissor(ScissorRect(0, 0, renderWidth, renderHeight))

        val staticVertexBuffer = sceneVertexBuffer
        if (indexCount > 0) {
            encoder.setVertexBuffer(0, staticVertexBuffer)
            indexBuffer?.let { encoder.setIndexBuffer(it, IndexFormat.UInt32) }
            encoder.drawIndexed(indexCount)
        }
        val soupBuffer = dynamicBuffer
        if (soupBuffer != null && dynamicVertexCount > 0) {
            encoder.setVertexBuffer(0, soupBuffer)
            encoder.draw(dynamicVertexCount)
        }

        encoder.endRenderPass()
        device.submit(encoder.finish())
    }

    public override fun close() {
        vertexBuffer?.let(device::destroyBuffer)
        indexBuffer?.let(device::destroyBuffer)
        dynamicBuffer?.let(device::destroyBuffer)
        colorTexture?.let(device::destroyTexture)
        depthTexture?.let(device::destroyTexture)
        device.destroyRenderPipeline(pipeline)
        device.destroyShaderModule(vertexShader)
        device.destroyShaderModule(fragmentShader)
        vertexBuffer = null
        indexBuffer = null
        dynamicBuffer = null
        colorTexture = null
        depthTexture = null
    }

    private fun recreateTargets() {
        colorTexture?.let(device::destroyTexture)
        depthTexture?.let(device::destroyTexture)
        colorTexture = device.createTexture(
            TextureDescriptor(
                width = renderWidth,
                height = renderHeight,
                format = TextureFormat.Rgba8Unorm,
                usage = setOf(TextureUsage.RenderTarget),
                label = "ether-map color target",
            ),
        )
        depthTexture = device.createTexture(
            TextureDescriptor(
                width = renderWidth,
                height = renderHeight,
                format = TextureFormat.Depth24Plus,
                usage = setOf(TextureUsage.DepthStencil),
                label = "ether-map depth target",
            ),
        )
    }

    /** Minimal SPIR-V module placeholder; shader compilation is a render HAL milestone. */
    private companion object {
        val SPIRV_PLACEHOLDER = ByteArray(4)
    }
}

/** Packs a [FloatArray] into little-endian raw bytes, the upload layout of the backends. */
internal fun packFloats(values: FloatArray): ByteArray {
    val bytes = ByteArray(values.size * 4)
    for (index in values.indices) {
        val bits = values[index].toRawBits()
        bytes[index * 4] = bits.toByte()
        bytes[index * 4 + 1] = (bits ushr 8).toByte()
        bytes[index * 4 + 2] = (bits ushr 16).toByte()
        bytes[index * 4 + 3] = (bits ushr 24).toByte()
    }
    return bytes
}

/** Packs an [IntArray] into little-endian raw bytes. */
internal fun packInts(values: IntArray): ByteArray {
    val bytes = ByteArray(values.size * 4)
    for (index in values.indices) {
        val bits = values[index]
        bytes[index * 4] = bits.toByte()
        bytes[index * 4 + 1] = (bits ushr 8).toByte()
        bytes[index * 4 + 2] = (bits ushr 16).toByte()
        bytes[index * 4 + 3] = (bits ushr 24).toByte()
    }
    return bytes
}
