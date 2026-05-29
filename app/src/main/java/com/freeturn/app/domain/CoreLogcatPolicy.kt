package com.freeturn.app.domain

object CoreLogcatPolicy {
    private val captchaMarkers = listOf(
        "Captcha",
        "[VK Auth]",
        "FATAL_CAPTCHA",
        "CAPTCHA_WAIT_REQUIRED",
        "captcha error data"
    )

    private val secretQueryRegex =
        Regex("""(?i)(session_token|success_token|access_token|captcha_key|captcha_sid|auth|password|obfs-password|wrap-key)=([^&\s]+)""")
    private val uriRegex = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")

    fun sanitizeForLogcat(line: String): String? {
        if (captchaMarkers.none { line.contains(it, ignoreCase = true) }) return null
        return uriRegex
            .replace(line) { sanitizeUri(it.value) }
            .replace(secretQueryRegex) { "${it.groupValues[1]}=***" }
            .take(800)
    }

    private fun sanitizeUri(raw: String): String {
        val scheme = raw.substringBefore("://", "unknown").ifBlank { "unknown" }
        val hostPort = raw
            .substringAfter("://", "")
            .substringAfter('@')
            .substringBefore('/')
            .substringBefore('?')
            .ifBlank { "unknown" }
        return "$scheme://***@$hostPort"
    }
}
