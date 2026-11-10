package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.BackendDeviceDescriptor
import dev.instrum.ether.render.api.BackendKind
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.BufferHandle
import dev.instrum.ether.render.api.CommandBufferHandle
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackend
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.RenderPipelineHandle
import dev.instrum.ether.render.api.SamplerDescriptor
import dev.instrum.ether.render.api.SamplerHandle
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderModuleHandle
import dev.instrum.ether.render.api.TextureDescriptor
import dev.instrum.ether.render.api.TextureHandle
import dev.instrum.ether.render.core.error.RenderBackendUnavailableException
import dev.instrum.ether.render.core.error.RenderDeviceClosedException
import dev.instrum.ether.render.core.error.RenderException
import dev.instrum.ether.render.core.error.RenderSubmissionException
import dev.instrum.ether.render.core.error.RenderValidationException

/**
 * The public rendering device facade.
 *
 * A device owns typed generational handle tables, resource validation, command encoding, and the
 * deferred-destruction queue. Backends never see portable handles; the device translates recorded
 * commands into backend tokens at submission time.
 *
 * Destruction is deferred while submissions that reference a resource are in flight. Completed
 * submissions are collected by [pollCompletedSubmissions], [waitIdle], or automatically at the end
 * of [submit].
 *
 * Instances are single-threaded, matching the engine owner model.
 */
