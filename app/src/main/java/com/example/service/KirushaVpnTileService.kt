package com.example.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.room.Room
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.SettingsRepository
import com.example.model.VpnState
import com.example.vpn.XrayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class KirushaVpnTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            XrayVpnService.vpnState.collectLatest { state ->
                updateTileState(state)
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        super.onStopListening()
    }

    private fun updateTileState(state: VpnState) {
        val tile = qsTile ?: return
        when (state) {
            is VpnState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "KirushaVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = state.serverName
                }
            }
            is VpnState.Connecting, is VpnState.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "KirushaVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Подключение..."
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "KirushaVPN"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = "Отключено"
                }
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val currentState = XrayVpnService.vpnState.value

        if (currentState is VpnState.Connected || currentState is VpnState.Connecting || currentState is VpnState.Reconnecting) {
            XrayVpnService.stop(this)
            updateTileState(VpnState.Disconnected)
        } else {
            val vpnPrepareIntent = VpnService.prepare(this)
            if (vpnPrepareIntent != null) {
                // Permission not granted yet, open app
                val appIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startActivityAndCollapse(
                        android.app.PendingIntent.getActivity(
                            this,
                            0,
                            appIntent,
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                } else {
                    @Suppress("DEPRECATION")
                    startActivityAndCollapse(appIntent)
                }
            } else {
                val lastServerId = settingsRepository.selectedServerId.value
                XrayVpnService.start(this, lastServerId)
                updateTileState(VpnState.Connecting)
            }
        }
    }
}
