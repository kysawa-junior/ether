package dev.instrum.ether.render.lwjgl

import dev.instrum.ether.render.api.BackendColorAttachment
import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.BackendRenderPassDescriptor
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.ClearColor
import dev.instrum.ether.render.api.ColorTargetState
import dev.instrum.ether.render.api.FragmentState
import dev.instrum.ether.render.api.LoadOperation
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderPassColorAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.ScissorRect
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderModuleHandle
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.StoreOp
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.VertexAttribute
import dev.instrum.ether.render.api.VertexBufferLayout
import dev.instrum.ether.render.api.VertexFormat
import dev.instrum.ether.render.api.VertexState
import dev.instrum.ether.render.api.VertexStepMode
import dev.instrum.ether.render.api.Viewport
import dev.instrum.ether.render.core.GraphicsDevice
import dev.instrum.ether.render.lwjgl.internal.shaders.BuiltinShaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real device smoke tests for the LWJGL Vulkan backend.
 *
 * Every test executes actual GPU work through the LWJGL natives: it creates a pipeline from the
 * built in SPIR-V shaders, renders a triangle into an offscreen texture, reads the texels back,
 * and asserts on pixel values. Driver stacks that ship with desktop Linux distributions (the
 * `llvmpipe` software driver or an installed hardware driver) suffice; no GPU hardware is
 * required.
 */
class LwjglSmokeTest {

    private companion object {
        /** Render target extent shared by both execution paths. */
        const val TARGET_SIZE = 64
    }

    // ------------------------------------------------------------------
    // Backend HAL smoke test with pixel readback
    // ------------------------------------------------------------------

    @Test
    fun triangleRendersRedAtCentreAndClearsCornersBlack() {
        val device = createRealBackendDevice()
        val vertexBuffer = device.createBuffer(
            BufferDescriptor(sizeBytes = 36L, usage = setOf(BufferUsage.Vertex)),
        )
        device.writeBuffer(vertexBuffer, 0L, triangleVertices())
        val target = device.createTexture(
            textureDescriptor(),
        )
        val vertexShader = device.createShaderModule(
            ShaderModuleDescriptor(
                code = BuiltinShaders.TriangleVertex,
                stage = ShaderStage.Vertex,
            ),
        )
        val fragmentShader = device.createShaderModule(
            ShaderModuleDescriptor(
                code = BuiltinShaders.TriangleFragment,
                stage = ShaderStage.Fragment,
            ),
        )
        val pipeline = device.createRenderPipeline(
            RenderPipelineDescriptor(
                label = "smoke-pipeline",
                vertex = VertexState(ShaderModuleHandle(vertexShader), listOf(positionLayout())),
                fragment = FragmentState(
                    shader = ShaderModuleHandle(fragmentShader),
                    targets = listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                ),
            ),
            vertexShaderToken = vertexShader,
            fragmentShaderToken = fragmentShader,
        )
        try {
            val commandBuffer = device.createCommandBuffer(
                "smoke-frame",
                listOf(
                    BackendCommand.BeginRenderPass(
                        BackendRenderPassDescriptor(
                            label = "smoke-pass",
                            colorAttachments = listOf(
                                BackendColorAttachment(
                                    texture = target,
                                    load = LoadOperation.Clear(ClearColor.Black),
                                    store = StoreOp.Store,
                                ),
                            ),
                        ),
                    ),
                    BackendCommand.SetPipeline(pipeline),
                    BackendCommand.SetVertexBuffer(0, vertexBuffer),
                    BackendCommand.SetViewport(
                        Viewport(0f, 0f, TARGET_SIZE.toFloat(), TARGET_SIZE.toFloat()),
                    ),
                    BackendCommand.SetScissor(ScissorRect(0, 0, TARGET_SIZE, TARGET_SIZE)),
                    BackendCommand.Draw(vertexCount = 3),
                    BackendCommand.EndRenderPass,
                ),
            )
            val submission = device.submit(listOf(commandBuffer))
            device.waitIdle()
            assertTrue(
                device.isSubmissionComplete(submission),
                "waitIdle must complete every submitted submission token",
            )

            val bytes = device.readTextureBytes(target)
            assertEquals(
                TARGET_SIZE * TARGET_SIZE * 4,
                bytes.size,
                "Readback must be tightly packed RGBA",
            )

            val centre = colorAt(bytes, 32, 32)
            assertEquals(255, centre[0], "The triangle must cover the texture centre in red")
            assertEquals(0, centre[1], "The triangle fragment must not write green")
            assertEquals(0, centre[2], "The triangle fragment must not write blue")
            assertEquals(255, centre[3], "The triangle fragment must write opaque alpha")

            val corner = colorAt(bytes, 0, 0)
            assertEquals(0, corner[0], "The clear color must leave the corner black")
            assertEquals(0, corner[1], "The clear color must leave the corner black")
            assertEquals(0, corner[2], "The clear color must leave the corner black")
            assertEquals(255, corner[3], "The clear color alpha must stay opaque black")

            // Vulkan NDC y = +0.5 lands on image rows 45-46 (origin at row 0, y descends);
            // row 16 spans the base x 16..47 at the widest row.
            val redPixels = countPixels(bytes) { red, _, _, _ -> red == 255 }
            assertEquals(512, redPixels, "The triangle rasterization must cover 512 pixels")
            val row16 = rowExtent(bytes, 16)
            assertEquals(16, row16.first, "Base row 16 must start filling at x=16")
            assertEquals(47, row16.last, "Base row 16 must span x=16..47")
            val row45 = rowExtent(bytes, 45)
            assertEquals(31, row45.first, "Apex row 45 must start filling at x=31")
            assertEquals(32, row45.last, "Apex row 45 must span x=31..32")
            for (row in listOf(0, 15, 47, 63)) {
                assertEquals(-1, rowExtent(bytes, row).first, "Row $row must be fully cleared")
            }

        } finally {
            device.destroy(vertexBuffer)
            device.destroy(target)
            device.destroy(vertexShader)
            device.destroy(fragmentShader)
            device.destroy(pipeline)
            device.close()
        }
    }

