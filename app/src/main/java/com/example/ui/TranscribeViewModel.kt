package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAiService
import com.example.data.AppDatabase
import com.example.data.TranscriptEntity
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

enum class VoiceEngineMode(val title: String, val description: String) {
    GOOGLE_AI_SEARCH("Google AI Mode", "udm=50 Headless Browser Engine"),
    CONTINUOUS_NATIVE("Continuous Dictation", "Android Speech Engine")
}

enum class AppTab {
    TRANSCRIBE,
    HISTORY,
    AI_SEARCH
}

data class UiMessage(
    val id: Long = System.currentTimeMillis(),
    val message: String
)

class TranscribeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val transcriptDao = database.transcriptDao()
    private val speechManager = ContinuousSpeechManager(application)
    private val aiService = GeminiAiService()
    val googleScraper = com.example.scraper.GoogleSearchAiScraper(application, viewModelScope)

    val scraperState = googleScraper.scraperState
    val scraperLogs = googleScraper.scraperLogs

    val availableLanguages = listOf(
        LanguageOption("Auto Detect", "default"),
        LanguageOption("English (US)", "en-US"),
        LanguageOption("Русский (Russian)", "ru-RU"),
        LanguageOption("O'zbekcha (Uzbek)", "uz-UZ"),
        LanguageOption("Español (Spanish)", "es-ES"),
        LanguageOption("Deutsch (German)", "de-DE"),
        LanguageOption("Français (French)", "fr-FR")
    )

    private val _selectedLanguage = MutableStateFlow(availableLanguages[0])
    val selectedLanguage: StateFlow<LanguageOption> = _selectedLanguage.asStateFlow()

    private val _engineMode = MutableStateFlow(VoiceEngineMode.GOOGLE_AI_SEARCH)
    val engineMode: StateFlow<VoiceEngineMode> = _engineMode.asStateFlow()

    private val _currentTab = MutableStateFlow(AppTab.TRANSCRIBE)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    val speechState: StateFlow<SpeechState> = speechManager.speechState
    val confirmedSegments: StateFlow<List<SpeechSegment>> = speechManager.confirmedSegments
    val partialText: StateFlow<String> = speechManager.partialText
    val rmsVolume: StateFlow<Float> = speechManager.rmsVolume

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _formattedAiText = MutableStateFlow<String?>(null)
    val formattedAiText: StateFlow<String?> = _formattedAiText.asStateFlow()

    private val _aiSummary = MutableStateFlow<String?>(null)
    val aiSummary: StateFlow<String?> = _aiSummary.asStateFlow()

    private val _isAiLoading = MutableStateFlow(false)
    val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

    private val _uiMessage = MutableStateFlow<UiMessage?>(null)
    val uiMessage: StateFlow<UiMessage?> = _uiMessage.asStateFlow()

    private val _aiSearchQuery = MutableStateFlow("")
    val aiSearchQuery: StateFlow<String> = _aiSearchQuery.asStateFlow()

    private val _aiSearchAnswer = MutableStateFlow<String?>(null)
    val aiSearchAnswer: StateFlow<String?> = _aiSearchAnswer.asStateFlow()

    private val _isAiSearching = MutableStateFlow(false)
    val isAiSearching: StateFlow<Boolean> = _isAiSearching.asStateFlow()

    val savedTranscripts: StateFlow<List<TranscriptEntity>> = transcriptDao.getAllTranscripts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var timerJob: Job? = null
    private var simulationJob: Job? = null

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun selectLanguage(language: LanguageOption) {
        _selectedLanguage.value = language
        if (speechState.value is SpeechState.Listening) {
            speechManager.startListening(language.code)
        }
    }

    fun setEngineMode(mode: VoiceEngineMode) {
        _engineMode.value = mode
        showMessage("Switched to ${mode.title}")
    }

    fun toggleRecording() {
        if (_engineMode.value == VoiceEngineMode.GOOGLE_AI_SEARCH) {
            // Headless Google Search AI Mode Engine
            val curText = getFullTranscriptText().trim()
            if (curText.isNotBlank()) {
                googleScraper.searchAndScrapeAiMode(curText)
                selectTab(AppTab.AI_SEARCH)
            } else {
                googleScraper.triggerGoogleVoiceMicInBrowser()
                selectTab(AppTab.AI_SEARCH)
            }
        } else {
            // Native Continuous Speech Engine
            when (speechState.value) {
                is SpeechState.Listening -> {
                    speechManager.pauseListening()
                    stopTimer()
                }
                is SpeechState.Paused -> {
                    speechManager.resumeListening()
                    startTimer()
                }
                else -> {
                    speechManager.startListening(_selectedLanguage.value.code)
                    startTimer()
                }
            }
        }
    }

    fun stopAndSaveRecording(title: String? = null) {
        speechManager.stopListening()
        stopTimer()
        val text = getFullTranscriptText().trim()
        if (text.isNotBlank()) {
            viewModelScope.launch {
                val finalTitle = title ?: generateDefaultTitle(text)
                val wordCount = text.split("\\s+".toRegex()).count { it.isNotBlank() }
                val entity = TranscriptEntity(
                    title = finalTitle,
                    rawContent = text,
                    formattedContent = _formattedAiText.value,
                    summary = _aiSummary.value,
                    durationSeconds = _elapsedSeconds.value,
                    languageCode = _selectedLanguage.value.code,
                    wordCount = wordCount
                )
                transcriptDao.insertTranscript(entity)
                showMessage("Transcript saved to History!")
            }
        }
    }

    fun clearCurrentTranscript() {
        simulationJob?.cancel()
        speechManager.stopListening()
        speechManager.clearTranscript()
        stopTimer()
        _elapsedSeconds.value = 0L
        _formattedAiText.value = null
        _aiSummary.value = null
        _aiSearchAnswer.value = null
        _aiSearchQuery.value = ""
    }

    fun loadTranscriptIntoEditor(transcript: TranscriptEntity) {
        clearCurrentTranscript()
        speechManager.setTranscript(transcript.rawContent)
        _formattedAiText.value = transcript.formattedContent
        _aiSummary.value = transcript.summary
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

    fun copyToClipboard(customText: String? = null) {
        val textToCopy = customText ?: _formattedAiText.value ?: getFullTranscriptText()
        if (textToCopy.isBlank()) {
            showMessage("No text to copy yet!")
            return
        }

        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AI Transcribe Voice Notes", textToCopy)
        clipboard.setPrimaryClip(clip)
        showMessage("Copied ${textToCopy.length} characters to clipboard!")
    }

    fun shareTranscript(customText: String? = null) {
        val textToShare = customText ?: _formattedAiText.value ?: getFullTranscriptText()
        if (textToShare.isBlank()) {
            showMessage("No text to share yet!")
            return
        }

        val context = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Voice Transcript (${formatTime(_elapsedSeconds.value)})")
            putExtra(Intent.EXTRA_TEXT, textToShare)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val chooser = Intent.createChooser(intent, "Share Voice Transcript").apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(chooser)
    }

    fun triggerAiFormat() {
        val raw = getFullTranscriptText().trim()
        if (raw.isBlank()) {
            showMessage("Please speak or record some text first.")
            return
        }

        viewModelScope.launch {
            _isAiLoading.value = true
            val result = aiService.formatAndPunctuate(raw)
            _isAiLoading.value = false

            result.onSuccess { formatted ->
                _formattedAiText.value = formatted
                showMessage("AI Formatting applied successfully!")
            }.onFailure { err ->
                showMessage("Formatting error: ${err.localizedMessage}")
            }
        }
    }

    fun triggerAiSummary() {
        val raw = _formattedAiText.value ?: getFullTranscriptText().trim()
        if (raw.isBlank()) {
            showMessage("Please speak or record some text first.")
            return
        }

        viewModelScope.launch {
            _isAiLoading.value = true
            val result = aiService.generateSummary(raw)
            _isAiLoading.value = false

            result.onSuccess { summary ->
                _aiSummary.value = summary
                showMessage("AI Summary generated!")
            }.onFailure { err ->
                showMessage("Summary error: ${err.localizedMessage}")
            }
        }
    }

    fun setAiSearchQuery(query: String) {
        _aiSearchQuery.value = query
    }

    fun executeAiSearch() {
        val query = _aiSearchQuery.value.trim()
        val raw = _formattedAiText.value ?: getFullTranscriptText().trim()
        if (query.isBlank()) return
        if (raw.isBlank()) {
            showMessage("No speech transcript available to search through.")
            return
        }

        viewModelScope.launch {
            _isAiSearching.value = true
            val result = aiService.askQuestionAboutTranscript(raw, query)
            _isAiSearching.value = false

            result.onSuccess { answer ->
                _aiSearchAnswer.value = answer
            }.onFailure { err ->
                showMessage("Search error: ${err.localizedMessage}")
            }
        }
    }

    fun simulateLongDictation() {
        simulationJob?.cancel()
        speechManager.clearTranscript()
        startTimer()

        val samplePhrases = listOf(
            "Hello everyone, welcome to our architecture and planning session.",
            "Today we are discussing continuous speech recognition using Google AI mode design.",
            "When users speak for 30 minutes or more, the system seamlessly buffers audio and streams text in real time.",
            "Key advantages include instant copy to clipboard, automatic punctuation formatting, and structured paragraphs.",
            "The vibrant palette theme brings Google Blue, Red, Yellow, and Green glowing visual accents to life.",
            "All transcripts are persisted safely in the local Room database with full timestamp history and word metrics."
        )

        simulationJob = viewModelScope.launch {
            for (phrase in samplePhrases) {
                delay(1200)
                val current = confirmedSegments.value.toMutableList()
                current.add(SpeechSegment(timestampSeconds = _elapsedSeconds.value, text = phrase))
                speechManager.setTranscript(current.joinToString(" ") { it.text })
            }
            showMessage("Simulated conversation loaded! You can now copy or format with AI.")
        }
    }

    fun getFullTranscriptText(): String {
        return speechManager.getFullText()
    }

    fun getWordCount(): Int {
        val text = getFullTranscriptText().trim()
        if (text.isBlank()) return 0
        return text.split("\\s+".toRegex()).count { it.isNotBlank() }
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
            "Voice Recording"
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

    fun searchGoogleAiMode(query: String) {
        googleScraper.searchAndScrapeAiMode(query)
    }

    fun triggerGoogleVoiceMicScraper() {
        googleScraper.triggerGoogleVoiceMicInBrowser()
    }

    fun searchCurrentTranscriptInGoogleAi() {
        val transcript = getFullTranscriptText().trim()
        if (transcript.isNotBlank()) {
            val query = transcript.take(120)
            googleScraper.searchAndScrapeAiMode(query)
            selectTab(AppTab.AI_SEARCH)
        } else {
            showMessage("No voice transcript yet. Speak or type a query first!")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimer()
        simulationJob?.cancel()
        speechManager.stopListening()
        googleScraper.cleanup()
    }
}
