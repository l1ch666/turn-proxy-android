package com.freeturn.app.tunnel

import java.net.URI

data class FullTunnelParsedUri(
    val scheme: String,
    val host: String,
    val port: Int
)

data class FullTunnelUriValidationResult(
    val parsed: FullTunnelParsedUri?,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isValid: Boolean get() = errors.isEmpty()
}

data class HostPort(val host: String, val port: Int)

object FullTunnelUriValidator {
    val acceptedSchemes: Set<String> = setOf("vless", "hysteria", "hysteria2", "hy2")

    fun validate(raw: String): FullTunnelUriValidationResult {
        val value = raw.trim()
        if (value.isEmpty()) {
            return FullTunnelUriValidationResult(
                parsed = null,
                errors = listOf("Full tunnel client URI is empty")
            )
        }

        val uri = try {
            URI(value)
        } catch (e: Exception) {
            return FullTunnelUriValidationResult(
                parsed = null,
                errors = listOf("Invalid full tunnel client URI")
            )
        }

        val scheme = uri.scheme?.lowercase().orEmpty()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (scheme !in acceptedSchemes) {
            errors += "Unsupported full tunnel URI scheme: ${scheme.ifBlank { "missing" }}"
        }

        val host = uri.host.orEmpty()
        if (host.isBlank()) errors += "Full tunnel URI host is missing"

        val port = uri.port
        if (port <= 0 || port > 65_535) errors += "Full tunnel URI port is missing or invalid"

        if (host.isNotBlank() && !isClearlyLocalHost(host)) {
            warnings += "Full tunnel URI host is not clearly local; expected 127.0.0.1, localhost, ::1, or another loopback address"
        }

        val parsed = if (host.isNotBlank() && port in 1..65_535 && scheme.isNotBlank()) {
            FullTunnelParsedUri(scheme, host, port)
        } else {
            null
        }
        return FullTunnelUriValidationResult(parsed = parsed, errors = errors, warnings = warnings)
    }

    fun sanitizeForLogs(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase().orEmpty().ifBlank { "unknown" }
            val host = uri.host?.let(::formatHostForUri).orEmpty().ifBlank { "unknown" }
            val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            "$scheme://***@$host$port"
        } catch (_: Exception) {
            val scheme = value.substringBefore("://", "unknown").ifBlank { "unknown" }
            "$scheme://***"
        }
    }

    fun sanitizeMessage(message: String): String =
        Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""").replace(message) { match ->
            sanitizeForLogs(match.value)
        }

    fun parseHostPort(raw: String): HostPort? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val host: String
        val portText: String
        if (value.startsWith("[")) {
            val end = value.indexOf(']')
            if (end <= 1 || value.getOrNull(end + 1) != ':') return null
            host = value.substring(1, end)
            portText = value.substring(end + 2)
        } else {
            val colon = value.lastIndexOf(':')
            if (colon <= 0 || colon == value.lastIndex) return null
            host = value.substring(0, colon)
            portText = value.substring(colon + 1)
        }
        val port = portText.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65_535) return null
        return HostPort(host, port)
    }

    private fun isClearlyLocalHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized == "0.0.0.0" ||
            normalized.startsWith("127.")
    }

    private fun formatHostForUri(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
}
