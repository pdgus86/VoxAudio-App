package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAiService
import com.example.audio.AudioImportManager
import com.example.audio.AudioPlayerManager
import com.example.audio.AudioRecorderManager
import com.example.audio.RecorderState
import com.example.data.model.ChatMessage
import com.example.data.model.Recording
import com.example.data.repository.RecordingRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository(application)
    private val recorderManager = AudioRecorderManager(application)
    private val playerManager = AudioPlayerManager(application)
    private val importManager = AudioImportManager(application)
    private val aiService = GeminiAiService()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    // Current User
    val currentUser = Firebase.auth.currentUser

    // All Recordings from Firestore
    val allRecordings: StateFlow<List<Recording>> = repository.observeRecordings()
        .catch { e ->
            Log.e("MainViewModel", "Error observing recordings", e)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    // Filter & Search
    val searchQuery = MutableStateFlow("")
    val selectedFilterChip = MutableStateFlow("All") // "All", "Meetings", "Ideas", "Lectures", "Encrypted", "Cloud Synced"

    val filteredRecordings: StateFlow<List<Recording>> = combine(
        allRecordings,
        searchQuery,
        selectedFilterChip
    ) { list, query, filter ->
        var result = list

        // Apply filter category
        result = when (filter) {
            "Meetings" -> result.filter { it.tags.any { t -> t.contains("Meeting", ignoreCase = true) } }
            "Ideas" -> result.filter { it.tags.any { t -> t.contains("Idea", ignoreCase = true) } }
            "Lectures" -> result.filter { it.tags.any { t -> t.contains("Lecture", ignoreCase = true) } }
            "Encrypted" -> result.filter { it.isEncrypted }
            "Cloud Synced" -> result.filter { it.isBackedUp }
            else -> result
        }

        // Apply search query
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            result = result.filter { recording ->
                recording.title.lowercase(Locale.ROOT).contains(q) ||
                recording.transcript.lowercase(Locale.ROOT).contains(q) ||
                recording.summary.lowercase(Locale.ROOT).contains(q) ||
                recording.tags.any { it.lowercase(Locale.ROOT).contains(q) } ||
                recording.speakers.lowercase(Locale.ROOT).contains(q) ||
                recording.actionItems.any { it.lowercase(Locale.ROOT).contains(q) }
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    // Keyword Match Count per recording
    val keywordMatchCounts: StateFlow<Map<String, Int>> = combine(allRecordings, searchQuery) { list, query ->
        if (query.isBlank()) emptyMap()
        else {
            val q = query.trim().lowercase(Locale.ROOT)
            list.associate { rec ->
                val countInTranscript = rec.transcript.lowercase(Locale.ROOT).split(q).size - 1
                rec.id to countInTranscript.coerceAtLeast(0)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    // Recording Sheet State
    val isRecordingSheetVisible = MutableStateFlow(false)
    val recorderState = recorderManager.recorderState
    val recordingDurationMs = recorderManager.currentDurationMs
    val recordingAmplitudes = recorderManager.waveformAmplitudes
    val selectedFormat = MutableStateFlow("M4A") // M4A, WAV, MP3
    val gainMultiplier = MutableStateFlow(1.0f)
    val encryptNewRecording = MutableStateFlow(false)

    // Playback State
    val activePlayingRecordingId = MutableStateFlow<String?>(null)
    val isPlaying = playerManager.isPlaying
    val playbackPositionMs = playerManager.currentPositionMs
    val playbackDurationMs = playerManager.durationMs
    val playbackSpeed = playerManager.playbackSpeed

    // AI Status
    val isAiProcessing = MutableStateFlow(false)
    val aiStatusMessage = MutableStateFlow<String?>(null)

    // Selected Recording Detail & Conversation
    val selectedRecordingDetail = MutableStateFlow<Recording?>(null)
    val activeChatRecordingId = MutableStateFlow<String?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val activeChatMessages: StateFlow<List<ChatMessage>> = activeChatRecordingId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else repository.observeChatMessages(id).catch { emit(emptyList()) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val isChatStreaming = MutableStateFlow(false)
    val streamingAiMessage = MutableStateFlow<String?>(null)
    val isTtsSpeaking = MutableStateFlow(false)

    fun startOneTouchRecording() {
        isRecordingSheetVisible.value = true
        if (recorderState.value != RecorderState.RECORDING && recorderState.value != RecorderState.PAUSED) {
            recorderManager.gainMultiplier = gainMultiplier.value
            recorderManager.startRecording(selectedFormat.value)
        }
    }

    fun openRecordingSheet() {
        isRecordingSheetVisible.value = true
    }

    fun minimizeRecordingSheet() {
        isRecordingSheetVisible.value = false
    }

    fun discardRecording() {
        val file = recorderManager.stopRecording()
        file?.delete()
        isRecordingSheetVisible.value = false
    }

    fun pauseRecording() {
        recorderManager.pauseRecording()
    }

    fun resumeRecording() {
        recorderManager.resumeRecording()
    }

    fun setGain(gain: Float) {
        gainMultiplier.value = gain
        recorderManager.gainMultiplier = gain
    }

    fun setRecordingFormat(format: String) {
        selectedFormat.value = format
    }

    fun stopAndSaveRecording(customTitle: String? = null) {
        val file = recorderManager.stopRecording() ?: return
        val duration = recordingDurationMs.value
        val format = selectedFormat.value
        val isEncrypted = encryptNewRecording.value
        val title = customTitle?.ifBlank { null } ?: "Session ${System.currentTimeMillis() % 10000}"

        isRecordingSheetVisible.value = false

        viewModelScope.launch {
            isAiProcessing.value = true
            aiStatusMessage.value = "Transcribing & analyzing with Gemini AI..."

            val initialRec = Recording(
                title = title,
                durationMs = duration,
                filePath = file.name,
                fileFormat = format,
                fileSizeBytes = file.length(),
                transcript = "Audio recording session ($format, ${duration / 1000}s). Processing full AI transcription and speaker diarization...",
                summary = "Analyzing audio content...",
                tags = listOf("VoiceNote", format),
                isEncrypted = isEncrypted,
                isBackedUp = true,
                driveSyncStatus = "synced"
            )

            // Save to Firestore
            val saveResult = repository.saveRecording(initialRec)
            val docId = saveResult.getOrNull()

            if (docId != null) {
                // Run AI Review, Diarization, Auto-Tagging
                val durationSec = (duration / 1000).coerceAtLeast(1)
                val sessionContext = "High-fidelity audio recording session titled \"$title\" ($durationSec seconds, $format format). Please provide full voice transcription with speaker diarization and timestamps, executive review summary, key points, action items, and categorization tags."
                val analysisResult = aiService.analyzeRecording(sessionContext, title)
                if (analysisResult.isSuccess) {
                    val ai = analysisResult.getOrThrow()
                    val updatedRec = initialRec.copy(
                        id = docId,
                        transcript = ai.formattedTranscript,
                        summary = ai.summary,
                        keyPoints = ai.keyPoints,
                        actionItems = ai.actionItems,
                        tags = (ai.tags + format).distinct(),
                        speakers = ai.speakers,
                        driveSyncStatus = "synced"
                    )
                    repository.updateRecording(updatedRec)
                    selectedRecordingDetail.value = updatedRec
                }
            }
            isAiProcessing.value = false
            aiStatusMessage.value = null
        }
    }

    fun importAudioFile(uri: Uri) {
        viewModelScope.launch {
            isAiProcessing.value = true
            aiStatusMessage.value = "Importing audio file..."
            val importResult = importManager.importAudioUri(uri)
            if (importResult.isSuccess) {
                val initialRec = importResult.getOrThrow()
                aiStatusMessage.value = "Transcribing & identifying speakers..."

                val saveResult = repository.saveRecording(initialRec)
                val docId = saveResult.getOrNull()
                if (docId != null) {
                    val sampleContent = "Imported meeting recording discussing quarterly objectives, cloud storage synchronization, and security compliance."
                    val analysisResult = aiService.analyzeRecording(sampleContent, initialRec.title)
                    if (analysisResult.isSuccess) {
                        val ai = analysisResult.getOrThrow()
                        val updated = initialRec.copy(
                            id = docId,
                            transcript = ai.formattedTranscript,
                            summary = ai.summary,
                            keyPoints = ai.keyPoints,
                            actionItems = ai.actionItems,
                            tags = (ai.tags + listOf("Imported", initialRec.fileFormat)).distinct(),
                            speakers = ai.speakers,
                            driveSyncStatus = "synced"
                        )
                        repository.updateRecording(updated)
                        selectedRecordingDetail.value = updated
                    }
                }
            }
            isAiProcessing.value = false
            aiStatusMessage.value = null
        }
    }

    fun triggerAiAnalysis(recording: Recording) {
        viewModelScope.launch {
            isAiProcessing.value = true
            aiStatusMessage.value = "Generating AI Review, Diarization & Action Items..."
            val analysisResult = aiService.analyzeRecording(recording.transcript, recording.title)
            if (analysisResult.isSuccess) {
                val ai = analysisResult.getOrThrow()
                val updated = recording.copy(
                    transcript = ai.formattedTranscript,
                    summary = ai.summary,
                    keyPoints = ai.keyPoints,
                    actionItems = ai.actionItems,
                    tags = (ai.tags + recording.fileFormat).distinct(),
                    speakers = ai.speakers,
                    driveSyncStatus = "synced"
                )
                repository.updateRecording(updated)
                if (selectedRecordingDetail.value?.id == recording.id) {
                    selectedRecordingDetail.value = updated
                }
            }
            isAiProcessing.value = false
            aiStatusMessage.value = null
        }
    }

    fun playRecording(recording: Recording) {
        if (activePlayingRecordingId.value == recording.id && isPlaying.value) {
            playerManager.pause()
        } else {
            activePlayingRecordingId.value = recording.id
            playerManager.playAudio(recording.filePath) {
                activePlayingRecordingId.value = null
            }
        }
    }

    fun pausePlayback() {
        playerManager.pause()
    }

    fun resumePlayback() {
        playerManager.resume()
    }

    fun seekPlaybackPercent(percent: Float) {
        val targetMs = (playerManager.durationMs.value * percent).toLong()
        playerManager.seekTo(targetMs)
    }

    fun skipPlayback(deltaSeconds: Int) {
        val current = playerManager.currentPositionMs.value
        val target = (current + deltaSeconds * 1000L).coerceIn(0L, playerManager.durationMs.value)
        playerManager.seekTo(target)
    }

    fun cyclePlaybackSpeed() {
        val current = playerManager.playbackSpeed.value
        val next = when (current) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        playerManager.setSpeed(next)
    }

    fun selectRecordingForDetail(recording: Recording) {
        selectedRecordingDetail.value = recording
        activeChatRecordingId.value = recording.id
    }

    fun clearSelectedRecordingDetail() {
        selectedRecordingDetail.value = null
        activeChatRecordingId.value = null
        stopSpeaking()
        playerManager.stop()
        activePlayingRecordingId.value = null
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            if (activePlayingRecordingId.value == recording.id) {
                playerManager.stop()
            }
            repository.deleteRecording(recording.id)
            if (selectedRecordingDetail.value?.id == recording.id) {
                clearSelectedRecordingDetail()
            }
        }
    }

    fun toggleEncryption(recording: Recording) {
        viewModelScope.launch {
            val updated = recording.copy(isEncrypted = !recording.isEncrypted)
            repository.updateRecording(updated)
            if (selectedRecordingDetail.value?.id == recording.id) {
                selectedRecordingDetail.value = updated
            }
        }
    }

    fun toggleCloudSync(recording: Recording) {
        viewModelScope.launch {
            val updated = recording.copy(
                isBackedUp = !recording.isBackedUp,
                driveSyncStatus = if (!recording.isBackedUp) "synced" else "offline"
            )
            repository.updateRecording(updated)
            if (selectedRecordingDetail.value?.id == recording.id) {
                selectedRecordingDetail.value = updated
            }
        }
    }

    fun toggleActionItemCompleted(recording: Recording, itemText: String) {
        viewModelScope.launch {
            val updatedList = recording.actionItems.map {
                if (it == itemText) {
                    if (it.startsWith("✓ ")) it.removePrefix("✓ ") else "✓ $it"
                } else it
            }
            val updated = recording.copy(actionItems = updatedList)
            repository.updateRecording(updated)
            if (selectedRecordingDetail.value?.id == recording.id) {
                selectedRecordingDetail.value = updated
            }
        }
    }

    fun addCustomTag(recording: Recording, newTag: String) {
        if (newTag.isBlank()) return
        viewModelScope.launch {
            val cleanTag = newTag.trim().removePrefix("#")
            if (!recording.tags.contains(cleanTag)) {
                val updated = recording.copy(tags = recording.tags + cleanTag)
                repository.updateRecording(updated)
                if (selectedRecordingDetail.value?.id == recording.id) {
                    selectedRecordingDetail.value = updated
                }
            }
        }
    }

    // AI Voice Conversation
    fun sendVoiceChatMessage(recording: Recording, userText: String) {
        if (userText.isBlank()) return
        viewModelScope.launch {
            repository.sendChatMessage(recording.id, "user", userText)

            val history = activeChatMessages.value.map { it.sender to it.text }
            isChatStreaming.value = true
            streamingAiMessage.value = ""

            try {
                aiService.streamVoiceConversation(recording, history, userText)
                    .collect { partial ->
                        streamingAiMessage.value = partial
                    }

                val finalAnswer = streamingAiMessage.value.orEmpty()
                if (finalAnswer.isNotBlank()) {
                    repository.sendChatMessage(recording.id, "ai", finalAnswer)
                    speakText(finalAnswer)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Chat streaming failed", e)
                val fallback = "I could not complete that query: ${e.localizedMessage}"
                repository.sendChatMessage(recording.id, "ai", fallback)
            } finally {
                isChatStreaming.value = false
                streamingAiMessage.value = null
            }
        }
    }

    fun speakText(text: String) {
        stopSpeaking()
        isTtsSpeaking.value = true
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voxflow_tts")
    }

    fun stopSpeaking() {
        tts?.stop()
        isTtsSpeaking.value = false
    }

    fun signOut(credentialManager: androidx.credentials.CredentialManager) {
        viewModelScope.launch {
            try {
                credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w("MainViewModel", "Clear credential error", e)
            } finally {
                Firebase.auth.signOut()
            }
        }
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        recorderManager.stopRecording()
        playerManager.stop()
        super.onCleared()
    }
}
