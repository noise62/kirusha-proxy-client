package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

data class ColorPreset(
    val name: String,
    val colorArgb: Long
)

object ColorGenerator {

    val PRESET_COLORS = listOf(
        ColorPreset("Kirusha Green", 0xFF68B456),
        ColorPreset("Cyber Cyan", 0xFF00B4D8),
        ColorPreset("Electric Violet", 0xFF7209B7),
        ColorPreset("Emerald Shield", 0xFF00BFA5),
        ColorPreset("Sunset Amber", 0xFFFF6F00),
        ColorPreset("Crimson Neon", 0xFFE63946),
        ColorPreset("Sapphire Blue", 0xFF2A6F97),
        ColorPreset("Material Purple", 0xFF6750A4)
    )

    fun createColorScheme(seedArgb: Long, isDark: Boolean): ColorScheme {
        val seedColor = Color(seedArgb)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(seedColor.toArgb(), hsv)
        val hue = hsv[0]
        val sat = hsv[1]

        return if (isDark) {
            val primary = hsvToColor(hue, (sat * 0.7f).coerceIn(0.2f, 0.9f), 0.90f)
            val onPrimary = hsvToColor(hue, 0.9f, 0.15f)
            val primaryContainer = hsvToColor(hue, (sat * 0.85f).coerceIn(0.3f, 0.95f), 0.35f)
            val onPrimaryContainer = hsvToColor(hue, 0.3f, 0.95f)

            val secHue = (hue + 20f) % 360f
            val secondary = hsvToColor(secHue, 0.45f, 0.85f)
            val onSecondary = hsvToColor(secHue, 0.8f, 0.18f)
            val secondaryContainer = hsvToColor(secHue, 0.5f, 0.30f)
            val onSecondaryContainer = hsvToColor(secHue, 0.3f, 0.92f)

            val tertHue = (hue + 60f) % 360f
            val tertiary = hsvToColor(tertHue, 0.5f, 0.88f)
            val onTertiary = hsvToColor(tertHue, 0.8f, 0.15f)
            val tertiaryContainer = hsvToColor(tertHue, 0.5f, 0.32f)
            val onTertiaryContainer = hsvToColor(tertHue, 0.3f, 0.92f)

            darkColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = Color(0xFF0F1216),
                onBackground = Color(0xFFE2E4E9),
                surface = Color(0xFF13171D),
                onSurface = Color(0xFFE2E4E9),
                surfaceVariant = Color(0xFF222831),
                onSurfaceVariant = Color(0xFFB0B7C3),
                outline = Color(0xFF555E6D),
                outlineVariant = Color(0xFF2E3642),
                surfaceContainerLowest = Color(0xFF0B0E12),
                surfaceContainerLow = Color(0xFF161A21),
                surfaceContainer = Color(0xFF1C222B),
                surfaceContainerHigh = Color(0xFF232A35),
                surfaceContainerHighest = Color(0xFF2B3340)
            )
        } else {
            val primary = hsvToColor(hue, (sat * 0.95f).coerceIn(0.5f, 1.0f), 0.55f)
            val onPrimary = Color(0xFFFFFFFF)
            val primaryContainer = hsvToColor(hue, (sat * 0.35f).coerceIn(0.12f, 0.5f), 0.92f)
            val onPrimaryContainer = hsvToColor(hue, 0.9f, 0.22f)

            val secHue = (hue + 20f) % 360f
            val secondary = hsvToColor(secHue, 0.6f, 0.48f)
            val onSecondary = Color(0xFFFFFFFF)
            val secondaryContainer = hsvToColor(secHue, 0.25f, 0.93f)
            val onSecondaryContainer = hsvToColor(secHue, 0.8f, 0.20f)

            val tertHue = (hue + 60f) % 360f
            val tertiary = hsvToColor(tertHue, 0.65f, 0.50f)
            val onTertiary = Color(0xFFFFFFFF)
            val tertiaryContainer = hsvToColor(tertHue, 0.25f, 0.93f)
            val onTertiaryContainer = hsvToColor(tertHue, 0.85f, 0.18f)

            lightColorScheme(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = onSecondary,
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = onSecondaryContainer,
                tertiary = tertiary,
                onTertiary = onTertiary,
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = onTertiaryContainer,
                background = Color(0xFFF8F9FC),
                onBackground = Color(0xFF181C20),
                surface = Color(0xFFFAFBFD),
                onSurface = Color(0xFF181C20),
                surfaceVariant = Color(0xFFE2E6EC),
                onSurfaceVariant = Color(0xFF454B54),
                outline = Color(0xFF767C86),
                outlineVariant = Color(0xFFC6CBD4),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF3F5F9),
                surfaceContainer = Color(0xFFECF0F6),
                surfaceContainerHigh = Color(0xFFE5EAF1),
                surfaceContainerHighest = Color(0xFFDFE4EC)
            )
        }
    }

    fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
        val hsv = floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        val argb = android.graphics.Color.HSVToColor(hsv)
        return Color(argb)
    }

    fun colorToHex(color: Color): String {
        val argb = color.toArgb()
        return String.format("#%06X", 0xFFFFFF and argb)
    }

    fun parseHexColor(hex: String, fallback: Long = 0xFF68B456): Long {
        return try {
            val clean = hex.trim().removePrefix("#")
            val parsed = clean.toLong(16)
            if (clean.length == 6) {
                0xFF000000L or parsed
            } else {
                parsed
            }
        } catch (e: Exception) {
            fallback
        }
    }
}
