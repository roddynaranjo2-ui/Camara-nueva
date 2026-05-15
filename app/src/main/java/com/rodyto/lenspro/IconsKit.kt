package com.rodyto.lenspro

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BoltOutlined
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.HdrOn
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NightlightRound
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Timer10Select
import androidx.compose.material.icons.outlined.Timer3Select
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Catálogo unificado de iconos LensPro.
 *
 * Reemplaza por completo el uso de emojis (⚙ ⟳ ☀ 🖼) que en muchos dispositivos
 * Android se renderizaban como "blobs amarillos" cuando faltaba la fuente emoji,
 * o tomaban el color de tinte (LensAccent amarillo).
 *
 * Estilo: trazo fino "Outlined" similar a SF Symbols / iOS 19.
 */
object LensIcons {
    val Settings:    ImageVector get() = Icons.Outlined.Settings
    val FlashOn:     ImageVector get() = Icons.Outlined.FlashOn
    val FlashOff:    ImageVector get() = Icons.Outlined.FlashOff
    val FlashAuto:   ImageVector get() = Icons.Outlined.Bolt
    val Hdr:         ImageVector get() = Icons.Outlined.HdrOn
    val Grid:        ImageVector get() = Icons.Outlined.GridOn
    val SoundOn:     ImageVector get() = Icons.Outlined.VolumeUp
    val SoundOff:    ImageVector get() = Icons.Outlined.VolumeOff
    val Timer:       ImageVector get() = Icons.Outlined.Timer
    val TimerOff:    ImageVector get() = Icons.Outlined.TimerOff
    val Timer3:      ImageVector get() = Icons.Outlined.Timer3Select
    val Timer10:     ImageVector get() = Icons.Outlined.Timer10Select
    val Aspect:      ImageVector get() = Icons.Outlined.AspectRatio
    val Exposure:    ImageVector get() = Icons.Outlined.WbSunny
    val Flip:        ImageVector get() = Icons.Outlined.Cameraswitch
    val Gallery:     ImageVector get() = Icons.Outlined.Image
    val Photo:       ImageVector get() = Icons.Outlined.Photo
    val Theme:       ImageVector get() = Icons.Outlined.NightlightRound
    val More:        ImageVector get() = Icons.Outlined.MoreHoriz
    val Sparkle:     ImageVector get() = Icons.Filled.AutoAwesome
}

/**
 * Wrapper estilizado para garantizar consistencia visual (tamaño, color)
 * en todos los iconos de la barra de chips iOS 19.
 */
@Composable
fun LensIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    tint: Color = Color.White,
    size: Dp = 20.dp,
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}
