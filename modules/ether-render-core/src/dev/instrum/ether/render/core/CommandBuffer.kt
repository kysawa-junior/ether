package dev.instrum.ether.render.core

import dev.instrum.ether.render.api.CommandBufferHandle
import dev.instrum.ether.render.api.RenderCommand

/**
 * A recorded list of portable render commands that is ready to submit.
 *
 * Command buffers are single-use. They are created by [CommandEncoder.finish] and consumed by
 * [GraphicsDevice.submit].
 */
public class CommandBuffer internal constructor(
    /** Debug identity of this recording. */
    public val handle: CommandBufferHandle,
    /** Optional debug label supplied to the encoder. */
    public val label: String?,
    /** The immutable recorded command list. */
    public val commands: List<RenderCommand>,
    internal val ownerDeviceId: Long,
) {
    internal var submitted: Boolean = false
}
