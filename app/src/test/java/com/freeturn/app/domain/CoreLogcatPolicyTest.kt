package com.freeturn.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogcatPolicyTest {
    @Test
    fun dropsUnrelatedCoreLine() {
        assertNull(CoreLogcatPolicy.sanitizeForLogcat("[STREAM 1] Established DTLS connection"))
    }

    @Test
    fun keepsCaptchaStatusLine() {
        assertEquals(
            "[STREAM 1] [Captcha] Solving captcha...",
            CoreLogcatPolicy.sanitizeForLogcat("[STREAM 1] [Captcha] Solving captcha...")
        )
    }

    @Test
    fun masksCaptchaUrisAndTokens() {
        val sanitized = CoreLogcatPolicy.sanitizeForLogcat(
            "[Captcha Proxy] ERROR for GET https://id.vk.ru/captcha?session_token=secret&captcha_sid=123"
        )!!

        assertTrue(sanitized.contains("https://***@id.vk.ru"))
        assertTrue(!sanitized.contains("secret"))
        assertTrue(!sanitized.contains("123"))
    }
}
