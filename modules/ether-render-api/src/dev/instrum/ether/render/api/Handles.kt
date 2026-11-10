package dev.instrum.ether.render.api

import kotlin.jvm.JvmInline

/**
 * Opaque handle to a GPU buffer owned by a device.
 *
 * The value is stable and cheap to copy. It is not a pointer and must only be used with the device
 * that created it.
 */
@JvmInline
public value class BufferHandle(public val raw: Long)

/**
 * Opaque handle to a GPU texture owned by a device.
 */
@JvmInline
public value class TextureHandle(public val raw: Long)

/**
 * Opaque handle to a texture sampler owned by a device.
 */
@JvmInline
public value class SamplerHandle(public val raw: Long)

/**
 * Opaque handle to a compiled shader module owned by a device.
 */
@JvmInline
public value class ShaderModuleHandle(public val raw: Long)

/**
 * Opaque handle to a render pipeline owned by a device.
 */
@JvmInline
public value class RenderPipelineHandle(public val raw: Long)

/**
 * Opaque handle to a recorded command buffer owned by a device.
 */
@JvmInline
public value class CommandBufferHandle(public val raw: Long)
