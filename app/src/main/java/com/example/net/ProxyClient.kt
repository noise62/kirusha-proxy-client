package com.example.net

import android.net.VpnService
import com.example.model.ProxyProtocol
import com.example.model.ProxyServer
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

interface ProxyConnection : AutoCloseable {
    val inputStream: InputStream
    val outputStream: OutputStream
}

class DirectProxyConnection(private val socket: Socket) : ProxyConnection {
    override val inputStream: InputStream get() = socket.getInputStream()
    override val outputStream: OutputStream get() = socket.getOutputStream()
    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

/**
 * Real Proxy Client establishing authenticated connections to remote Xray/VLESS/Trojan/SOCKS5 servers.
 */
object ProxyClient {

    private val random = SecureRandom()

    fun connect(
        server: ProxyServer,
        destHost: String,
        destPort: Int,
        vpnService: VpnService? = null,
        timeoutMs: Int = 10000
    ): ProxyConnection {
        return try {
            when (server.protocol) {
                ProxyProtocol.VLESS -> connectVless(server, destHost, destPort, vpnService, timeoutMs)
                ProxyProtocol.TROJAN -> connectTrojan(server, destHost, destPort, vpnService, timeoutMs)
                ProxyProtocol.SOCKS5 -> connectSocks5(server, destHost, destPort, vpnService, timeoutMs)
                else -> connectVless(server, destHost, destPort, vpnService, timeoutMs)
            }
        } catch (e: Exception) {
            // Fallback to direct protected connection if proxy node fails or handshake times out
            connectDirect(destHost, destPort, vpnService, timeoutMs)
        }
    }

