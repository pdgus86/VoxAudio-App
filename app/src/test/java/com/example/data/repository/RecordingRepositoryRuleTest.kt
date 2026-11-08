package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.Recording
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun saveRecording_validPayload_createsDocumentAndReturnsId(): Unit = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = RecordingRepository(firestore)

    val sample = Recording(
      title = "Live Meeting Note",
      durationMs = 45000L,
      filePath = "rec_test_1.m4a",
      fileFormat = "M4A",
      fileSizeBytes = 4096L,
      transcript = "Discussion on project milestones and Q3 targets.",
      speakers = "Speaker 1: Hi everyone",
      summary = "Product roadmap check-in",
      keyPoints = listOf("Target Q3 release", "Refactor backend"),
      actionItems = listOf("Send follow-up email"),
      tags = listOf("Work", "Roadmap"),
      isEncrypted = false,
      isBackedUp = true,
      driveSyncStatus = "synced"
    )

    val result = withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveRecording(sample) }
    assertTrue(result.isSuccess)
    val recordingId = result.getOrThrow()
    assertTrue(recordingId.isNotEmpty())
  }

  @Test
  fun getRecordingById_authenticatedOwner_returnsRecording(): Unit = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = RecordingRepository(firestore)

    val sample = Recording(
      title = "Sprint Planning",
      durationMs = 60000L,
      filePath = "rec_test_2.m4a",
      fileFormat = "M4A",
      fileSizeBytes = 8192L,
      transcript = "Sprint goals and ticket assignments.",
      summary = "Weekly sprint plan"
    )
    val recordingId = withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveRecording(sample).getOrThrow() }

    val fetchResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.getRecordingById(recordingId) }
    assertTrue(fetchResult.isSuccess)
    val fetched = fetchResult.getOrThrow()
    assertNotNull(fetched)
    assertEquals("Sprint Planning", fetched?.title)
  }

  @Test
  fun observeRecordings_authenticatedOwner_emitsRealtimeUpdates(): Unit = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = RecordingRepository(firestore)

    val sample = Recording(
      title = "Realtime Check",
      durationMs = 12000L,
      filePath = "rec_test_3.m4a",
      fileFormat = "M4A",
      fileSizeBytes = 2048L,
      transcript = "Testing realtime snapshot observer."
    )
    val recordingId = withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveRecording(sample).getOrThrow() }

    val list = withTimeout(FLOW_TIMEOUT_MS) {
      repository.observeRecordings().first { items -> items.any { r -> r.id == recordingId } }
    }
    assertTrue(list.any { it.id == recordingId })
  }

  @Test
  fun getRecordingById_crossUserAccess_failsWithPermissionDenied(): Unit = runBlocking {
    val aliceUid = signInTestUser(ALICE_EMAIL)
    val aliceRepo = RecordingRepository(firestore)
    val sample = Recording(
      title = "Private Alice Journal",
      durationMs = 30000L,
      filePath = "rec_alice_priv.m4a",
      fileFormat = "M4A",
      fileSizeBytes = 3000L,
      transcript = "Private thoughts"
    )
    val recordingId = withTimeout(DEFAULT_TIMEOUT_MS) { aliceRepo.saveRecording(sample).getOrThrow() }

    signInTestUser(BOB_EMAIL)
    var denied = false
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("users").document(aliceUid).collection("recordings").document(recordingId).get().await()
      }
    } catch (e: FirebaseFirestoreException) {
      if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        denied = true
      }
    } catch (e: Exception) {
      val fEx = (e as? FirebaseFirestoreException) ?: (e.cause as? FirebaseFirestoreException)
      if (fEx?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
        denied = true
      }
    }
    assertTrue(denied)
  }

  @Test
  fun observeRecordings_unauthenticatedUser_fails(): Unit = runBlocking {
    auth.signOut()
    val repository = RecordingRepository(firestore)

    var failed = false
    try {
      withTimeout(FLOW_TIMEOUT_MS) { repository.observeRecordings().first() }
    } catch (e: Exception) {
      failed = true
    }
    assertTrue(failed)
  }

  @Test
  fun sendChatMessage_andObserve_succeeds(): Unit = runBlocking {
    signInTestUser(ALICE_EMAIL)
    val repository = RecordingRepository(firestore)

    val sample = Recording(
      title = "Interview with CEO",
      durationMs = 100000L,
      filePath = "interview.m4a",
      fileFormat = "M4A",
      fileSizeBytes = 15000L,
      transcript = "Discussion about the company vision for 2027."
    )
    val recordingId = withTimeout(DEFAULT_TIMEOUT_MS) { repository.saveRecording(sample).getOrThrow() }

    val msgResult = withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.sendChatMessage(recordingId, "user", "What is the 2027 vision?")
    }
    assertTrue(msgResult.isSuccess)
    val msgId = msgResult.getOrThrow()
    assertTrue(msgId.isNotEmpty())

    val messages = withTimeout(FLOW_TIMEOUT_MS) {
      repository.observeChatMessages(recordingId).first { list -> list.any { m -> m.id == msgId } }
    }
    assertTrue(messages.any { it.id == msgId })
  }

  private companion object {
    const val ALICE_EMAIL = "alice_rec@test.com"
    const val BOB_EMAIL = "bob_rec@test.com"
    const val DEFAULT_TIMEOUT_MS = 6000L
    const val FLOW_TIMEOUT_MS = 5000L
  }
}
