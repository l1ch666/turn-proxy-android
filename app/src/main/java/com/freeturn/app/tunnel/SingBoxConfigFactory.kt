package com.freeturn.app.tunnel

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

object SingBoxConfigFactory {
    val supportedSchemes: Set<String> = setOf("vless", "hysteria", "hysteria2", "hy2")

    fun build(config: FullTunnelConfig): String {
        val validation = FullTunnelUriValidator.validate(config.clientUri)
        require(validation.isValid) {
            validation.errors.joinToString("; ")
        }

        val uri = URI(config.clientUri.trim())
        val scheme = uri.scheme.orEmpty().lowercase()
        require(scheme in supportedSchemes) {
            "Unsupported full tunnel URI scheme: $scheme"
        }

        val root = JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
            .put("dns", dnsConfig())
            .put("inbounds", JSONArray().put(tunInbound()))
            .put(
                "outbounds",
                JSONArray()
                    .put(outboundFor(uri))
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
            )
            .put("route", routeConfig())

        return root.toString()
    }

    private fun tunInbound(): JSONObject =
        JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("interface_name", "freeturn0")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put(
                "route_address",
                JSONArray()
                    .put("0.0.0.0/2")
                    .put("64.0.0.0/3")
                    .put("96.0.0.0/4")
                    .put("112.0.0.0/5")
                    .put("120.0.0.0/6")
                    .put("124.0.0.0/7")
                    .put("126.0.0.0/8")
                    .put("128.0.0.0/1")
            )
            .put("mtu", 1400)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "system")
            .put("sniff", true)

    private fun dnsConfig(): JSONObject =
        JSONObject()
            .put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "remote-dns")
                        .put("address", "https://1.1.1.1/dns-query")
                        .put("detour", "proxy")
                )
            )
            .put("final", "remote-dns")
            .put("strategy", "ipv4_only")

    private fun routeConfig(): JSONObject =
        JSONObject()
            .put(
                "rules",
                JSONArray().put(
                    JSONObject()
                        .put("protocol", "dns")
                        .put("action", "hijack-dns")
                )
            )
            .put("auto_detect_interface", true)
            .put("override_android_vpn", false)
            .put("final", "proxy")

    private fun outboundFor(uri: URI): JSONObject {
        val scheme = uri.scheme.orEmpty().lowercase()
        return when (scheme) {
            "vless" -> vlessOutbound(uri)
            "hysteria2", "hy2" -> hysteria2Outbound(uri)
            "hysteria" -> hysteriaOutbound(uri)
            else -> error("Unsupported full tunnel URI scheme: $scheme")
        }
    }

    private fun vlessOutbound(uri: URI): JSONObject {
        val params = uri.queryParams()
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("uuid", uri.decodedUserInfo().substringBefore(':'))

        params.firstValue("flow")?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        params.firstValue("packetencoding", "packet_encoding")?.takeIf { it.isNotBlank() }?.let {
            outbound.put("packet_encoding", it)
        }

        val network = params.firstValue("network")?.lowercase()
        if (network == "tcp" || network == "udp") outbound.put("network", network)

        val security = params.firstValue("security")?.lowercase().orEmpty()
        if (security == "tls" || security == "reality" || params.hasAny("sni", "peer")) {
            outbound.put("tls", tlsConfig(params, security))
        }

        transportConfig(params)?.let { outbound.put("transport", it) }

        return outbound
    }

    private fun hysteria2Outbound(uri: URI): JSONObject {
        val params = uri.queryParams()
        val outbound = JSONObject()
            .put("type", "hysteria2")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("password", params.firstValue("password", "auth") ?: uri.decodedUserInfo())
            .put("tls", tlsConfig(params, "tls", forceEnabled = true))

        params.firstInt("upmbps", "up_mbps")?.let { outbound.put("up_mbps", it) }
        params.firstInt("downmbps", "down_mbps")?.let { outbound.put("down_mbps", it) }
        params.firstValue("network")?.takeIf { it == "tcp" || it == "udp" }?.let {
            outbound.put("network", it)
        }
        hysteria2Obfs(params)?.let { outbound.put("obfs", it) }

        return outbound
    }

    private fun hysteriaOutbound(uri: URI): JSONObject {
        val params = uri.queryParams()
        val outbound = JSONObject()
            .put("type", "hysteria")
            .put("tag", "proxy")
            .put("server", uri.host)
            .put("server_port", uri.port)
            .put("up_mbps", params.firstInt("upmbps", "up_mbps") ?: 100)
            .put("down_mbps", params.firstInt("downmbps", "down_mbps") ?: 100)
            .put("tls", tlsConfig(params, "tls", forceEnabled = true))

        val auth = params.firstValue("auth")
        if (auth != null) {
            outbound.put("auth", auth)
        } else {
            outbound.put("auth_str", params.firstValue("auth_str") ?: uri.decodedUserInfo())
        }

        params.firstValue("obfs")?.takeIf { it.isNotBlank() }?.let { outbound.put("obfs", it) }
        params.firstValue("network")?.takeIf { it == "tcp" || it == "udp" }?.let {
            outbound.put("network", it)
        }

        return outbound
    }

    private fun tlsConfig(
        params: Map<String, List<String>>,
        security: String,
        forceEnabled: Boolean = false
    ): JSONObject {
        val tls = JSONObject()
            .put("enabled", forceEnabled || security == "tls" || security == "reality" || params.hasAny("sni", "peer"))

        params.firstValue("sni", "peer", "servername", "server_name")?.takeIf { it.isNotBlank() }?.let {
            tls.put("server_name", it)
        }
        if (params.firstBool("insecure", "allowinsecure", "allow_insecure", "skip-cert-verify")) {
            tls.put("insecure", true)
        }
        params.firstValue("alpn")?.takeIf { it.isNotBlank() }?.let { value ->
            tls.put("alpn", JSONArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
        }
        params.firstValue("fp", "fingerprint")?.takeIf { it.isNotBlank() }?.let { fp ->
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
        }
        if (security == "reality") {
            val reality = JSONObject().put("enabled", true)
            params.firstValue("pbk", "publickey", "public_key")?.takeIf { it.isNotBlank() }?.let {
                reality.put("public_key", it)
            }
            params.firstValue("sid", "shortid", "short_id")?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
        }
        return tls
    }

    private fun transportConfig(params: Map<String, List<String>>): JSONObject? {
        val type = params.firstValue("type", "transport")?.lowercase() ?: return null
        return when (type) {
            "ws", "websocket" -> {
                val transport = JSONObject().put("type", "ws")
                params.firstValue("path")?.let { transport.put("path", it) }
                params.firstValue("host")?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                transport
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                params.firstValue("serviceName", "servicename", "service_name")?.let {
                    transport.put("service_name", it)
                }
                transport
            }
            "http", "h2" -> {
                val transport = JSONObject().put("type", "http")
                params.firstValue("path")?.let { transport.put("path", it) }
                params.firstValue("host")?.let { transport.put("host", JSONArray().put(it)) }
                transport
            }
            "httpupgrade", "http-upgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                params.firstValue("path")?.let { transport.put("path", it) }
                params.firstValue("host")?.let { transport.put("host", it) }
                transport
            }
            "tcp" -> null
            else -> error("Unsupported VLESS transport type: $type")
        }
    }

    private fun hysteria2Obfs(params: Map<String, List<String>>): JSONObject? {
        val type = params.firstValue("obfs")?.takeIf { it.isNotBlank() } ?: return null
        val password = params.firstValue("obfs-password", "obfs_password", "obfspassword").orEmpty()
        return JSONObject()
            .put("type", if (type == "salamander") type else "salamander")
            .put("password", password)
    }

    private fun URI.queryParams(): Map<String, List<String>> {
        val query = rawQuery ?: return emptyMap()
        return query.split('&')
            .filter { it.isNotBlank() }
            .map { item ->
                val key = item.substringBefore('=')
                val value = item.substringAfter('=', "")
                percentDecode(key).lowercase() to percentDecode(value)
            }
            .groupBy({ it.first }, { it.second })
    }

    private fun URI.decodedUserInfo(): String =
        rawUserInfo?.let(::percentDecode).orEmpty()

    private fun Map<String, List<String>>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> this[key.lowercase()]?.firstOrNull() }

    private fun Map<String, List<String>>.firstInt(vararg keys: String): Int? =
        firstValue(*keys)?.toIntOrNull()

    private fun Map<String, List<String>>.firstBool(vararg keys: String): Boolean =
        firstValue(*keys)?.lowercase() in setOf("1", "true", "yes", "y")

    private fun Map<String, List<String>>.hasAny(vararg keys: String): Boolean =
        keys.any { containsKey(it.lowercase()) }

    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    out.append(hex.toChar())
                    index += 3
                    continue
                }
            }
            out.append(ch)
            index += 1
        }
        return out.toString()
    }
}
