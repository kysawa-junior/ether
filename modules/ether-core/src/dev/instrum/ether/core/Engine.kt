package dev.instrum.ether.core

import dev.instrum.ether.core.config.EngineConfig
import dev.instrum.ether.core.error.EngineInitializationException
import dev.instrum.ether.core.error.InvalidEngineStateException
import dev.instrum.ether.core.event.Event
import dev.instrum.ether.core.event.EventBus
import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.LogSink
import dev.instrum.ether.core.log.Logger
import dev.instrum.ether.core.log.SinkLogger
import dev.instrum.ether.core.log.defaultLogSink
import dev.instrum.ether.core.time.FrameTiming
import dev.instrum.ether.core.time.FrameTimer
import dev.instrum.ether.core.time.MonotonicTimeSource
import dev.instrum.ether.core.time.TimeSource

/**
 * The lifecycle states an [Engine] transitions through.
 *
 * The legal transitions are:
 *
 * ```
 * Created -> Initializing -> Running <-> Paused -> Stopping -> Stopped
 *                   |                                  ^
 *                   +--------------> Failed -----------+
 * ```
 */
public enum class EngineState {
    /** The engine has been constructed but [Engine.initialize] has not been called. */
    Created,

    /** [Engine.initialize] is executing. */
    Initializing,

    /** The engine is running and [Engine.update] may be called. */
    Running,

    /** The engine is initialised but temporarily not processing frames. */
    Paused,

    /** [Engine.shutdown] is executing. */
    Stopping,

    /** The engine has been shut down and cannot be reused. */
    Stopped,

    /** Initialisation or shutdown failed. */
    Failed,
}

/**
 * The Ether engine core.
 *
 * The engine owns the lifecycle, the frame clock, the event bus and the logging pipeline. It does
 * not own a window, a renderer or a scene; those are layered on top in later milestones.
 *
 * Typical use:
 *
 * ```
 * val engine = Engine.create(EngineConfig(applicationName = "Demo"))
 * engine.initialize()
 * while (engine.currentState == EngineState.Running) {
 *     engine.update()
 * }
 * engine.shutdown()
 * ```
 *
 * Instances are not thread-safe. All lifecycle and update calls must happen on the owning thread.
 */
public open class Engine protected constructor(
    /** The configuration this engine was created with. */
    public val config: EngineConfig,
    /** The bus used to dispatch engine events. */
    public val events: EventBus,
    /** The logger this engine writes to. */
    public val logger: Logger,
    private val timer: FrameTimer,
) {
    private var state: EngineState = EngineState.Created

    /** The current lifecycle state of this engine. */
    public val currentState: EngineState
        get() = state

    /** The timing state of the most recently completed frame. */
    public var lastFrameTiming: FrameTiming = FrameTiming(0L, 0L, 0L)
        private set

    /**
     * Prepares the engine for use.
     *
     * @throws InvalidEngineStateException if the engine is not in the [EngineState.Created] state.
     * Any exception thrown by [onInitialize] is wrapped in [EngineInitializationException], which
     * propagates to the caller after the engine moves to [EngineState.Failed].
     */
    public fun initialize() {
        requireState(EngineState.Created, "initialize")
        state = EngineState.Initializing
        try {
            logger.info("Initializing engine '${config.applicationName}'")
            onInitialize()
            state = EngineState.Running
            logger.info("Engine initialized")
        } catch (error: Throwable) {
            state = EngineState.Failed
            throw EngineInitializationException(
                "Engine '${config.applicationName}' failed to initialize",
                error,
            )
        }
    }

    /**
     * Advances the engine by one frame.
     *
     * @return the timing state of the frame that was processed.
     * @throws InvalidEngineStateException if the engine is not in the [EngineState.Running] state.
     */
    public fun update(): FrameTiming {
        requireState(EngineState.Running, "update")
        val raw = timer.beginFrame()
        val clamped = raw.copy(deltaNanos = clampDelta(raw.deltaNanos))
        lastFrameTiming = clamped
        onUpdate(clamped)
        return clamped
    }

    /**
     * Pauses frame processing. The engine keeps its resources and can be resumed with [resume].
     */
    public fun pause() {
        requireState(EngineState.Running, "pause")
        state = EngineState.Paused
        logger.info("Engine paused")
    }

    /** Resumes frame processing after [pause]. */
    public fun resume() {
        requireState(EngineState.Paused, "resume")
        state = EngineState.Running
        logger.info("Engine resumed")
    }

    /**
     * Shuts the engine down and releases its resources.
     *
     * @throws InvalidEngineStateException if the engine is already stopped.
     */
    public fun shutdown() {
        when (state) {
            EngineState.Stopped -> throw InvalidEngineStateException("Engine is already stopped")
            EngineState.Stopping -> throw InvalidEngineStateException("Engine is already stopping")
            else -> Unit
        }
        state = EngineState.Stopping
        try {
            onShutdown()
            state = EngineState.Stopped
            logger.info("Engine stopped")
        } catch (error: Throwable) {
            state = EngineState.Failed
            throw error
        }
    }

    /** Hook invoked during [initialize]. Subclasses may override to acquire resources. */
    protected open fun onInitialize() {}

    /** Hook invoked on every [update]. Subclasses may override to advance engine state. */
    protected open fun onUpdate(timing: FrameTiming) {}

    /** Hook invoked during [shutdown]. Subclasses may override to release resources. */
    protected open fun onShutdown() {}

    private fun clampDelta(deltaNanos: Long): Long {
        val maxNanos = (config.maximumDeltaSeconds * 1_000_000_000L).toLong()
        return if (deltaNanos > maxNanos) maxNanos else deltaNanos
    }

    private fun requireState(expected: EngineState, operation: String) {
        if (state != expected) {
            throw InvalidEngineStateException(
                "Cannot $operation while the engine is in state $state; expected $expected",
            )
        }
    }

    public companion object {
        /**
         * Creates an engine with the given [config].
         *
         * @param config the engine configuration.
         * @param sink the destination for log records; defaults to the platform console.
         * @param timeSource the clock used for frame timing.
         */
        public fun create(
            config: EngineConfig = EngineConfig(),
            sink: LogSink? = null,
            timeSource: TimeSource = MonotonicTimeSource,
        ): Engine = Engine(
            config = config,
            events = EventBus(),
            logger = SinkLogger(
                sink = sink ?: defaultLogSink(),
                tag = "ether",
                level = config.logLevel,
            ),
            timer = FrameTimer(timeSource),
        )
    }
}

/**
 * Creates an engine with the given [config] and the platform's default logging sink.
 */
public fun createEngine(config: EngineConfig = EngineConfig()): Engine = Engine.create(config)

/**
 * The log level used by the engine when none is configured explicitly.
 */
public val DefaultEngineLogLevel: LogLevel = LogLevel.Info
