package dev.instrum.ether.render.nullbackend

import dev.instrum.ether.render.api.BackendCommand
import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.GraphicsCapabilities
import dev.instrum.ether.render.api.RenderBackendDevice
import dev.instrum.ether.render.api.RenderPipelineDescriptor
import dev.instrum.ether.render.api.SamplerDescriptor
import dev.instrum.ether.render.api.ShaderModuleDescriptor
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor

/**
 * The null backend's device.
 *
 * Resources and command buffers are tracked by token so tests can assert exact lifetimes,
 * validation never touches native memory, and no work is executed.
 *
 * This class is single-threaded like the rest of the device layer. Unknown tokens are programming
 * errors and raise [IllegalArgumentException].
 */
public class NullBackendDevice internal constructor(
    /** Debug label of this device. */
    public val label: String,
    /** Capabilities reported by the creating backend. */
    public val capabilities: GraphicsCapabilities,
    private val completionMode: NullCompletionMode,
) : RenderBackendDevice {
    private data class ResourceRecord(
        val kind: String,
        val descriptor: Any,
        val shaderStage: ShaderStage? = null,
        val contents: ByteArray? = null,
    )

    private data class RecordedCommandBuffer(
        val label: String?,
        val commands: List<BackendCommand>,
    )

    private var nextTokenValue: Long = 1L
    private var lastSubmissionToken: Long = 0L
    private var submissionsReceived: Long = 0L
    private var completedThrough: Long = 0L
    private var closed: Boolean = false

    private val resources: MutableMap<Long, ResourceRecord> = LinkedHashMap()
    private val commandBuffers: MutableMap<Long, RecordedCommandBuffer> = LinkedHashMap()
    private val pendingSubmissions: MutableSet<Long> = LinkedHashSet()
    private val submissionBatches: MutableList<List<BackendCommand>> = ArrayList()

    /** Number of resources that have not been destroyed. */
    public val liveResourceCount: Int
        get() = resources.size

    /** Number of command buffers created but not yet submitted or destroyed. */
    public val liveCommandBufferCount: Int
        get() = commandBuffers.size

    /** Number of submissions that are not complete yet. */
    public val pendingSubmissionCount: Int
        get() = pendingSubmissions.size

    /** Total number of submissions received. */
    public val submissionCount: Long
        get() = submissionsReceived

    /** Number of times [waitIdle] was called. */
    public var waitIdleCount: Int = 0
        private set

    /** Every command batch submitted to this device, in submission order. */
    public val submittedBatches: List<List<BackendCommand>>
        get() = submissionBatches.map { it.toList() }

    /** Whether [close] has been called. */
    public val isClosed: Boolean
        get() = closed

    override fun createBuffer(descriptor: BufferDescriptor): Long =
        register("buffer", descriptor)

    override fun writeBuffer(bufferToken: Long, offsetBytes: Long, data: ByteArray) {
        ensureOpen()
        val record = resources[bufferToken]
            ?: throw IllegalArgumentException("Unknown buffer token: $bufferToken")
        if (record.kind != "buffer") {
            throw IllegalArgumentException("Token $bufferToken is not a buffer")
        }
        val descriptor = record.descriptor as BufferDescriptor
        if (offsetBytes < 0L || offsetBytes + data.size.toLong() > descriptor.sizeBytes) {
            throw IllegalArgumentException(
                "Write of ${data.size} bytes at offset $offsetBytes exceeds the " +
                    "${descriptor.sizeBytes}-byte buffer",
            )
        }
        val target = record.contents ?: ByteArray(descriptor.sizeBytes.toInt())
        data.copyInto(target, offsetBytes.toInt())
        resources[bufferToken] = record.copy(contents = target)
    }

    override fun createTexture(descriptor: TextureDescriptor): Long =
        register("texture", descriptor)

    override fun createSampler(descriptor: SamplerDescriptor): Long =
        register("sampler", descriptor)

    override fun createShaderModule(descriptor: ShaderModuleDescriptor): Long =
        register("shader", descriptor, descriptor.stage)

    override fun createRenderPipeline(
        descriptor: RenderPipelineDescriptor,
        vertexShaderToken: Long,
        fragmentShaderToken: Long?,
    ): Long {
        ensureOpen()
        requireShader(vertexShaderToken, ShaderStage.Vertex)
        fragmentShaderToken?.let { token -> requireShader(token, ShaderStage.Fragment) }
        return register("pipeline", descriptor)
    }

    override fun destroy(token: Long) {
        ensureOpen()
        if (resources.remove(token) != null) return
        if (commandBuffers.remove(token) != null) return
        throw IllegalArgumentException("Unknown or already destroyed token: $token")
    }

    override fun createCommandBuffer(label: String?, commands: List<BackendCommand>): Long {
        ensureOpen()
        val token = nextToken()
        commandBuffers[token] = RecordedCommandBuffer(label, commands.toList())
        return token
    }

    override fun submit(commandBufferTokens: List<Long>): Long {
        ensureOpen()
        val batches = commandBufferTokens.map { token ->
            val record = commandBuffers.remove(token)
                ?: throw IllegalArgumentException("Unknown command buffer token: $token")
            record.commands
        }
        val submissionToken = nextToken()
        lastSubmissionToken = submissionToken
        submissionsReceived++
        submissionBatches += batches.flatten()
        when (completionMode) {
            NullCompletionMode.Automatic -> completedThrough = submissionToken
            NullCompletionMode.Manual -> pendingSubmissions += submissionToken
        }
        return submissionToken
    }

    override fun isSubmissionComplete(submissionToken: Long): Boolean {
        if (submissionToken <= 0L || submissionToken > lastSubmissionToken) {
            throw IllegalArgumentException("Unknown submission token: $submissionToken")
        }
        return submissionToken <= completedThrough
    }

    override fun waitIdle() {
        ensureOpen()
        waitIdleCount++
        completeAllSubmissions()
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingSubmissions.clear()
        commandBuffers.clear()
        resources.clear()
        completedThrough = lastSubmissionToken
    }

    /**
     * Marks every pending submission complete.
     *
     * This has no effect in [NullCompletionMode.Automatic], where submissions complete on submit.
     */
    public fun completeAllSubmissions() {
        if (pendingSubmissions.isEmpty()) return
        completedThrough = lastSubmissionToken
        pendingSubmissions.clear()
    }

    /** Whether [token] currently names a live resource. */
    public fun isTokenAlive(token: Long): Boolean = resources.containsKey(token)

    /** The resource kind for [token], or `null` when it is not a live resource. */
    public fun resourceKind(token: Long): String? = resources[token]?.kind

    /** The recorded command buffer for [token], or `null` when it was submitted or destroyed. */
    public fun recordedCommandBuffer(token: Long): List<BackendCommand>? =
        commandBuffers[token]?.commands

    /**
     * The bytes written into a buffer, or `null` when nothing was written yet.
     *
     * The null backend never executes GPU work; it only records what the engine asked for.
     */
    public fun bufferContents(token: Long): ByteArray? = resources[token]?.contents

    private fun register(kind: String, descriptor: Any, stage: ShaderStage? = null): Long {
        ensureOpen()
        val token = nextToken()
        resources[token] = ResourceRecord(kind, descriptor, stage)
        return token
    }

    private fun requireShader(token: Long, expectedStage: ShaderStage) {
        val record = resources[token]
            ?: throw IllegalArgumentException("Unknown shader module token: $token")
        if (record.kind != "shader") {
            throw IllegalArgumentException("Token $token is not a shader module")
        }
        if (record.shaderStage != expectedStage) {
            throw IllegalArgumentException(
                "Shader module token $token has stage ${record.shaderStage}, expected $expectedStage",
            )
        }
    }

    private fun nextToken(): Long = nextTokenValue++

    private fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Null backend device '$label' is closed")
        }
    }
}
