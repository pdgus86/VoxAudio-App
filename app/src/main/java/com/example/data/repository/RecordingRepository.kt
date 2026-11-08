package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.Recording
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class RecordingRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing recordings.")
    }

    fun observeRecordings(): Flow<List<Recording>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/recordings"
        emitAll(
            db.collection("users").document(uid).collection("recordings")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(Recording::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getRecordingById(recordingId: String): Result<Recording?> {
        val uid = requireUserId()
        val path = "users/$uid/recordings/$recordingId"
        return try {
            val doc = db.collection("users").document(uid).collection("recordings").document(recordingId).get().await()
            Result.success(doc.toObject(Recording::class.java))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun saveRecording(recording: Recording): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("recordings")
        val docRef = if (recording.id.isNotBlank()) colRef.document(recording.id) else colRef.document()

        val payload = mutableMapOf<String, Any?>(
            "userId" to uid,
            "title" to recording.title,
            "durationMs" to recording.durationMs,
            "filePath" to recording.filePath,
            "fileFormat" to recording.fileFormat,
            "fileSizeBytes" to recording.fileSizeBytes,
            "transcript" to recording.transcript,
            "speakers" to recording.speakers,
            "summary" to recording.summary,
            "keyPoints" to recording.keyPoints,
            "actionItems" to recording.actionItems,
            "tags" to recording.tags,
            "isEncrypted" to recording.isEncrypted,
            "isBackedUp" to recording.isBackedUp,
            "driveSyncStatus" to recording.driveSyncStatus,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateRecording(recording: Recording): Result<Unit> {
        val uid = requireUserId()
        require(recording.id.isNotBlank()) { "Recording ID must not be blank for update" }
        requireNotNull(recording.createdAt) { "Recording createdAt must not be null for update" }
        val docRef = db.collection("users").document(uid).collection("recordings").document(recording.id)

        return try {
            docRef.set(recording.copy(updatedAt = null)).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteRecording(recordingId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("recordings").document(recordingId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeChatMessages(recordingId: String): Flow<List<ChatMessage>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/recordings/$recordingId/chatMessages"
        emitAll(
            db.collection("users").document(uid)
                .collection("recordings").document(recordingId)
                .collection("chatMessages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot -> snapshot.toObjects(ChatMessage::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun sendChatMessage(recordingId: String, sender: String, text: String): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid)
            .collection("recordings").document(recordingId)
            .collection("chatMessages")
        val docRef = colRef.document()

        val payload = mapOf(
            "recordingId" to recordingId,
            "userId" to uid,
            "sender" to sender,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }
}
