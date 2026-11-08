package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class Recording(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val durationMs: Long = 0L,
    val filePath: String = "",
    val fileFormat: String = "M4A",
    val fileSizeBytes: Long = 0L,
    val transcript: String = "",
    val speakers: String = "",
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val actionItems: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val isEncrypted: Boolean = false,
    val isBackedUp: Boolean = true,
    val driveSyncStatus: String = "synced",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val recordingId: String = "",
    val userId: String = "",
    val sender: String = "user", // "user" or "ai"
    val text: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)
