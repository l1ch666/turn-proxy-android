package com.freeturn.app.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullTunnelUriValidatorTest {

    @Test
    fun vlessLoopbackUriIsValid() {
        val result = FullTunnelUriValidator.validate(
            "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000?security=tls"
        )

        assertTrue(result.isValid)
        assertEquals("vless", result.parsed?.scheme)
        assertEquals("127.0.0.1", result.parsed?.host)
        assertEquals(9000, result.parsed?.port)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun hysteria2LoopbackUriIsValid() {
        val result = FullTunnelUriValidator.validate(
            "hysteria2://secret-password@127.0.0.1:9000?sni=example.com"
        )

        assertTrue(result.isValid)
        assertEquals("hysteria2", result.parsed?.scheme)
        assertEquals("127.0.0.1", result.parsed?.host)
        assertEquals(9000, result.parsed?.port)
    }

    @Test
    fun emptyUriIsInvalidInFullTunnelMode() {
        val result = FullTunnelUriValidator.validate("")

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("empty", ignoreCase = true) })
    }

    @Test
    fun httpUriIsInvalid() {
        val result = FullTunnelUriValidator.validate("http://127.0.0.1:9000")

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Unsupported", ignoreCase = true) })
    }

    @Test
    fun remoteHostProducesWarning() {
        val result = FullTunnelUriValidator.validate(
            "hy2://secret@example.com:443?sni=example.com"
        )

        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("local", ignoreCase = true) })
    }

    @Test
    fun missingPortIsInvalid() {
        val result = FullTunnelUriValidator.validate(
            "vless://00000000-0000-0000-0000-000000000000@127.0.0.1"
        )

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("port", ignoreCase = true) })
    }

    @Test
    fun sanitizedUriDoesNotExposeCredentialsOrQuery() {
        val sanitized = FullTunnelUriValidator.sanitizeForLogs(
            "vless://00000000-0000-0000-0000-000000000000@127.0.0.1:9000?security=tls&pbk=secret#frag"
        )

        assertEquals("vless://***@127.0.0.1:9000", sanitized)
    }
}
