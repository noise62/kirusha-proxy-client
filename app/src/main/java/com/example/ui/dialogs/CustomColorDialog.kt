package com.example.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.ColorGenerator

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomColorDialog(
    initialColorArgb: Long,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialColor = Color(initialColorArgb)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)

    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hexInput by remember { mutableStateOf(ColorGenerator.colorToHex(initialColor)) }

    val currentColor = ColorGenerator.hsvToColor(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = currentColor,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text("Кастомный цвет темы")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Live preview banner
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = currentColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Предпросмотр акцента",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (value > 0.5f && saturation < 0.6f) Color.Black else Color.White
                        )
                        Text(
                            text = ColorGenerator.colorToHex(currentColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value > 0.5f && saturation < 0.6f) Color.Black.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                // Hue slider with rainbow gradient track
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Оттенок (Hue)", style = MaterialTheme.typography.bodySmall)
                        Text("${hue.toInt()}°", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = hue,
                        onValueChange = {
                            hue = it
                            hexInput = ColorGenerator.colorToHex(ColorGenerator.hsvToColor(hue, saturation, value))
                        },
                        valueRange = 0f..360f,
                        modifier = Modifier.testTag("hue_slider")
                    )
                }

                // Saturation slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Насыщенность", style = MaterialTheme.typography.bodySmall)
                        Text("${(saturation * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = saturation,
                        onValueChange = {
                            saturation = it
                            hexInput = ColorGenerator.colorToHex(ColorGenerator.hsvToColor(hue, saturation, value))
                        },
                        valueRange = 0.1f..1f,
                        modifier = Modifier.testTag("saturation_slider")
                    )
                }

                // Value/Brightness slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Яркость", style = MaterialTheme.typography.bodySmall)
                        Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                    Slider(
                        value = value,
                        onValueChange = {
                            value = it
                            hexInput = ColorGenerator.colorToHex(ColorGenerator.hsvToColor(hue, saturation, value))
                        },
                        valueRange = 0.2f..1f,
                        modifier = Modifier.testTag("brightness_slider")
                    )
                }

                // Hex input
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        hexInput = input
                        if (input.startsWith("#") && (input.length == 7 || input.length == 9)) {
                            val parsed = ColorGenerator.parseHexColor(input)
                            val parsedColor = Color(parsed)
                            val tempHsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(parsedColor.toArgb(), tempHsv)
                            hue = tempHsv[0]
                            saturation = tempHsv[1]
                            value = tempHsv[2]
                        }
                    },
                    label = { Text("HEX код цвета") },
                    modifier = Modifier.fillMaxWidth().testTag("hex_color_input"),
                    singleLine = true
                )

                // Quick preset color swatches
                Text("Популярные палитры:", style = MaterialTheme.typography.bodySmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ColorGenerator.PRESET_COLORS.forEach { preset ->
                        val presetColor = Color(preset.colorArgb)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(presetColor)
                                .border(
                                    width = if (ColorGenerator.colorToHex(presetColor) == hexInput) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    shape = CircleShape
                                )
                                .clickable {
                                    val tempHsv = FloatArray(3)
                                    android.graphics.Color.colorToHSV(presetColor.toArgb(), tempHsv)
                                    hue = tempHsv[0]
                                    saturation = tempHsv[1]
                                    value = tempHsv[2]
                                    hexInput = ColorGenerator.colorToHex(presetColor)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (ColorGenerator.colorToHex(presetColor) == hexInput) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalArgb = (currentColor.toArgb().toLong() and 0xFFFFFFFFL)
                    onColorSelected(finalArgb)
                },
                modifier = Modifier.testTag("apply_custom_color_button")
            ) {
                Text("Применить")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt()
    val r = (red * 255f + 0.5f).toInt()
    val g = (green * 255f + 0.5f).toInt()
    val b = (blue * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
