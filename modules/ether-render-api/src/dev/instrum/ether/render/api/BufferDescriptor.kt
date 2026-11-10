package dev.instrum.ether.render.api

/**
 * How a buffer will be used during its lifetime.
 */
public enum class BufferUsage {
    /** Vertex data consumed by draw calls. */
    Vertex,

    /** Index data consumed by indexed draw calls. */
    Index,

    /** Uniform data read by shaders. */
    Uniform,

    /** Storage data read or written by shaders. */
    Storage,

    /** Indirect draw arguments. */
    Indirect,

    /** Source of a copy operation. */
    CopySource,

    /** Destination of a copy operation. */
    CopyDestination,
}

/**
 * Describes a GPU buffer.
 *
 * @property sizeBytes the buffer size in bytes; must be positive.
 * @property usage the non-empty set of usages. A buffer may have several usages.
 * @property label an optional debug label.
 */
public data class BufferDescriptor(
    public val sizeBytes: Long,
    public val usage: Set<BufferUsage>,
    public val label: String? = null,
) {
    init {
        require(sizeBytes > 0) { "Buffer size must be positive, was $sizeBytes" }
        require(usage.isNotEmpty()) { "Buffer usage must not be empty" }
    }
}
