package com.example.data

import com.example.model.ProxyServer

object DefaultServers {
    fun getInitialServers(): List<ProxyServer> {
        // App starts with zero servers by default as requested
        return emptyList()
    }
}
