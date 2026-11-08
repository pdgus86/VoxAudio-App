const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// Security boundary tests
test("Unauthenticated user: cannot read recordings", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("recordings").get());
});

test("Authenticated user: cannot read another user's recordings", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(BOB_UID).collection("recordings").doc("rec_1").set({
      userId: BOB_UID,
      title: "Bob Meeting",
      durationMs: 60000,
      filePath: "rec_bob.m4a",
      fileFormat: "M4A",
      fileSizeBytes: 1024,
      transcript: "Secret transcript",
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("recordings").doc("rec_1").get());
});

test("Authenticated user: can create and read own valid recording", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const recRef = aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_alice");

  await assertSucceeds(recRef.set({
    userId: ALICE_UID,
    title: "Alice Voice Note",
    durationMs: 35000,
    filePath: "alice_note.m4a",
    fileFormat: "M4A",
    fileSizeBytes: 2048,
    transcript: "Real-time transcribed audio notes",
    speakers: "Speaker 1: Hello world",
    summary: "Brief note",
    keyPoints: ["Point 1", "Point 2"],
    actionItems: ["Action 1"],
    tags: ["Meeting", "Work"],
    isEncrypted: true,
    isBackedUp: true,
    driveSyncStatus: "synced",
    createdAt: now,
    updatedAt: now,
  }));

  await assertSucceeds(recRef.get());
});

test("Chat messages: authenticated owner can create and read chat message", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  const msgRef = aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_alice").collection("chatMessages").doc("msg_1");

  await assertSucceeds(msgRef.set({
    recordingId: "rec_alice",
    userId: ALICE_UID,
    sender: "user",
    text: "Can you summarize the action items again?",
    createdAt: now,
  }));

  await assertSucceeds(msgRef.get());
});

test("Chat messages: update is rejected (immutable)", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await context.firestore().collection("users").doc(ALICE_UID).collection("recordings").doc("rec_alice").collection("chatMessages").doc("msg_1").set({
      recordingId: "rec_alice",
      userId: ALICE_UID,
      sender: "user",
      text: "Original message",
      createdAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const msgRef = aliceDb.collection("users").doc(ALICE_UID).collection("recordings").doc("rec_alice").collection("chatMessages").doc("msg_1");
  await assertFails(msgRef.update({ text: "Tampered message" }));
});
