package com.hybrid.whatsappbot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WhatsAppDarkGreen = Color(0xFF075E54)
private val WhatsAppTeal = Color(0xFF128C7E)
private val WhatsAppLightGreen = Color(0xFF25D366)
private val SurfaceBg = Color(0xFFF5F7F8)

private val LightColors = lightColorScheme(
    primary = WhatsAppDarkGreen,
    onPrimary = Color.White,
    secondary = WhatsAppTeal,
    onSecondary = Color.White,
    tertiary = WhatsAppLightGreen,
    background = SurfaceBg,
    surface = Color.White,
    onSurface = Color(0xFF1B1F23)
)

@Composable
fun HybridWhatsAppBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
