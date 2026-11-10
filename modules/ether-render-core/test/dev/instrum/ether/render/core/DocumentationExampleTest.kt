package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferUsage
import dev.instrum.ether.render.nullbackend.NullBackend
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Executes the example published in `README.md`.
 *
 * The documentation is part of the public surface, so the snippet must keep compiling and running.
 */
class DocumentationExampleTest {
    @Test
    fun readmeHeadlessRenderingExampleRuns() {
        val backends = RenderBackendRegistry()
        backends.register(NullBackend())
        val device = backends.createDevice(BackendDeviceDescriptor(label = "headless"))

        val buffer = device.createBuffer(
            BufferDescriptor(sizeBytes = 1024, usage = setOf(BufferUsage.Uniform)),
        )
        assertEquals(1, device.liveResourceCount)
        device.destroyBuffer(buffer)
        assertEquals(0, device.liveResourceCount)
        device.close()
        assertEquals(true, device.isClosed)
    }
}