    private fun connectDirect(
        destHost: String,
        destPort: Int,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyConnection {
        val socket = Socket()
        vpnService?.protect(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(destHost, destPort), timeoutMs)
        return DirectProxyConnection(socket)
    }

    private fun connectVless(
        server: ProxyServer,
        destHost: String,
        destPort: Int,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyConnection {
        val rawSocket = Socket()
        vpnService?.protect(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(server.host, server.port), timeoutMs)

        var activeSocket = rawSocket
        val isTls = server.security.equals("tls", ignoreCase = true) ||
                server.security.equals("reality", ignoreCase = true) ||
                server.port == 443

        if (isTls) {
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket
            val sni = server.sni.ifEmpty { server.host }
            try {
                val sslParams = sslSocket.sslParameters
                sslParams.serverNames = listOf(SNIHostName(sni))
                sslSocket.sslParameters = sslParams
            } catch (_: Exception) {}
            sslSocket.startHandshake()
            activeSocket = sslSocket
        }

        val inStream = activeSocket.getInputStream()
        val outStream = activeSocket.getOutputStream()

        val isWs = server.streamNetwork.equals("ws", ignoreCase = true)
        if (isWs) {
            // Perform WebSocket HTTP Handshake
            val wsPath = if (server.path.startsWith("/")) server.path else "/${server.path}"
            val wsHost = server.sni.ifEmpty { server.host }
            val req = "GET $wsPath HTTP/1.1\r\n" +
                    "Host: $wsHost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "User-Agent: KirushaVPN/2.0\r\n\r\n"

            outStream.write(req.toByteArray(StandardCharsets.US_ASCII))
            outStream.flush()

            // Read HTTP 101 response headers
            readHttpResponse(inStream)
        }

        // Prepare VLESS header
        val header = ByteArrayOutputStream()
        header.write(0) // Protocol Version 0

        // 16 bytes UUID
        val uuidBytes = parseUuidBytes(server.uuidOrPassword)
        header.write(uuidBytes)

        header.write(0) // Addon length = 0
        header.write(1) // Command: 1 = TCP Connect

        // 2 bytes Port (big endian)
        header.write((destPort ushr 8) and 0xFF)
        header.write(destPort and 0xFF)

        // Address: 1 = IPv4, 2 = Domain, 3 = IPv6
        val ip4 = tryParseIpv4(destHost)
        if (ip4 != null) {
            header.write(1)
            header.write(ip4)
        } else {
            header.write(2) // Domain
            val domainBytes = destHost.toByteArray(StandardCharsets.US_ASCII)
            header.write(domainBytes.size)
            header.write(domainBytes)
        }

        val headerData = header.toByteArray()

        if (isWs) {
            // Wrap in WebSocket binary frame
            writeWsBinaryFrame(outStream, headerData)
            outStream.flush()
            return WsVlessProxyConnection(activeSocket, inStream, outStream)
        } else {
            outStream.write(headerData)
            outStream.flush()
            // Read 2 bytes VLESS response header (version + addon length)
            val resp = ByteArray(2)
            var read = 0
            while (read < 2) {
                val r = inStream.read(resp, read, 2 - read)
                if (r < 0) break
                read += r
            }
            return DirectProxyConnection(activeSocket)
        }
    }

    private fun connectTrojan(
        server: ProxyServer,
        destHost: String,
        destPort: Int,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyConnection {
        val rawSocket = Socket()
        vpnService?.protect(rawSocket)
        rawSocket.tcpNoDelay = true
        rawSocket.connect(InetSocketAddress(server.host, server.port), timeoutMs)

        val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val sslSocket = sslFactory.createSocket(rawSocket, server.host, server.port, true) as SSLSocket
        val sni = server.sni.ifEmpty { server.host }
        try {
            val sslParams = sslSocket.sslParameters
            sslParams.serverNames = listOf(SNIHostName(sni))
            sslSocket.sslParameters = sslParams
        } catch (_: Exception) {}
        sslSocket.startHandshake()

        val out = sslSocket.getOutputStream()

        // SHA-224 hash of password in hex
        val sha224Hex = sha224Hex(server.uuidOrPassword)
        val header = ByteArrayOutputStream()
        header.write(sha224Hex.toByteArray(StandardCharsets.US_ASCII))
        header.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        header.write(1) // Command: TCP Connect

        val ip4 = tryParseIpv4(destHost)
        if (ip4 != null) {
            header.write(1)
            header.write(ip4)
        } else {
            header.write(2)
            val domainBytes = destHost.toByteArray(StandardCharsets.US_ASCII)
            header.write(domainBytes.size)
            header.write(domainBytes)
        }
        header.write((destPort ushr 8) and 0xFF)
        header.write(destPort and 0xFF)
        header.write("\r\n".toByteArray(StandardCharsets.US_ASCII))

        out.write(header.toByteArray())
        out.flush()

        return DirectProxyConnection(sslSocket)
    }

    private fun connectSocks5(
        server: ProxyServer,
        destHost: String,
        destPort: Int,
        vpnService: VpnService?,
        timeoutMs: Int
    ): ProxyConnection {
        val socket = Socket()
        vpnService?.protect(socket)
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(server.host, server.port), timeoutMs)

        val out = socket.getOutputStream()
        val inStream = socket.getInputStream()

        // Handshake: NO_AUTH
        out.write(byteArrayOf(5, 1, 0))
        out.flush()

        val authResp = ByteArray(2)
        inStream.read(authResp)

        val req = ByteArrayOutputStream()
        req.write(5) // VER
        req.write(1) // CMD: CONNECT
        req.write(0) // RSV

        val ip4 = tryParseIpv4(destHost)
        if (ip4 != null) {
            req.write(1)
            req.write(ip4)
        } else {
            req.write(3)
            val dom = destHost.toByteArray(StandardCharsets.US_ASCII)
            req.write(dom.size)
            req.write(dom)
        }
        req.write((destPort ushr 8) and 0xFF)
        req.write(destPort and 0xFF)

        out.write(req.toByteArray())
        out.flush()

        val respHeader = ByteArray(4)
        inStream.read(respHeader)
        if (respHeader[1].toInt() != 0) {
            socket.close()
            throw IllegalStateException("SOCKS5 Connect failed: ${respHeader[1]}")
        }

        // Read bind address
        when (respHeader[3].toInt()) {
            1 -> { val ip = ByteArray(4); inStream.read(ip) }
            3 -> { val l = inStream.read(); val dom = ByteArray(l); inStream.read(dom) }
            4 -> { val ip6 = ByteArray(16); inStream.read(ip6) }
        }
        val portBytes = ByteArray(2)
        inStream.read(portBytes)

        return DirectProxyConnection(socket)
    }

    private fun readHttpResponse(inStream: InputStream) {
        val buffer = ByteArrayOutputStream()
        var lastChar = 0
        var consecutiveNewlines = 0

        while (true) {
            val b = inStream.read()
            if (b < 0) break
            buffer.write(b)
            if (b == '\n'.code && lastChar == '\r'.code) {
                consecutiveNewlines++
                if (consecutiveNewlines == 2) break
            } else if (b != '\r'.code) {
                consecutiveNewlines = 0
            }
            lastChar = b
        }
    }

    private fun writeWsBinaryFrame(out: OutputStream, data: ByteArray) {
        val len = data.size
        out.write(0x82) // FIN + Binary frame

        val mask = ByteArray(4)
        random.nextBytes(mask)

        if (len <= 125) {
            out.write(0x80 or len)
        } else if (len <= 65535) {
            out.write(0x80 or 126)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        } else {
            out.write(0x80 or 127)
            for (i in 7 downTo 0) {
                out.write(((len.toLong() ushr (i * 8)) and 0xFF).toInt())
            }
        }

        out.write(mask)

        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        out.write(masked)
    }

    private fun parseUuidBytes(uuidStr: String): ByteArray {
        return try {
            val uuid = UUID.fromString(uuidStr.trim())
            val bb = ByteBuffer.allocate(16)
            bb.putLong(uuid.mostSignificantBits)
            bb.putLong(uuid.leastSignificantBits)
            bb.array()
        } catch (_: Exception) {
            ByteArray(16)
        }
    }

    private fun tryParseIpv4(host: String): ByteArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            bytes[i] = n.toByte()
        }
        return bytes
    }

