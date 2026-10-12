package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.ProxyProtocol
import com.example.model.ProxyServer

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val uuidOrPassword: String = "",
    val security: String = "tls",
    val streamNetwork: String = "tcp",
    val path: String = "",
    val sni: String = "",
    val rawConfig: String = "",
    val countryCode: String = "UN",
    val pingMs: Long = -1,
    val isAutoSelected: Boolean = false,
    val isCustom: Boolean = false,
    val lastTestedTime: Long = 0
) {
    fun toDomain(): ProxyServer {
        return ProxyServer(
            id = id,
            name = name,
            protocol = ProxyProtocol.fromString(protocol),
            host = host,
            port = port,
            uuidOrPassword = uuidOrPassword,
            security = security,
            streamNetwork = streamNetwork,
            path = path,
            sni = sni,
            rawConfig = rawConfig,
            countryCode = countryCode,
            pingMs = pingMs,
            isAutoSelected = isAutoSelected,
            isCustom = isCustom,
            lastTestedTime = lastTestedTime
        )
    }

    companion object {
        fun fromDomain(server: ProxyServer): ServerEntity {
            return ServerEntity(
                id = server.id,
                name = server.name,
                protocol = server.protocol.name,
                host = server.host,
                port = server.port,
                uuidOrPassword = server.uuidOrPassword,
                security = server.security,
                streamNetwork = server.streamNetwork,
                path = server.path,
                sni = server.sni,
                rawConfig = server.rawConfig,
                countryCode = server.countryCode,
                pingMs = server.pingMs,
                isAutoSelected = server.isAutoSelected,
                isCustom = server.isCustom,
                lastTestedTime = server.lastTestedTime
            )
        }
    }
}
