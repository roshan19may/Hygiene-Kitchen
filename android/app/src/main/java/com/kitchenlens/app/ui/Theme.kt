package com.kitchenlens.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Leaf = Color(0xFF0F5C4A)
val LeafLight = Color(0xFFDDEFE8)
val Turmeric = Color(0xFFF2C14E)
val Chilli = Color(0xFFC0392B)
val Amber = Color(0xFFD9822B)
val Ok = Color(0xFF1E8E5A)

private val colors = lightColorScheme(
    primary = Leaf,
    onPrimary = Color.White,
    primaryContainer = LeafLight,
    onPrimaryContainer = Color(0xFF06372C),
    secondary = Turmeric,
    onSecondary = Color(0xFF3A2A00),
    error = Chilli,
    background = Color(0xFFF8F6F1),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0EDE6),
)

@Composable
fun KitchenLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
