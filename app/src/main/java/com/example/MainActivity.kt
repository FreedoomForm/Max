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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.ui.components.TopHeader
import com.example.ui.screens.AiSearchScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.LiveTranscribeScreen
import com.example.ui.theme.GoogleBlue
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryBlueContainer
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val currentTab by viewModel.currentTab.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val speechState by viewModel.speechState.collectAsState()
    val confirmedSegments by viewModel.confirmedSegments.collectAsState()
    val partialText by viewModel.partialText.collectAsState()
    val rmsVolume by viewModel.rmsVolume.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val formattedAiText by viewModel.formattedAiText.collectAsState()
    val aiSummary by viewModel.aiSummary.collectAsState()
    val isAiLoading by viewModel.isAiLoading.collectAsState()
    val savedTranscripts by viewModel.savedTranscripts.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()

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
                            speechState = speechState,
                            confirmedSegments = confirmedSegments,
                            partialText = partialText,
                            rmsVolume = rmsVolume,
                            elapsedSeconds = elapsedSeconds,
                            formattedAiText = formattedAiText,
                            aiSummary = aiSummary,
                            isAiLoading = isAiLoading,
                            onToggleRecording = {
                                if (hasAudioPermission) {
                                    viewModel.toggleRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onClearTranscript = { viewModel.clearCurrentTranscript() },
                            onCopyTranscript = { viewModel.copyToClipboard(it) },
                            onShareTranscript = { viewModel.shareTranscript(it) },
                            onTriggerAiFormat = { viewModel.triggerAiFormat() },
                            onTriggerAiSummary = { viewModel.triggerAiSummary() },
                            onSearchGoogleAi = { viewModel.searchCurrentTranscriptInGoogleAi() },
                            onSaveTranscript = { viewModel.stopAndSaveRecording() },
                            onSimulateDictation = { viewModel.simulateLongDictation() },
                            formatTime = { viewModel.formatTime(it) }
                        )
                    }
                    AppTab.AI_SEARCH -> {
                        AiSearchScreen(
                            scraper = viewModel.googleScraper,
                            currentTranscriptText = viewModel.getFullTranscriptText(),
                            onCopyText = { viewModel.copyToClipboard(it) },
                            onShareText = { viewModel.shareTranscript(it) }
                        )
                    }
                    AppTab.HISTORY -> {
                        HistoryScreen(
                            savedTranscripts = savedTranscripts,
                            onLoadTranscript = { viewModel.loadTranscriptIntoEditor(it) },
                            onDeleteTranscript = { viewModel.deleteSavedTranscript(it) },
                            onCopyText = { viewModel.copyToClipboard(it) },
                            onShareText = { viewModel.shareTranscript(it) },
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
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavBarItem(
                icon = Icons.Default.Mic,
                label = "Transcribe",
                isSelected = currentTab == AppTab.TRANSCRIBE,
                onClick = { onSelectTab(AppTab.TRANSCRIBE) },
                testTag = "nav_transcribe"
            )

            NavBarItem(
                icon = Icons.Default.AutoAwesome,
                label = "Google AI Search",
                isSelected = currentTab == AppTab.AI_SEARCH,
                onClick = { onSelectTab(AppTab.AI_SEARCH) },
                testTag = "nav_ai_search"
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
fun NavBarItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val activeColor = PrimaryBlue
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) PrimaryBlueContainer else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) activeColor else inactiveColor,
            fontSize = 11.sp
        )
    }
}
