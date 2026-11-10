package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.core.error.RenderBackendUnavailableException
import dev.instrum.ether.render.core.error.RenderValidationException
import dev.instrum.ether.render.nullbackend.NullBackend
import dev.instrum.ether.render.nullbackend.defaultNullCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendSelectionTest {
    private class StubBackend(
        override val kind: BackendKind,
        private val available: Boolean,
    ) : RenderBackend {
        override fun isAvailable(): Boolean = available

        override fun capabilities(): GraphicsCapabilities =
            defaultNullCapabilities().copy(backend = kind)

        override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
            throw IllegalStateException("Stub backend cannot create devices")
    }

    @Test
    fun selectsFirstAvailablePreference() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        registry.register(StubBackend(BackendKind.Vulkan, available = true))

        val selected = registry.select(listOf(BackendKind.Vulkan, BackendKind.Null))
        assertEquals(BackendKind.Vulkan, selected?.kind)
    }

    @Test
    fun skipsUnavailableBackends() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        registry.register(StubBackend(BackendKind.Vulkan, available = false))

        val selected = registry.select(listOf(BackendKind.Vulkan, BackendKind.Null))
        assertEquals(BackendKind.Null, selected?.kind)
    }

    @Test
    fun fallsBackToAnyRegisteredAvailableBackend() {
        val registry = RenderBackendRegistry()
        registry.register(StubBackend(BackendKind.Vulkan, available = true))

        val selected = registry.select(listOf(BackendKind.Metal))
        assertEquals(BackendKind.Vulkan, selected?.kind)
    }

    @Test
    fun noBackendProducesNullSelection() {
        val registry = RenderBackendRegistry()
        assertNull(registry.select())
        assertFailsWith<RenderBackendUnavailableException> {
            registry.createDevice()
        }
    }

    @Test
    fun duplicateRegistrationFails() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        assertFailsWith<RenderValidationException> {
            registry.register(NullBackend())
        }
        assertEquals(1, registry.size)
    }

    @Test
    fun unregisterRemovesBackend() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        assertTrue(registry.unregister(BackendKind.Null))
        assertNull(registry.find(BackendKind.Null))
        assertTrue(!registry.unregister(BackendKind.Null))
    }

    @Test
    fun availableListContainsOnlyAvailableBackends() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        registry.register(StubBackend(BackendKind.Vulkan, available = false))
        registry.register(StubBackend(BackendKind.Metal, available = true))

        val available = registry.available(listOf(BackendKind.Vulkan, BackendKind.Metal, BackendKind.Null))
        assertEquals(listOf(BackendKind.Metal, BackendKind.Null), available.map { it.kind })
    }

    @Test
    fun registryCreatesDeviceFromSelectedBackend() {
        val registry = RenderBackendRegistry()
        registry.register(NullBackend())
        val device = registry.createDevice(BackendDeviceDescriptor("registry"))
        try {
            assertEquals(BackendKind.Null, device.kind)
            assertEquals("registry", device.label)
        } finally {
            device.close()
        }
    }
}
