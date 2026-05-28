package com.freeturn.app.tunnel

import com.freeturn.app.data.ClientConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTransportResolverTest {

    @Test
    fun fullTunnelVlessWithBondUsesVlessAndBond() {
        val config = ClientConfig(
            tunnelMode = TunnelMode.FULL_TUNNEL,
            fullTunnelClientUri = "vless://uuid@127.0.0.1:9000?security=tls",
            threads = 10,
            enableVlessBond = true
        )

        val flags = ClientTransportResolver.resolve(config, serverVlessBond = false)

        assertTrue(flags.vlessMode)
        assertTrue(flags.vlessBond)
    }

    @Test
    fun fullTunnelHy2DoesNotUseVlessBond() {
        val config = ClientConfig(
            tunnelMode = TunnelMode.FULL_TUNNEL,
            fullTunnelClientUri = "hy2://pass@127.0.0.1:9000",
            enableVlessBond = true
        )

        val flags = ClientTransportResolver.resolve(config, serverVlessBond = true)

        assertFalse(flags.vlessMode)
        assertFalse(flags.vlessBond)
    }

    @Test
    fun localProxyDoesNotEnableBondByDefault() {
        val config = ClientConfig(
            tunnelMode = TunnelMode.LOCAL_PROXY,
            vlessMode = true
        )

        val flags = ClientTransportResolver.resolve(config, serverVlessBond = false)

        assertTrue(flags.vlessMode)
        assertFalse(flags.vlessBond)
    }
}
