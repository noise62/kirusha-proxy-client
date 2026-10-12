package com.example.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.model.ProxyServer
import com.example.net.ProxyClient
import com.example.net.ProxyConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class TunPacketRelay(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor,
    private val targetServer: ProxyServer,
    private val scope: CoroutineScope
) {
    private var isRunning = true
    val totalUpBytes = AtomicLong(0)
    val totalDownBytes = AtomicLong(0)
    val liveUpSpeed = AtomicLong(0)
    val liveDownSpeed = AtomicLong(0)

    private val dnsCache = ConcurrentHashMap<String, String>()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private var localSocksServer: ServerSocket? = null
    private var localHttpServer: ServerSocket? = null

    private val outputStream: FileOutputStream by lazy {
        FileOutputStream(vpnInterface.fileDescriptor)
    }

    private class TcpSession(
        val clientPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val destHost: String,
        var clientSeq: Long,
        var serverSeq: Long,
        var proxyConn: ProxyConnection? = null,
        var isClosed: Boolean = false
    )

    fun start() {
        isRunning = true
        scope.launch(Dispatchers.IO) {
            runTunPacketLoop()
        }
        scope.launch(Dispatchers.IO) {
            runLocalSocksProxy()
        }
        scope.launch(Dispatchers.IO) {
            runLocalHttpProxy()
        }
    }

    fun stop() {
        isRunning = false
        for (session in tcpSessions.values) {
            session.isClosed = true
            session.proxyConn?.close()
        }
        tcpSessions.clear()
        try { localSocksServer?.close() } catch (_: Exception) {}
        try { localHttpServer?.close() } catch (_: Exception) {}
    }

    private fun runTunPacketLoop() {
        val inputStream = FileInputStream(vpnInterface.fileDescriptor)
        val packetBuffer = ByteArray(32767)

        while (isRunning && scope.isActive) {
            try {
                val length = inputStream.read(packetBuffer)
                if (length <= 0) continue

                val versionAndIHL = packetBuffer[0].toInt() and 0xFF
                val version = versionAndIHL ushr 4
                if (version != 4) continue // IPv4 only

                val ihl = (versionAndIHL and 0x0F) * 4
                if (length < ihl) continue

                val protocol = packetBuffer[9].toInt() and 0xFF

                // Handle UDP DNS (Protocol 17)
                if (protocol == 17 && length >= ihl + 8) {
                    val dstPort = ((packetBuffer[ihl + 2].toInt() and 0xFF) shl 8) or
                            (packetBuffer[ihl + 3].toInt() and 0xFF)
                    val srcPort = ((packetBuffer[ihl].toInt() and 0xFF) shl 8) or
                            (packetBuffer[ihl + 1].toInt() and 0xFF)

                    if (dstPort == 53) {
                        val udpPayloadOffset = ihl + 8
                        val udpPayloadLen = length - udpPayloadOffset
                        if (udpPayloadLen > 0) {
                            scope.launch(Dispatchers.IO) {
                                relayDnsQuery(
                                    packetBuffer,
                                    srcPort,
                                    udpPayloadOffset,
                                    udpPayloadLen
                                )
                            }
                        }
                    }
                }
                // Handle TCP (Protocol 6)
                else if (protocol == 6 && length >= ihl + 20) {
                    handleTcpPacket(packetBuffer, ihl, length)
                }

            } catch (e: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun relayDnsQuery(
        packet: ByteArray,
        clientPort: Int,
        payloadOffset: Int,
        payloadLen: Int
    ) {
        var querySocket: DatagramSocket? = null
        try {
            val dnsPayload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLen)
            val dnsServer = InetAddress.getByName("1.1.1.1")
            val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, dnsServer, 53)

            querySocket = DatagramSocket()
            vpnService.protect(querySocket)
            querySocket.soTimeout = 2500
            querySocket.send(sendPacket)

            val responseBuffer = ByteArray(2048)
            val recvPacket = DatagramPacket(responseBuffer, responseBuffer.size)
            querySocket.receive(recvPacket)

            val respLen = recvPacket.length
            if (respLen <= 0) return

            totalDownBytes.addAndGet(respLen.toLong())
            liveDownSpeed.addAndGet(respLen.toLong())

            // Cache domain name to IP mappings from DNS response
            parseAndCacheDnsResponse(recvPacket.data, respLen)

            // Build reply IPv4 + UDP packet to TUN
            val totalPacketSize = 20 + 8 + respLen
            val replyPacket = ByteArray(totalPacketSize)

            replyPacket[0] = 0x45.toByte()
            replyPacket[2] = ((totalPacketSize ushr 8) and 0xFF).toByte()
            replyPacket[3] = (totalPacketSize and 0xFF).toByte()
            replyPacket[8] = 64.toByte() // TTL
            replyPacket[9] = 17.toByte() // UDP

            // Source IP: 1.1.1.1
            replyPacket[12] = 1.toByte()
            replyPacket[13] = 1.toByte()
            replyPacket[14] = 1.toByte()
            replyPacket[15] = 1.toByte()

            // Destination IP: 10.0.0.2
            replyPacket[16] = 10.toByte()
            replyPacket[17] = 0.toByte()
            replyPacket[18] = 0.toByte()
            replyPacket[19] = 2.toByte()

            val ipChecksum = computeChecksum(replyPacket, 0, 20)
            replyPacket[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            replyPacket[11] = (ipChecksum and 0xFF).toByte()

            // UDP Header
            replyPacket[20] = 0.toByte()
            replyPacket[21] = 53.toByte()
            replyPacket[22] = ((clientPort ushr 8) and 0xFF).toByte()
            replyPacket[23] = (clientPort and 0xFF).toByte()

            val udpLen = 8 + respLen
            replyPacket[24] = ((udpLen ushr 8) and 0xFF).toByte()
            replyPacket[25] = (udpLen and 0xFF).toByte()

            System.arraycopy(recvPacket.data, 0, replyPacket, 28, respLen)

            synchronized(outputStream) {
                outputStream.write(replyPacket)
                outputStream.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { querySocket?.close() } catch (_: Exception) {}
        }
    }

    private fun parseAndCacheDnsResponse(data: ByteArray, len: Int) {
        try {
            if (len < 12) return
            val ancount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (ancount == 0) return

            // Skip Question section
            var idx = 12
            val qnameSb = StringBuilder()
            while (idx < len && data[idx].toInt() != 0) {
                val labelLen = data[idx].toInt() and 0xFF
                idx++
                if (idx + labelLen > len) return
                for (i in 0 until labelLen) {
                    qnameSb.append(data[idx + i].toInt().toChar())
                }
                qnameSb.append('.')
                idx += labelLen
            }
            if (idx >= len) return
            idx++ // skip 0x00
            idx += 4 // skip QTYPE + QCLASS
            val domain = qnameSb.toString().trimEnd('.')

            // Parse Answers
            for (i in 0 until ancount) {
                if (idx >= len) break
                // Name pointer or label
                if ((data[idx].toInt() and 0xC0) == 0xC0) {
                    idx += 2
                } else {
                    while (idx < len && data[idx].toInt() != 0) idx += (data[idx].toInt() and 0xFF) + 1
                    idx++
                }
                if (idx + 10 > len) break
                val type = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
                val rdlength = ((data[idx + 8].toInt() and 0xFF) shl 8) or (data[idx + 9].toInt() and 0xFF)
                idx += 10
                if (type == 1 && rdlength == 4 && idx + 4 <= len) { // Type A (IPv4)
                    val ip = "${data[idx].toInt() and 0xFF}.${data[idx + 1].toInt() and 0xFF}.${data[idx + 2].toInt() and 0xFF}.${data[idx + 3].toInt() and 0xFF}"
                    dnsCache[ip] = domain
                }
                idx += rdlength
            }
        } catch (_: Exception) {}
    }

    private fun handleTcpPacket(packet: ByteArray, ihl: Int, length: Int) {
        val srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}.${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}"
        val dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}.${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)

        val seq = (((packet[ihl + 4].toLong() and 0xFF) shl 24) or
                ((packet[ihl + 5].toLong() and 0xFF) shl 16) or
                ((packet[ihl + 6].toLong() and 0xFF) shl 8) or
                (packet[ihl + 7].toLong() and 0xFF)) and 0xFFFFFFFFL

        val tcpOffset = ((packet[ihl + 12].toInt() and 0xF0) ushr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0
        val isAck = (flags and 0x10) != 0

        val payloadOffset = ihl + tcpOffset
        val payloadLen = length - payloadOffset

        val key = "$srcPort-$dstIp:$dstPort"

        if (isSyn) {
            val destHost = dnsCache[dstIp] ?: dstIp
            val session = TcpSession(
                clientPort = srcPort,
                dstIp = dstIp,
                dstPort = dstPort,
                destHost = destHost,
                clientSeq = seq + 1,
                serverSeq = 1000L
            )
            tcpSessions[key] = session

            // Send SYN+ACK packet back to TUN
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x12, // SYN | ACK
                payload = null
            )
            session.serverSeq += 1

            // Connect to remote target via ProxyClient
            scope.launch(Dispatchers.IO) {
                try {
                    val conn = ProxyClient.connect(targetServer, destHost, dstPort, vpnService)
                    session.proxyConn = conn

                    // Read from proxy connection and write packets back to TUN
                    val buffer = ByteArray(1360) // Safe MSS segment size
                    val inStream = conn.inputStream

                    while (isRunning && !session.isClosed) {
                        val read = inStream.read(buffer)
                        if (read <= 0) break

                        totalDownBytes.addAndGet(read.toLong())
                        liveDownSpeed.addAndGet(read.toLong())

                        val chunk = buffer.copyOfRange(0, read)
                        sendTcpPacket(
                            srcIp = session.dstIp,
                            srcPort = session.dstPort,
                            dstIp = "10.0.0.2",
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x18, // PSH | ACK
                            payload = chunk
                        )
                        session.serverSeq = (session.serverSeq + read) and 0xFFFFFFFFL
                    }
                } catch (_: Exception) {
                } finally {
                    if (!session.isClosed) {
                        sendTcpPacket(
                            srcIp = session.dstIp,
                            srcPort = session.dstPort,
                            dstIp = "10.0.0.2",
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x11, // FIN | ACK
                            payload = null
                        )
                    }
                    session.isClosed = true
                    session.proxyConn?.close()
                    tcpSessions.remove(key)
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (isRst) {
            session.isClosed = true
            session.proxyConn?.close()
            tcpSessions.remove(key)
            return
        }

        if (isFin) {
            session.clientSeq = seq + 1
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x11, // FIN | ACK
                payload = null
            )
            session.isClosed = true
            session.proxyConn?.close()
            tcpSessions.remove(key)
            return
        }

        if (payloadLen > 0) {
            session.clientSeq = seq + payloadLen
            totalUpBytes.addAndGet(payloadLen.toLong())
            liveUpSpeed.addAndGet(payloadLen.toLong())

            // ACK client data packet immediately
            sendTcpPacket(
                srcIp = dstIp,
                srcPort = dstPort,
                dstIp = srcIp,
                dstPort = srcPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x10, // ACK
                payload = null
            )

            // Forward data to proxy connection
            scope.launch(Dispatchers.IO) {
                try {
                    val conn = session.proxyConn
                    if (conn != null) {
                        conn.outputStream.write(packet, payloadOffset, payloadLen)
                        conn.outputStream.flush()
                    }
                } catch (_: Exception) {
                    session.isClosed = true
                    session.proxyConn?.close()
                    tcpSessions.remove(key)
                }
            }
        }
    }

    private fun sendTcpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray?
    ) {
        val payloadLen = payload?.size ?: 0
        val totalLen = 20 + 20 + payloadLen
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[6] = 0x40.toByte() // Don't fragment
        packet[8] = 64.toByte() // TTL
        packet[9] = 6.toByte() // TCP Protocol

        val srcBytes = InetAddress.getByName(srcIp).address
        val dstBytes = InetAddress.getByName(dstIp).address
        System.arraycopy(srcBytes, 0, packet, 12, 4)
        System.arraycopy(dstBytes, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // TCP Header (20 bytes)
        packet[20] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()

        // Seq number (4 bytes)
        packet[24] = ((seq ushr 24) and 0xFF).toByte()
        packet[25] = ((seq ushr 16) and 0xFF).toByte()
        packet[26] = ((seq ushr 8) and 0xFF).toByte()
        packet[27] = (seq and 0xFF).toByte()

        // Ack number (4 bytes)
        packet[28] = ((ack ushr 24) and 0xFF).toByte()
        packet[29] = ((ack ushr 16) and 0xFF).toByte()
        packet[30] = ((ack ushr 8) and 0xFF).toByte()
        packet[31] = (ack and 0xFF).toByte()

        packet[32] = 0x50.toByte() // Data offset (5 words = 20 bytes)
        packet[33] = flags.toByte()

        // Window size (65535)
        packet[34] = 0xFF.toByte()
        packet[35] = 0xFF.toByte()

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, 0, packet, 40, payloadLen)
        }

        // Compute TCP Checksum with Pseudo-Header
        val tcpChecksum = computeTcpChecksum(packet, 12, 20, 20 + payloadLen)
        packet[36] = ((tcpChecksum ushr 8) and 0xFF).toByte()
        packet[37] = (tcpChecksum and 0xFF).toByte()

        try {
            synchronized(outputStream) {
                outputStream.write(packet)
                outputStream.flush()
            }
        } catch (_: Exception) {}
    }

    private fun computeTcpChecksum(packet: ByteArray, ipOffset: Int, tcpOffset: Int, tcpLength: Int): Int {
        var sum = 0L

        // Pseudo Header: Source IP (4 bytes), Dest IP (4 bytes)
        for (i in 0 until 8 step 2) {
            val word = ((packet[ipOffset + i].toInt() and 0xFF) shl 8) or
                    (packet[ipOffset + i + 1].toInt() and 0xFF)
            sum += word
        }
        // Zero (1 byte) + Protocol (1 byte = 6)
        sum += 6
        // TCP Length
        sum += tcpLength

        // TCP Header and Data
        var idx = tcpOffset
        val end = tcpOffset + tcpLength
        while (idx < end - 1) {
            val word = ((packet[idx].toInt() and 0xFF) shl 8) or
                    (packet[idx + 1].toInt() and 0xFF)
            sum += word
            idx += 2
        }
        if (idx < end) {
            sum += (packet[idx].toInt() and 0xFF) shl 8
        }

        while (sum ushr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < offset + length) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 > 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    /**
     * Local SOCKS5 proxy on 127.0.0.1:10808
     */
    private fun runLocalSocksProxy() {
        try {
            localSocksServer = ServerSocket()
            localSocksServer?.reuseAddress = true
            localSocksServer?.bind(InetSocketAddress("127.0.0.1", 10808))

            while (isRunning && scope.isActive) {
                val client = localSocksServer?.accept() ?: break
                scope.launch(Dispatchers.IO) {
                    handleSocksClient(client)
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleSocksClient(client: Socket) {
        var proxyConn: ProxyConnection? = null
        try {
            val inClient = client.getInputStream()
            val outClient = client.getOutputStream()

            // SOCKS5 Handshake
            val ver = inClient.read()
            if (ver != 5) { client.close(); return }
            val nMethods = inClient.read()
            val methods = ByteArray(nMethods)
            inClient.read(methods)

            outClient.write(byteArrayOf(0x05, 0x00))
            outClient.flush()

            // Connect request
            inClient.read() // ver
            val cmd = inClient.read()
            inClient.read() // rsv
            val atyp = inClient.read()

            var destHost = ""
            var destPort = 80

            when (atyp) {
                1 -> {
                    val ip = ByteArray(4)
                    inClient.read(ip)
                    destHost = InetAddress.getByAddress(ip).hostAddress ?: targetServer.host
                }
                3 -> {
                    val len = inClient.read()
                    val dom = ByteArray(len)
                    inClient.read(dom)
                    destHost = String(dom)
                }
                4 -> {
                    val ip6 = ByteArray(16)
                    inClient.read(ip6)
                    destHost = InetAddress.getByAddress(ip6).hostAddress ?: targetServer.host
                }
            }
            val p1 = inClient.read()
            val p2 = inClient.read()
            destPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

            proxyConn = ProxyClient.connect(targetServer, destHost, destPort, vpnService)

            outClient.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x2A, 0x38))
            outClient.flush()

            bridgeSockets(inClient, outClient, proxyConn.inputStream, proxyConn.outputStream, client, proxyConn)
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
            proxyConn?.close()
        }
    }

    /**
     * Local HTTP proxy on 127.0.0.1:10809 supporting HTTP CONNECT tunneling
     */
    private fun runLocalHttpProxy() {
        try {
            localHttpServer = ServerSocket()
            localHttpServer?.reuseAddress = true
            localHttpServer?.bind(InetSocketAddress("127.0.0.1", 10809))

            while (isRunning && scope.isActive) {
                val client = localHttpServer?.accept() ?: break
                scope.launch(Dispatchers.IO) {
                    handleHttpClient(client)
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleHttpClient(client: Socket) {
        var proxyConn: ProxyConnection? = null
        try {
            val inClient = client.getInputStream()
            val outClient = client.getOutputStream()

            val line = readLine(inClient)
            if (line.startsWith("CONNECT ", ignoreCase = true)) {
                val parts = line.split(" ")
                val hostPort = parts[1].split(":")
                val host = hostPort[0]
                val port = if (hostPort.size > 1) hostPort[1].toIntOrNull() ?: 443 else 443

                // Skip headers
                while (true) {
                    val h = readLine(inClient)
                    if (h.isEmpty() || h == "\r") break
                }

                proxyConn = ProxyClient.connect(targetServer, host, port, vpnService)

                outClient.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                outClient.flush()

                bridgeSockets(inClient, outClient, proxyConn.inputStream, proxyConn.outputStream, client, proxyConn)
            } else {
                client.close()
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) {}
            proxyConn?.close()
        }
    }

    private fun readLine(inStream: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inStream.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun bridgeSockets(
        inClient: InputStream,
        outClient: OutputStream,
        inRemote: InputStream,
        outRemote: OutputStream,
        client: Socket,
        remote: AutoCloseable
    ) {
        val job1 = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16384)
            try {
                while (isRunning) {
                    val r = inClient.read(buf)
                    if (r <= 0) break
                    outRemote.write(buf, 0, r)
                    outRemote.flush()
                    totalUpBytes.addAndGet(r.toLong())
                    liveUpSpeed.addAndGet(r.toLong())
                }
            } catch (_: Exception) {}
        }

        val job2 = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16384)
            try {
                while (isRunning) {
                    val r = inRemote.read(buf)
                    if (r <= 0) break
                    outClient.write(buf, 0, r)
                    outClient.flush()
                    totalDownBytes.addAndGet(r.toLong())
                    liveDownSpeed.addAndGet(r.toLong())
                }
            } catch (_: Exception) {}
        }

        scope.launch {
            job1.join()
            job2.join()
            try { client.close() } catch (_: Exception) {}
            try { remote.close() } catch (_: Exception) {}
        }
    }
}