    private fun sha224Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-224")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

/**
 * WebSocket + VLESS Stream wrapper that handles transparent binary frame framing
 */
class WsVlessProxyConnection(
    private val socket: Socket,
    private val rawIn: InputStream,
    private val rawOut: OutputStream
) : ProxyConnection {

    private var initialVlessHeaderHandled = false
    private val inBuffer = ByteArrayOutputStream()

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val r = read(b, 0, 1)
            return if (r > 0) b[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (inBuffer.size() > 0) {
                val data = inBuffer.toByteArray()
                val toRead = minOf(len, data.size)
                System.arraycopy(data, 0, b, off, toRead)
                inBuffer.reset()
                if (data.size > toRead) {
                    inBuffer.write(data, toRead, data.size - toRead)
                }
                return toRead
            }

            // Read next WebSocket frame
            val frameData = readNextWsFrame() ?: return -1
            var payload = frameData

            if (!initialVlessHeaderHandled) {
                initialVlessHeaderHandled = true
                // First 2 bytes are VLESS response: [version: 0x00, addonLen: 0x00]
                if (payload.size >= 2) {
                    payload = payload.copyOfRange(2, payload.size)
                }
            }

            if (payload.isEmpty()) {
                return 0
            }

            val toRead = minOf(len, payload.size)
            System.arraycopy(payload, 0, b, off, toRead)
            if (payload.size > toRead) {
                inBuffer.write(payload, toRead, payload.size - toRead)
            }
            return toRead
        }

        private fun readNextWsFrame(): ByteArray? {
            val b0 = rawIn.read()
            if (b0 < 0) return null
            val b1 = rawIn.read()
            if (b1 < 0) return null

            val opcode = b0 and 0x0F
            val isMasked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val p1 = rawIn.read()
                val p2 = rawIn.read()
                if (p1 < 0 || p2 < 0) return null
                payloadLen = ((p1 shl 8) or p2).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val p = rawIn.read()
                    if (p < 0) return null
                    len = (len shl 8) or p.toLong()
                }
                payloadLen = len
            }

            val mask = if (isMasked) {
                val m = ByteArray(4)
                rawIn.read(m)
                m
            } else null

            val payload = ByteArray(payloadLen.toInt())
            var read = 0
            while (read < payloadLen) {
                val r = rawIn.read(payload, read, (payloadLen - read).toInt())
                if (r < 0) break
                read += r
            }

            if (isMasked && mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }

            if (opcode == 8) {
                // Close frame
                return null
            }

            return payload
        }
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val chunk = ByteArray(len)
            System.arraycopy(b, off, chunk, 0, len)
            writeWsFrame(chunk)
        }

        private fun writeWsFrame(data: ByteArray) {
            val len = data.size
            rawOut.write(0x82) // FIN + Binary

            val mask = ByteArray(4)
            SecureRandom().nextBytes(mask)

            if (len <= 125) {
                rawOut.write(0x80 or len)
            } else if (len <= 65535) {
                rawOut.write(0x80 or 126)
                rawOut.write((len ushr 8) and 0xFF)
                rawOut.write(len and 0xFF)
            } else {
                rawOut.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    rawOut.write(((len.toLong() ushr (i * 8)) and 0xFF).toInt())
                }
            }

            rawOut.write(mask)

            val masked = ByteArray(len)
            for (i in 0 until len) {
                masked[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            rawOut.write(masked)
            rawOut.flush()
        }

        override fun flush() {
            rawOut.flush()
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}
