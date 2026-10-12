package com.example.model

enum class ProxyProtocol(val displayName: String, val badgeColor: Long) {
    VLESS("VLESS", 0xFF00B4D8),
    VMESS("VMess", 0xFF7209B7),
    TROJAN("Trojan", 0xFFF72585),
    SHADOWSOCKS("SS", 0xFF4361EE),
    SOCKS5("SOCKS5", 0xFF4CC9F0),
    HTTP("HTTP", 0xFF2A9D8F),
    HYSTERIA2("Hy2", 0xFFE76F51),
    RAW_JSON("Xray JSON", 0xFFE9C46A);

    companion object {
        fun fromString(str: String): ProxyProtocol {
            return when (str.lowercase().trim()) {
                "vless" -> VLESS
                "vmess" -> VMESS
                "trojan" -> TROJAN
                "ss", "shadowsocks" -> SHADOWSOCKS
                "socks", "socks5" -> SOCKS5
                "http" -> HTTP
                "hy2", "hysteria2" -> HYSTERIA2
                else -> RAW_JSON
            }
        }
    }
}

enum class PingType(val displayName: String, val description: String) {
    TCP_HANDSHAKE("TCP Handshake", "Прямое подключение к порту прокси-сервера"),
    HTTP_204_GOOGLE("HTTP 204 (Google)", "Проверка сквозного веб-доступа через Google"),
    HTTP_204_CLOUDFLARE("HTTP 204 (Cloudflare)", "Проверка задержки через сеть Cloudflare"),
    ICMP_PING("ICMP Ping", "Стандартная эхо-проверка сетевого узла")
}

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"),
    LIGHT("Светлая"),
    DARK("Тёмная")
}

sealed class VpnState {
    object Disconnected : VpnState()
    object Connecting : VpnState()
    data class Connected(val serverName: String, val protocol: ProxyProtocol, val pingMs: Long) : VpnState()
    data class Reconnecting(val reason: String) : VpnState()
    data class Error(val message: String) : VpnState()
}

data class TrafficStats(
    val uploadSpeedBps: Long = 0,
    val downloadSpeedBps: Long = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val connectedDurationSeconds: Long = 0
) {
    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_048_576 -> String.format("%.2f МБ/с", bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1024 -> String.format("%.1f КБ/с", bytesPerSec / 1024.0)
            else -> "$bytesPerSec Б/с"
        }
    }

    fun formatTotal(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.2f ГБ", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f МБ", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f КБ", bytes / 1024.0)
            else -> "$bytes Б"
        }
    }

    fun formatDuration(): String {
        val hours = connectedDurationSeconds / 3600
        val minutes = (connectedDurationSeconds % 3600) / 60
        val seconds = connectedDurationSeconds % 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}
