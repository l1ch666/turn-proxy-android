package com.freeturn.app.tunnel

import com.freeturn.app.ConnectionStats
import com.freeturn.app.ProxyServiceState
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.viewmodel.ProxyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TunnelSupervisor(
    private val localProxy: LocalProxyGateway,
    private val fullTunnelEngine: FullTunnelEngine,
    private val proxyRunning: StateFlow<Boolean> = ProxyServiceState.isRunning,
    private val connectionStats: StateFlow<ConnectionStats> = ProxyServiceState.connectionStats,
    private val log: (String) -> Unit = ProxyServiceState::addLog
) {
    private val _fullTunnelState = MutableStateFlow<FullTunnelState>(FullTunnelState.Disabled)
    val fullTunnelState: StateFlow<FullTunnelState> = _fullTunnelState.asStateFlow()

    private val engineMutex = Mutex()
    private var requestedConfig: ClientConfig? = null

    suspend fun observeProxyAvailability() {
        combine(proxyRunning, connectionStats) { _, _ -> Unit }
            .collect { onProxyAvailabilityChanged() }
    }

    suspend fun start(cfg: ClientConfig) {
        if (cfg.tunnelMode == TunnelMode.LOCAL_PROXY) {
            requestedConfig = null
            stopFullTunnelEngine()
            _fullTunnelState.value = FullTunnelState.Disabled
            localProxy.startProxy(cfg)
            return
        }

        val validation = FullTunnelUriValidator.validate(cfg.fullTunnelClientUri)
        if (!validation.isValid) {
            requestedConfig = null
            setFailure(validation.errors.joinToString("; "))
            return
        }

        requestedConfig = cfg
        _fullTunnelState.value = FullTunnelState.WaitingForProxy
        log("Full tunnel URI: ${FullTunnelUriValidator.sanitizeForLogs(cfg.fullTunnelClientUri)}")
        validation.warnings.forEach { log("Full tunnel warning: $it") }

        localProxy.startProxy(cfg)
        if (localProxy.proxyState.value is ProxyState.Error) {
            requestedConfig = null
            setFailure("Local proxy failed")
            return
        }
        onProxyAvailabilityChanged()
    }

    suspend fun stop() {
        requestedConfig = null
        stopFullTunnelEngine()
        _fullTunnelState.value = FullTunnelState.Disabled
        localProxy.stopProxy()
    }

    fun failVpnPermissionDenied() {
        requestedConfig = null
        _fullTunnelState.value = FullTunnelState.Failed("VPN permission denied")
        log("Full tunnel failed: VPN permission denied")
    }

    suspend fun onProxyAvailabilityChanged() {
        val cfg = requestedConfig ?: return
        if (cfg.tunnelMode != TunnelMode.FULL_TUNNEL) return

        val hasActiveProxyConnection = proxyRunning.value && connectionStats.value.active >= 1
        if (!hasActiveProxyConnection) {
            stopFullTunnelEngine()
            if (_fullTunnelState.value !is FullTunnelState.Failed) {
                _fullTunnelState.value = FullTunnelState.WaitingForProxy
            }
            return
        }

        ensureFullTunnelStarted(cfg)
    }

    private suspend fun ensureFullTunnelStarted(cfg: ClientConfig) {
        engineMutex.withLock {
            if (fullTunnelEngine.isRunning()) return

            val validation = FullTunnelUriValidator.validate(cfg.fullTunnelClientUri)
            if (!validation.isValid) {
                requestedConfig = null
                setFailure(validation.errors.joinToString("; "))
                return
            }

            val localEndpoint = FullTunnelUriValidator.parseHostPort(cfg.localPort)
            if (localEndpoint == null) {
                requestedConfig = null
                setFailure("Invalid local proxy listen address")
                return
            }

            _fullTunnelState.value = FullTunnelState.Starting
            val fullConfig = FullTunnelConfig(
                clientUri = cfg.fullTunnelClientUri.trim(),
                localProxyHost = localEndpoint.host,
                localProxyPort = localEndpoint.port,
                excludeOwnAppFromVpn = true
            )
            try {
                fullTunnelEngine.start(fullConfig)
                _fullTunnelState.value = FullTunnelState.Running
                log("Full tunnel backend started: ${FullTunnelUriValidator.sanitizeForLogs(fullConfig.clientUri)}")
            } catch (e: Exception) {
                if (fullTunnelEngine.isRunning()) {
                    runCatching { fullTunnelEngine.stop() }
                }
                setFailure(FullTunnelUriValidator.sanitizeMessage(e.message ?: e::class.java.simpleName))
            }
        }
    }

    private suspend fun stopFullTunnelEngine() {
        engineMutex.withLock {
            if (!fullTunnelEngine.isRunning()) return
            try {
                fullTunnelEngine.stop()
            } catch (e: Exception) {
                log("Full tunnel stop failed: ${FullTunnelUriValidator.sanitizeMessage(e.message ?: e::class.java.simpleName)}")
            }
        }
    }

    private fun setFailure(message: String) {
        val sanitized = FullTunnelUriValidator.sanitizeMessage(message)
        _fullTunnelState.value = FullTunnelState.Failed(sanitized)
        log("Full tunnel failed: $sanitized")
    }
}
