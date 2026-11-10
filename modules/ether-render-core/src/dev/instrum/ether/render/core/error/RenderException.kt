package dev.instrum.ether.render.core.error

import dev.instrum.ether.core.error.EtherException

/**
 * Base type for failures raised by the rendering core.
 */
public open class RenderException(
    message: String,
    cause: Throwable? = null,
) : EtherException(message, cause)

/**
 * Raised when a descriptor, command, or render pass violates the render API contract or the
 * capabilities reported by the device.
 */
public class RenderValidationException(
    message: String,
) : RenderException(message)

/**
 * Raised when a handle is stale, already destroyed, or belongs to a different device.
 */
public class InvalidRenderResourceException(
    message: String,
) : RenderException(message)

/**
 * Raised when a rendering operation is attempted on a closed device.
 */
public class RenderDeviceClosedException(
    message: String,
) : RenderException(message)

/**
 * Raised when no registered backend can create a device.
 */
public class RenderBackendUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RenderException(message, cause)

/**
 * Raised when a backend fails while creating or submitting a command buffer.
 */
public class RenderSubmissionException(
    message: String,
    cause: Throwable? = null,
) : RenderException(message, cause)

/**
 * Raised when a render graph is malformed or cannot be executed.
 */
public class RenderGraphException(
    message: String,
) : RenderException(message)
