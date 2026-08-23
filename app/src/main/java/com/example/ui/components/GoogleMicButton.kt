package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.speech.SpeechState
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.GoogleGreen
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleYellow

@Composable
fun GoogleMicButton(
    speechState: SpeechState,
    volumeLevel: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = speechState is SpeechState.Listening
    val isPaused = speechState is SpeechState.Paused

    val infiniteTransition = rememberInfiniteTransition(label = "GoogleMicGlow")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
    )

    val googleGradient = Brush.linearGradient(
        colors = listOf(
            GoogleBlue,
            GoogleRed,
            GoogleYellow,
            GoogleGreen,
            GoogleBlue
        ),
        start = Offset(gradientOffset, 0f),
        end = Offset(gradientOffset + 500f, 500f)
    )

    Box(
        modifier = modifier
            .size(110.dp)
            .testTag("google_mic_button_container"),
        contentAlignment = Alignment.Center
    ) {
        // Multi-colored Glowing Aura (Google 4-color palette)
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(if (isListening) pulseScale + (volumeLevel * 0.3f) else 1f)
                .clip(CircleShape)
                .background(googleGradient)
                .blur(20.dp)
        )

        // Additional Outer Pulse Ring when listening
        if (isListening) {
            Box(
                modifier = Modifier
                    .size(105.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .border(2.dp, googleGradient, CircleShape)
            )
        }

        // White Center Container Button
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, radius = 44.dp),
                    onClick = onClick
                )
                .testTag("google_mic_button"),
            contentAlignment = Alignment.Center
        ) {
            // Subtle internal gradient tint
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .alpha(pulseAlpha * 0.2f)
                    .background(googleGradient)
            )

            // Inner Dark Circle
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) Color(0xFF1F1F1F)
                        else if (isPaused) Color(0xFF444746)
                        else Color(0xFF1F1F1F)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isListening -> Icons.Default.Mic
                        isPaused -> Icons.Default.Pause
                        else -> Icons.Default.Mic
                    },
                    contentDescription = if (isListening) "Pause recording" else "Start recording",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
