package com.example

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay

data class TranscriptionState(
    val isRecording: Boolean = false,
    val transcriptionText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val recordingDurationMillis: Long = 0L
)

class TranscriptionViewModel : ViewModel() {
    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    private var recordingStartTime = 0L
    private var timerJob: kotlinx.coroutines.Job? = null

    fun toggleRecording(context: Context) {
        if (_state.value.isRecording) {
            stopRecording()
        } else {
            startRecording(context)
        }
    }

    private fun startRecording(context: Context) {
        try {
            val cacheDir = context.cacheDir
            outputFile = File(cacheDir, "recording.m4a")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32000)
                setAudioSamplingRate(16000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
                start()
            }

            recordingStartTime = System.currentTimeMillis()
            _state.update { it.copy(
                isRecording = true, 
                error = null, 
                recordingDurationMillis = 0L,
                transcriptionText = "" // clear previous transcription
            ) }

            timerJob = viewModelScope.launch {
                while (_state.value.isRecording) {
                    delay(1000)
                    _state.update { it.copy(recordingDurationMillis = System.currentTimeMillis() - recordingStartTime) }
                }
            }

        } catch (e: Exception) {
            _state.update { it.copy(isRecording = false, error = "Failed to start recording: ${e.message}") }
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Error stopping recording: ${e.message}") }
        } finally {
            mediaRecorder = null
            _state.update { it.copy(isRecording = false) }
            timerJob?.cancel()
            
            // Transcribe the audio
            outputFile?.let { file ->
                if (file.exists()) {
                    transcribeAudio(file)
                }
            }
        }
    }

    private fun transcribeAudio(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    _state.update { it.copy(isLoading = false, error = "Gemini API Key is missing. Please configure it in AI Studio Secrets.") }
                    return@launch
                }

                // Read file to Base64
                val base64Audio = withContext(Dispatchers.IO) {
                    val bytes = FileInputStream(file).readBytes()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }

                // 20MB is approx 20 * 1024 * 1024 bytes. Base64 is larger.
                val prompt = "Transcribe the following audio accurately. Output only the transcribed text, without any additional conversational text or formatting. Ignore any silence."
                val request = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = prompt),
                                Part(inlineData = InlineData(mimeType = "audio/m4a", data = base64Audio))
                            )
                        )
                    )
                )

                val response = RetrofitClient.service.generateContent(apiKey, request)
                val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No speech detected."
                
                _state.update { it.copy(isLoading = false, transcriptionText = text) }

            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Transcription failed: ${e.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        mediaRecorder?.release()
    }
}
