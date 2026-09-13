package com.nexus.mobile

object PrivacyRedactor {
    private val cardLike = Regex("""(?<!\d)(?:\d[ -]?){13,19}(?!\d)""")
    private val otpLike = Regex("""(?<!\d)\d{4,8}(?!\d)""")
    private val sensitiveWords = Regex(
        """(?i)\b(password|passcode|pin|otp|verification code|security code|cvv|card number|payment)\b|密码|验证码|支付|卡号""",
    )

    fun sanitize(
        value: String?,
        passwordField: Boolean = false,
        sensitiveContext: Boolean = false,
    ): String {
        val text = value.orEmpty().trim().take(160)
        if (text.isEmpty()) return ""
        if (passwordField) return "[REDACTED]"
        if (cardLike.containsMatchIn(text)) return cardLike.replace(text, "[REDACTED]")
        if (sensitiveContext && otpLike.containsMatchIn(text)) return otpLike.replace(text, "[REDACTED]")
        return text
    }

    fun indicatesSensitiveContext(value: String?): Boolean =
        sensitiveWords.containsMatchIn(value.orEmpty())
}
