@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.instrum.ether.render.vulkan.internal

import dev.instrum.ether.render.vulkan.VulkanException
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkGetInstanceProcAddr
import dev.instrum.ether.render.vulkan.internal.vkc.PFN_vkVoidFunction
import dev.instrum.ether.render.vulkan.internal.vkc.VkInstance
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.RTLD_LOCAL
import platform.posix.RTLD_NOW
import platform.posix.dlclose
import platform.posix.dlerror
import platform.posix.dlopen
import platform.posix.dlsym

/**
 * The dynamically loaded Vulkan shared library.
 *
 * Ether never links against Vulkan at build time. The loader is opened at runtime so a build made
 * on a machine without a Vulkan driver still starts and can report the backend as unavailable.
 */
internal class VulkanLibrary private constructor(
    private val handle: COpaquePointer,
    private val getInstanceProcAddr: PFN_vkGetInstanceProcAddr,
) {

    /**
     * Looks up an instance-level or global Vulkan entry point.
     *
     * @param instance the instance used for the lookup, or `null` for global functions.
     * @param name the Vulkan function name.
     */
    fun lookup(instance: VkInstance?, name: String): PFN_vkVoidFunction? =
        withNativeString(name) { getInstanceProcAddr.invoke(instance, it) }

    /** Releases the shared library handle. */
    fun close() {
        dlclose(handle)
    }

    companion object {
        /**
         * Candidate shared library names, tried in order.
         *
         * `libvulkan.so.1` is the versioned soname shipped by every distribution; the unversioned
         * name is only present when the development package is installed.
         */
        private val libraryNames = listOf(
            "libvulkan.so.1",
            "libvulkan.so",
        )

        /**
         * Opens the Vulkan loader.
         *
         * @throws VulkanException if no Vulkan loader can be opened or does not export
         * `vkGetInstanceProcAddr`.
         */
        fun open(): VulkanLibrary {
            var lastError: String? = null
            for (name in libraryNames) {
                val handle = dlopen(name, RTLD_NOW or RTLD_LOCAL)
                if (handle == null) {
                    lastError = dlerror()?.toKString()
                    continue
                }
                val symbol = dlsym(handle, "vkGetInstanceProcAddr")
                if (symbol == null) {
                    lastError = dlerror()?.toKString()
                    dlclose(handle)
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val rawPointer = symbol as PFN_vkVoidFunction
                val procAddr = loadVulkanFunction<PFN_vkGetInstanceProcAddr>(
                    rawPointer,
                    "vkGetInstanceProcAddr",
                )
                return VulkanLibrary(handle, procAddr)
            }
            throw VulkanException(
                "Could not open the Vulkan loader (${libraryNames.joinToString(", ")}): " +
                    (lastError ?: "unknown error"),
            )
        }
    }
}

/**
 * Copies [value] into a NUL terminated native string and hands it to [block].
 *
 * The buffer lives for the duration of [block] only.
 */
internal inline fun <T> withNativeString(value: String, block: (CPointer<ByteVar>) -> T): T =
    memScoped {
        val bytes = value.encodeToByteArray()
        val buffer = allocArray<ByteVar>(bytes.size + 1)
        bytes.forEachIndexed { index, byte -> buffer[index] = byte }
        buffer[bytes.size] = 0
        block(buffer)
    }

/**
 * Loads a Vulkan function pointer of the requested type.
 *
 * @param raw the raw pointer returned by the Vulkan loader.
 * @param name the function name used in the failure message.
 */
internal inline fun <reified P> loadVulkanFunction(raw: PFN_vkVoidFunction?, name: String): P {
    if (raw == null) {
        throw VulkanException("The Vulkan loader does not provide $name")
    }
    // Function pointers are plain machine addresses, so a loader supplied pointer is safe to
    // expose under every PFN alias a caller asks for; the cast is erased by the native runtime.
    @Suppress("UNCHECKED_CAST")
    return raw as P
}
