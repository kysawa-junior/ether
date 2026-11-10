package dev.instrum.ether.render.nullbackend

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.ColorTargetState
import dev.instrum.ether.render.api.FragmentState
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderModuleHandle
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureFormat
import dev.instrum.ether.render.api.VertexState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NullBackendTest {
    private fun device(
        mode: NullCompletionMode = NullCompletionMode.Automatic,
    ): Pair<NullBackend, NullBackendDevice> {
        val backend = NullBackend(mode)
        val device = backend.createDevice(BackendDeviceDescriptor("null-test")) as NullBackendDevice
        return backend to device
    }

    @Test
    fun defaultCapabilitiesAreSyntheticAndPermissive() {
        val capabilities = defaultNullCapabilities()
        assertEquals(dev.instrum.ether.render.api.BackendKind.Null, capabilities.backend)
        assertEquals(GraphicsFeature.entries.toSet(), capabilities.features)
        assertEquals(TextureFormat.entries.toSet(), capabilities.supportedTextureFormats)
        assertEquals(GraphicsLimits.Permissive, capabilities.limits)
        assertTrue(capabilities.clipSpaceDepthZeroToOne)
        assertFalse(capabilities.framebufferOriginBottomLeft)
    }

    @Test
    fun resourcesLiveUntilDestroyed() {
        val (_, device) = device()
        val token = device.createBuffer(
            BufferDescriptor(16L, setOf(BufferUsage.Vertex)),
        )
        assertTrue(device.isTokenAlive(token))
        assertEquals("buffer", device.resourceKind(token))
        assertEquals(1, device.liveResourceCount)

        device.destroy(token)
        assertFalse(device.isTokenAlive(token))
        assertEquals(0, device.liveResourceCount)
        assertFailsWith<IllegalArgumentException> { device.destroy(token) }
    }

    @Test
    fun pipelinesRequireMatchingShaderStages() {
        val (_, device) = device()
        val vertex = device.createShaderModule(
            ShaderModuleDescriptor(byteArrayOf(1), ShaderStage.Vertex),
        )
        val fragment = device.createShaderModule(
            ShaderModuleDescriptor(byteArrayOf(2), ShaderStage.Fragment),
        )
        assertFailsWith<IllegalArgumentException> {
            device.createRenderPipeline(
                descriptor = RenderPipelineDescriptor(
                    vertex = VertexState(ShaderModuleHandle(vertex)),
                    fragment = FragmentState(
                        ShaderModuleHandle(fragment),
                        listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                    ),
                ),
                vertexShaderToken = fragment,
                fragmentShaderToken = vertex,
            )
        }

        val pipeline = device.createRenderPipeline(
            descriptor = RenderPipelineDescriptor(
                vertex = VertexState(ShaderModuleHandle(vertex)),
                fragment = FragmentState(
                    ShaderModuleHandle(fragment),
                    listOf(ColorTargetState(TextureFormat.Rgba8Unorm)),
                ),
            ),
            vertexShaderToken = vertex,
            fragmentShaderToken = fragment,
        )
        assertEquals("pipeline", device.resourceKind(pipeline))
    }

    @Test
    fun automaticSubmissionsCompleteOnSubmit() {
        val (_, device) = device(NullCompletionMode.Automatic)
        val commandBuffer = device.createCommandBuffer(
            "frame",
            listOf(BackendCommand.EndRenderPass),
        )
        assertEquals(1, device.liveCommandBufferCount)
        val submission = device.submit(listOf(commandBuffer))
        assertEquals(0, device.liveCommandBufferCount)
        assertEquals(1, device.submissionCount)
        assertTrue(device.isSubmissionComplete(submission))
        assertEquals(0, device.pendingSubmissionCount)
        assertEquals(
            listOf(BackendCommand.EndRenderPass),
            device.submittedBatches.single(),
        )
        assertEquals(0, device.waitIdleCount)
        device.waitIdle()
        assertEquals(1, device.waitIdleCount)
    }

    @Test
    fun manualSubmissionsWaitForExplicitCompletion() {
        val (_, device) = device(NullCompletionMode.Manual)
        val first = device.createCommandBuffer("first", listOf(BackendCommand.EndRenderPass))
        val submission = device.submit(listOf(first))
        assertFalse(device.isSubmissionComplete(submission))
        assertEquals(1, device.pendingSubmissionCount)

        device.completeAllSubmissions()
        assertTrue(device.isSubmissionComplete(submission))
        assertEquals(0, device.pendingSubmissionCount)

        val second = device.createCommandBuffer("second", listOf(BackendCommand.EndRenderPass))
        val secondSubmission = device.submit(listOf(second))
        assertFalse(device.isSubmissionComplete(secondSubmission))
        device.waitIdle()
        assertTrue(device.isSubmissionComplete(secondSubmission))
        assertEquals(1, device.waitIdleCount)
    }

    @Test
    fun destroyingAnUnsubmittedCommandBufferWorks() {
        val (_, device) = device()
        val commandBuffer = device.createCommandBuffer("unused", emptyList())
        device.destroy(commandBuffer)
        assertEquals(0, device.liveCommandBufferCount)
        assertFailsWith<IllegalArgumentException> {
            device.submit(listOf(commandBuffer))
        }
    }

    @Test
    fun unknownSubmissionTokensAreRejected() {
        val (_, device) = device()
        assertFailsWith<IllegalArgumentException> {
            device.isSubmissionComplete(1L)
        }
    }

    @Test
    fun closeReleasesEverythingAndIsIdempotent() {
        val (_, device) = device()
        device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.Vertex)))
        device.createCommandBuffer("pending", emptyList())
        device.close()
        assertTrue(device.isClosed)
        assertEquals(0, device.liveResourceCount)
        assertEquals(0, device.liveCommandBufferCount)
        device.close()
        assertFailsWith<IllegalStateException> {
            device.createBuffer(BufferDescriptor(16L, setOf(BufferUsage.Vertex)))
        }
    }
}