    // ------------------------------------------------------------------
    // Portable GraphicsDevice integration on the real backend
    // ------------------------------------------------------------------

    @Test
    fun graphicsDeviceSubmitsTriangleOnLwjglVulkan() {
        val backendDevice = createRealBackendDevice()
        val backend = backendFor(backendDevice)
        val device = GraphicsDevice.create(backend, BackendDeviceDescriptor("ether-portable"))
        try {
            assertEquals(BackendKind.Vulkan, device.kind)
            assertTrue(
                device.capabilities.supportedTextureFormats.contains(TextureFormat.Rgba8Unorm),
                "The Vulkan backend must support Rgba8Unorm render targets",
            )
            assertTrue(
                device.capabilities.clipSpaceDepthZeroToOne,
                "Vulkan clip space depth spans [0, 1]",
            )
            assertTrue(
                !device.capabilities.framebufferOriginBottomLeft,
                "Vulkan framebuffer origins start at the top left",
            )

            val vertexBuffer = device.createBuffer(
                BufferDescriptor(sizeBytes = 36L, usage = setOf(BufferUsage.Vertex)),
            )
            device.writeBuffer(vertexBuffer, 0L, triangleVertices())
            val target = device.createTexture(textureDescriptor())
            val vertexShader = device.createShaderModule(
                ShaderModuleDescriptor(
                    code = BuiltinShaders.TriangleVertex,
                    stage = ShaderStage.Vertex,
                ),
            )
            val fragmentShader = device.createShaderModule(
                ShaderModuleDescriptor(
                    code = BuiltinShaders.TriangleFragment,
                    stage = ShaderStage.Fragment,
                ),
            )
            val pipeline = device.createRenderPipeline(
                RenderPipelineDescriptor(
                    label = "smoke-pipeline",
                    vertex = VertexState(vertexShader, listOf(positionLayout())),
                    fragment = FragmentState(
                        shader = fragmentShader,
                        targets = listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                    ),
                ),
            )

            val encoder = device.createCommandEncoder("smoke-frame")
            encoder.beginRenderPass(
                RenderPassDescriptor(
                    label = "smoke-pass",
                    colorAttachments = listOf(
                        RenderPassColorAttachment(
                            texture = target,
                            load = LoadOperation.Clear(ClearColor.Black),
                            store = StoreOp.Store,
                        ),
                    ),
                ),
            )
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertexBuffer)
            encoder.setViewport(
                Viewport(0f, 0f, TARGET_SIZE.toFloat(), TARGET_SIZE.toFloat()),
            )
            encoder.setScissor(ScissorRect(0, 0, TARGET_SIZE, TARGET_SIZE))
            encoder.draw(vertexCount = 3)
            encoder.endRenderPass()
            device.submit(encoder.finish())
            device.waitIdle()
            assertEquals(
                0,
                device.pendingSubmissionCount,
                "waitIdle must retire every submission",
            )

            device.destroyRenderPipeline(pipeline)
            device.destroyShaderModule(vertexShader)
            device.destroyShaderModule(fragmentShader)
            device.destroyTexture(target)
            device.destroyBuffer(vertexBuffer)
            assertEquals(0, device.liveResourceCount, "Every resource must have been destroyed")
        } finally {
            device.close()
            assertTrue(device.isClosed)
        }
    }

    // ------------------------------------------------------------------
    // Readback diagnostics
    // ------------------------------------------------------------------

    @Test
    fun unknownTextureTokenReadbackFails() {
        val device = createRealBackendDevice()
        try {
            assertFailsWith<IllegalArgumentException> {
                device.readTextureBytes(987_654_321L)
            }
        } finally {
            device.close()
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun createRealBackendDevice(): LwjglVulkanBackendDevice {
        val backend = LwjglVulkanBackend()
        assertTrue(
            backend.isAvailable(),
            "A Vulkan device is required; this test exercises real GPU submission",
        )
        return backend.createDevice(BackendDeviceDescriptor("ether-lwjgl-smoke"))
            as LwjglVulkanBackendDevice
    }

    private fun backendFor(backendDevice: LwjglVulkanBackendDevice): RenderBackend =
        object : RenderBackend {
            override val kind: BackendKind
                get() = BackendKind.Vulkan

            override fun isAvailable(): Boolean = true

            override fun capabilities() = backendDevice.capabilities

            override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
                backendDevice
        }

    private fun textureDescriptor(): TextureDescriptor = TextureDescriptor(
        width = TARGET_SIZE,
        height = TARGET_SIZE,
        format = TextureFormat.Rgba8Unorm,
        usage = setOf(TextureUsage.RenderTarget, TextureUsage.CopySource),
    )

    private fun positionLayout(): VertexBufferLayout = VertexBufferLayout(
        strideBytes = VertexFormat.Float32x3.byteSize,
        stepMode = VertexStepMode.Vertex,
        attributes = listOf(VertexAttribute(0, VertexFormat.Float32x3, 0)),
    )

    /** First and last red pixel column of a row, or `first=-1` when the row is fully clear. */
    private fun rowExtent(bytes: ByteArray, row: Int): IntRange {
        var first = -1
        var last = -1
        for (col in 0 until TARGET_SIZE) {
            if (bytes[((row * TARGET_SIZE) + col) * 4].toUByte().toInt() == 255) {
                if (first == -1) first = col
                last = col
            }
        }
        return first..last
    }

    private fun colorAt(bytes: ByteArray, x: Int, y: Int): IntArray =
        IntArray(4) { channel -> bytes[((y * TARGET_SIZE) + x) * 4 + channel].toUByte().toInt() }

    private fun countPixels(bytes: ByteArray, predicate: (Int, Int, Int, Int) -> Boolean): Int {
        var count = 0
        for (pixel in 0 until bytes.size / 4) {
            val red = bytes[pixel * 4].toUByte().toInt()
            val green = bytes[pixel * 4 + 1].toUByte().toInt()
            val blue = bytes[pixel * 4 + 2].toUByte().toInt()
            val alpha = bytes[pixel * 4 + 3].toUByte().toInt()
            if (predicate(red, green, blue, alpha)) count++
        }
        return count
    }

    /** Triangle vertices spanning the visible quadrant of clip space, little endian floats. */
    private fun triangleVertices(): ByteArray {
        val vertices = floatArrayOf(-0.5f, -0.5f, 0.0f, 0.5f, -0.5f, 0.0f, 0.0f, 0.5f, 0.0f)
        val bytes = ByteArray(vertices.size * Float.SIZE_BYTES)
        vertices.forEachIndexed { index, value ->
            val bits = value.toRawBits()
            bytes[index * 4] = bits.toByte()
            bytes[index * 4 + 1] = (bits shr 8).toByte()
            bytes[index * 4 + 2] = (bits shr 16).toByte()
            bytes[index * 4 + 3] = (bits shr 24).toByte()
        }
        return bytes
    }
}
