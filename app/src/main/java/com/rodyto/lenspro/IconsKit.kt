package com.rodyto.lenspro

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Exposure
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.LensBlur
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object LensIcons {
    val Settings: ImageVector get() = Icons.Rounded.Settings
    val FlashOn: ImageVector get() = Icons.Rounded.FlashOn
    val FlashOff: ImageVector get() = Icons.Rounded.FlashOff
    val Hdr: ImageVector get() = Icons.Rounded.HdrAuto
    val Grid: ImageVector get() = Icons.Rounded.GridOn
    val SoundOn: ImageVector get() = Icons.Rounded.VolumeUp
    val SoundOff: ImageVector get() = Icons.Rounded.VolumeOff
    val Timer: ImageVector get() = Icons.Rounded.Timer
    val TimerOff: ImageVector get() = Icons.Rounded.TimerOff
    val Aspect: ImageVector get() = Icons.Rounded.LensBlur
    val Exposure: ImageVector get() = Icons.Rounded.Exposure
    val Flip: ImageVector get() = Icons.Rounded.Cameraswitch
    val Gallery: ImageVector get() = Icons.Rounded.Collections
    val Theme: ImageVector get() = Icons.Rounded.Palette
    val More: ImageVector get() = Icons.Rounded.Tune
    val Sparkle: ImageVector get() = Icons.Rounded.AutoAwesome
    val Brightness: ImageVector get() = Icons.Rounded.WbSunny
}

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
