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
                    // URI host:port is a remote VPS on purpose: the outbound must
                    // still target the LOCAL core, not the VPS (TURN-bypass guard).
                    clientUri = "vless://00000000-0000-0000-0000-000000000000@vps.example.com:8443?security=tls&sni=example.com&type=ws&path=/ray&host=cdn.example.com",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000,
                    tunMtu = 1280
                )
            )
        )

        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", inbound.getString("type"))
        assertEquals(1280, inbound.getInt("mtu"))
        assertEquals("172.19.0.1/30", inbound.getJSONArray("address").getString(0))
        assertEquals("0.0.0.0/2", inbound.getJSONArray("route_address").getString(0))
        assertEquals(false, inbound.has("route_exclude_address"))
        assertEquals(false, inbound.has("inet4_address"))
        assertEquals(false, inbound.has("inet6_address"))
        assertEquals(false, inbound.has("inet4_route_address"))
        assertEquals(false, inbound.has("inet6_route_address"))
        assertEquals("vless", outbound.getString("type"))
        assertEquals("proxy", outbound.getString("tag"))
        // Must point at the LOCAL core, NOT the URI host — otherwise traffic
        // bypasses the TURN tunnel and goes straight to the VPS.
        assertEquals("127.0.0.1", outbound.getString("server"))
        assertEquals(9000, outbound.getInt("server_port"))
        assertEquals("example.com", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
        assertEquals("/ray", outbound.getJSONObject("transport").getString("path"))
    }

    @Test
    fun tunConfigAvoidsIpv6AddressesOnAndroid() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        val addresses = inbound.getJSONArray("address")
        val routes = inbound.getJSONArray("route_address")

        assertEquals(1, addresses.length())
        assertEquals("172.19.0.1/30", addresses.getString(0))
        assertEquals(false, (0 until routes.length()).any { routes.getString(it).contains(":") })
        assertEquals(false, inbound.has("route_exclude_address"))
    }

    @Test
    fun tunConfigAvoidsLoopbackWithoutAndroidExcludeRoute() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val inbound = json.getJSONArray("inbounds").getJSONObject(0)
        val routes = inbound.getJSONArray("route_address")
        val routeValues = (0 until routes.length()).map { routes.getString(it) }

        assertEquals(false, inbound.has("route_exclude_address"))
        assertEquals(true, routeValues.contains("126.0.0.0/8"))
        assertEquals(true, routeValues.contains("128.0.0.0/1"))
        assertEquals(false, routeValues.any { it == "0.0.0.0/1" || it.startsWith("127.") })
    }

    @Test
    fun configTagsRouteDnsThroughProxyOutbound() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000?type=tcp&encryption=none",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        val dnsServer = json.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        val route = json.getJSONObject("route")

        assertEquals("proxy", outbound.getString("tag"))
        assertEquals("proxy", dnsServer.getString("detour"))
        assertEquals("proxy", route.getString("final"))
        assertEquals(false, outbound.has("transport"))
    }

    @Test
    fun hysteria2UriBuildsHysteria2Outbound() {
        val json = JSONObject(
            SingBoxConfigFactory.build(
                FullTunnelConfig(
                    clientUri = "hy2://secret@vps.example.com:8443?sni=example.com&obfs=salamander&obfs-password=mask",
                    localProxyHost = "127.0.0.1",
                    localProxyPort = 9000
                )
            )
        )

        val outbound = json.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("hysteria2", outbound.getString("type"))
        // Local core, not the VPS URI host.
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
