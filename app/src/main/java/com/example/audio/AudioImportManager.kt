package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.model.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AudioImportManager(private val context: Context) {

    suspend fun importAudioUri(uri: Uri): Result<Recording> = withContext(Dispatchers.IO) {
        try {
            val fileName = queryDisplayName(uri) ?: "imported_audio_${System.currentTimeMillis()}.m4a"
            val extension = fileName.substringAfterLast('.', "m4a")
            val targetDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val targetFile = File(targetDir, "imported_${System.currentTimeMillis()}.$extension")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Could not open input stream from selected audio URI"))

            var durationMs = 0L
            var title = fileName.substringBeforeLast('.')
            val format = when (extension.lowercase()) {
                "wav" -> "WAV"
                "mp3" -> "MP3"
                "aac" -> "AAC"
                else -> "M4A"
            }

            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(targetFile.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull() ?: 0L
                val extractedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (!extractedTitle.isNullOrBlank()) {
                    title = extractedTitle
                }
                retriever.release()
            } catch (e: Exception) {
                Log.w("AudioImportManager", "Could not read media metadata: ${e.message}")
            }

            val recording = Recording(
                title = title,
                durationMs = durationMs,
                filePath = targetFile.name,
                fileFormat = format,
                fileSizeBytes = targetFile.length(),
                transcript = "Imported audio ready for AI transcription...",
                summary = "",
                keyPoints = emptyList(),
                actionItems = emptyList(),
                tags = listOf("Imported", format),
                isEncrypted = false,
                isBackedUp = true,
                driveSyncStatus = "pending"
            )

            Result.success(recording)
        } catch (e: Exception) {
            Log.e("AudioImportManager", "Failed to import audio", e)
            Result.failure(e)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
