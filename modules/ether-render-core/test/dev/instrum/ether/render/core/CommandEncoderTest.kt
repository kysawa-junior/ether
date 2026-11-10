package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.DepthLoadOperation
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.IndexFormat
import dev.instrum.ether.render.api.PrimitiveState
import dev.instrum.ether.render.api.PrimitiveTopology
import dev.instrum.ether.render.api.RenderCommand
import dev.instrum.ether.render.api.RenderPassDepthStencilAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.ScissorRect
import dev.instrum.ether.render.api.StoreOp
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.api.Viewport
import dev.instrum.ether.render.core.error.RenderValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CommandEncoderTest {
    private fun vertexBuffer(size: Long = 36L): BufferDescriptor =
        BufferDescriptor(sizeBytes = size, usage = setOf(BufferUsage.Vertex))

    @Test
    fun recordsACompleteDrawPass() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val vertices = device.createBuffer(vertexBuffer())

            val encoder = device.createCommandEncoder("frame")
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertices)
            encoder.setViewport(Viewport(0f, 0f, 64f, 64f))
            encoder.setScissor(ScissorRect(0, 0, 64, 64))
            encoder.draw(3)
            encoder.endRenderPass()
            val commandBuffer = encoder.finish()

            assertEquals("frame", commandBuffer.label)
            assertEquals(7, commandBuffer.commands.size)
            assertIs<RenderCommand.BeginRenderPass>(commandBuffer.commands[0])
            assertIs<RenderCommand.SetPipeline>(commandBuffer.commands[1])
            assertIs<RenderCommand.SetVertexBuffer>(commandBuffer.commands[2])
            assertIs<RenderCommand.SetViewport>(commandBuffer.commands[3])
            assertIs<RenderCommand.SetScissor>(commandBuffer.commands[4])
            assertIs<RenderCommand.Draw>(commandBuffer.commands[5])
            assertIs<RenderCommand.EndRenderPass>(commandBuffer.commands[6])
        } finally {
            device.close()
        }
    }

    @Test
    fun drawOutsidePassFails() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val encoder = device.createCommandEncoder()
            assertFailsWith<RenderValidationException> { encoder.draw(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun finishWhilePassActiveFails() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> { encoder.finish() }
        } finally {
            device.close()
        }
    }

    @Test
    fun encoderIsSingleUse() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val encoder = device.createCommandEncoder()
            encoder.finish()
            assertFailsWith<RenderValidationException> { encoder.finish() }
            assertFailsWith<RenderValidationException> {
                encoder.beginRenderPass(colorPass(device.createTestTexture()))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun colorAttachmentRequiresRenderTargetUsage() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val sampled = device.createTestTexture(usage = setOf(TextureUsage.Sampled))
            val encoder = device.createCommandEncoder()
            assertFailsWith<RenderValidationException> {
                encoder.beginRenderPass(colorPass(sampled))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineColorFormatMustMatchPass() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture(format = TextureFormat.Rgba8Unorm)
            val pipeline = device.createTestPipeline(colorFormat = TextureFormat.Bgra8Unorm)
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> {
                encoder.setPipeline(pipeline)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun depthPipelineRequiresDepthAttachment() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(depthFormat = TextureFormat.Depth24Plus)
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> {
                encoder.setPipeline(pipeline)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineSampleCountMustMatchPass() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(sampleCount = 2)
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> {
                encoder.setPipeline(pipeline)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun depthPassDrawsSuccessfully() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val color = device.createTestTexture()
            val depth = device.createTestTexture(
                format = TextureFormat.Depth24Plus,
                usage = setOf(TextureUsage.DepthStencil),
            )
            val pipeline = device.createTestPipeline(
                depthFormat = TextureFormat.Depth24Plus,
                vertexLayouts = listOf(positionLayout()),
            )
            val vertices = device.createBuffer(vertexBuffer())
            val pass = RenderPassDescriptor(
                label = "depth-pass",
                colorAttachments = colorPass(color).colorAttachments,
                depthStencilAttachment = RenderPassDepthStencilAttachment(
                    texture = depth,
                    depthLoad = DepthLoadOperation.Clear(1f),
                    depthStore = StoreOp.Store,
                ),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(pass)
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertices)
            encoder.draw(3)
            encoder.endRenderPass()
            assertEquals(5, encoder.finish().commands.size)
        } finally {
            device.close()
        }
    }

    @Test
    fun drawWithoutPipelineFails() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> { encoder.draw(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun pipelineVertexSlotMustBeBound() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            assertFailsWith<RenderValidationException> { encoder.draw(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun drawRespectsVertexBufferSize() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val vertices = device.createBuffer(vertexBuffer(size = 24L))
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertices)
            assertFailsWith<RenderValidationException> { encoder.draw(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun vertexBufferUsageIsValidated() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val notVertex = device.createBuffer(
                BufferDescriptor(16L, setOf(BufferUsage.Uniform)),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            assertFailsWith<RenderValidationException> {
                encoder.setVertexBuffer(0, notVertex)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun indexedDrawRequiresIndexBuffer() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline()
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            assertFailsWith<RenderValidationException> { encoder.drawIndexed(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun indexedDrawRespectsIndexBufferSize() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline()
            val indices = device.createBuffer(
                BufferDescriptor(4L, setOf(BufferUsage.Index)),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setIndexBuffer(indices, IndexFormat.UInt16)
            assertFailsWith<RenderValidationException> { encoder.drawIndexed(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun indexedDrawRecords() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline()
            val indices = device.createBuffer(
                BufferDescriptor(6L, setOf(BufferUsage.Index)),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setIndexBuffer(indices, IndexFormat.UInt16)
            encoder.drawIndexed(3)
            encoder.endRenderPass()
            val commands = encoder.finish().commands
            assertIs<RenderCommand.DrawIndexed>(commands[3])
        } finally {
            device.close()
        }
    }

    @Test
    fun stripIndexFormatMustMatchIndexBuffer() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val vertex = device.createTestShader(dev.instrum.ether.render.api.ShaderStage.Vertex)
            val fragment = device.createTestShader(dev.instrum.ether.render.api.ShaderStage.Fragment)
            val pipeline = device.createRenderPipeline(
                dev.instrum.ether.render.api.RenderPipelineDescriptor(
                    vertex = dev.instrum.ether.render.api.VertexState(vertex),
                    fragment = dev.instrum.ether.render.api.FragmentState(
                        fragment,
                        listOf(dev.instrum.ether.render.api.ColorTargetState(TextureFormat.Rgba8Unorm)),
                    ),
                    primitive = PrimitiveState(
                        topology = PrimitiveTopology.TriangleStrip,
                        stripIndexFormat = IndexFormat.UInt32,
                    ),
                ),
            )
            val indices = device.createBuffer(
                BufferDescriptor(6L, setOf(BufferUsage.Index)),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            assertFailsWith<RenderValidationException> {
                encoder.setIndexBuffer(indices, IndexFormat.UInt16)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun instancedDrawingRequiresFeature() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                features = GraphicsFeature.entries.toSet() - GraphicsFeature.InstancedDrawing,
            ),
        )
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val vertices = device.createBuffer(vertexBuffer())
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertices)
            assertFailsWith<RenderValidationException> {
                encoder.draw(3, instanceCount = 2)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun drawCallBudgetIsEnforced() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxDrawCallsPerFrame = 1),
            ),
        )
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val pipeline = device.createTestPipeline(vertexLayouts = listOf(positionLayout()))
            val vertices = device.createBuffer(vertexBuffer())
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setPipeline(pipeline)
            encoder.setVertexBuffer(0, vertices)
            encoder.draw(3)
            assertFailsWith<RenderValidationException> { encoder.draw(3) }
        } finally {
            device.close()
        }
    }

    @Test
    fun viewportAndScissorMustFitAttachment() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture(width = 64, height = 64)
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> {
                encoder.setViewport(Viewport(0f, 0f, 65f, 64f))
            }
            assertFailsWith<RenderValidationException> {
                encoder.setScissor(ScissorRect(0, 0, 64, 65))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun multisampleResolveIsValidated() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val color = device.createTestTexture(sampleCount = 2)
            val resolve = device.createTestTexture(sampleCount = 1)
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(color, resolveTarget = resolve))
            encoder.endRenderPass()
            encoder.finish()

            val badResolve = device.createTestTexture(sampleCount = 2)
            val badEncoder = device.createCommandEncoder()
            assertFailsWith<RenderValidationException> {
                badEncoder.beginRenderPass(colorPass(color, resolveTarget = badResolve))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun attachmentDimensionsMustMatch() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val first = device.createTestTexture(width = 64, height = 64)
            val second = device.createTestTexture(width = 32, height = 32)
            val descriptor = RenderPassDescriptor(
                colorAttachments = listOf(
                    dev.instrum.ether.render.api.RenderPassColorAttachment(first),
                    dev.instrum.ether.render.api.RenderPassColorAttachment(second),
                ),
            )
            val encoder = device.createCommandEncoder()
            assertFailsWith<RenderValidationException> {
                encoder.beginRenderPass(descriptor)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun stripIndexFormatIsCheckedWhenPipelineIsBoundAfterIndexBuffer() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val vertex = device.createTestShader(dev.instrum.ether.render.api.ShaderStage.Vertex)
            val fragment = device.createTestShader(dev.instrum.ether.render.api.ShaderStage.Fragment)
            val pipeline = device.createRenderPipeline(
                dev.instrum.ether.render.api.RenderPipelineDescriptor(
                    vertex = dev.instrum.ether.render.api.VertexState(vertex),
                    fragment = dev.instrum.ether.render.api.FragmentState(
                        fragment,
                        listOf(dev.instrum.ether.render.api.ColorTargetState(TextureFormat.Rgba8Unorm)),
                    ),
                    primitive = PrimitiveState(
                        topology = PrimitiveTopology.TriangleStrip,
                        stripIndexFormat = IndexFormat.UInt32,
                    ),
                ),
            )
            val indices = device.createBuffer(
                BufferDescriptor(12L, setOf(BufferUsage.Index)),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            encoder.setIndexBuffer(indices, IndexFormat.UInt16)
            assertFailsWith<RenderValidationException> {
                encoder.setPipeline(pipeline)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun copyCanOnlyBeRecordedOutsidePass() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val source = device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.CopySource)))
            val destination = device.createTestTexture(
                width = 2,
                height = 2,
                usage = setOf(TextureUsage.CopyDestination),
            )
            val encoder = device.createCommandEncoder()
            encoder.beginRenderPass(colorPass(target))
            assertFailsWith<RenderValidationException> {
                encoder.copyBufferToTexture(source, 0, 8, destination, width = 2, height = 2)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun copyValidatesRowPitchAndRanges() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val source = device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.CopySource)))
            val destination = device.createTestTexture(
                width = 2,
                height = 2,
                usage = setOf(TextureUsage.CopyDestination),
            )
            val encoder = device.createCommandEncoder()
            assertFailsWith<RenderValidationException> {
                encoder.copyBufferToTexture(source, 0, 4, destination, width = 2, height = 2)
            }
            assertFailsWith<RenderValidationException> {
                encoder.copyBufferToTexture(source, 0, 8, destination, width = 2, height = 2, depth = 2)
            }
            assertFailsWith<RenderValidationException> {
                encoder.copyBufferToTexture(
                    source,
                    0,
                    8,
                    destination,
                    mipLevel = 1,
                    width = 2,
                    height = 2,
                )
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun validCopyRecords() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val source = device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.CopySource)))
            val destination = device.createTestTexture(
                width = 2,
                height = 2,
                usage = setOf(TextureUsage.CopyDestination),
            )
            val encoder = device.createCommandEncoder()
            encoder.copyBufferToTexture(source, 0, 8, destination, width = 2, height = 2)
            val commands = encoder.finish().commands
            assertEquals(1, commands.size)
            assertIs<RenderCommand.CopyBufferToTexture>(commands[0])
        } finally {
            device.close()
        }
    }
}
