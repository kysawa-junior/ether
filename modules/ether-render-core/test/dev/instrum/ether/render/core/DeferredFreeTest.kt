package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferHandle
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.api.TextureUsage
import dev.instrum.ether.render.core.error.InvalidRenderResourceException
import dev.instrum.ether.render.nullbackend.NullCompletionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeferredFreeTest {
    private fun copyResources(fixture: NullTestDevice): Pair<BufferHandle, TextureHandle> {
        val buffer = fixture.device.createBuffer(
            BufferDescriptor(16L, setOf(BufferUsage.CopySource)),
        )
        val texture = fixture.device.createTestTexture(
            width = 2,
            height = 2,
            usage = setOf(TextureUsage.CopyDestination),
        )
        return buffer to texture
    }

    private fun recordCopy(
        fixture: NullTestDevice,
        buffer: BufferHandle,
        texture: TextureHandle,
        label: String = "copy",
    ): CommandBuffer {
        val encoder = fixture.device.createCommandEncoder(label)
        encoder.copyBufferToTexture(buffer, 0, 8, texture, width = 2, height = 2)
        return encoder.finish()
    }

    @Test
    fun destroyWithoutSubmissionFreesImmediately() {
        val fixture = createNullTestDevice()
        try {
            val buffer = fixture.device.createBuffer(
                BufferDescriptor(16L, setOf(BufferUsage.Vertex)),
            )
            assertEquals(1, fixture.backend.liveResourceCount)
            fixture.device.destroyBuffer(buffer)
            assertEquals(0, fixture.backend.liveResourceCount)
            assertEquals(0, fixture.device.liveResourceCount)
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun destroyAfterSubmitWaitsForSubmissionCompletion() {
        val fixture = createNullTestDevice(NullCompletionMode.Manual)
        try {
            val (buffer, texture) = copyResources(fixture)
            val commandBuffer = recordCopy(fixture, buffer, texture)
            fixture.device.submit(commandBuffer)

            assertEquals(1, fixture.device.pendingSubmissionCount)
            assertEquals(1, fixture.backend.pendingSubmissionCount)
            fixture.device.destroyBuffer(buffer)
            assertEquals(2, fixture.device.liveResourceCount)
            assertEquals(2, fixture.backend.liveResourceCount)
            assertEquals(0, fixture.device.pollCompletedSubmissions())

            fixture.backend.completeAllSubmissions()
            assertEquals(1, fixture.device.pollCompletedSubmissions())
            assertEquals(1, fixture.device.liveResourceCount)
            assertEquals(1, fixture.backend.liveResourceCount)
            assertEquals(0, fixture.device.pendingSubmissionCount)

            fixture.device.destroyTexture(texture)
            assertEquals(0, fixture.backend.liveResourceCount)
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun waitIdleReleasesDeferredResources() {
        val fixture = createNullTestDevice(NullCompletionMode.Manual)
        try {
            val (buffer, texture) = copyResources(fixture)
            fixture.device.submit(recordCopy(fixture, buffer, texture))
            fixture.device.destroyBuffer(buffer)
            assertEquals(2, fixture.device.liveResourceCount)

            fixture.device.waitIdle()
            assertEquals(0, fixture.device.pendingSubmissionCount)
            assertEquals(1, fixture.device.liveResourceCount)
            assertEquals(1, fixture.backend.liveResourceCount)
            assertEquals(1, fixture.backend.waitIdleCount)

            fixture.device.destroyTexture(texture)
            assertEquals(0, fixture.backend.liveResourceCount)
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun automaticCompletionReleasesReferencesImmediately() {
        val fixture = createNullTestDevice(NullCompletionMode.Automatic)
        try {
            val (buffer, texture) = copyResources(fixture)
            fixture.device.submit(recordCopy(fixture, buffer, texture))
            assertEquals(0, fixture.device.pendingSubmissionCount)

            fixture.device.destroyBuffer(buffer)
            assertEquals(1, fixture.device.liveResourceCount)
            assertEquals(1, fixture.backend.liveResourceCount)
            fixture.device.destroyTexture(texture)
            assertEquals(0, fixture.backend.liveResourceCount)
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun multipleSubmissionsRetainUntilAllComplete() {
        val fixture = createNullTestDevice(NullCompletionMode.Manual)
        try {
            val (buffer, texture) = copyResources(fixture)
            val first = recordCopy(fixture, buffer, texture, "first")
            val second = recordCopy(fixture, buffer, texture, "second")
            fixture.device.submit(first)
            fixture.device.submit(second)
            fixture.device.destroyBuffer(buffer)

            assertEquals(2, fixture.device.pendingSubmissionCount)
            fixture.backend.completeAllSubmissions()
            assertEquals(2, fixture.device.pollCompletedSubmissions())
            assertEquals(1, fixture.device.liveResourceCount)
            assertEquals(1, fixture.backend.liveResourceCount)
            fixture.device.destroyTexture(texture)
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun destroyedResourceCannotBeUsedByANewSubmission() {
        val fixture = createNullTestDevice(NullCompletionMode.Manual)
        try {
            val (buffer, texture) = copyResources(fixture)
            val first = recordCopy(fixture, buffer, texture, "first")
            val second = recordCopy(fixture, buffer, texture, "second")
            fixture.device.submit(first)
            fixture.device.destroyBuffer(buffer)

            assertFailsWith<InvalidRenderResourceException> {
                fixture.device.submit(second)
            }
        } finally {
            fixture.device.close()
        }
    }

    @Test
    fun closeDestroysPendingAndLiveResources() {
        val fixture = createNullTestDevice(NullCompletionMode.Manual)
        try {
            val (buffer, texture) = copyResources(fixture)
            fixture.device.submit(recordCopy(fixture, buffer, texture))
            fixture.device.destroyBuffer(buffer)
            assertEquals(2, fixture.backend.liveResourceCount)

            fixture.device.close()
            assertEquals(0, fixture.device.liveResourceCount)
            assertEquals(0, fixture.backend.liveResourceCount)
            assertEquals(0, fixture.device.pendingSubmissionCount)
        } finally {
            fixture.device.close()
        }
    }
}
