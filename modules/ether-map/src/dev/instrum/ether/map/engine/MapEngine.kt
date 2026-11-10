package dev.instrum.ether.map.engine

import dev.instrum.ether.core.EngineState
import dev.instrum.ether.core.config.EngineConfig
import dev.instrum.ether.core.error.EngineInitializationException
import dev.instrum.ether.core.log.ConsoleLogSink
import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.Logger
import dev.instrum.ether.core.log.SinkLogger
import dev.instrum.ether.render.core.GraphicsDevice

/**
 * A module of the map engine: named lifecycle hook pair driven by [MapEngine].
 */
public interface MapEngineModule {
    public val name: String
    public fun initialize(engine: MapEngine) {}
    public fun update(engine: MapEngine, deltaSeconds: Double) {}
    public fun shutdown(engine: MapEngine) {}
}

/**
 * Map engine runtime: an explicit owner of modules and resources; initialization and shutdown are
 * transactional.
 *
 * It replaces the removed `UrbanEngine`, reusing the `ether-core` lifecycle types ([EngineState],
 * [EngineConfig], [Logger]). Divergence from the removed engine: graphics resources are owned by
 * the injected [GraphicsDevice] (which releases everything on [GraphicsDevice.close]), so the old
 * `registerResource` registry does not exist here.
 */
public class MapEngine(
    public val config: EngineConfig = EngineConfig(applicationName = "Ether Map"),
    public val logger: Logger = SinkLogger(ConsoleLogSink, tag = "ether.map", level = config.logLevel),
    public var graphicsDevice: GraphicsDevice? = null,
) {
    private val modules = mutableListOf<MapEngineModule>()
    private val initializedModules = mutableListOf<MapEngineModule>()
    private var currentState: EngineState = EngineState.Created

    public val state: EngineState
        get() = currentState

    public val frameNumber: Long
        get() = frames

    private var frames = 0L

    public fun addModule(module: MapEngineModule): MapEngine {
        check(currentState == EngineState.Created || currentState == EngineState.Running) {
            "módulo não pode ser adicionado no estado $currentState"
        }
        check(modules.none { it.name == module.name }) { "módulo duplicado: ${module.name}" }
        modules += module
        return this
    }

    public fun initialize() {
        check(currentState == EngineState.Created) { "initialize inválido no estado $currentState" }
        currentState = EngineState.Initializing
        try {
            for (module in modules) {
                module.initialize(this)
                initializedModules += module
                logger.log(LogLevel.Info, "módulo inicializado: ${module.name}")
            }
            currentState = EngineState.Running
        } catch (error: Throwable) {
            currentState = EngineState.Failed
            shutdownInitialized()
            throw EngineInitializationException("Falha ao inicializar Ether Map Engine", error)
        }
    }

    public fun frame(deltaSeconds: Double) {
        check(currentState == EngineState.Running) { "frame inválido no estado $currentState" }
        val delta = deltaSeconds.coerceIn(0.0, config.maximumDeltaSeconds.toDouble())
        for (module in modules) module.update(this, delta)
        frames++
    }

    public fun shutdown() {
        if (currentState == EngineState.Stopped || currentState == EngineState.Created) return
        currentState = EngineState.Stopping
        shutdownInitialized()
        currentState = EngineState.Stopped
    }

    private fun shutdownInitialized() {
        for (module in initializedModules.asReversed()) {
            try {
                module.shutdown(this)
            } catch (error: Throwable) {
                logger.log(LogLevel.Error, "falha ao encerrar módulo ${module.name}", error)
            }
        }
        initializedModules.clear()

        val device = graphicsDevice
        if (device != null) {
            try {
                device.close()
            } catch (error: Throwable) {
                logger.log(LogLevel.Error, "falha ao encerrar dispositivo gráfico", error)
            }
            graphicsDevice = null
        }
    }
}
