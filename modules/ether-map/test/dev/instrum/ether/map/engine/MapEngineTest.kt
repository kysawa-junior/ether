package dev.instrum.ether.map.engine

import dev.instrum.ether.core.EngineState
import dev.instrum.ether.core.config.EngineConfig
import dev.instrum.ether.core.error.EngineInitializationException
import dev.instrum.ether.core.log.LogLevel
import dev.instrum.ether.core.log.Logger
import dev.instrum.ether.render.core.GraphicsDevice
import dev.instrum.ether.render.nullbackend.NullBackend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine lifecycle contracts, ported from the removed `UrbanEngine` test (`engine lifecycle
 * inicializa e encerra modulos`): transactional init/shutdown, frame gating and the injected
 * graphics device released on shutdown.
 */
class MapEngineTest {

    /** Silent logger standing in for the engine default. */
    private fun silentLogger() = object : Logger {
        override val tag: String = "test.engine"
        override val level: LogLevel = LogLevel.Info
        override fun log(level: LogLevel, message: String, throwable: Throwable?) {}
    }

    private class RecordingModule(
        override val name: String,
        private val events: MutableList<String>,
        private val failOnInit: Boolean = false,
    ) : MapEngineModule {
        override fun initialize(engine: MapEngine) {
            if (failOnInit) throw IllegalStateException("falha proposital de inicialização")
            events += "init:$name"
        }

        override fun shutdown(engine: MapEngine) {
            events += "shutdown:$name"
        }
    }

    @Test
    fun `engine lifecycle inicializa e encerra modulos`() {
        val events = mutableListOf<String>()
        val module = RecordingModule("test", events)
        val engine = MapEngine(logger = silentLogger()).addModule(module)
        engine.initialize()
        assertEquals(EngineState.Running, engine.state)
        engine.frame(0.01)
        assertEquals(1, engine.frameNumber)
        engine.shutdown()
        assertEquals(EngineState.Stopped, engine.state)
        assertEquals(listOf("init:test", "shutdown:test"), events)
    }

    @Test
    fun `frame antes do initialize lanca`() {
        val engine = MapEngine(logger = silentLogger())
        assertFailsWith<IllegalStateException> { engine.frame(0.01) }
    }

    @Test
    fun `modulo duplicado lanca`() {
        val events = mutableListOf<String>()
        val engine = MapEngine(logger = silentLogger())
        engine.addModule(RecordingModule("same", events))
        assertFailsWith<IllegalStateException> { engine.addModule(RecordingModule("same", events)) }
    }

    @Test
    fun `falha na inicializacao desfaz modulos ja iniciados`() {
        val events = mutableListOf<String>()
        val engine = MapEngine(logger = silentLogger())
        engine.addModule(RecordingModule("good", events))
        engine.addModule(RecordingModule("bad", events, failOnInit = true))
        val error = assertFailsWith<EngineInitializationException> { engine.initialize() }
        assertEquals(EngineState.Failed, engine.state)
        assertEquals(
            listOf("init:good", "shutdown:good"),
            events,
            "rollback encerra apenas o que chegou a inicializar, em ordem reversa",
        )
        assertTrue(error.message!!.contains("Ether Map"))
    }

    @Test
    fun `shutdown encerra dispositivo grafico injetado`() {
        val engine = MapEngine(logger = silentLogger())
        val device = GraphicsDevice.create(NullBackend())
        engine.graphicsDevice = device
        engine.initialize()
        engine.shutdown()
        assertTrue(device.isClosed, "shutdown fecha o dispositivo")
        assertNull(engine.graphicsDevice, "referência liberada após o shutdown")
    }

    @Test
    fun `shutdown sem initialize e permitido`() {
        val engine = MapEngine(logger = silentLogger())
        engine.shutdown()
        assertEquals(EngineState.Created, engine.state, "sem módulos inicializados, nada a fazer")
    }
}
