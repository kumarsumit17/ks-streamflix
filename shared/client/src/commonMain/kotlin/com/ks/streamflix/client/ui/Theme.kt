package com.ks.streamflix.client.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// In the style of shadcn/ui (MIT, https://ui.shadcn.com): neutral zinc colours, thin borders,
// 10 dp corners, and a clear focus ring, which the TV remote needs.
private val Zinc50 = Color(0xFFFAFAFA)
private val Zinc100 = Color(0xFFF4F4F5)
private val Zinc200 = Color(0xFFE4E4E7)
private val Zinc400 = Color(0xFFA1A1AA)
private val Zinc500 = Color(0xFF71717A)
private val Zinc800 = Color(0xFF27272A)
private val Zinc900 = Color(0xFF18181B)
private val Zinc950 = Color(0xFF09090B)

private val Dark: ColorScheme = darkColorScheme(
    primary = Zinc50, onPrimary = Zinc900,
    secondary = Zinc800, onSecondary = Zinc50,
    background = Zinc950, onBackground = Zinc50,
    surface = Zinc950, onSurface = Zinc50,
    surfaceVariant = Zinc900, onSurfaceVariant = Zinc400,
    outline = Zinc800, outlineVariant = Zinc500,
    error = Color(0xFFF87171), onError = Zinc950,
)

private val Light: ColorScheme = lightColorScheme(
    primary = Zinc900, onPrimary = Zinc50,
    secondary = Zinc100, onSecondary = Zinc900,
    background = Color.White, onBackground = Zinc950,
    surface = Color.White, onSurface = Zinc950,
    surfaceVariant = Zinc100, onSurfaceVariant = Zinc500,
    outline = Zinc200, outlineVariant = Zinc400,
    error = Color(0xFFDC2626), onError = Color.White,
)

val CardShape = RoundedCornerShape(10.dp)

@Composable
fun StreamflixTheme(isTv: Boolean, content: @Composable () -> Unit) {
    // A TV is always dark (a bright screen in a dark room); other devices follow the system.
    val dark = isTv || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = CardShape, large = RoundedCornerShape(12.dp)),
        content = content,
    )
}

/** A 2 dp ring around the focused element, so the D-pad focus is always visible. */
fun Modifier.focusRing(shape: RoundedCornerShape = CardShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.primary
    onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(if (focused) 2.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
}
