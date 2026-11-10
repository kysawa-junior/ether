package dev.instrum.ether.render.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderApiContractTest {
    private fun sampledTexture(
        width: Int = 4,
        height: Int = 4,
        depthOrLayers: Int = 1,
        mipLevels: Int = 1,
        sampleCount: Int = 1,
        format: TextureFormat = TextureFormat.Rgba8Unorm,
        usage: Set<TextureUsage> = setOf(TextureUsage.Sampled),
        dimension: TextureDimension = TextureDimension.D2,
    ): TextureDescriptor = TextureDescriptor(
        width = width,
        height = height,
        depthOrLayers = depthOrLayers,
        mipLevels = mipLevels,
        sampleCount = sampleCount,
        format = format,
        usage = usage,
        dimension = dimension,
    )

    @Test
    fun bufferRejectsInvalidDescriptors() {
        assertFailsWith<IllegalArgumentException> {
            BufferDescriptor(sizeBytes = 0, usage = setOf(BufferUsage.Vertex))
        }
        assertFailsWith<IllegalArgumentException> {
            BufferDescriptor(sizeBytes = 16, usage = emptySet())
        }
    }

    @Test
    fun textureMipCountIsBoundedByExtent() {
        assertEquals(3, sampledTexture(mipLevels = 3).mipLevels)
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(mipLevels = 4)
        }
    }

    @Test
    fun arrayLayersDoNotIncreaseMipCount() {
        val array = sampledTexture(depthOrLayers = 100, mipLevels = 3)
        assertEquals(100, array.depthOrLayers)
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(depthOrLayers = 100, mipLevels = 4)
        }
    }

    @Test
    fun textureDimensionsAreValidated() {
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(width = 4, height = 2, dimension = TextureDimension.D1)
        }
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(depthOrLayers = 1, dimension = TextureDimension.D3)
        }
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(sampleCount = 3)
        }
    }

    @Test
    fun textureUsageMustMatchFormatKind() {
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(
                format = TextureFormat.Depth32Float,
                usage = setOf(TextureUsage.RenderTarget),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(usage = setOf(TextureUsage.DepthStencil))
        }
        assertFailsWith<IllegalArgumentException> {
            sampledTexture(
                usage = setOf(TextureUsage.RenderTarget, TextureUsage.DepthStencil),
            )
        }
    }

    @Test
    fun samplerRejectsInvalidRanges() {
        assertFailsWith<IllegalArgumentException> {
            SamplerDescriptor(lodMinClamp = 2f, lodMaxClamp = 1f)
        }
        assertFailsWith<IllegalArgumentException> {
            SamplerDescriptor(lodMinClamp = -1f)
        }
        assertFailsWith<IllegalArgumentException> {
            SamplerDescriptor(maxAnisotropy = 0)
        }
    }

    @Test
    fun shaderDescriptorCopiesCodeDefensively() {
        val source = byteArrayOf(1, 2, 3)
        val descriptor = ShaderModuleDescriptor(source, ShaderStage.Vertex)
        source[0] = 99
        assertEquals(1, descriptor.code[0])
        assertEquals(ShaderStage.Vertex, descriptor.stage)
        assertEquals("main", descriptor.entryPoint)
    }

    @Test
    fun shaderDescriptorRejectsEmptyCodeAndBlankEntryPoint() {
        assertFailsWith<IllegalArgumentException> {
            ShaderModuleDescriptor(ByteArray(0), ShaderStage.Fragment)
        }
        assertFailsWith<IllegalArgumentException> {
            ShaderModuleDescriptor(byteArrayOf(1), ShaderStage.Fragment, entryPoint = " ")
        }
    }

    @Test
    fun vertexLayoutRejectsOverflowAndDuplicateLocations() {
        assertFailsWith<IllegalArgumentException> {
            VertexBufferLayout(
                strideBytes = 8,
                attributes = listOf(VertexAttribute(0, VertexFormat.Float32x3, 0)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VertexBufferLayout(
                strideBytes = 16,
                attributes = listOf(
                    VertexAttribute(0, VertexFormat.Float32, 0),
                    VertexAttribute(0, VertexFormat.Float32, 4),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VertexBufferLayout(strideBytes = 4, attributes = emptyList())
        }
    }

    @Test
    fun primitiveStateRequiresStripIndexFormatOnlyForStrips() {
        assertFailsWith<IllegalArgumentException> {
            PrimitiveState(topology = PrimitiveTopology.TriangleStrip)
        }
        assertFailsWith<IllegalArgumentException> {
            PrimitiveState(
                topology = PrimitiveTopology.LineList,
                stripIndexFormat = IndexFormat.UInt16,
            )
        }
        val strip = PrimitiveState(
            topology = PrimitiveTopology.TriangleStrip,
            stripIndexFormat = IndexFormat.UInt32,
        )
        assertEquals(IndexFormat.UInt32, strip.stripIndexFormat)
    }

    @Test
    fun colorTargetRejectsDepthFormats() {
        assertFailsWith<IllegalArgumentException> {
            ColorTargetState(format = TextureFormat.Depth24Plus)
        }
    }

    @Test
    fun depthStateRequiresDepthFormat() {
        assertFailsWith<IllegalArgumentException> {
            DepthStencilState(format = TextureFormat.Rgba8Unorm)
        }
    }

    @Test
    fun clearDepthMustBeInNormalizedRange() {
        assertFailsWith<IllegalArgumentException> {
            DepthLoadOperation.Clear(-0.1f)
        }
        assertFailsWith<IllegalArgumentException> {
            DepthLoadOperation.Clear(1.1f)
        }
        assertEquals(1f, DepthLoadOperation.Clear(1f).depth)
    }

    @Test
    fun renderPassRequiresUniqueAttachments() {
        val texture = TextureHandle(7)
        assertFailsWith<IllegalArgumentException> {
            RenderPassDescriptor()
        }
        assertFailsWith<IllegalArgumentException> {
            RenderPassDescriptor(
                colorAttachments = listOf(
                    RenderPassColorAttachment(texture),
                    RenderPassColorAttachment(texture),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RenderPassDescriptor(
                colorAttachments = listOf(RenderPassColorAttachment(texture)),
                depthStencilAttachment = RenderPassDepthStencilAttachment(texture),
            )
        }
    }

    @Test
    fun viewportAndScissorRejectInvalidGeometry() {
        assertFailsWith<IllegalArgumentException> { Viewport(0f, 0f, 0f, 10f) }
        assertFailsWith<IllegalArgumentException> {
            Viewport(0f, 0f, 10f, 10f, minDepth = 0.5f, maxDepth = 0.25f)
        }
        assertFailsWith<IllegalArgumentException> { ScissorRect(-1, 0, 10, 10) }
        assertFailsWith<IllegalArgumentException> { ScissorRect(0, 0, 0, 10) }
    }

    @Test
    fun colorWriteMaskTracksChannels() {
        val mask = ColorWriteMask.of(ColorChannel.Red, ColorChannel.Blue)
        assertTrue(mask.contains(ColorChannel.Red))
        assertTrue(mask.contains(ColorChannel.Blue))
        assertFalse(mask.contains(ColorChannel.Green))
        assertFalse(mask.contains(ColorChannel.Alpha))
        assertTrue(ColorWriteMask.All.contains(ColorChannel.Alpha))
        assertFailsWith<IllegalArgumentException> { ColorWriteMask(0x10) }
    }

    @Test
    fun renderPipelineRejectsWritesToNothing() {
        val vertex = ShaderModuleHandle(1)
        assertFailsWith<IllegalArgumentException> {
            RenderPipelineDescriptor(
                vertex = VertexState(vertex),
                fragment = null,
                depthStencil = null,
            )
        }
    }

    @Test
    fun vertexLocationsAreUniqueAcrossBuffers() {
        val vertex = ShaderModuleHandle(1)
        val fragment = ShaderModuleHandle(2)
        val layout = VertexBufferLayout(
            strideBytes = 4,
            attributes = listOf(VertexAttribute(0, VertexFormat.Float32, 0)),
        )
        assertFailsWith<IllegalArgumentException> {
            RenderPipelineDescriptor(
                vertex = VertexState(vertex, listOf(layout, layout)),
                fragment = FragmentState(fragment, listOf(ColorTargetState(TextureFormat.Rgba8Unorm))),
            )
        }
    }

    @Test
    fun capabilitiesAnswerQueries() {
        val capabilities = GraphicsCapabilities(
            backend = BackendKind.Null,
            features = setOf(GraphicsFeature.ComputeShaders),
            limits = GraphicsLimits.Conservative,
            supportedTextureFormats = setOf(TextureFormat.Rgba8Unorm),
            clipSpaceDepthZeroToOne = true,
            framebufferOriginBottomLeft = false,
            supportsMappableBuffers = true,
            supportsPersistentMappedBuffers = false,
        )
        assertTrue(capabilities.supports(GraphicsFeature.ComputeShaders))
        assertFalse(capabilities.supports(GraphicsFeature.IndirectDraw))
        assertTrue(capabilities.supports(TextureFormat.Rgba8Unorm))
        assertFalse(capabilities.supports(TextureFormat.Depth32Float))
        assertFailsWith<IllegalArgumentException> {
            capabilities.copy(supportedTextureFormats = emptySet())
        }
    }

    @Test
    fun limitsRejectNonPositiveValues() {
        assertFailsWith<IllegalArgumentException> {
            GraphicsLimits(maxTexture2DSize = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GraphicsLimits(maxFramesInFlight = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GraphicsLimits(maxSamplerAnisotropy = 0)
        }
    }
}
