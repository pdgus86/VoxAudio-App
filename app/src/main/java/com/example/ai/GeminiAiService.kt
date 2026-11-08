package com.example.ai

import android.util.Log
import com.example.data.model.Recording
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class AiAnalysisResult(
    val formattedTranscript: String,
    val summary: String,
    val keyPoints: List<String>,
    val actionItems: List<String>,
    val tags: List<String>,
    val speakers: String
)

class GeminiAiService {

    private val TAG = "GeminiAiService"

    // Model for conversational insights and chat
    private val conversationalModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash"
        )
    }

    // Structured JSON schema for comprehensive review, diarization, and action items
    private val reviewSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "formattedTranscript" to Schema.string(description = "Full transcription with speaker diarization and timestamps, e.g., [00:03] Speaker 1: Hello"),
                "summary" to Schema.string(description = "Concise executive review summary"),
                "keyPoints" to Schema.array(
                    items = Schema.string(),
                    description = "Bulleted list of key discussion takeaways"
                ),
                "actionItems" to Schema.array(
                    items = Schema.string(),
                    description = "Follow-up tasks, action items, or next steps"
                ),
                "tags" to Schema.array(
                    items = Schema.string(),
                    description = "3 to 6 automated categorization tags (e.g., #Meeting, #Product, #Actionable)"
                ),
                "speakers" to Schema.string(description = "Speaker identification breakdown summary")
            )
        )
    }

    private val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = reviewSchema
            }
        )
    }

    suspend fun analyzeRecording(rawText: String, title: String): Result<AiAnalysisResult> {
        val prompt = """
            You are VoxFlow AI, an advanced audio intelligence and voice transcription assistant.
            Analyze the following recorded audio session titled "$title".
            
            Recorded Audio Transcript Content:
            \"\"\"
            $rawText
            \"\"\"
            
            Perform:
            1. Clean transcription and automatic speaker identification/diarization (Speaker 1, Speaker 2, etc. with estimated timestamps).
            2. High-value executive review summarizing key points.
            3. Action items extraction identifying concrete follow-up tasks.
            4. Automated tags for organized categorization.
            5. Summary of identified speakers and their contributions.
        """.trimIndent()

        return try {
            val response = withTimeout(60_000L) {
                structuredModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw IllegalStateException("Model returned empty text.")
            val json = JSONObject(jsonStr)

            val transcript = json.optString("formattedTranscript", rawText)
            val summary = json.optString("summary", "No summary generated.")
            val speakers = json.optString("speakers", "Speaker 1")

            val kpArray = json.optJSONArray("keyPoints")
            val keyPoints = mutableListOf<String>()
            if (kpArray != null) {
                for (i in 0 until kpArray.length()) {
                    keyPoints.add(kpArray.getString(i))
                }
            }

            val aiArray = json.optJSONArray("actionItems")
            val actionItems = mutableListOf<String>()
            if (aiArray != null) {
                for (i in 0 until aiArray.length()) {
                    actionItems.add(aiArray.getString(i))
                }
            }

            val tagsArray = json.optJSONArray("tags")
            val tags = mutableListOf<String>()
            if (tagsArray != null) {
                for (i in 0 until tagsArray.length()) {
                    tags.add(tagsArray.getString(i))
                }
            }

            Result.success(
                AiAnalysisResult(
                    formattedTranscript = transcript,
                    summary = summary,
                    keyPoints = keyPoints,
                    actionItems = actionItems,
                    tags = tags,
                    speakers = speakers
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI analysis timed out", e)
            Result.failure(Exception("AI analysis timed out after 60 seconds."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI analysis failed", e)
            Result.failure(e)
        }
    }

    fun streamVoiceConversation(
        recording: Recording,
        conversationHistory: List<Pair<String, String>>, // sender ("user" / "ai") to text
        userQuestion: String
    ): Flow<String> = flow {
        val historyContents = mutableListOf<Content>()
        // System context as first exchange
        val contextPrompt = """
            You are the VoxFlow AI Voice Assistant. You are discussing the user's recorded audio session:
            Title: ${recording.title}
            Duration: ${recording.durationMs / 1000}s
            Summary: ${recording.summary}
            Key Points: ${recording.keyPoints.joinToString("; ")}
            Action Items: ${recording.actionItems.joinToString("; ")}
            Full Transcript:
            ${recording.transcript}
            
            Answer questions concisely, accurately, and naturally for voice conversation. Provide deep insights, clarifications, and quote relevant speaker statements if helpful.
        """.trimIndent()

        historyContents.add(content("user") { text(contextPrompt) })
        historyContents.add(content("model") { text("I am ready to discuss this recording session with you. What would you like to explore or clarify?") })

        conversationHistory.forEach { (sender, message) ->
            if (sender == "user") {
                historyContents.add(content("user") { text(message) })
            } else {
                historyContents.add(content("model") { text(message) })
            }
        }

        val chat = conversationalModel.startChat(history = historyContents)

        val fullResponse = StringBuilder()
        withTimeout(60_000L) {
            chat.sendMessageStream(userQuestion).collect { chunk ->
                val chunkText = chunk.text.orEmpty()
                fullResponse.append(chunkText)
                emit(fullResponse.toString())
            }
        }
    }

    suspend fun searchTranscriptsAi(
        query: String,
        recordings: List<Recording>
    ): List<String> {
        if (recordings.isEmpty() || query.isBlank()) return emptyList()

        val prompt = """
            You are an AI-powered search tool.
            The user wants to search for: "$query"
            
            Here are the available recordings (ID and text snippet):
            ${recordings.take(20).joinToString("\n") { "ID: ${it.id} | Title: ${it.title} | Tags: ${it.tags.joinToString()} | Transcript: ${it.transcript.take(300)}" }}
            
            Return ONLY a comma-separated list of the matching Recording IDs that best match the semantic intent or keyword. Do not include explanation.
        """.trimIndent()

        return try {
            val response = withTimeout(30_000L) {
                conversationalModel.generateContent(prompt)
            }
            val text = response.text.orEmpty()
            text.split(",")
                .map { it.trim().removePrefix("ID:").trim() }
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "AI Search failed, falling back to local search", e)
            emptyList()
        }
    }
}
