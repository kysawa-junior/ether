package dev.instrum.ether.render.api

/**
 * Describes a backend device to create.
 *
 * @property label an optional debug label.
 */
public data class BackendDeviceDescriptor(
    public val label: String = "Ether Device",
)

/**
 * A rendering backend implementation.
 *
 * Backends are registered at runtime and selected by preference. Implementations must report
 * capabilities truthfully; a feature or format may only be reported when it can be executed.
 */
public interface RenderBackend {
    /** The backend kind. */
    public val kind: BackendKind

    /** Whether this backend can create a device on the current platform. */
    public fun isAvailable(): Boolean

    /** Reports capabilities, limits, supported formats, and layout conventions. */
    public fun capabilities(): GraphicsCapabilities

    /**
     * Creates a device.
     *
     * @throws RuntimeException if the backend cannot create a device.
     */
    public fun createDevice(descriptor: BackendDeviceDescriptor): RenderBackendDevice
}

/**
 * A backend device created by a [RenderBackend].
 *
 * All methods return opaque backend tokens. Token values are owned by the backend and must not be
 * interpreted by callers.
 */
public interface RenderBackendDevice {
    /** Creates a buffer and returns its backend token. */
    public fun createBuffer(descriptor: BufferDescriptor): Long

    /**
     * Writes [data] into a buffer created by [createBuffer].
     *
     * The write must be visible to every later submission that uses the buffer. Implementations
     * may stage the data and copy it on the next submission, but they must not report the write as
     * complete before the data reaches the buffer.
     *
     * @param bufferToken the backend token of the destination buffer.
     * @param offsetBytes the byte offset of the first written byte.
     * @param data the bytes to write.
     * @throws IllegalArgumentException if the token is unknown or the range is out of bounds.
     */
    public fun writeBuffer(bufferToken: Long, offsetBytes: Long, data: ByteArray)

    /** Creates a texture and returns its backend token. */
    public fun createTexture(descriptor: TextureDescriptor): Long

    /** Creates a sampler and returns its backend token. */
    public fun createSampler(descriptor: SamplerDescriptor): Long

    /** Creates a shader module and returns its backend token. */
    public fun createShaderModule(descriptor: ShaderModuleDescriptor): Long

    /**
     * Creates a render pipeline.
     *
     * @param descriptor the validated pipeline state. Shader handles inside the descriptor are
     * informational; the backend must use [vertexShaderToken] and [fragmentShaderToken].
     * @param vertexShaderToken backend token of the vertex shader.
     * @param fragmentShaderToken backend token of the fragment shader, or `null`.
     */
    public fun createRenderPipeline(
        descriptor: RenderPipelineDescriptor,
        vertexShaderToken: Long,
        fragmentShaderToken: Long?,
    ): Long

    /**
     * Destroys a resource token.
     *
     * The core calls this only when the resource is no longer in flight. Destroying an unknown or
     * already destroyed token is an error. Command buffer tokens created by
     * [createCommandBuffer] may also be destroyed before submission.
     */
    public fun destroy(token: Long)

    /** Creates a backend command buffer from translated backend commands. */
    public fun createCommandBuffer(label: String?, commands: List<BackendCommand>): Long

    /**
     * Submits command buffers for execution.
     *
     * Ownership of each token transfers to the backend; the core will not destroy or submit it
     * again. Returns a token that identifies this submission until [isSubmissionComplete] reports
     * `true`. Submission tokens must be unique while a submission is pending.
     */
    public fun submit(commandBufferTokens: List<Long>): Long

    /**
     * Reports whether all GPU work for [submissionToken] has completed.
     *
     * Once this returns `true` for a token, the core may release resources referenced by that
     * submission. Implementations may report completion eagerly but must never report it early.
     */
    public fun isSubmissionComplete(submissionToken: Long): Boolean

    /** Blocks until all submitted work has completed. */
    public fun waitIdle()

    /** Releases the backend device and any resources it still owns. */
    public fun close()
}
