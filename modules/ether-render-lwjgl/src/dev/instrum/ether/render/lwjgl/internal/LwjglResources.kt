package dev.instrum.ether.render.lwjgl.internal

import dev.instrum.ether.render.api.BufferDescriptor
import dev.instrum.ether.render.api.ShaderStage
import dev.instrum.ether.render.api.TextureDescriptor
import java.nio.ByteBuffer

/**
 * Base type for every resource owned by an LWJGL Vulkan backend device.
 */
internal sealed interface LwjglResource

/**
 * A GPU buffer with its bound device memory.
 */
internal class LwjglBufferResource(
    val buffer: Long,
    val memory: Long,
    val size: Long,
    /** Size of the bound device memory; it can be larger than [size] because of alignment. */
    val allocationSize: Long,
    val descriptor: BufferDescriptor,
    val memoryTypeIndex: Int,
    val hostVisible: Boolean,
    val coherent: Boolean,
    /** Persistent mapping of [memory], or `null` when the buffer lives in device local memory. */
    val mapped: ByteBuffer?,
) : LwjglResource

/**
 * A GPU texture with its image view and bound device memory.
 */
internal class LwjglTextureResource(
    val image: Long,
    val view: Long,
    val memory: Long,
    val descriptor: TextureDescriptor,
    val format: Int,
    val aspectMask: Int,
) : LwjglResource

/**
 * A texture sampler.
 */
internal class LwjglSamplerResource(
    val sampler: Long,
) : LwjglResource

/**
 * A compiled SPIR-V shader module.
 */
internal class LwjglShaderResource(
    val module: Long,
    val stage: ShaderStage,
    val entryPoint: String,
) : LwjglResource

/**
 * A compiled graphics pipeline.
 */
internal class LwjglPipelineResource(
    val pipeline: Long,
) : LwjglResource

/**
 * Copies [data] into a mapped buffer starting at [offset]. Self contained: the mapping is readable
 * from the start again when the call returns.
 */
internal fun ByteBuffer.writeBytes(data: ByteArray, offset: Int) {
    position(offset)
    put(data)
    position(0)
}

/**
 * Reads [count] bytes from the start of a mapped buffer. Self contained: the position is reset so
 * repeated reads return the same bytes.
 */
internal fun ByteBuffer.readBytes(count: Int): ByteArray {
    position(0)
    val bytes = ByteArray(count)
    get(bytes)
    position(0)
    return bytes
}
