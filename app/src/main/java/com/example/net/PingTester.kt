package com.example.net

import com.example.model.PingType
import com.example.model.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object PingTester {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun testPing(server: ProxyServer, pingType: PingType, timeoutMs: Long): Long =
        withContext(Dispatchers.IO) {
            when (pingType) {
                PingType.TCP_HANDSHAKE -> testTcpHandshake(server.host, server.port, timeoutMs.toInt())
                PingType.HTTP_204_GOOGLE -> testHttp204("http://www.google.com/generate_204", server.host, server.port, timeoutMs)
                PingType.HTTP_204_CLOUDFLARE -> testHttp204("http://cp.cloudflare.com/generate_204", server.host, server.port, timeoutMs)
                PingType.ICMP_PING -> testIcmpOrFallback(server.host, timeoutMs.toInt())
            }
        }

    private fun testTcpHandshake(host: String, port: Int, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        var socket: Socket? = null
        return try {
            socket = Socket()
            val socketAddress = InetSocketAddress(host, port)
            socket.connect(socketAddress, timeoutMs)
            val duration = System.currentTimeMillis() - start
            duration.coerceAtLeast(1L)
        } catch (e: Exception) {
            -2L // Timeout or error
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun testHttp204(url: String, host: String, port: Int, timeoutMs: Long): Long {
        // First check TCP connectivity to the proxy node
        val tcpDuration = testTcpHandshake(host, port, (timeoutMs / 2).toInt().coerceAtLeast(1000))
        if (tcpDuration == -2L) {
            return -2L
        }

        val start = System.currentTimeMillis()
        return try {
            val customClient = httpClient.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Xray-Core-2.0-Ping")
                .build()

            val response = customClient.newCall(request).execute()
            response.close()
            val end = System.currentTimeMillis()
            (end - start).coerceAtLeast(1L)
        } catch (e: Exception) {
            // If full HTTP fails, fallback to TCP duration if TCP was responsive
            if (tcpDuration > 0) tcpDuration else -2L
        }
    }

    private fun testIcmpOrFallback(host: String, timeoutMs: Int): Long {
        val start = System.currentTimeMillis()
        return try {
            val address = InetAddress.getByName(host)
            val reachable = address.isReachable(timeoutMs)
            if (reachable) {
                val duration = System.currentTimeMillis() - start
                duration.coerceAtLeast(1L)
            } else {
                // Many cloud providers block ICMP echo, fallback to port 80/443 handshake
                val fallback = testTcpHandshake(host, 443, timeoutMs)
                if (fallback > 0) fallback else -2L
            }
        } catch (e: Exception) {
            -2L
        }
    }
}
