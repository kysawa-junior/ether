package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.core.error.InvalidRenderResourceException
import dev.instrum.ether.render.core.error.RenderBackendUnavailableException
import dev.instrum.ether.render.core.error.RenderDeviceClosedException
import dev.instrum.ether.render.core.error.RenderValidationException
import dev.instrum.ether.render.nullbackend.defaultNullCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GraphicsDeviceTest {
    private fun vertexBuffer(size: Long = 36L): BufferDescriptor =
        BufferDescriptor(sizeBytes = size, usage = setOf(BufferUsage.Vertex))

    @Test
    fun createsResourcesAndTracksLiveCount() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            assertEquals(0, device.liveResourceCount)
            val texture = device.createTestTexture()
            val buffer = device.createBuffer(vertexBuffer())
            assertEquals(2, device.liveResourceCount)
            assertEquals(2, fixture.backend.liveResourceCount)

            device.destroyBuffer(buffer)
            assertEquals(1, device.liveResourceCount)
            device.destroyTexture(texture)
            assertEquals(0, device.liveResourceCount)
            assertEquals(0, fixture.backend.liveResourceCount)
        } finally {
            device.close()
        }
    }

    @Test
    fun destroyInvalidatesHandleAndAdvancesGeneration() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val first = device.createBuffer(vertexBuffer())
            device.destroyBuffer(first)
            val second = device.createBuffer(vertexBuffer())

            assertNotEquals(first.raw, second.raw)
            assertFailsWith<InvalidRenderResourceException> {
                device.destroyBuffer(first)
            }
            assertEquals(1, device.liveResourceCount)
        } finally {
            device.close()
        }
    }

    @Test
    fun destroyingTwiceFails() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val buffer = device.createBuffer(vertexBuffer())
            device.destroyBuffer(buffer)
            assertFailsWith<InvalidRenderResourceException> {
                device.destroyBuffer(buffer)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun handlesAreScopedToTheirDevice() {
        val first = createNullTestDevice()
        val second = createNullTestDevice()
        try {
            val buffer = first.device.createBuffer(vertexBuffer())
            assertFailsWith<InvalidRenderResourceException> {
                second.device.destroyBuffer(buffer)
            }
            assertEquals(1, first.device.liveResourceCount)
            assertEquals(0, second.device.liveResourceCount)
        } finally {
            first.device.close()
            second.device.close()
        }
    }

    @Test
    fun closeIsIdempotentAndBlocksFurtherOperations() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        val buffer = device.createBuffer(vertexBuffer())

        device.close()
        assertTrue(device.isClosed)
        assertEquals(0, device.liveResourceCount)
        assertEquals(0, fixture.backend.liveResourceCount)
        device.close()

        assertFailsWith<RenderDeviceClosedException> {
            device.createBuffer(vertexBuffer())
        }
        assertFailsWith<RenderDeviceClosedException> {
            device.destroyBuffer(buffer)
        }
    }

    @Test
    fun createRejectsUnavailableBackend() {
        val backend = object : RenderBackend {
            override val kind: BackendKind
                get() = BackendKind.Null

            override fun isAvailable(): Boolean = false

            override fun capabilities(): GraphicsCapabilities = defaultNullCapabilities()

            override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
                throw IllegalStateException("should not be called")
        }
        assertFailsWith<RenderBackendUnavailableException> {
            GraphicsDevice.create(backend)
        }
    }

    @Test
    fun createRejectsCapabilityKindMismatch() {
        val backend = object : RenderBackend {
            override val kind: BackendKind
                get() = BackendKind.Null

            override fun isAvailable(): Boolean = true

            override fun capabilities(): GraphicsCapabilities =
                defaultNullCapabilities().copy(backend = BackendKind.Vulkan)

            override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
                throw IllegalStateException("should not be called")
        }
        assertFailsWith<RenderValidationException> {
            GraphicsDevice.create(backend)
        }
    }

    @Test
    fun createWrapsBackendFailure() {
        val backend = object : RenderBackend {
            override val kind: BackendKind
                get() = BackendKind.Null

            override fun isAvailable(): Boolean = true

            override fun capabilities(): GraphicsCapabilities = defaultNullCapabilities()

            override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
                throw IllegalStateException("device exploded")
        }
        val error = assertFailsWith<RenderBackendUnavailableException> {
            GraphicsDevice.create(backend)
        }
        assertTrue(error.message?.contains("device exploded") == true)
    }

    @Test
    fun commandBufferIsSingleUse() {
        val fixture = createNullTestDevice()
        val device = fixture.device
        try {
            val commandBuffer = device.createCommandEncoder("frame").finish()
            device.submit(commandBuffer)
            assertFailsWith<RenderValidationException> {
                device.submit(commandBuffer)
            }
        } finally {
            device.close()
        }
    }

    @Test
    fun commandBufferCannotCrossDevices() {
        val first = createNullTestDevice()
        val second = createNullTestDevice()
        try {
            val commandBuffer = first.device.createCommandEncoder("frame").finish()
            assertFailsWith<RenderValidationException> {
                second.device.submit(commandBuffer)
            }
        } finally {
            first.device.close()
            second.device.close()
        }
    }
}
