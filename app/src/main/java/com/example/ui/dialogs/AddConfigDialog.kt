package com.example.ui.dialogs

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parser.SubscriptionInfo
import com.example.parser.XrayConfigParser
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddConfigDialog(
    onImport: (String) -> Int,
    onImportUrl: (String, (Int) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoadingUrl by remember { mutableStateOf(false) }

    val isHttpUrl = remember(configText) {
        val t = configText.trim().lowercase()
        (t.startsWith("http://") || t.startsWith("https://")) && !t.contains("\n")
    }

    val parsedSub: SubscriptionInfo = remember(configText) {
        if (configText.isNotBlank() && !isHttpUrl) {
            XrayConfigParser.parseSubscription(configText)
        } else {
            SubscriptionInfo()
        }
    }

    val parsedPreview = parsedSub.servers

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Добавить конфиг / подписку")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Вставьте ссылку подписки Happ / Incy, конфигурацию vless://, vmess://, hysteria2://, trojan:// или JSON:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Paste from clipboard button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString() ?: ""
                                if (text.isNotBlank()) {
                                    configText = text
                                }
                            }
                        },
                        label = { Text("Вставить из буфера") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.testTag("paste_clipboard_button")
                    )

                    if (configText.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { configText = "" }
                        ) {
                            Text("Очистить")
                        }
                    }
                }

                // Text field
                OutlinedTextField(
                    value = configText,
                    onValueChange = {
                        configText = it
                        errorMessage = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .testTag("config_input_field"),
                    placeholder = {
                        Text("#profile-title: Кирюша Випиэн\nvless://...\nhysteria2://...\nили https://sub.link...")
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                // If user entered a direct HTTP/HTTPS URL, show direct download button
                if (isHttpUrl) {
                    Button(
                        onClick = {
                            isLoadingUrl = true
                            errorMessage = null
                            onImportUrl(configText.trim()) { count ->
                                isLoadingUrl = false
                                if (count > 0) {
                                    onDismiss()
                                } else {
                                    errorMessage = "Не удалось загрузить узлы по ссылке подписки"
                                }
                            }
                        },
                        enabled = !isLoadingUrl,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoadingUrl) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Загрузка подписки...")
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Скачать узлы по ссылке")
                        }
                    }
                }

                // Example chips for quick testing
                Text(
                    text = "Примеры для быстрой вставки:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SuggestionChip(
                        onClick = {
                            configText = """
#profile-title: Кирюша Випиэн
#announce: Кирюша Випиэн бустит
#profile-update-interval: 1
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@titanjuniper.yournextidea.digital:443?encryption=none&security=tls&sni=titanjuniper.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@trcdn3.tcncdnn.digital:443?encryption=none&security=tls&sni=trcdn3.tcncdnn.digital&fp=firefox&type=ws&host=&path=%2Fmedia%2Fws%2F#%F0%9F%87%B9%F0%9F%87%B7%20%D0%A2%D1%83%D1%80%D1%86%D0%B8%D1%8F
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@othertags.animwatch.biz:443?encryption=none&security=tls&sni=othertags.animwatch.biz&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%A9%F0%9F%87%AA%20%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F%20%D0%AE%D1%82%D1%83%D0%B1
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@paneltimeout.yournextidea.digital:443?encryption=none&security=tls&sni=paneltimeout.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B1%F0%9F%87%B9%20%D0%9B%D0%B8%D1%82%D0%B2%D0%B0%20%D0%AE%D1%82%D1%83%D0%B1
hysteria2://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@new.animwatch.biz:443/?sni=new.animwatch.biz&alpn=h3&fp=firefox#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%20(%D0%98%D0%93%D0%A0%D0%AB)
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@packetquery.yournextidea.digital:443?encryption=none&security=tls&sni=packetquery.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%A8%F0%9F%87%AD%20%D0%A8%D0%B2%D0%B5%D0%B9%D1%86%D0%B0%D1%80%D0%B8%D1%8F
vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@insightatlas.yournextidea.digital:443?encryption=none&security=tls&sni=insightatlas.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B
                            """.trimIndent()
                        },
                        label = { Text("Кирюша Випиэн (7 узлов)") }
                    )
                    SuggestionChip(
                        onClick = {
                            configText = "vless://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@titanjuniper.yournextidea.digital:443?encryption=none&security=tls&sni=titanjuniper.yournextidea.digital&fp=firefox&type=ws&path=%2Fmedia%2Fws%2F#%F0%9F%87%B5%F0%9F%87%B1%20%D0%9F%D0%BE%D0%BB%D1%8C%D1%88%D0%B0"
                        },
                        label = { Text("🇵🇱 VLESS Польша") }
                    )
                    SuggestionChip(
                        onClick = {
                            configText = "hysteria2://b6c958fa-02db-4a72-99b7-ee5ae2ba8633@new.animwatch.biz:443/?sni=new.animwatch.biz&alpn=h3&fp=firefox#%F0%9F%87%B3%F0%9F%87%B1%20%D0%9D%D0%B8%D0%B4%D0%B5%D1%80%D0%BB%D0%B0%D0%BD%D0%B4%D1%8B%20(%D0%98%D0%93%D0%A0%D0%AB)"
                        },
                        label = { Text("🇳🇱 Hy2 Нидерланды") }
                    )
                }

                // Subscription & Config Preview
                if (parsedPreview.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (parsedSub.title.isNotEmpty()) "Подписка: ${parsedSub.title}"
                                           else "Распознано: ${parsedPreview.size} сервер(ов)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }

                            if (parsedSub.announcement.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Campaign,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = parsedSub.announcement,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            // Flags overview
                            val flags = parsedPreview.map { it.countryFlag }.distinct().joinToString(" ")
                            Text(
                                text = "Узлы ($flags): ${parsedPreview.size} шт.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                } else if (configText.isNotBlank() && !isHttpUrl) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "Не удалось распознать формат. Проверьте ссылку.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isHttpUrl) {
                Button(
                    onClick = {
                        val count = onImport(configText)
                        if (count > 0) {
                            onDismiss()
                        } else {
                            errorMessage = "Неверный формат ссылки или JSON"
                        }
                    },
                    enabled = parsedPreview.isNotEmpty(),
                    modifier = Modifier.testTag("confirm_add_config_button")
                ) {
                    Text("Добавить (${parsedPreview.size})")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
