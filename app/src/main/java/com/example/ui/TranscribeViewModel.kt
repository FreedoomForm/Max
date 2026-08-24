package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.TranscriptEntity
import com.example.scraper.GoogleSearchAiVoiceEngine
import com.example.scraper.GoogleVoiceState
import com.example.speech.ContinuousSpeechManager
import com.example.speech.SpeechSegment
import com.example.speech.SpeechState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LanguageOption(
    val name: String,
    val code: String
)

enum class AppTab {
    TRANSCRIBE,
    HISTORY
}

data class UiMessage(
    val id: Long = System.currentTimeMillis(),
    val message: String
)

class TranscribeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val transcriptDao = database.transcriptDao()
    private val speechManager = ContinuousSpeechManager(application)

    val availableLanguages = listOf(
        LanguageOption("Auto Detect", "default"),
        LanguageOption("English (US)", "en-US"),
        LanguageOption("O'zbekcha (Uzbek)", "uz-UZ"),
        LanguageOption("Русский (Russian)", "ru-RU"),
        LanguageOption("Español (Spanish)", "es-ES"),
        LanguageOption("Deutsch (German)", "de-DE"),
        LanguageOption("Français (French)", "fr-FR")
    )

    private val _selectedLanguage = MutableStateFlow(availableLanguages[0])
    val selectedLanguage: StateFlow<LanguageOption> = _selectedLanguage.asStateFlow()

    private val _currentTab = MutableStateFlow(AppTab.TRANSCRIBE)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    // Confirmed text accumulated so far
    private var confirmedTextBuffer: String = ""

    // Main editable speech transcript text shown in UI
    private val _transcriptText = MutableStateFlow("")
    val transcriptText: StateFlow<String> = _transcriptText.asStateFlow()

    // Live interim text while speaking (real-time streaming preview)
    private val _liveInterimText = MutableStateFlow("")
    val liveInterimText: StateFlow<String> = _liveInterimText.asStateFlow()

    val speechState: StateFlow<SpeechState> = speechManager.speechState
    val rmsVolume: StateFlow<Float> = speechManager.rmsVolume

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    // Google Search AI Mode (udm=50) Voice Engine with auto-reconnect
    val googleVoiceEngine = GoogleSearchAiVoiceEngine(
        context = application,
        coroutineScope = viewModelScope,
        onChunkFinalized = { finalizedChunk ->
            handleFinalizedChunk(finalizedChunk)
        },
        onInterimText = { interim ->
            if (_isRecording.value) {
                _liveInterimText.value = interim
                syncDisplayTranscript()
            }
        }
    )

    val googleVoiceState = googleVoiceEngine.voiceState
    val googleEngineStatus = googleVoiceEngine.engineStatus

    val savedTranscripts: StateFlow<List<TranscriptEntity>> = transcriptDao.getAllTranscripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var timerJob: Job? = null

    init {
        // Collect real-time speech segments from native recognizer to give instant word-by-word streaming
        viewModelScope.launch {
            speechManager.partialText.collect { partial ->
                if (_isRecording.value) {
                    _liveInterimText.value = partial
                    syncDisplayTranscript()
                }
            }
        }

        viewModelScope.launch {
            speechManager.confirmedSegments.collect { segments ->
                if (_isRecording.value && segments.isNotEmpty()) {
                    val lastSegment = segments.lastOrNull()?.text?.trim()
                    if (!lastSegment.isNullOrBlank()) {
                        handleFinalizedChunk(lastSegment)
                    }
                }
            }
        }
    }

    private fun handleFinalizedChunk(chunk: String) {
        val clean = chunk.trim()
        if (clean.isBlank()) return

        if (confirmedTextBuffer.isEmpty()) {
            confirmedTextBuffer = clean
        } else {
            // Avoid duplicate appends if both engines emit the same phrase
            if (!confirmedTextBuffer.endsWith(clean, ignoreCase = true)) {
                confirmedTextBuffer = "$confirmedTextBuffer $clean"
            }
        }
        _liveInterimText.value = ""
        _transcriptText.value = confirmedTextBuffer
    }

    private fun syncDisplayTranscript() {
        val interim = _liveInterimText.value.trim()
        if (interim.isBlank()) {
            _transcriptText.value = confirmedTextBuffer
        } else {
            _transcriptText.value = if (confirmedTextBuffer.isBlank()) interim else "$confirmedTextBuffer $interim"
        }
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun selectLanguage(language: LanguageOption) {
        _selectedLanguage.value = language
        if (_isRecording.value) {
            speechManager.startListening(language.code)
        }
    }

    fun updateTranscriptText(newText: String) {
        _transcriptText.value = newText
        confirmedTextBuffer = newText
        _liveInterimText.value = ""
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        _isRecording.value = true
        confirmedTextBuffer = _transcriptText.value.trim()
        _liveInterimText.value = ""
        startTimer()

        // 1. Start Google Search AI Mode (udm=50) engine with auto-reconnect watchdog
        googleVoiceEngine.startVoiceRecognition()

        // 2. Start local live streaming engine for instant zero-latency word-by-word streaming
        speechManager.startListening(_selectedLanguage.value.code)

        showMessage("Google Voice is active. Speak freely!")
    }

    private fun stopRecording() {
        _isRecording.value = false
        stopTimer()
        googleVoiceEngine.stopVoiceRecognition()
        speechManager.stopListening()

        // Commit any remaining live words into confirmed buffer
        val interim = _liveInterimText.value.trim()
        if (interim.isNotBlank()) {
            if (confirmedTextBuffer.isBlank()) {
                confirmedTextBuffer = interim
            } else if (!confirmedTextBuffer.endsWith(interim, ignoreCase = true)) {
                confirmedTextBuffer = "$confirmedTextBuffer $interim"
            }
        }
        _liveInterimText.value = ""
        _transcriptText.value = confirmedTextBuffer
        showMessage("Voice recording finished")
    }

    fun saveTranscriptToHistory(customTitle: String? = null) {
        val text = _transcriptText.value.trim()
        if (text.isBlank()) {
            showMessage("No text to save. Please speak or type first.")
            return
        }

        viewModelScope.launch {
            val title = customTitle?.takeIf { it.isNotBlank() } ?: generateDefaultTitle(text)
            val wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() }
            val entity = TranscriptEntity(
                title = title,
                rawContent = text,
                formattedContent = null,
                summary = null,
                durationSeconds = _elapsedSeconds.value,
                languageCode = _selectedLanguage.value.code,
                wordCount = wordCount
            )
            transcriptDao.insertTranscript(entity)
            showMessage("Saved to History!")
        }
    }

    fun clearTranscript() {
        if (_isRecording.value) {
            stopRecording()
        }
        confirmedTextBuffer = ""
        _liveInterimText.value = ""
        _transcriptText.value = ""
        speechManager.clearTranscript()
        _elapsedSeconds.value = 0L
        showMessage("Cleared text")
    }

    fun loadTranscriptIntoEditor(transcript: TranscriptEntity) {
        if (_isRecording.value) {
            stopRecording()
        }
        confirmedTextBuffer = transcript.rawContent
        _liveInterimText.value = ""
        _transcriptText.value = transcript.rawContent
        _elapsedSeconds.value = transcript.durationSeconds
        _currentTab.value = AppTab.TRANSCRIBE
        showMessage("Loaded '${transcript.title}' into editor")
    }

    fun deleteSavedTranscript(transcript: TranscriptEntity) {
        viewModelScope.launch {
            transcriptDao.deleteTranscript(transcript)
            showMessage("Deleted transcript")
        }
    }

    fun copyToClipboard() {
        val text = _transcriptText.value.trim()
        if (text.isBlank()) {
            showMessage("No text to copy yet!")
            return
        }

        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Google AI Voice to Text", text)
        clipboard.setPrimaryClip(clip)
        showMessage("Copied text to clipboard!")
    }

    fun shareTranscript() {
        val text = _transcriptText.value.trim()
        if (text.isBlank()) {
            showMessage("No text to share yet!")
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Voice Transcript (${formatTime(_elapsedSeconds.value)})")
            putExtra(Intent.EXTRA_TEXT, text)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(intent, "Share Voice Transcript").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    fun clearUiMessage() {
        _uiMessage.value = null
    }

    private fun showMessage(msg: String) {
        _uiMessage.value = UiMessage(message = msg)
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value += 1
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun generateDefaultTitle(text: String): String {
        val words = text.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val titleSnippet = if (words.isNotEmpty()) {
            words.take(5).joinToString(" ")
        } else {
            "Voice Note"
        }
        val dateStr = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())
        return "$titleSnippet ($dateStr)"
    }

    fun formatTime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, secs)
        }
    }
}
