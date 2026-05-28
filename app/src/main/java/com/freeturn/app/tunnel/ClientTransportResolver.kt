package com.freeturn.app.tunnel

import com.freeturn.app.data.ClientConfig

data class ClientTransportFlags(
    val vlessMode: Boolean,
    val vlessBond: Boolean,
    val fullTunnelScheme: String?
)

object ClientTransportResolver {
    fun resolve(config: ClientConfig, serverVlessBond: Boolean): ClientTransportFlags {
        val scheme = fullTunnelScheme(config)
        val fullTunnelVless = config.tunnelMode == TunnelMode.FULL_TUNNEL && scheme == "vless"
        val fullTunnelNonVless = config.tunnelMode == TunnelMode.FULL_TUNNEL &&
            scheme != null &&
            scheme != "vless"
        val effectiveVlessMode = config.vlessMode || fullTunnelVless
        val requestedBond = config.enableVlessBond || serverVlessBond
        val effectiveVlessBond = effectiveVlessMode && requestedBond && !fullTunnelNonVless
        return ClientTransportFlags(
            vlessMode = effectiveVlessMode,
            vlessBond = effectiveVlessBond,
            fullTunnelScheme = scheme
        )
    }

    fun fullTunnelScheme(config: ClientConfig): String? {
        if (config.tunnelMode != TunnelMode.FULL_TUNNEL) return null
        return FullTunnelUriValidator.validate(config.fullTunnelClientUri).parsed?.scheme
    }
}
