package com.freeturn.app.tunnel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class FullTunnelConfig(
    val clientUri: String,
    val localProxyHost: String,
    val localProxyPort: Int,
    val excludeOwnAppFromVpn: Boolean = true,
    // TUN (inner) MTU. 1280 is the conservative default that avoids outer-path
    // fragmentation given the VLESS+smux+KCP+DTLS+TURN overhead. Raise toward
    // ~1340 if your path allows for slightly higher throughput. See docs/TUNING.md.
    val tunMtu: Int = 1280
)

sealed class FullTunnelState {
    data object Disabled : FullTunnelState()
    data object WaitingForProxy : FullTunnelState()
    data object Starting : FullTunnelState()
    data object Running : FullTunnelState()
    data class Failed(val message: String) : FullTunnelState()
}

interface FullTunnelEngine {
    suspend fun start(config: FullTunnelConfig)
    suspend fun stop()
    fun isRunning(): Boolean
}

class FullTunnelBackendException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

class SingBoxFullTunnelEngine(
    context: Context,
    private val startTimeoutMs: Long = 12_000L,
    private val stopTimeoutMs: Long = 5_000L
) : FullTunnelEngine {
    private val appContext = context.applicationContext

    override suspend fun start(config: FullTunnelConfig) {
        val validation = FullTunnelUriValidator.validate(config.clientUri)
        if (!validation.isValid) {
            throw FullTunnelBackendException(validation.errors.joinToString("; "))
        }

        val configJson = withContext(Dispatchers.Default) {
            SingBoxConfigFactory.build(config)
        }

        FullTunnelRuntime.markStarting()
        FullTunnelVpnService.start(appContext, configJson)

        when (val state = FullTunnelRuntime.awaitTerminalStart(startTimeoutMs)) {
            FullTunnelCoreStatus.Running -> Unit
            is FullTunnelCoreStatus.Failed -> throw FullTunnelBackendException(state.message)
            else -> throw FullTunnelBackendException("Full tunnel core start timed out")
        }
    }

    override suspend fun stop() {
        if (!isRunning() && FullTunnelRuntime.state.value !is FullTunnelCoreStatus.Starting) return
        FullTunnelVpnService.stop(appContext)
        FullTunnelRuntime.awaitStopped(stopTimeoutMs)
    }

    override fun isRunning(): Boolean = FullTunnelRuntime.state.value == FullTunnelCoreStatus.Running
}

internal sealed class FullTunnelCoreStatus {
    data object Stopped : FullTunnelCoreStatus()
    data object Starting : FullTunnelCoreStatus()
    data object Running : FullTunnelCoreStatus()
    data class Failed(val message: String) : FullTunnelCoreStatus()
}

internal object FullTunnelRuntime {
    val state = MutableStateFlow<FullTunnelCoreStatus>(FullTunnelCoreStatus.Stopped)

    fun markStarting() {
        state.value = FullTunnelCoreStatus.Starting
    }

    fun markRunning() {
        state.value = FullTunnelCoreStatus.Running
    }

    fun markStopped() {
        if (state.value !is FullTunnelCoreStatus.Failed) {
            state.value = FullTunnelCoreStatus.Stopped
        }
    }

    fun resetStopped() {
        state.value = FullTunnelCoreStatus.Stopped
    }

    fun markFailed(message: String) {
        state.value = FullTunnelCoreStatus.Failed(message)
    }

    suspend fun awaitTerminalStart(timeoutMs: Long): FullTunnelCoreStatus? =
        withTimeoutOrNull(timeoutMs) {
            state.filter {
                it == FullTunnelCoreStatus.Running || it is FullTunnelCoreStatus.Failed
            }.first()
        }

    suspend fun awaitStopped(timeoutMs: Long): FullTunnelCoreStatus? =
        withTimeoutOrNull(timeoutMs) {
            state.filter {
                it == FullTunnelCoreStatus.Stopped || it is FullTunnelCoreStatus.Failed
            }.first()
        }
}
