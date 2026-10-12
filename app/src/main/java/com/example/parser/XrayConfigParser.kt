package com.example.parser

import android.net.Uri
import android.util.Base64
import com.example.model.ProxyProtocol
import com.example.model.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

data class SubscriptionInfo(
    val title: String = "",
    val announcement: String = "",
    val updateIntervalHours: Int = 24,
    val servers: List<ProxyServer> = emptyList()
)

object XrayConfigParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun parse(rawInput: String): List<ProxyServer> {
        return parseSubscription(rawInput).servers
    }

    suspend fun fetchAndParseSubscription(url: String): SubscriptionInfo = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Happ/1.0.0 (Android; Xray 2.0); Incy/2.0.0; v2rayNG/1.8.5")
                .header("Accept", "text/plain, application/json, */*")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext SubscriptionInfo()
            }

            val body = response.body?.string() ?: ""
            var headerTitle = response.header("profile-title")
                ?: response.header("subscription-title")
                ?: ""

            if (headerTitle.isNotEmpty()) {
                headerTitle = decodeUrl(headerTitle)
            }

            val sub = parseSubscription(body)
            if (headerTitle.isNotEmpty() && sub.title.isEmpty()) {
                sub.copy(title = headerTitle)
            } else {
                sub
            }
        } catch (e: Exception) {
            SubscriptionInfo()
        }
    }

    fun parseSubscription(rawInput: String): SubscriptionInfo {
        var text = rawInput.trim()
        if (text.isEmpty()) return SubscriptionInfo()

        // 1. Check if raw JSON
        if (text.startsWith("{") && text.endsWith("}")) {
            val fromJson = parseJsonConfig(text)
            if (fromJson != null) {
                return SubscriptionInfo(servers = listOf(fromJson))
            }
        }

        // 2. Check if whole content is a single Base64 blob (common in subscriptions)
        if (!text.contains("\n") && !text.contains("://") && isBase64(text)) {
            val decoded = decodeBase64(text)
            if (decoded.contains("://") || decoded.contains("#profile") || decoded.contains("#announce")) {
                text = decoded.trim()
            }
        }

        var profileTitle = ""
        var announcement = ""
        var updateInterval = 24
        val results = mutableListOf<ProxyServer>()

        val lines = text.split("\n", "\r")
        for (line in lines) {
            val item = line.trim()
            if (item.isEmpty()) continue

            // Parse metadata headers from Happ, Incy, v2rayNG subscriptions
            val lower = item.lowercase()
            if (lower.startsWith("#profile-title:") || lower.startsWith("#profile-title=")) {
                profileTitle = extractHeaderValue(item)
                continue
            }
            if (lower.startsWith("#announce:") || lower.startsWith("#announce=") || lower.startsWith("#announcement:")) {
                announcement = extractHeaderValue(item)
                continue
            }
            if (lower.startsWith("#profile-update-interval:") || lower.startsWith("#profile-update-interval=")) {
                updateInterval = extractHeaderValue(item).toIntOrNull() ?: 24
                continue
            }

            // Ignore metadata comment lines
            if (item.startsWith("#") || item.startsWith("//")) continue

            val server = parseSingleLink(item)
            if (server != null) {
                results.add(server)
            }
        }

        return SubscriptionInfo(
            title = profileTitle,
            announcement = announcement,
            updateIntervalHours = updateInterval,
            servers = results
        )
    }

    private fun extractHeaderValue(line: String): String {
        val delimIdx = when {
            line.contains(":") -> line.indexOf(":")
            line.contains("=") -> line.indexOf("=")
            else -> -1
        }
        return if (delimIdx >= 0 && delimIdx < line.length - 1) {
            decodeUrl(line.substring(delimIdx + 1).trim())
        } else ""
    }

    fun parseSingleLink(link: String): ProxyServer? {
        val trimmed = link.trim()
        return try {
            when {
                trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("socks://", ignoreCase = true) || trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks(trimmed)
                trimmed.startsWith("hy2://", ignoreCase = true) || trimmed.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(trimmed)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVless(uriStr: String): ProxyServer? {
        val hashIdx = uriStr.indexOf('#')
        val fragmentStr = if (hashIdx >= 0) uriStr.substring(hashIdx + 1) else ""
        val remark = decodeUrl(fragmentStr).ifEmpty { "VLESS Node" }

        val uriPart = if (hashIdx >= 0) uriStr.substring(0, hashIdx) else uriStr
        val uri = Uri.parse(uriPart)

        val userInfo = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val security = uri.getQueryParameter("security") ?: "tls"
        val type = uri.getQueryParameter("type") ?: "tcp"
        val sniParam = uri.getQueryParameter("sni") ?: uri.getQueryParameter("peer")
        val sni = if (!sniParam.isNullOrEmpty()) sniParam else host

        val path = uri.getQueryParameter("path") ?: ""

        return ProxyServer(
            name = remark.ifEmpty { "VLESS-$host" },
            protocol = ProxyProtocol.VLESS,
            host = host,
            port = port,
            uuidOrPassword = userInfo,
            security = security,
            streamNetwork = type,
            path = path,
            sni = sni,
            rawConfig = uriStr,
            countryCode = detectCountry(remark, host),
            isCustom = true
        )
    }

    private fun parseHysteria2(uriStr: String): ProxyServer? {
        val hashIdx = uriStr.indexOf('#')
        val fragmentStr = if (hashIdx >= 0) uriStr.substring(hashIdx + 1) else ""
        val remark = decodeUrl(fragmentStr).ifEmpty { "Hysteria2 Node" }

        val uriPart = if (hashIdx >= 0) uriStr.substring(0, hashIdx) else uriStr
        val uri = Uri.parse(uriPart)

        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        val sniParam = uri.getQueryParameter("sni")
        val sni = if (!sniParam.isNullOrEmpty()) sniParam else host

        return ProxyServer(
            name = remark.ifEmpty { "Hysteria2-$host" },
            protocol = ProxyProtocol.HYSTERIA2,
            host = host,
            port = port,
            uuidOrPassword = uri.userInfo ?: "",
            security = "tls",
            streamNetwork = "udp",
            sni = sni,
            rawConfig = uriStr,
            countryCode = detectCountry(remark, host),
            isCustom = true
        )
    }

    private fun parseVmess(uriStr: String): ProxyServer? {
        val b64 = uriStr.substring(8).trim()
        val jsonStr = decodeBase64(b64)
        val json = JSONObject(jsonStr)

        val host = json.optString("add", "")
        val port = json.optInt("port", 443)
        val id = json.optString("id", "")
        val remark = decodeUrl(json.optString("ps", "VMess Node"))
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "none")
        val path = json.optString("path", "")
        val sni = json.optString("sni", host)

        if (host.isEmpty()) return null

        return ProxyServer(
            name = remark.ifEmpty { "VMess-$host" },
            protocol = ProxyProtocol.VMESS,
            host = host,
            port = port,
            uuidOrPassword = id,
            security = if (tls.isNotEmpty() && tls != "none") tls else "none",
            streamNetwork = net,
            path = path,
            sni = sni,
            rawConfig = uriStr,
            countryCode = detectCountry(remark, host),
            isCustom = true
        )
    }

    private fun parseTrojan(uriStr: String): ProxyServer? {
        val hashIdx = uriStr.indexOf('#')
        val fragmentStr = if (hashIdx >= 0) uriStr.substring(hashIdx + 1) else ""
        val remark = decodeUrl(fragmentStr).ifEmpty { "Trojan Node" }

        val uriPart = if (hashIdx >= 0) uriStr.substring(0, hashIdx) else uriStr
        val uri = Uri.parse(uriPart)

        val password = uri.userInfo ?: ""
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443

        val security = uri.getQueryParameter("security") ?: "tls"
        val type = uri.getQueryParameter("type") ?: "tcp"
        val sni = uri.getQueryParameter("sni") ?: host
        val path = uri.getQueryParameter("path") ?: ""

        return ProxyServer(
            name = remark.ifEmpty { "Trojan-$host" },
            protocol = ProxyProtocol.TROJAN,
            host = host,
            port = port,
            uuidOrPassword = password,
            security = security,
            streamNetwork = type,
            path = path,
            sni = sni,
            rawConfig = uriStr,
            countryCode = detectCountry(remark, host),
            isCustom = true
        )
    }

    private fun parseShadowsocks(uriStr: String): ProxyServer? {
        var body = uriStr.substring(5).trim()
        var fragment = "Shadowsocks Node"
        val hashIdx = body.indexOf('#')
        if (hashIdx >= 0) {
            fragment = decodeUrl(body.substring(hashIdx + 1))
            body = body.substring(0, hashIdx)
        }

        var host = ""
        var port = 8388
        var password = ""

        if (body.contains("@")) {
            val parts = body.split("@", limit = 2)
            val userInfo = if (isBase64(parts[0])) decodeBase64(parts[0]) else parts[0]
            password = userInfo
            val hostPort = parts[1].split(":", limit = 2)
            host = hostPort[0]
            if (hostPort.size > 1) port = hostPort[1].toIntOrNull() ?: 8388
        } else {
            val decoded = decodeBase64(body)
            if (decoded.contains("@")) {
                val parts = decoded.split("@", limit = 2)
                password = parts[0]
                val hostPort = parts[1].split(":", limit = 2)
                host = hostPort[0]
                if (hostPort.size > 1) port = hostPort[1].toIntOrNull() ?: 8388
            }
        }

        if (host.isEmpty()) return null

        return ProxyServer(
            name = fragment.ifEmpty { "SS-$host" },
            protocol = ProxyProtocol.SHADOWSOCKS,
            host = host,
            port = port,
            uuidOrPassword = password,
            security = "none",
            streamNetwork = "tcp",
            rawConfig = uriStr,
            countryCode = detectCountry(fragment, host),
            isCustom = true
        )
    }

    private fun parseSocks(uriStr: String): ProxyServer? {
        val hashIdx = uriStr.indexOf('#')
        val fragmentStr = if (hashIdx >= 0) uriStr.substring(hashIdx + 1) else ""
        val remark = decodeUrl(fragmentStr).ifEmpty { "Socks5 Node" }

        val uriPart = if (hashIdx >= 0) uriStr.substring(0, hashIdx) else uriStr
        val uri = Uri.parse(uriPart)

        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 1080

        return ProxyServer(
            name = remark,
            protocol = ProxyProtocol.SOCKS5,
            host = host,
            port = port,
            uuidOrPassword = uri.userInfo ?: "",
            security = "none",
            streamNetwork = "tcp",
            rawConfig = uriStr,
            countryCode = detectCountry(remark, host),
            isCustom = true
        )
    }

    private fun parseJsonConfig(jsonStr: String): ProxyServer? {
        return try {
            val root = JSONObject(jsonStr)
            val outbounds = root.optJSONArray("outbounds") ?: return null
            if (outbounds.length() == 0) return null

            val firstOutbound = outbounds.getJSONObject(0)
            val protocolStr = firstOutbound.optString("protocol", "vless")
            val protocol = ProxyProtocol.fromString(protocolStr)
            val tag = firstOutbound.optString("tag", "Xray Outbound")

            val settings = firstOutbound.optJSONObject("settings")
            var host = "127.0.0.1"
            var port = 443
            var uuid = ""

            if (settings != null) {
                val vnext = settings.optJSONArray("vnext")
                if (vnext != null && vnext.length() > 0) {
                    val serverObj = vnext.getJSONObject(0)
                    host = serverObj.optString("address", "127.0.0.1")
                    port = serverObj.optInt("port", 443)
                    val users = serverObj.optJSONArray("users")
                    if (users != null && users.length() > 0) {
                        uuid = users.getJSONObject(0).optString("id", "")
                    }
                } else {
                    val servers = settings.optJSONArray("servers")
                    if (servers != null && servers.length() > 0) {
                        val serverObj = servers.getJSONObject(0)
                        host = serverObj.optString("address", "127.0.0.1")
                        port = serverObj.optInt("port", 443)
                        uuid = serverObj.optString("password", "")
                    }
                }
            }

            val stream = firstOutbound.optJSONObject("streamSettings")
            val network = stream?.optString("network", "tcp") ?: "tcp"
            val security = stream?.optString("security", "none") ?: "none"

            ProxyServer(
                name = tag,
                protocol = protocol,
                host = host,
                port = port,
                uuidOrPassword = uuid,
                security = security,
                streamNetwork = network,
                rawConfig = jsonStr,
                countryCode = detectCountry(tag, host),
                isCustom = true
            )
        } catch (e: Exception) {
            null
        }
    }

    fun detectCountry(name: String, host: String): String {
        val upper = (name + " " + host).uppercase()
        return when {
            upper.contains("🇵🇱") || upper.contains("ПОЛЬША") || upper.contains("POLAND") || upper.contains("WARSAW") || upper.contains(".PL") -> "PL"
            upper.contains("🇹🇷") || upper.contains("ТУРЦИЯ") || upper.contains("TURKEY") || upper.contains("ISTANBUL") || upper.contains(".TR") -> "TR"
            upper.contains("🇩🇪") || upper.contains("ГЕРМАНИЯ") || upper.contains("GERMANY") || upper.contains("FRANKFURT") || upper.contains(".DE") -> "DE"
            upper.contains("🇱🇹") || upper.contains("ЛИТВА") || upper.contains("LITHUANIA") || upper.contains("VILNIUS") || upper.contains(".LT") -> "LT"
            upper.contains("🇳🇱") || upper.contains("НИДЕРЛАНДЫ") || upper.contains("NETHERLAND") || upper.contains("AMSTERDAM") || upper.contains(".NL") -> "NL"
            upper.contains("🇨🇭") || upper.contains("ШВЕЙЦАРИЯ") || upper.contains("SWITZERLAND") || upper.contains("ZURICH") || upper.contains(".CH") -> "CH"
            upper.contains("🇺🇸") || upper.contains("США") || upper.contains("АМЕРИКА") || upper.contains("USA") || upper.contains("NEW YORK") || upper.contains(".US") -> "US"
            upper.contains("🇫🇮") || upper.contains("ФИНЛЯНДИЯ") || upper.contains("FINLAND") || upper.contains("HELSINKI") || upper.contains(".FI") -> "FI"
            upper.contains("🇯🇵") || upper.contains("ЯПОНИЯ") || upper.contains("JAPAN") || upper.contains("TOKYO") || upper.contains(".JP") -> "JP"
            upper.contains("🇸🇬") || upper.contains("СИНГАПУР") || upper.contains("SINGAPORE") || upper.contains(".SG") -> "SG"
            upper.contains("🇬🇧") || upper.contains("ВЕЛИКОБРИТАНИЯ") || upper.contains("АНГЛИЯ") || upper.contains("UK") || upper.contains("LONDON") || upper.contains(".UK") -> "GB"
            upper.contains("🇫🇷") || upper.contains("ФРАНЦИЯ") || upper.contains("FRANCE") || upper.contains("PARIS") || upper.contains(".FR") -> "FR"
            upper.contains("🇸🇪") || upper.contains("ШВЕЦИЯ") || upper.contains("SWEDEN") || upper.contains(".SE") -> "SE"
            upper.contains("🇷🇺") || upper.contains("РОССИЯ") || upper.contains("RUSSIA") || upper.contains("MOSCOW") || upper.contains(".RU") -> "RU"
            upper.contains("🇺🇦") || upper.contains("УКРАИНА") || upper.contains("UKRAINE") || upper.contains("KIEV") || upper.contains(".UA") -> "UA"
            upper.contains("🇰🇿") || upper.contains("КАЗАХСТАН") || upper.contains("KAZAKHSTAN") || upper.contains("ALMATY") || upper.contains(".KZ") -> "KZ"
            else -> "UN"
        }
    }

    private fun decodeUrl(str: String): String {
        return try {
            if (str.contains("%")) {
                URLDecoder.decode(str, StandardCharsets.UTF_8.name())
            } else {
                str
            }
        } catch (_: Exception) {
            str
        }
    }

    private fun decodeBase64(str: String): String {
        return try {
            val cleaned = str.replace('-', '+').replace('_', '/').replace("\n", "").replace("\r", "")
            val padded = when (cleaned.length % 4) {
                2 -> "$cleaned=="
                3 -> "$cleaned="
                else -> cleaned
            }
            val bytes = Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun isBase64(str: String): Boolean {
        return try {
            val cleaned = str.trim()
            if (cleaned.length < 8) return false
            Base64.decode(cleaned, Base64.DEFAULT)
            true
        } catch (_: Exception) {
            false
        }
    }
}
