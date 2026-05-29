package com.freeturn.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkHandoverPolicyTest {
    @Test
    fun internetNonVpnNetworkCanRestartProxy() {
        assertTrue(NetworkHandoverPolicy.shouldRestartForNetwork(hasInternet = true, isVpn = false))
    }

    @Test
    fun vpnNetworkDoesNotRestartProxy() {
        assertFalse(NetworkHandoverPolicy.shouldRestartForNetwork(hasInternet = true, isVpn = true))
    }

    @Test
    fun nonInternetNetworkDoesNotRestartProxy() {
        assertFalse(NetworkHandoverPolicy.shouldRestartForNetwork(hasInternet = false, isVpn = false))
    }

    @Test
    fun physicalInternetNetworkCanBeUsedForFullTunnelOutbound() {
        assertTrue(NetworkHandoverPolicy.shouldUseForFullTunnelOutbound(hasInternet = true, isVpn = false))
    }

    @Test
    fun vpnNetworkCannotBeUsedForFullTunnelOutbound() {
        assertFalse(NetworkHandoverPolicy.shouldUseForFullTunnelOutbound(hasInternet = true, isVpn = true))
    }
}
