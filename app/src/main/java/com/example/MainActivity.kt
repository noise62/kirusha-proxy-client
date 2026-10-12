package com.example

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.VpnState
import com.example.ui.MainViewModel
import com.example.ui.dialogs.AddConfigDialog
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

enum class AppScreen(val title: String) {
    HOME("Главная"),
    SERVERS("Серверы"),
    SETTINGS("Настройки")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val customSeedColor by viewModel.customSeedColor.collectAsState()

            MyApplicationTheme(
                themeMode = themeMode,
                customSeedColor = customSeedColor
            ) {
                MainAppContent(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContent(viewModel: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }
    var showAddDialog by remember { mutableStateOf(false) }

    // State collections
    val vpnState by viewModel.vpnState.collectAsState()
    val trafficStats by viewModel.trafficStats.collectAsState()
    val allServers by viewModel.allServers.collectAsState()
    val filteredServers by viewModel.filteredServers.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val isPingingAll by viewModel.isPingingAll.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val protocolFilter by viewModel.protocolFilter.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val activeSubscriptionInfo by viewModel.activeSubscriptionInfo.collectAsState()

    val pingType by viewModel.pingType.collectAsState()
    val pingTimeoutMs by viewModel.pingTimeoutMs.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val customSeedColor by viewModel.customSeedColor.collectAsState()

    // Handle back button on secondary screens
    if (currentScreen != AppScreen.HOME) {
        BackHandler {
            currentScreen = AppScreen.HOME
        }
    }

    // VpnService permission launcher
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.toggleVpn()
        } else {
            Toast.makeText(context, "Требуется разрешение для создания VPN-туннеля", Toast.LENGTH_SHORT).show()
        }
    }

    // Notification permission launcher for Android 13+
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Show user snackbar messages
    LaunchedEffect(userMessage) {
        userMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUserMessage()
        }
    }

    fun handleVpnToggle() {
        val state = vpnState
        if (state is VpnState.Connected || state is VpnState.Connecting || state is VpnState.Reconnecting) {
            viewModel.toggleVpn()
        } else {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                vpnLauncher.launch(vpnIntent)
            } else {
                viewModel.toggleVpn()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (currentScreen) {
                            AppScreen.HOME -> "KirushaVPN"
                            AppScreen.SERVERS -> "Серверы (${allServers.size})"
                            AppScreen.SETTINGS -> "Настройки"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (currentScreen == AppScreen.SERVERS) {
                        IconButton(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.testTag("top_add_config_icon")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Добавить конфиг")
                        }
                    } else if (currentScreen == AppScreen.HOME) {
                        IconButton(
                            onClick = { viewModel.pingAllServers() },
                            modifier = Modifier.testTag("top_ping_all_icon")
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = "Пинг серверов")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.testTag("main_bottom_nav")
            ) {
                NavigationBarItem(
                    selected = currentScreen == AppScreen.HOME,
                    onClick = { currentScreen = AppScreen.HOME },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.HOME) Icons.Default.Shield else Icons.Outlined.Home,
                            contentDescription = "Главная"
                        )
                    },
                    label = { Text("Главная") },
                    modifier = Modifier.testTag("nav_home")
                )

                NavigationBarItem(
                    selected = currentScreen == AppScreen.SERVERS,
                    onClick = { currentScreen = AppScreen.SERVERS },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.SERVERS) Icons.Default.Dns else Icons.Outlined.Dns,
                            contentDescription = "Серверы"
                        )
                    },
                    label = { Text("Серверы") },
                    modifier = Modifier.testTag("nav_servers")
                )

                NavigationBarItem(
                    selected = currentScreen == AppScreen.SETTINGS,
                    onClick = { currentScreen = AppScreen.SETTINGS },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.SETTINGS) Icons.Default.Settings else Icons.Outlined.Settings,
                            contentDescription = "Настройки"
                        )
                    },
                    label = { Text("Настройки") },
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                AppScreen.HOME -> HomeScreen(
                    vpnState = vpnState,
                    trafficStats = trafficStats,
                    selectedServer = selectedServer,
                    onToggleVpn = { handleVpnToggle() },
                    onNavigateToServers = { currentScreen = AppScreen.SERVERS },
                    onOpenAddDialog = { showAddDialog = true }
                )

                AppScreen.SERVERS -> ServersScreen(
                    servers = filteredServers,
                    selectedServerId = selectedServer?.id ?: -1L,
                    isPingingAll = isPingingAll,
                    searchQuery = searchQuery,
                    selectedProtocol = protocolFilter,
                    activeSubscriptionInfo = activeSubscriptionInfo,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    onProtocolSelect = { viewModel.setProtocolFilter(it) },
                    onSelectServer = { viewModel.selectServer(it) },
                    onPingServer = { viewModel.pingServer(it) },
                    onPingAll = { viewModel.pingAllServers() },
                    onDeleteServer = { viewModel.deleteServer(it) },
                    onOpenAddDialog = { showAddDialog = true }
                )

                AppScreen.SETTINGS -> SettingsScreen(
                    pingType = pingType,
                    pingTimeoutMs = pingTimeoutMs,
                    themeMode = themeMode,
                    customSeedColor = customSeedColor,
                    onSetPingType = { viewModel.setPingType(it) },
                    onSetPingTimeout = { viewModel.setPingTimeoutMs(it) },
                    onSetThemeMode = { viewModel.setThemeMode(it) },
                    onSetCustomSeedColor = { viewModel.setCustomSeedColor(it) },
                    onClearAllServers = { viewModel.clearAllServers() }
                )
            }

            if (showAddDialog) {
                AddConfigDialog(
                    onImport = { text -> viewModel.importConfig(text) },
                    onImportUrl = { url, callback -> viewModel.importFromUrl(url, callback) },
                    onDismiss = { showAddDialog = false }
                )
            }
        }
    }
}
