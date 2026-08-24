package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.AppTab
import com.example.ui.TranscribeViewModel
import com.example.ui.components.CaptchaResolutionDialog
import com.example.ui.components.TopHeader
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.LiveTranscribeScreen
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryBlueContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun MainAppScreen(
    viewModel: TranscribeViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentTab by viewModel.currentTab.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val transcriptText by viewModel.transcriptText.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val speechState by viewModel.speechState.collectAsState()
    val rmsVolume by viewModel.rmsVolume.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val engineStatus by viewModel.googleEngineStatus.collectAsState()
    val isCaptchaShowing by viewModel.googleVoiceEngine.isCaptchaShowing.collectAsState()
    val savedTranscripts by viewModel.savedTranscripts.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val isAiProcessing by viewModel.isAiProcessing.collectAsState()

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasAudioPermission = isGranted
            if (isGranted) {
                viewModel.toggleRecording()
            } else {
                Toast.makeText(context, "Microphone permission is required for voice transcription.", Toast.LENGTH_SHORT).show()
            }
        }
    )

    LaunchedEffect(uiMessage) {
        uiMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg.message,
                duration = SnackbarDuration.Short
            )
            viewModel.clearUiMessage()
        }
    }

    // Interactive Security/Captcha dialog when Google prompts a verification
    if (isCaptchaShowing) {
        CaptchaResolutionDialog(
            webView = viewModel.googleVoiceEngine.webView,
            onSolved = {
                viewModel.googleVoiceEngine.dismissCaptchaSolved()
            },
            onReload = {
                viewModel.googleVoiceEngine.reloadEngine()
            },
            onDismiss = {
                viewModel.googleVoiceEngine.dismissCaptchaSolved()
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopHeader(
                selectedLanguage = selectedLanguage,
                languages = viewModel.availableLanguages,
                onSelectLanguage = { viewModel.selectLanguage(it) }
            )
        },
        bottomBar = {
            AppBottomNavigationBar(
                currentTab = currentTab,
                onSelectTab = { viewModel.selectTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(
                targetState = currentTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ScreenNavigation"
            ) { tab ->
                when (tab) {
                    AppTab.TRANSCRIBE -> {
                        LiveTranscribeScreen(
                            transcriptText = transcriptText,
                            onTranscriptTextChange = { viewModel.updateTranscriptText(it) },
                            isRecording = isRecording,
                            speechState = speechState,
                            rmsVolume = rmsVolume,
                            elapsedSeconds = elapsedSeconds,
                            engineStatus = engineStatus,
                            onToggleRecording = {
                                if (hasAudioPermission) {
                                    viewModel.toggleRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onClearTranscript = { viewModel.clearTranscript() },
                            onCopyTranscript = { viewModel.copyToClipboard() },
                            onShareTranscript = { viewModel.shareTranscript() },
                            onSaveTranscript = { viewModel.saveTranscriptToHistory() },
                            onAiFormatTranscript = { viewModel.formatCurrentTranscript() },
                            isAiProcessing = isAiProcessing,
                            formatTime = { viewModel.formatTime(it) }
                        )
                    }
                    AppTab.HISTORY -> {
                        HistoryScreen(
                            savedTranscripts = savedTranscripts,
                            onLoadTranscript = { viewModel.loadTranscriptIntoEditor(it) },
                            onDeleteTranscript = { viewModel.deleteSavedTranscript(it) },
                            onCopyText = { viewModel.copyToClipboard(it) },
                            onShareText = { viewModel.shareTranscript(it) },
                            onAiFormatTranscript = { viewModel.formatHistoryTranscript(it) },
                            onAiSummarizeTranscript = { viewModel.summarizeHistoryTranscript(it) },
                            onRenameTitle = { entity, newTitle -> viewModel.updateTranscriptTitle(entity, newTitle) },
                            isAiProcessing = isAiProcessing,
                            formatTime = { viewModel.formatTime(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppBottomNavigationBar(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(
                icon = Icons.Default.Mic,
                label = "Speech to Text",
                isSelected = currentTab == AppTab.TRANSCRIBE,
                onClick = { onSelectTab(AppTab.TRANSCRIBE) },
                testTag = "nav_transcribe"
            )

            NavBarItem(
                icon = Icons.Default.History,
                label = "History",
                isSelected = currentTab == AppTab.HISTORY,
                onClick = { onSelectTab(AppTab.HISTORY) },
                testTag = "nav_history"
            )
        }
    }
}

@Composable
private fun NavBarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val activeColor = GoogleBlue
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = if (isSelected) PrimaryBlueContainer.copy(alpha = 0.4f) else Color.Transparent,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )

            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) activeColor else inactiveColor
            )
        }
    }
}
