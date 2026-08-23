package com.example.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class SpeechSegment(
    val timestampSeconds: Long,
    val text: String
)

sealed class SpeechState {
    object Idle : SpeechState()
    object Listening : SpeechState()
    object Paused : SpeechState()
    data class Error(val message: String) : SpeechState()
}

class ContinuousSpeechManager(private val context: Context) {
    private val tag = "ContinuousSpeech"
    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private val _confirmedSegments = MutableStateFlow<List<SpeechSegment>>(emptyList())
    val confirmedSegments: StateFlow<List<SpeechSegment>> = _confirmedSegments.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsVolume = MutableStateFlow(0f)
    val rmsVolume: StateFlow<Float> = _rmsVolume.asStateFlow()

    private var isSessionActive = false
    private var isPaused = false
    private var currentLanguageCode: String = "default"
    private var sessionStartTime = 0L

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(tag, "onReadyForSpeech")
            if (isSessionActive && !isPaused) {
                _speechState.value = SpeechState.Listening
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(tag, "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            _rmsVolume.value = normalized
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(tag, "onEndOfSpeech")
            _rmsVolume.value = 0f
        }

        override fun onError(error: Int) {
            Log.w(tag, "SpeechRecognizer error: $error")
            _rmsVolume.value = 0f

            if (!isSessionActive || isPaused) return

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _speechState.value = SpeechState.Error("Audio recording permission missing.")
                    isSessionActive = false
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    scheduleRestart(250)
                }
                SpeechRecognizer.ERROR_NETWORK -> {
                    scheduleRestart(1000)
                }
                else -> {
                    scheduleRestart(500)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            _rmsVolume.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val newText = matches?.firstOrNull()?.trim()

            if (!newText.isNullOrEmpty()) {
                val elapsed = getElapsedSeconds()
                val currentList = _confirmedSegments.value.toMutableList()
                currentList.add(SpeechSegment(timestampSeconds = elapsed, text = newText))
                _confirmedSegments.value = currentList
                _partialText.value = ""
            }

            if (isSessionActive && !isPaused) {
                scheduleRestart(100)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim().orEmpty()
            if (partial.isNotEmpty()) {
                _partialText.value = partial
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleRestart(delayMillis: Long) {
        mainHandler.postDelayed({
            if (isSessionActive && !isPaused) {
                restartListening()
            }
        }, delayMillis)
    }

    fun startListening(languageCode: String = "default") {
        currentLanguageCode = languageCode
        isSessionActive = true
        isPaused = false
        sessionStartTime = System.currentTimeMillis()
        _speechState.value = SpeechState.Listening

        mainHandler.post {
            initAndStartRecognizer()
        }
    }

    fun pauseListening() {
        isPaused = true
        _speechState.value = SpeechState.Paused
        _rmsVolume.value = 0f
        mainHandler.post {
            destroyRecognizer()
        }
    }

    fun resumeListening() {
        if (!isSessionActive) {
            startListening(currentLanguageCode)
            return
        }
        isPaused = false
        _speechState.value = SpeechState.Listening
        mainHandler.post {
            initAndStartRecognizer()
        }
    }

    fun stopListening() {
        isSessionActive = false
        isPaused = false
        _speechState.value = SpeechState.Idle
        _rmsVolume.value = 0f
        _partialText.value = ""
        mainHandler.post {
            destroyRecognizer()
        }
    }

    fun clearTranscript() {
        _confirmedSegments.value = emptyList()
        _partialText.value = ""
        sessionStartTime = System.currentTimeMillis()
    }

    fun setTranscript(text: String) {
        if (text.isNotBlank()) {
            _confirmedSegments.value = listOf(SpeechSegment(timestampSeconds = 0, text = text))
        } else {
            _confirmedSegments.value = emptyList()
        }
        _partialText.value = ""
    }

    private fun initAndStartRecognizer() {
        try {
            destroyRecognizer()
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _speechState.value = SpeechState.Error("Speech recognition is not available on this device.")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)

                if (currentLanguageCode != "default" && currentLanguageCode.isNotBlank()) {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguageCode)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguageCode)
                } else {
                    val defaultLocale = Locale.getDefault().toLanguageTag()
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, defaultLocale)
                }
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to start SpeechRecognizer", e)
            _speechState.value = SpeechState.Error("Could not start speech recognition: ${e.localizedMessage}")
        }
    }

    private fun restartListening() {
        if (!isSessionActive || isPaused) return
        try {
            initAndStartRecognizer()
        } catch (e: Exception) {
            Log.e(tag, "Failed to restart speech recognizer", e)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(tag, "Error destroying recognizer", e)
        } finally {
            speechRecognizer = null
        }
    }

    fun getFullText(): String {
        val confirmed = _confirmedSegments.value.joinToString(" ") { it.text.trim() }
        val partial = _partialText.value.trim()
        return if (partial.isNotEmpty()) {
            if (confirmed.isNotEmpty()) "$confirmed $partial" else partial
        } else {
            confirmed
        }
    }

    private fun getElapsedSeconds(): Long {
        return if (sessionStartTime > 0) {
            (System.currentTimeMillis() - sessionStartTime) / 1000
        } else 0
    }
}
