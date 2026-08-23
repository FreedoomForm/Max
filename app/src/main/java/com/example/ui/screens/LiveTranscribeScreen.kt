package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.speech.SpeechSegment
import com.example.speech.SpeechState
import com.example.ui.components.AudioWaveVisualizer
import com.example.ui.components.GoogleMicButton
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.GoogleGreen
import com.example.ui.theme.GoogleRed
import com.example.ui.theme.GoogleYellow
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryBlueContainer

@Composable
fun LiveTranscribeScreen(
    speechState: SpeechState,
    confirmedSegments: List<SpeechSegment>,
    partialText: String,
    rmsVolume: Float,
    elapsedSeconds: Long,
    formattedAiText: String?,
    aiSummary: String?,
    isAiLoading: Boolean,
    onToggleRecording: () -> Unit,
    onClearTranscript: () -> Unit,
    onCopyTranscript: (String) -> Unit,
    onShareTranscript: (String) -> Unit,
    onTriggerAiFormat: () -> Unit,
    onTriggerAiSummary: () -> Unit,
    onSearchGoogleAi: () -> Unit = {},
    onSaveTranscript: () -> Unit,
    onSimulateDictation: () -> Unit,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    val isListening = speechState is SpeechState.Listening
    val isPaused = speechState is SpeechState.Paused

    val fullRawText = buildString {
        if (confirmedSegments.isNotEmpty()) {
            append(confirmedSegments.joinToString(" ") { it.text })
        }
        if (partialText.isNotEmpty()) {
            if (isNotEmpty()) append(" ")
            append(partialText)
        }
    }

    val wordCount = if (fullRawText.isBlank()) 0 else fullRawText.trim().split("\\s+".toRegex()).size

    LaunchedEffect(confirmedSegments.size, partialText) {
        if (isListening && fullRawText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Main Transcription Container Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 6.dp)
                .testTag("transcription_container_card"),
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            shadowElevation = 1.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header inside the card: Badge, Time, and Actions (Copy, Share, Clear, Save)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Badge with Pulsing Wave Indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isListening) PrimaryBlueContainer
                                    else if (isPaused) Color(0xFFFEF7E0)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = when {
                                    isListening -> "LIVE RECORDING"
                                    isPaused -> "PAUSED"
                                    formattedAiText != null -> "AI FORMATTED"
                                    fullRawText.isNotEmpty() -> "TRANSCRIPTION"
                                    else -> "VOICE TO TEXT"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isListening -> PrimaryBlue
                                    isPaused -> Color(0xFFB06000)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                letterSpacing = 0.8.sp
                            )
                        }

                        if (isListening) {
                            AudioWaveVisualizer(
                                isListening = true,
                                volumeLevel = rmsVolume,
                                modifier = Modifier.height(18.dp)
                            )
                        }
                    }

                    // Card Action Icons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (fullRawText.isNotEmpty()) {
                            IconButton(
                                onClick = { onCopyTranscript(formattedAiText ?: fullRawText) },
                                modifier = Modifier.size(34.dp).testTag("copy_all_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy all",
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { onShareTranscript(formattedAiText ?: fullRawText) },
                                modifier = Modifier.size(34.dp).testTag("share_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onSaveTranscript,
                                modifier = Modifier.size(34.dp).testTag("save_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = "Save to History",
                                    tint = GoogleGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = onClearTranscript,
                                modifier = Modifier.size(34.dp).testTag("clear_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Transcription Content Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (isAiLoading) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                color = PrimaryBlue,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Formatting with Google AI...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (fullRawText.isEmpty() && formattedAiText == null) {
                        // Empty State Guide
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.sweepGradient(
                                            listOf(
                                                GoogleBlue.copy(alpha = 0.25f),
                                                GoogleRed.copy(alpha = 0.25f),
                                                GoogleYellow.copy(alpha = 0.25f),
                                                GoogleGreen.copy(alpha = 0.25f),
                                                GoogleBlue.copy(alpha = 0.25f)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(30.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Continuous Long-Speech Dictation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Tap the Google microphone button below and talk for 30+ minutes. Your speech will stream live into text with automatic reconnection and one-tap instant copy.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = onSimulateDictation,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = PrimaryBlue
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.testTag("simulate_demo_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Load Sample Dictation",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .testTag("transcription_scroll_content")
                        ) {
                            if (formattedAiText != null) {
                                Text(
                                    text = formattedAiText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 26.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            } else {
                                confirmedSegments.forEach { segment ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = formatTime(segment.timestampSeconds),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                        Text(
                                            text = segment.text,
                                            style = MaterialTheme.typography.bodyLarge,
                                            lineHeight = 24.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                if (partialText.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = formatTime(elapsedSeconds),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = PrimaryBlue,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                        Text(
                                            text = partialText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            lineHeight = 24.sp,
                                            color = PrimaryBlue
                                        )
                                    }
                                }
                            }

                            if (aiSummary != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(14.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = GoogleBlue,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "AI EXECUTIVE SUMMARY",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = GoogleBlue,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = aiSummary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            lineHeight = 22.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Gradient Fade inside container
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                    )
                                )
                            )
                    )
                }
            }
        }

        // Metrics & AI Action Pills Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timer & Word count metrics
            Column {
                Text(
                    text = formatTime(elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isListening) PrimaryBlue else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$wordCount words • ${if (isListening) "REC" else if (isPaused) "PAUSED" else "READY"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // AI Action Quick Pills
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // AI Format Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = fullRawText.isNotEmpty() && !isAiLoading) {
                            onTriggerAiFormat()
                        }
                        .testTag("ai_format_button"),
                    shape = RoundedCornerShape(16.dp),
                    color = if (formattedAiText != null) PrimaryBlueContainer else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (formattedAiText != null) PrimaryBlue else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Format",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "AI Format",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                }

                // AI Summary Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = fullRawText.isNotEmpty() && !isAiLoading) {
                            onTriggerAiSummary()
                        }
                        .testTag("ai_summary_button"),
                    shape = RoundedCornerShape(16.dp),
                    color = if (aiSummary != null) Color(0xFFE6F4EA) else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (aiSummary != null) GoogleGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ShortText,
                            contentDescription = "Summary",
                            tint = GoogleGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Summary",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = GoogleGreen
                        )
                    }
                }

                // Search Google AI Mode Button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = fullRawText.isNotEmpty()) {
                            onSearchGoogleAi()
                        }
                        .testTag("search_google_ai_button"),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        GoogleYellow.copy(alpha = 0.8f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search Google AI",
                            tint = Color(0xFFEA8600),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Search AI",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEA8600)
                        )
                    }
                }
            }
        }

        // Bottom Section: Google Glowing Microphone Button
        Box(
            modifier = Modifier
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            GoogleMicButton(
                speechState = speechState,
                volumeLevel = rmsVolume,
                onClick = onToggleRecording
            )
        }

        Text(
            text = when {
                isListening -> "Listening... Tap to pause"
                isPaused -> "Recording paused. Tap to resume"
                else -> "Tap Google Mic to speak"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}
