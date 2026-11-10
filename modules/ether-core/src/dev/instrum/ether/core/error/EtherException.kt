package dev.instrum.ether.core.error

/**
 * The base type for all exceptions raised by the Ether engine.
 *
 * Engine failures are reported as exceptions rather than silently ignored, so that callers can
 * distinguish engine faults from application faults.
 */
public open class EtherException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Raised when the engine cannot be initialised, for example because no graphics backend is
 * available on the current platform.
 */
public class EngineInitializationException(
    message: String,
    cause: Throwable? = null,
) : EtherException(message, cause)

/**
 * Raised when an operation is attempted in an invalid engine lifecycle state.
 */
public class InvalidEngineStateException(
    message: String,
) : EtherException(message)

/**
 * Raised when a required service or resource is not registered.
 */
public class MissingServiceException(
    message: String,
) : EtherException(message)
