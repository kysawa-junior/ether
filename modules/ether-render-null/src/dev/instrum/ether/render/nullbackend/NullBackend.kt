package dev.instrum.ether.render.nullbackend

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.GraphicsFeature
import dev.instrum.ether.render.api.GraphicsLimits
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.TextureFormat

/**
 * How the null backend reports submission completion.
 */
public enum class NullCompletionMode {
    /** Submissions complete as soon as they are submitted. */
    Automatic,

    /** Submissions stay pending until [NullBackendDevice.completeAllSubmissions] or waitIdle. */
    Manual,
}

/**
 * A headless rendering backend that validates and records commands without touching a GPU.
 *
 * The null backend implements the complete backend HAL and is always available. It uses generous
 * synthetic limits; those numbers are not hardware claims. Command batches are retained for
 * inspection by tests and tools.
 */
public class NullBackend(
    /** Completion behavior for submissions created by devices from this backend. */
    public val completionMode: NullCompletionMode = NullCompletionMode.Automatic,
    private val capabilities: GraphicsCapabilities = defaultNullCapabilities(),
) : RenderBackend {
    override val kind: BackendKind
        get() = BackendKind.Null

    override fun isAvailable(): Boolean = true

    override fun capabilities(): GraphicsCapabilities = capabilities

    override fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice =
        NullBackendDevice(
            label = descriptor.label,
            capabilities = capabilities,
            completionMode = completionMode,
        )

    public companion object {
        /** Synthetic capabilities used when none are supplied. */
        public fun defaultCapabilities(): GraphicsCapabilities = defaultNullCapabilities()
    }
}

/**
 * Capabilities reported by a default null backend.
 *
 * Every feature and format is reported because recording a command never fails for lack of
 * hardware. Limits match [GraphicsLimits.Permissive].
 */
public fun defaultNullCapabilities(): GraphicsCapabilities = GraphicsCapabilities(
    backend = BackendKind.Null,
    features = GraphicsFeature.entries.toSet(),
    limits = GraphicsLimits.Permissive,
    supportedTextureFormats = TextureFormat.entries.toSet(),
    clipSpaceDepthZeroToOne = true,
    framebufferOriginBottomLeft = false,
    supportsMappableBuffers = true,
    supportsPersistentMappedBuffers = true,
)
