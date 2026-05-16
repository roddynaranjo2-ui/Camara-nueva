package com.rodyto.lenspro

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun ShutterBlinkOverlay(triggerKey: Int) {

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(triggerKey) {

        if (triggerKey <= 0) return@LaunchedEffect

        alpha.snapTo(0f)

        alpha.animateTo(
            targetValue = 0.18f,
            animationSpec = tween(
                durationMillis = 70,
                easing = FastOutSlowInEasing
            )
        )

        alpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = 120,
                easing = FastOutSlowInEasing
            )
        )
    }

    if (alpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                }
                .background(Color.White)
        )
    }
}