public class GraphicsDevice private constructor(
    /** The backend kind that produced this device. */
    public val kind: BackendKind,
    /** The capabilities reported by the backend for this device. */
    public val capabilities: GraphicsCapabilities,
    /** Debug label supplied when the device was created. */
    public val label: String,
    private val backendDevice: RenderBackendDevice,
) {
    internal val id: Long = nextDeviceId++

    internal val registry: ResourceRegistry = ResourceRegistry(backendDevice, capabilities)

    private val pendingSubmissions: MutableMap<Long, List<Resource>> = LinkedHashMap()
    private var nextCommandBufferHandle: Long = 1L
    private var closed: Boolean = false

    /** Whether [close] has completed. */
    public val isClosed: Boolean
        get() = closed

    /** Number of resources that have not been destroyed. */
    public val liveResourceCount: Int
        get() = registry.liveResourceCount

    /** Number of submissions whose GPU work has not been observed as complete. */
    public val pendingSubmissionCount: Int
        get() = pendingSubmissions.size

    /** Creates a GPU buffer. */
    public fun createBuffer(descriptor: BufferDescriptor): BufferHandle {
        ensureOpen()
        return registry.createBuffer(descriptor)
    }

    /**
     * Writes [data] into a GPU buffer.
     *
     * The data is visible to every submission made after this call. Writes to a buffer that is
     * still referenced by an in-flight submission are ordered by the backend, so the engine never
     * observes a partially updated buffer.
     *
     * @param handle the destination buffer.
     * @param offsetBytes the byte offset of the first written byte.
     * @param data the bytes to write.
     * @throws RenderValidationException if the range lies outside the buffer.
     */
    public fun writeBuffer(
        handle: BufferHandle,
        offsetBytes: Long = 0L,
        data: ByteArray,
    ) {
        ensureOpen()
        registry.writeBuffer(handle, offsetBytes, data)
    }

    /** Creates a GPU texture. */
    public fun createTexture(descriptor: TextureDescriptor): TextureHandle {
        ensureOpen()
        return registry.createTexture(descriptor)
    }

    /** Creates a texture sampler. */
    public fun createSampler(descriptor: SamplerDescriptor): SamplerHandle {
        ensureOpen()
        return registry.createSampler(descriptor)
    }

    /** Creates a shader module. */
    public fun createShaderModule(descriptor: ShaderModuleDescriptor): ShaderModuleHandle {
        ensureOpen()
        return registry.createShaderModule(descriptor)
    }

    /** Creates a render pipeline. */
    public fun createRenderPipeline(
        descriptor: RenderPipelineDescriptor,
    ): RenderPipelineHandle {
        ensureOpen()
        return registry.createRenderPipeline(descriptor)
    }

    /**
     * Creates a command encoder.
     *
     * @param label optional debug label for the resulting command buffer.
     */
    public fun createCommandEncoder(label: String? = null): CommandEncoder {
        ensureOpen()
        return CommandEncoder(this, label)
    }

    /**
     * Destroys [handle] immediately when no submission references it, otherwise after the last
     * referencing submission completes.
     */
    public fun destroyBuffer(handle: BufferHandle) {
        ensureOpen()
        registry.destroyBuffer(handle)
    }

    /** Destroys [handle] immediately when no submission references it, otherwise later. */
    public fun destroyTexture(handle: TextureHandle) {
        ensureOpen()
        registry.destroyTexture(handle)
    }

    /** Destroys [handle] immediately when no submission references it, otherwise later. */
    public fun destroySampler(handle: SamplerHandle) {
        ensureOpen()
        registry.destroySampler(handle)
    }

    /** Destroys [handle] immediately when no submission references it, otherwise later. */
    public fun destroyShaderModule(handle: ShaderModuleHandle) {
        ensureOpen()
        registry.destroyShaderModule(handle)
    }

    /** Destroys [handle] immediately when no submission references it, otherwise later. */
    public fun destroyRenderPipeline(handle: RenderPipelineHandle) {
        ensureOpen()
        registry.destroyRenderPipeline(handle)
    }

    /**
     * Submits one or more recorded command buffers.
     *
     * Command buffers are single-use. Submission tokens returned by the backend are tracked so
     * destroyed resources stay alive until their referencing work completes.
     *
     * @throws RenderValidationException if a command buffer belongs to another device, was already
     * submitted, or references a destroyed resource.
     * @throws RenderSubmissionException if the backend fails to create or submit command buffers.
     */
    public fun submit(vararg commandBuffers: CommandBuffer) {
        ensureOpen()
        if (commandBuffers.isEmpty()) return

        commandBuffers.forEach { commandBuffer ->
            if (commandBuffer.ownerDeviceId != id) {
                throw RenderValidationException(
                    "Command buffer ${commandBuffer.handle.raw} belongs to a different device",
                )
            }
            if (commandBuffer.submitted) {
                throw RenderValidationException(
                    "Command buffer ${commandBuffer.handle.raw} has already been submitted",
                )
            }
        }

        val translator = CommandTranslator(registry)
        val translated = commandBuffers.map { commandBuffer ->
            commandBuffer to translator.translate(commandBuffer.commands)
        }
        val resources = translated
            .flatMap { (_, result) -> result.resources }
            .distinctBy { it.key }

        val tokens = ArrayList<Long>(translated.size)
        try {
            translated.forEach { (commandBuffer, result) ->
                tokens += backendDevice.createCommandBuffer(commandBuffer.label, result.commands)
            }
        } catch (error: Throwable) {
            tokens.forEach { token -> runCatching { backendDevice.destroy(token) } }
            throw RenderSubmissionException("Backend failed to create command buffers", error)
        }

        registry.retain(resources)
        val submissionToken = try {
            backendDevice.submit(tokens)
        } catch (error: Throwable) {
            registry.release(resources)
            tokens.forEach { token -> runCatching { backendDevice.destroy(token) } }
            throw RenderSubmissionException("Backend failed to submit command buffers", error)
        }

        if (pendingSubmissions.containsKey(submissionToken)) {
            // The backend violated its contract by reusing a pending submission token. The work is
            // already submitted, so keep the resources retained rather than risking an early free.
            throw RenderValidationException(
                "Backend returned duplicate pending submission token $submissionToken",
            )
        }
        pendingSubmissions[submissionToken] = resources
        commandBuffers.forEach { it.submitted = true }
        pollCompletedSubmissions()
    }

    /**
     * Releases resources whose submissions have completed.
     *
     * @return the number of completed submissions collected.
     */
    public fun pollCompletedSubmissions(): Int {
        ensureOpen()
        if (pendingSubmissions.isEmpty()) return 0

        var completed = 0
        val iterator = pendingSubmissions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val submissionToken = entry.key
            val resources = entry.value
            if (backendDevice.isSubmissionComplete(submissionToken)) {
                iterator.remove()
                registry.release(resources)
                completed++
            }
        }
        return completed
    }

    /**
     * Blocks until all submitted work has completed, then releases every deferred resource.
     */
    public fun waitIdle() {
        ensureOpen()
        backendDevice.waitIdle()
        val completed = pendingSubmissions.values.toList()
        pendingSubmissions.clear()
        registry.release(completed.flatten())
    }

    /**
     * Closes the device and destroys every remaining resource.
     *
     * Closing is idempotent. After closing, every operation except [isClosed] throws
     * [RenderDeviceClosedException].
     */
    public fun close() {
        if (closed) return
        closed = true

        var firstFailure: Throwable? = null
        try {
            backendDevice.waitIdle()
        } catch (error: Throwable) {
            firstFailure = error
        }
        pendingSubmissions.clear()
        try {
            registry.destroyAll()
        } catch (error: Throwable) {
            if (firstFailure == null) firstFailure = error
        }
        try {
            backendDevice.close()
        } catch (error: Throwable) {
            if (firstFailure == null) firstFailure = error
        }
        firstFailure?.let { failure ->
            throw RenderException("Failed to close the rendering device", failure)
        }
    }

    internal fun resolveBuffer(handle: BufferHandle): BufferResource = registry.resolveBuffer(handle)

    internal fun resolveTexture(handle: TextureHandle): TextureResource =
        registry.resolveTexture(handle)

    internal fun resolveRenderPipeline(handle: RenderPipelineHandle): RenderPipelineResource =
        registry.resolveRenderPipeline(handle)

    internal fun allocateCommandBufferHandle(): CommandBufferHandle =
        CommandBufferHandle(nextCommandBufferHandle++)

    private fun ensureOpen() {
        if (closed) {
            throw RenderDeviceClosedException("Rendering device '$label' is closed")
        }
    }

    public companion object {
        private var nextDeviceId: Long = 1L

        /**
         * Creates a device from [backend].
         *
         * @throws RenderBackendUnavailableException if the backend reports that it is unavailable.
         * @throws RenderValidationException if the backend reports capabilities for a different
         * backend kind.
         */
        public fun create(
            backend: RenderBackend,
            descriptor: BackendDeviceDescriptor = BackendDeviceDescriptor(),
        ): GraphicsDevice {
            if (!backend.isAvailable()) {
                throw RenderBackendUnavailableException(
                    "Rendering backend ${backend.kind} is not available on this platform",
                )
            }
            val capabilities = backend.capabilities()
            if (capabilities.backend != backend.kind) {
                throw RenderValidationException(
                    "Backend ${backend.kind} reported capabilities for ${capabilities.backend}",
                )
            }
            val device = try {
                backend.createDevice(descriptor)
            } catch (error: Throwable) {
                throw RenderBackendUnavailableException(
                    "Rendering backend ${backend.kind} failed to create a device: " +
                        (error.message ?: error::class.simpleName),
                    error,
                )
            }
            return GraphicsDevice(
                kind = backend.kind,
                capabilities = capabilities,
                label = descriptor.label,
                backendDevice = device,
            )
        }
    }
}
