package com.example.ui

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.XrayVpnApp
import com.example.data.DefaultServers
import com.example.data.ServerEntity
import com.example.data.SettingsRepository
import com.example.model.PingType
import com.example.model.ProxyProtocol
import com.example.model.ProxyServer
import com.example.model.ThemeMode
import com.example.model.TrafficStats
import com.example.model.VpnState
import com.example.net.PingTester
import com.example.parser.SubscriptionInfo
import com.example.parser.XrayConfigParser
import com.example.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as XrayVpnApp
    private val dao = app.database.serverDao()
    private val settings: SettingsRepository = app.settingsRepository

    val vpnState: StateFlow<VpnState> = XrayVpnService.vpnState
    val trafficStats: StateFlow<TrafficStats> = XrayVpnService.trafficStats

    val allServers: StateFlow<List<ProxyServer>> = dao.getAllServersFlow()
        .combine(MutableStateFlow(Unit)) { entities, _ ->
            entities.map { it.toDomain() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedServer: StateFlow<ProxyServer?> = combine(allServers, settings.selectedServerId) { list, id ->
        list.find { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _protocolFilter = MutableStateFlow<ProxyProtocol?>(null)
    val protocolFilter: StateFlow<ProxyProtocol?> = _protocolFilter.asStateFlow()

    private val _isPingingAll = MutableStateFlow(false)
    val isPingingAll: StateFlow<Boolean> = _isPingingAll.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _activeSubscriptionInfo = MutableStateFlow<SubscriptionInfo?>(null)
    val activeSubscriptionInfo: StateFlow<SubscriptionInfo?> = _activeSubscriptionInfo.asStateFlow()

    val pingType = settings.pingType
    val pingTimeoutMs = settings.pingTimeoutMs
    val themeMode = settings.themeMode
    val customSeedColor = settings.customSeedColor

    val filteredServers: StateFlow<List<ProxyServer>> = combine(
        allServers,
        _searchQuery,
        _protocolFilter
    ) { list, query, filter ->
        list.filter { server ->
            val matchesQuery = if (query.isBlank()) true else {
                server.name.contains(query, ignoreCase = true) ||
                        server.host.contains(query, ignoreCase = true) ||
                        server.countryCode.contains(query, ignoreCase = true)
            }
            val matchesProtocol = filter == null || server.protocol == filter
            matchesQuery && matchesProtocol
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setProtocolFilter(protocol: ProxyProtocol?) {
        _protocolFilter.value = protocol
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun selectServer(server: ProxyServer) {
        settings.setSelectedServerId(server.id)
    }

    fun toggleVpn() {
        val state = vpnState.value
        if (state is VpnState.Connected || state is VpnState.Connecting || state is VpnState.Reconnecting) {
            XrayVpnService.stop(getApplication())
        } else {
            val server = selectedServer.value ?: return
            XrayVpnService.start(getApplication(), server.id)
        }
    }

    fun pingServer(server: ProxyServer) {
        viewModelScope.launch(Dispatchers.IO) {
            val type = pingType.value
            val timeout = pingTimeoutMs.value
            val ping = PingTester.testPing(server, type, timeout)
            dao.updatePing(server.id, ping, System.currentTimeMillis())
        }
    }

    fun pingAllServers() {
        if (_isPingingAll.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isPingingAll.value = true
            val servers = dao.getAllServers().map { it.toDomain() }
            val type = pingType.value
            val timeout = pingTimeoutMs.value

            val jobs = servers.map { server ->
                async {
                    val ping = PingTester.testPing(server, type, timeout)
                    dao.updatePing(server.id, ping, System.currentTimeMillis())
                }
            }
            jobs.awaitAll()
            _isPingingAll.value = false
            _userMessage.value = "Проверка задержки всех серверов завершена"
        }
    }

    fun importConfig(rawText: String): Int {
        val subInfo = XrayConfigParser.parseSubscription(rawText)
        val parsed = subInfo.servers
        if (parsed.isEmpty()) return 0

        if (subInfo.title.isNotEmpty() || subInfo.announcement.isNotEmpty()) {
            _activeSubscriptionInfo.value = subInfo
        }

        viewModelScope.launch(Dispatchers.IO) {
            val entities = parsed.map { ServerEntity.fromDomain(it) }
            val insertedIds = dao.insertAll(entities)

            // Automatically select first imported server if none was active
            if (selectedServer.value == null && insertedIds.isNotEmpty()) {
                settings.setSelectedServerId(insertedIds.first())
            }

            val type = pingType.value
            val timeout = pingTimeoutMs.value
            for (item in parsed) {
                PingTester.testPing(item, type, timeout)
            }
            val titleMsg = if (subInfo.title.isNotEmpty()) " (${subInfo.title})" else ""
            _userMessage.value = "Импортировано серверов: ${parsed.size}$titleMsg"
        }
        return parsed.size
    }

    fun importFromUrl(url: String, onComplete: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val subInfo = XrayConfigParser.fetchAndParseSubscription(url)
            val parsed = subInfo.servers
            if (parsed.isNotEmpty()) {
                if (subInfo.title.isNotEmpty() || subInfo.announcement.isNotEmpty()) {
                    _activeSubscriptionInfo.value = subInfo
                }
                val entities = parsed.map { ServerEntity.fromDomain(it) }
                val insertedIds = dao.insertAll(entities)
                if (selectedServer.value == null && insertedIds.isNotEmpty()) {
                    settings.setSelectedServerId(insertedIds.first())
                }
                val titleMsg = if (subInfo.title.isNotEmpty()) " (${subInfo.title})" else ""
                _userMessage.value = "Подписка обновлена: ${parsed.size} узлов$titleMsg"
            }
            launch(Dispatchers.Main) {
                onComplete(parsed.size)
            }
        }
    }

    fun deleteServer(server: ProxyServer) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteById(server.id)
            if (selectedServer.value?.id == server.id) {
                val remaining = dao.getAllServers()
                if (remaining.isNotEmpty()) {
                    settings.setSelectedServerId(remaining.first().id)
                }
            }
        }
    }

    fun deleteAllServers() {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteAll()
            _userMessage.value = "Все серверы удалены"
        }
    }

    fun clearAllServers() = deleteAllServers()

    fun setPingType(type: PingType) = settings.setPingType(type)
    fun setPingTimeoutMs(timeout: Long) = settings.setPingTimeoutMs(timeout)
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setCustomSeedColor(colorArgb: Long) = settings.setCustomSeedColor(colorArgb)
}
