package com.freeturn.app.domain

object NetworkHandoverPolicy {
    private fun isPhysicalInternetNetwork(hasInternet: Boolean, isVpn: Boolean): Boolean =
        hasInternet && !isVpn

    fun shouldRestartForNetwork(hasInternet: Boolean, isVpn: Boolean): Boolean =
        isPhysicalInternetNetwork(hasInternet, isVpn)

    fun shouldUseForFullTunnelOutbound(hasInternet: Boolean, isVpn: Boolean): Boolean =
        isPhysicalInternetNetwork(hasInternet, isVpn)
}
