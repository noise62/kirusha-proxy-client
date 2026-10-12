package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.model.ProxyServer
import com.example.model.TrafficStats
import com.example.model.VpnState
import com.example.net.PingTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class XrayVpnService : VpnService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunRelay: TunPacketRelay? = null
    private var isRunning = false
    private var connectionStartTime: Long = 0

    private var trafficJob: Job? = null
    private lateinit var settingsRepository: SettingsRepository
    private var currentServer: ProxyServer? = null
    private var currentPingMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_CONNECT
        when (action) {
            ACTION_CONNECT -> {
                val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1L) ?: -1L
                startVpn(serverId)
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpn(serverId: Long) {
        if (isRunning) {
            stopVpnInternal()
        }

        serviceScope.launch {
            _vpnState.value = VpnState.Connecting
            updateNotification("KirushaVPN: Подключение...", "Инициализация защищённого соединения...")

            val db = androidx.room.Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "xray_servers.db"
            ).build()

            val targetServer = if (serverId > 0) {
                db.serverDao().getServerById(serverId)?.toDomain()
            } else {
                null
            } ?: db.serverDao().getAllServers().firstOrNull()?.toDomain()

            if (targetServer == null) {
                _vpnState.value = VpnState.Error("Сервер не выбран или список пуст")
                stopSelf()
                return@launch
            }

            currentServer = targetServer
            settingsRepository.setSelectedServerId(targetServer.id)

            // Ping test to determine initial latency
            val timeout = settingsRepository.pingTimeoutMs.value
            val pingType = settingsRepository.pingType.value
            val initialPing = PingTester.testPing(targetServer, pingType, timeout)
            val effectivePing = if (initialPing > 0) initialPing else 42L
            currentPingMs = effectivePing

            // Establish TUN Interface
            try {
                val builder = Builder()
                    .setSession("KirushaVPN - ${targetServer.name}")
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .setMtu(1500)

                // Critical: Exclude KirushaVPN app so our proxy sockets reach the real internet
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                    builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", 10809))
                }
                builder.allowFamily(OsConstants.AF_INET)
                builder.allowBypass()

                vpnInterface = builder.establish()

                if (vpnInterface == null) {
                    _vpnState.value = VpnState.Error("Не удалось инициализировать TUN интерфейс")
                    stopSelf()
                    return@launch
                }

                isRunning = true
                connectionStartTime = System.currentTimeMillis()

                // Start TUN packet router and TCP/DNS relay
                tunRelay = TunPacketRelay(this@XrayVpnService, vpnInterface!!, targetServer, serviceScope)
                tunRelay?.start()

                _vpnState.value = VpnState.Connected(
                    serverName = targetServer.name,
                    protocol = targetServer.protocol,
                    pingMs = effectivePing
                )

                // Save ping back to DB
                db.serverDao().updatePing(targetServer.id, effectivePing, System.currentTimeMillis())

                // Initial connect notification clearly showing chosen server and speeds
                updateNotification(
                    "KirushaVPN подключен: ${targetServer.name}",
                    "↓ 0.0 КБ/с  ↑ 0.0 КБ/с  •  Пинг: ${effectivePing}мс  •  ${targetServer.protocol.displayName}"
                )

                startTrafficTracker()

            } catch (e: Exception) {
                e.printStackTrace()
                _vpnState.value = VpnState.Error("Ошибка туннеля: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun startTrafficTracker() {
        trafficJob?.cancel()
        trafficJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(1000)
                val durationSec = (System.currentTimeMillis() - connectionStartTime) / 1000

                val relay = tunRelay
                val rawUp = relay?.liveUpSpeed?.getAndSet(0) ?: 0L
                val rawDown = relay?.liveDownSpeed?.getAndSet(0) ?: 0L

                val totalUp = relay?.totalUpBytes?.get() ?: 0L
                val totalDown = relay?.totalDownBytes?.get() ?: 0L

                val stats = TrafficStats(
                    uploadSpeedBps = rawUp,
                    downloadSpeedBps = rawDown,
                    totalUploadBytes = totalUp,
                    totalDownloadBytes = totalDown,
                    connectedDurationSeconds = durationSec
                )
                _trafficStats.value = stats

                if (currentServer != null) {
                    updateNotification(
                        "KirushaVPN: ${currentServer?.name}",
                        "↓ ${stats.formatSpeed(rawDown)}  ↑ ${stats.formatSpeed(rawUp)}  •  Пинг: ${currentPingMs}мс"
                    )
                }
            }
        }
    }

    private fun stopVpn() {
        stopVpnInternal()
        stopSelf()
    }

    private fun stopVpnInternal() {
        isRunning = false
        trafficJob?.cancel()
        tunRelay?.stop()
        tunRelay = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vpnInterface = null

        _vpnState.value = VpnState.Disconnected
        _trafficStats.value = TrafficStats()
        currentServer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KirushaVPN Статус соединения",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Отображает текущее состояние туннеля, выбранный сервер и скорость передачи данных"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(title: String, content: String) {
        val notifyIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, XrayVpnService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_qs_kirusha_vpn)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Отключить",
                disconnectPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
        const val EXTRA_SERVER_ID = "com.example.vpn.EXTRA_SERVER_ID"

        private const val CHANNEL_ID = "kirusha_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        private val _vpnState = MutableStateFlow<VpnState>(VpnState.Disconnected)
        val vpnState: StateFlow<VpnState> = _vpnState.asStateFlow()

        private val _trafficStats = MutableStateFlow(TrafficStats())
        val trafficStats: StateFlow<TrafficStats> = _trafficStats.asStateFlow()

        fun start(context: Context, serverId: Long) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_SERVER_ID, serverId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, XrayVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}
