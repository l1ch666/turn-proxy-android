package com.freeturn.app.domain

import com.freeturn.app.data.AppPreferences
import com.freeturn.app.data.ClientConfig
import com.freeturn.app.data.DnsMode
import com.freeturn.app.tunnel.TunnelMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCommandBuilderTest {

    @Test
    fun fullTunnelVlessBondBuildsVlessBondArgs() {
        val args = ClientCommandBuilder.build(
            executable = "/data/app/libvkturn.so",
            cfg = ClientConfig(
                serverAddress = "1.2.3.4:56000",
                vkLink = "https://vk.com/call/join/test",
                localPort = "127.0.0.1:9000",
                threads = 10,
                tunnelMode = TunnelMode.FULL_TUNNEL,
                fullTunnelClientUri = "vless://uuid@127.0.0.1:9000?security=tls",
                enableVlessBond = true
            ),
            srv = AppPreferences.ServerOpts()
        )

        assertTrue(args.contains("-vless"))
        assertTrue(args.contains("-vless-bond"))
        assertTrue(args.contains("-n"))
        assertTrue(args.contains("10"))
    }

    @Test
    fun fullTunnelHy2DoesNotBuildVlessBondArgs() {
        val args = ClientCommandBuilder.build(
            executable = "/data/app/libvkturn.so",
            cfg = ClientConfig(
                serverAddress = "1.2.3.4:56000",
                vkLink = "https://vk.com/call/join/test",
                fullTunnelClientUri = "hy2://pass@127.0.0.1:9000",
                tunnelMode = TunnelMode.FULL_TUNNEL,
                enableVlessBond = true
            ),
            srv = AppPreferences.ServerOpts(vlessBond = true)
        )

        assertFalse(args.contains("-vless"))
        assertFalse(args.contains("-vless-bond"))
    }

    @Test
    fun localProxyDoesNotEnableBondByDefault() {
        val args = ClientCommandBuilder.build(
            executable = "/data/app/libvkturn.so",
            cfg = ClientConfig(
                serverAddress = "1.2.3.4:56000",
                vkLink = "https://vk.com/call/join/test",
                vlessMode = true
            ),
            srv = AppPreferences.ServerOpts()
        )

        assertTrue(args.contains("-vless"))
        assertFalse(args.contains("-vless-bond"))
    }

    @Test
    fun sanitizedCommandMasksLinkAndWrapKey() {
        val sanitized = ClientCommandBuilder.sanitizeForLogs(
            listOf("/data/app/libvkturn.so", "-vk-link", "https://vk.com/call/join/secret", "-wrap-key", "0123456789abcdef")
        )

        assertFalse(sanitized.contains("secret"))
        assertFalse(sanitized.contains("0123456789abcdef"))
    }

    @Test
    fun androidCompatibilityFlagsAreBuiltWhenConfigured() {
        val wrapKey = "a".repeat(64)
        val args = ClientCommandBuilder.build(
            executable = "/data/app/libvkturn.so",
            cfg = ClientConfig(
                serverAddress = "1.2.3.4:56000",
                vkLink = "https://vk.com/call/join/test",
                streamsPerCred = 4,
                useCarrierDns = true,
                dnsMode = DnsMode.UDP
            ),
            srv = AppPreferences.ServerOpts(wrapEnabled = true, wrapKey = wrapKey),
            carrierDns = "1.1.1.1:53"
        )

        assertTrue(args.windowed(2).any { it == listOf("-streams-per-cred", "4") })
        assertTrue(args.windowed(2).any { it == listOf("-dns-servers", "1.1.1.1:53") })
        assertTrue(args.windowed(2).any { it == listOf("-dns", DnsMode.UDP) })
        assertTrue(args.windowed(2).any { it == listOf("-wrap-key", wrapKey) })
    }
}
