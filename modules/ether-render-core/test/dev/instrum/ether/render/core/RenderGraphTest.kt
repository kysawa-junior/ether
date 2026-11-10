package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.RenderCommand
import dev.instrum.ether.render.api.RenderPassColorAttachment
import dev.instrum.ether.render.api.RenderPassDescriptor
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.core.error.InvalidRenderResourceException
import dev.instrum.ether.render.core.error.RenderGraphException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RenderGraphTest {
    @Test
    fun buildsAndExecutesPassesInOrder() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val firstTarget = device.createTestTexture()
            val secondTarget = device.createTestTexture()
            var recordCount = 0
            val graph = device.createRenderGraph("frame") {
                pass("produce", colorPass(firstTarget, "produce")) { recordCount++ }
                pass("consume", colorPass(secondTarget, "consume"), reads = setOf(firstTarget)) {
                    recordCount++
                }
            }

            assertEquals(2, graph.passCount)
            assertEquals(listOf("produce", "consume"), graph.passNames)
            val commandBuffer = graph.encode(device)
            assertEquals(2, recordCount)
            assertEquals(4, commandBuffer.commands.size)
            val first = assertIs<RenderCommand.BeginRenderPass>(commandBuffer.commands[0])
            val second = assertIs<RenderCommand.BeginRenderPass>(commandBuffer.commands[2])
            assertEquals("produce", first.descriptor.label)
            assertEquals("consume", second.descriptor.label)
            assertIs<RenderCommand.EndRenderPass>(commandBuffer.commands[1])
            assertIs<RenderCommand.EndRenderPass>(commandBuffer.commands[3])

            device.submit(commandBuffer)
            assertEquals(1, fixture.backend.submittedBatches.size)
            assertEquals(4, fixture.backend.submittedBatches.single().size)
        } finally {
            device.close()
        }
    }

    @Test
    fun importedTexturesCanBeRead() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val external = device.createTestTexture()
            val output = device.createTestTexture()
            val graph = device.createRenderGraph("import") {
                importTexture(external)
                pass("read-external", colorPass(output), reads = setOf(external))
            }
            assertEquals(1, graph.passCount)
        } finally {
            device.close()
        }
    }

    @Test
    fun readingBeforeProductionFails() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val unproduced = device.createTestTexture()
            val output = device.createTestTexture()
            assertFailsWith<RenderGraphException> {
                device.createRenderGraph("bad") {
                    pass("consumer", colorPass(output), reads = setOf(unproduced))
                }
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun feedbackLoopsFail() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            assertFailsWith<RenderGraphException> {
                device.createRenderGraph("feedback") {
                    importTexture(target)
                    pass("read-write", colorPass(target), reads = setOf(target))
                }
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun duplicateAndBlankPassNamesFail() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val target = device.createTestTexture()
            val builder = RenderGraphBuilder(device, "duplicates")
            builder.pass("main", colorPass(target))
            assertFailsWith<RenderGraphException> {
                builder.pass("main", colorPass(target))
            }
            assertFailsWith<RenderGraphException> {
                builder.pass(" ", colorPass(target))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun emptyGraphAndReusedBuilderFail() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            assertFailsWith<RenderGraphException> {
                device.createRenderGraph("empty") {}
            }

            val target = device.createTestTexture()
            val builder = RenderGraphBuilder(device, "reused")
            builder.pass("main", colorPass(target))
            builder.build()
            assertFailsWith<RenderGraphException> { builder.build() }
            assertFailsWith<RenderGraphException> {
                builder.pass("second", colorPass(target))
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun graphCannotExecuteOnAnotherDevice() {
        val first = createNullTestDevice()
        val second = createNullTestDevice()
        try {
            val target = first.device.createTestTexture()
            val graph = first.device.createRenderGraph("frame") {
                pass("main", colorPass(target))
            }
            assertFailsWith<RenderGraphException> {
                graph.execute(second.device.createCommandEncoder())
            }
        } finally {
            first.device.close()
            second.device.close()
        }
    }

    @Test
    fun attachmentCountIsLimited() {
        val fixture = createNullTestDevice(
            capabilities = testCapabilities(
                limits = GraphicsLimits.Permissive.copy(maxColorAttachments = 1),
            ),
        )
        val device = fixture.device
        try {
            val first = device.createTestTexture()
            val second = device.createTestTexture()
            val descriptor = RenderPassDescriptor(
                colorAttachments = listOf(
                    RenderPassColorAttachment(first),
                    RenderPassColorAttachment(second),
                ),
            )
            assertFailsWith<RenderGraphException> {
                device.createRenderGraph("too-many") {
                    pass("main", descriptor)
                }
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun unknownTexturesAreRejected() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            assertFailsWith<InvalidRenderResourceException> {
                device.createRenderGraph("unknown") {
                    importTexture(TextureHandle(999))
                }
            }
        } finally {
            device.close()
        }
    }
}
