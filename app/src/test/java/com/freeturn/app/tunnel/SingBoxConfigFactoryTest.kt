package com.freeturn.app.tunnel

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigFactoryTest {

    @Test
    fun vlessUriBuildsTunConfigAndVlessOutbound() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000?security=tls&sni=example.com&type=ws&path=/ray&host=cdn.example.com",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", inbound.getString("type"))
        assertEquals("vless", outbound.getString("type"))
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertEquals(9000, outbound.getInt("server_port"))
        assertEquals("example.com", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
        assertEquals("/ray", outbound.getJSONObject("transport").getString("path"))
    }

    @Test
    fun hysteria2UriBuildsHysteria2Outbound() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "hy2://secret@127.0.0.1:9000?sni=example.com&obfs=salamander&obfs-password=mask",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val outbound = json.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("hysteria2", outbound.getString("type"))
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertEquals(9000, outbound.getInt("server_port"))
        assertEquals("secret", outbound.getString("password"))
        assertEquals("salamander", outbound.getJSONObject("obfs").getString("type"))
        assertEquals("example.com", outbound.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun routeUsesProxyAndAvoidsAndroidVpnAsUpstream() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "hysteria://secret@127.0.0.1:9000?peer=example.com",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val route = json.getJSONObject("route")

        assertEquals("proxy", route.getString("final"))
        assertTrue(route.getBoolean("auto_detect_interface"))
        assertEquals(false, route.getBoolean("override_android_vpn"))
    }
}
