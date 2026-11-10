package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.core.error.RenderBackendUnavailableException
import dev.instrum.ether.render.core.error.RenderValidationException

/**
 * The default backend preference.
 *
 * Native and web GPU backends are preferred in platform-neutral order, with the null backend as
 * the final fallback. Platform packages may supply a narrower preference.
 */
public val DefaultBackendPreference: List<BackendKind> = listOf(
    BackendKind.Vulkan,
    BackendKind.Metal,
    BackendKind.WebGl2,
    BackendKind.WebGpu,
    BackendKind.Null,
)

/**
 * An explicit, instance-scoped registry of rendering backends.
 *
 * The registry has no global state, so tests and tools can create independent registries.
 * Registration is keyed by [BackendKind]; registering the same kind twice is a validation error.
 */
public class RenderBackendRegistry {
    private val backends: MutableMap<BackendKind, RenderBackend> = LinkedHashMap()

    /** Number of registered backends. */
    public val size: Int
        get() = backends.size

    /** Registers [backend]. */
    public fun register(backend: RenderBackend) {
        if (backend.kind in backends) {
            throw RenderValidationException(
                "A backend for ${backend.kind} is already registered",
            )
        }
        backends[backend.kind] = backend
    }

    /**
     * Unregisters the backend for [kind].
     *
     * @return `true` when a backend was removed.
     */
    public fun unregister(kind: BackendKind): Boolean = backends.remove(kind) != null

    /** Returns the registered backend for [kind], or `null`. */
    public fun find(kind: BackendKind): RenderBackend? = backends[kind]

    /** Returns registered backends in preference order, including only available backends. */
    public fun available(
        preference: List<BackendKind> = DefaultBackendPreference,
    ): List<RenderBackend> {
        val ordered = LinkedHashSet<RenderBackend>()
        preference.forEach { kind ->
            backends[kind]?.takeIf { it.isAvailable() }?.let(ordered::add)
        }
        backends.values.forEach { backend ->
            if (backend.isAvailable()) ordered.add(backend)
        }
        return ordered.toList()
    }

    /**
     * Selects the first available backend in [preference], then any other available registered
     * backend.
     */
    public fun select(
        preference: List<BackendKind> = DefaultBackendPreference,
    ): RenderBackend? = available(preference).firstOrNull()

    /**
     * Selects a backend and creates a device.
     *
     * @throws RenderBackendUnavailableException if no registered backend is available.
     */
    public fun createDevice(
        descriptor: BackendDeviceDescriptor = BackendDeviceDescriptor(),
        preference: List<BackendKind> = DefaultBackendPreference,
    ): GraphicsDevice {
        val backend = select(preference)
            ?: throw RenderBackendUnavailableException(
                "No rendering backend is available; registered kinds: " +
                    backends.keys.joinToString(),
            )
        return GraphicsDevice.create(backend, descriptor)
    }
}
