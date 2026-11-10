package dev.instrum.ether.render.lwjgl

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.lwjgl.internal.LwjglVulkanProbe

/**
 * The real Vulkan rendering backend for the JVM, built on the LWJGL Vulkan binding.
 *
 * Availability is decided by a short-lived probe that bootstraps the LWJGL natives, opens the
 * Vulkan loader, creates a temporary instance, and validates that a physical device supports
 * Vulkan 1.3 dynamic rendering. The probe result is cached so repeated [isAvailable] and
 * [capabilities] calls reuse the same report; a failure is remembered instead of re-probing the
 * machine.
 *
 * [capabilities] reports only the feature set the current device actually advertises, including
 * zero for every descriptor-set, push-constant, and compute limit because the portable API does
 * not exercise bindings yet, and a maximum sample count of 1 because multisampling is not part
 * of the supported portable surface yet.
 *
 * Every [createDevice] call probes the machine again instead of sharing the cached instance, so
 * the created device owns a fresh Vulkan instance; the cached probe is released after the device
 * took over its lifetime. The loader library itself stays mapped for the process lifetime.
 */
public class LwjglVulkanBackend(
    /**
     * When set, only a physical device with this exact driver-reported name is eligible; the
     * backends [isAvailable] and [capabilities] then describe that device only.
     */
    public val preferredDeviceName: String? = null,
) : RenderBackend {

    private var cachedProbe: LwjglVulkanProbe? = null
    private var probeFailed: Boolean = false

    /** The Vulkan 1.3 backend kind. */
    override val kind: BackendKind
        get() = BackendKind.Vulkan

    /** Whether Vulkan natives, a loader, and a dynamic-rendering-capable device exist here. */
    override fun isAvailable(): Boolean = obtainProbe() != null

    /**
     * Reports the probed capabilities of the current machine.
     *
     * @throws LwjglVulkanException when no usable Vulkan device exists; [isAvailable] is the
     *     check a caller should consult first.
     */
    override fun capabilities(): GraphicsCapabilities {
        val probe = obtainProbe()
            ?: throw LwjglVulkanException(
                "The Vulkan backend is not available on this platform",
            )
        return probe.capabilities
    }

    /**
     * Creates a real Vulkan device by running a fresh probe.
     *
     * The device inherits the Vulkan instance from that probe; the previously cached probe (if
     * any) is released afterwards so nothing owns an instance twice.
     *
     * @throws LwjglVulkanException if no device can be created.
     */
    override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice {
        val probe = LwjglVulkanProbe.create(preferredDeviceName)
        try {
            val device = LwjglVulkanBackendDevice.fromProbe(probe, descriptor.label)
            dropCachedProbe()
            return device
        } catch (error: Throwable) {
            probe.destroy()
            throw error
        }
    }

    /**
     * Releases the cached availability probe.
     *
     * Devices created through [createDevice] are unaffected: they own their own probe resources.
     */
    public fun close() {
        dropCachedProbe()
    }

    private fun obtainProbe(): LwjglVulkanProbe? {
        if (probeFailed) return null
        cachedProbe?.let { return it }
        return try {
            LwjglVulkanProbe.create(preferredDeviceName).also { cachedProbe = it }
        } catch (_: LwjglVulkanException) {
            // Probing is deliberately attempted once per backend instance: a machine without a
            // usable Vulkan device does not gain one by probing again on every call.
            probeFailed = true
            null
        }
    }

    private fun dropCachedProbe() {
        cachedProbe?.destroy()
        cachedProbe = null
    }
}
