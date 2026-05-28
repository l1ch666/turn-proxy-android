package com.freeturn.app.tunnel

import com.freeturn.app.ConnectionStats
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.viewmodel.ProxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSupervisorTest {

    @Test
    fun fullTunnelDoesNotStartBeforeActiveConnection() = runTest {
        val running = MutableStateFlow(false)
        val stats = MutableStateFlow(ConnectionStats.IDLE)
        val local = FakeLocalProxyGateway(running, stats)
        val engine = RecordingFullTunnelEngine()
        val supervisor = TunnelSupervisor(
            localProxy = local,
            proxyRunning = running,
            connectionStats = stats,
            fullTunnelEngine = engine,
            log = {}
        )

        supervisor.start(fullTunnelConfig())

        assertEquals(listOf("start-local"), local.events)
        assertFalse(engine.isRunning())

        running.value = true
        supervisor.onProxyAvailabilityChanged()
        assertFalse(engine.isRunning())

        stats.value = ConnectionStats(active = 1, total = 4)
        supervisor.onProxyAvailabilityChanged()

        assertTrue(engine.isRunning())
        assertEquals(1, engine.started.size)
    }

    @Test
    fun stopStopsFullTunnelBeforeLocalProxy() = runTest {
        val running = MutableStateFlow(true)
        val stats = MutableStateFlow(ConnectionStats(active = 1, total = 4))
        val events = mutableListOf<String>()
        val local = FakeLocalProxyGateway(running, stats, events)
        val engine = RecordingFullTunnelEngine(events)
        val supervisor = TunnelSupervisor(
            localProxy = local,
            proxyRunning = running,
            connectionStats = stats,
            fullTunnelEngine = engine,
            log = {}
        )

        supervisor.start(fullTunnelConfig())
        supervisor.stop()

        assertEquals(listOf("start-local", "start-full", "stop-full", "stop-local"), events)
    }

    @Test
    fun localProxyDropStopsFullTunnel() = runTest {
        val running = MutableStateFlow(true)
        val stats = MutableStateFlow(ConnectionStats(active = 1, total = 4))
        val local = FakeLocalProxyGateway(running, stats)
        val engine = RecordingFullTunnelEngine()
        val supervisor = TunnelSupervisor(
            localProxy = local,
            proxyRunning = running,
            connectionStats = stats,
            fullTunnelEngine = engine,
            log = {}
        )

        supervisor.start(fullTunnelConfig())
        assertTrue(engine.isRunning())

        stats.value = ConnectionStats.IDLE
        supervisor.onProxyAvailabilityChanged()

        assertFalse(engine.isRunning())
    }

    private fun fullTunnelConfig() = ClientConfig(
        serverAddress = "1.2.3.4:56000",
        vkLink = "https://vk.com/call/join/test",
        localPort = "127.0.0.1:9000",
        tunnelMode = TunnelMode.FULL_TUNNEL,
        fullTunnelClientUri = "vless://id@127.0.0.1:9000"
    )

    private class FakeLocalProxyGateway(
        private val running: MutableStateFlow<Boolean>,
        private val stats: MutableStateFlow<ConnectionStats>,
        val events: MutableList<String> = mutableListOf()
    ) : LocalProxyGateway {
        override val proxyState = MutableStateFlow<ProxyState>(ProxyState.Idle)

        override suspend fun startProxy(cfg: ClientConfig) {
            events += "start-local"
        }

        override fun stopProxy() {
            events += "stop-local"
            running.value = false
            stats.value = ConnectionStats.IDLE
        }
    }

    private class RecordingFullTunnelEngine(
        private val events: MutableList<String> = mutableListOf()
    ) : FullTunnelEngine {
        val started = mutableListOf<FullTunnelConfig>()
        private var running = false

        override suspend fun start(config: FullTunnelConfig) {
            events += "start-full"
            started += config
            running = true
        }

        override suspend fun stop() {
            events += "stop-full"
            running = false
        }

        override fun isRunning(): Boolean = running
    }
}
