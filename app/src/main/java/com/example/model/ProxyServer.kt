package com.example.model

data class ProxyServer(
    val id: Long = 0,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val uuidOrPassword: String = "",
    val security: String = "tls", // none, tls, reality
    val streamNetwork: String = "tcp", // tcp, ws, grpc, xhttp
    val path: String = "",
    val sni: String = "",
    val rawConfig: String = "",
    val countryCode: String = "UN", // DE, US, NL, JP, FI, etc.
    val pingMs: Long = -1, // -1 means untested, -2 timeout/error
    val isAutoSelected: Boolean = false,
    val isCustom: Boolean = false,
    val lastTestedTime: Long = 0
) {
    val pingStatus: PingStatus
        get() = when {
            pingMs < 0 && pingMs != -2L -> PingStatus.UNTESTED
            pingMs == -2L -> PingStatus.TIMEOUT
            pingMs < 100 -> PingStatus.EXCELLENT
            pingMs < 300 -> PingStatus.GOOD
            pingMs < 600 -> PingStatus.FAIR
            else -> PingStatus.POOR
        }

    val displayHostPort: String
        get() = "$host:$port"

    val countryFlag: String
        get() = countryCodeToEmoji(countryCode)

    companion object {
        fun countryCodeToEmoji(code: String): String {
            if (code.length != 2) return "🌐"
            val firstChar = Character.codePointAt(code.uppercase(), 0) - 0x41 + 0x1F1E6
            val secondChar = Character.codePointAt(code.uppercase(), 1) - 0x41 + 0x1F1E6
            return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
        }
    }
}

enum class PingStatus(val label: String, val colorHex: Long) {
    EXCELLENT("Отлично", 0xFF00E676),
    GOOD("Хорошо", 0xFF69F0AE),
    FAIR("Нормально", 0xFFFFD740),
    POOR("Высокий пинг", 0xFFFF5252),
    TIMEOUT("Таймаут", 0xFF9E9E9E),
    UNTESTED("Не проверен", 0xFF757575)
}
