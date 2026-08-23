package com.example.ai

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiAiService {
    private val tag = "GeminiAiService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun formatAndPunctuate(rawText: String): Result<String> = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) {
            return@withContext Result.success("")
        }

        val prompt = """
            You are a Google AI voice-to-text post-processing assistant.
            Convert the following raw continuous speech transcription into beautifully structured, readable text.
            
            Guidelines:
            1. Add proper punctuation, commas, sentence capitalization, and paragraph breaks.
            2. Remove filler speech words (e.g. um, uh, er, like, you know) where appropriate without altering meaning.
            3. Group logically related thoughts into clear paragraphs.
            4. Keep the exact original meaning and language of the speaker.
            5. Output ONLY the formatted text, no conversational intro or outro.
            
            Raw Transcript:
            $rawText
        """.trimIndent()

        callGemini(prompt)
    }

    suspend fun generateSummary(rawText: String): Result<String> = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) {
            return@withContext Result.success("")
        }

        val prompt = """
            You are Google AI Mode assistant. Provide an executive summary of the following speech transcript.
            
            Format your response clearly:
            - **Summary Overview**: 1-2 concise paragraphs summarizing the core topic.
            - **Key Points**: Bullet points of main ideas discussed.
            - **Action Items / Takeaways**: Any decisions, questions, or next steps mentioned.
            
            Transcript:
            $rawText
        """.trimIndent()

        callGemini(prompt)
    }

    suspend fun askQuestionAboutTranscript(rawText: String, question: String): Result<String> = withContext(Dispatchers.IO) {
        val prompt = """
            You are Google AI Search Mode assistant. Answer the user's question accurately based primarily on the provided speech transcript.
            
            Speech Transcript:
            $rawText
            
            User Question:
            $question
            
            Response format:
            Provide a direct, concise, well-structured answer (in paragraphs and markdown lists if helpful). If the transcript doesn't mention something, state that clearly and provide helpful context.
        """.trimIndent()

        callGemini(prompt)
    }

    private fun callGemini(promptText: String): Result<String> {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isNullOrBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.w(tag, "Gemini API key is not configured; using local smart formatting.")
            return Result.success(smartLocalPunctuate(promptText))
        }

        val modelsToTry = listOf("gemini-2.5-flash", "gemini-1.5-flash", "gemini-1.5-pro")

        for (model in modelsToTry) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray()
                    val contentObj = JSONObject()
                    val partsArray = JSONArray()
                    val partObj = JSONObject().apply {
                        put("text", promptText)
                    }
                    partsArray.put(partObj)
                    contentObj.put("parts", partsArray)
                    contentsArray.put(contentObj)
                    put("contents", contentsArray)

                    val genConfig = JSONObject().apply {
                        put("temperature", 0.3)
                        put("maxOutputTokens", 2048)
                    }
                    put("generationConfig", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val rootJson = JSONObject(responseBody)
                    val candidates = rootJson.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        val firstCandidate = candidates.getJSONObject(0)
                        val content = firstCandidate.getJSONObject("content")
                        val parts = content.getJSONArray("parts")
                        val output = parts.getJSONObject(0).getString("text").trim()
                        return Result.success(output)
                    }
                } else {
                    Log.e(tag, "Gemini API error ($model): HTTP ${response.code} $responseBody")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed calling Gemini model $model", e)
            }
        }

        return Result.success(smartLocalPunctuate(promptText))
    }

    private fun smartLocalPunctuate(text: String): String {
        val clean = if (text.contains("Raw Transcript:")) {
            text.substringAfter("Raw Transcript:").trim()
        } else if (text.contains("Transcript:")) {
            text.substringAfter("Transcript:").substringBefore("User Question:").trim()
        } else {
            text
        }

        if (clean.isBlank()) return ""

        val sentences = clean.split(". ", "? ", "! ", "\n")
        val formatted = sentences.mapNotNull { sentence ->
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) null
            else {
                val capitalized = trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                if (!capitalized.endsWith(".") && !capitalized.endsWith("?") && !capitalized.endsWith("!")) {
                    "$capitalized."
                } else {
                    capitalized
                }
            }
        }

        val paragraphs = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (s in formatted) {
            current.add(s)
            if (current.size >= 4) {
                paragraphs.add(current.joinToString(" "))
                current.clear()
            }
        }
        if (current.isNotEmpty()) {
            paragraphs.add(current.joinToString(" "))
        }

        return paragraphs.joinToString("\n\n")
    }
}
