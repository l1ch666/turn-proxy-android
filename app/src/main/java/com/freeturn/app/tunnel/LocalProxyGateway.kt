package com.freeturn.app.tunnel

import com.freeturn.app.data.ClientConfig
import com.freeturn.app.viewmodel.ProxyState
import kotlinx.coroutines.flow.StateFlow

interface LocalProxyGateway {
    val proxyState: StateFlow<ProxyState>
    suspend fun startProxy(cfg: ClientConfig)
    fun stopProxy()
}
