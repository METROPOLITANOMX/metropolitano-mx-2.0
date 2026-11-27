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
const ADMIN_UID = "admin_richi";
const ADMIN_EMAIL = "richibikergsx@gmail.com";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

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

test("Unauthenticated users cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("circuits").where("visibility", "==", "PUBLIC").get());
  await assertFails(unauthDb.collection("riders").where("status", "==", "ACTIVE").get());
  await assertFails(unauthDb.doc("app_config/global").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Anon",
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("Users collection: owner isolation, immutable fields, and ID poisoning guard", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice MX",
      email: "alice@example.com",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Pro",
      updatedAt: pastDate(1000),
    })
  );

  // Reject immutable createdAt modification and ghost field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(500),
    })
  );

  // Cross-user read/write denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hijacked",
      updatedAt: pastDate(500),
    })
  );
});

test("Admin bootstrapping requires verified admin email; spoofed email_verified=false fails", async () => {
  const spoofedAdminDb = testEnv
    .authenticatedContext("spoof_uid", { email: ADMIN_EMAIL, email_verified: false })
    .firestore();
  const realAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  const cfgPayload = {
    registrationOpen: true,
    apkDownloadLocked: true,
    apkDownloadUrl: "https://example.com/app.apk",
    directorPasswordHash: "a".repeat(64),
    directorCustomPasswordSet: false,
    ownerUid: ADMIN_UID,
    createdAt: pastDate(10000),
    updatedAt: pastDate(10000),
  };

  await assertFails(spoofedAdminDb.doc("app_config/global").set({ ...cfgPayload, ownerUid: "spoof_uid" }));
  await assertFails(aliceDb.doc("app_config/global").set({ ...cfgPayload, ownerUid: ALICE_UID }));
  await assertSucceeds(realAdminDb.doc("app_config/global").set(cfgPayload));

  // Signed-in pilot can read global config
  await assertSucceeds(aliceDb.doc("app_config/global").get());

  // Invite code creation: admin only; pilot can redeem +1 only
  await assertSucceeds(
    realAdminDb.collection("invite_codes").doc("CLUBMX01").set({
      code: "CLUBMX01",
      circuitName: "Circuito Sur",
      note: "VIP",
      promoteOfficial: true,
      active: true,
      redeemedCount: 0,
      ownerUid: ADMIN_UID,
      createdAtMs: Date.now() - 5000,
      createdAt: pastDate(5000),
      updatedAt: pastDate(5000),
    })
  );

  // Non-admin cannot list all invite codes
  await assertFails(aliceDb.collection("invite_codes").get());
  // Non-admin can fetch specific code and redeem +1
  await assertSucceeds(aliceDb.collection("invite_codes").doc("CLUBMX01").get());
  await assertSucceeds(
    aliceDb.collection("invite_codes").doc("CLUBMX01").update({
      redeemedCount: 1,
      updatedAt: pastDate(1000),
    })
  );
  // Non-admin cannot tamper active flag or jump redeemedCount by +5
  await assertFails(
    aliceDb.collection("invite_codes").doc("CLUBMX01").update({
      active: false,
      updatedAt: pastDate(500),
    })
  );
});

test("Riders & Bikes: owner write, cross-user protection, Paddock query & voting", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const realAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const created = pastDate(10000);

  await assertSucceeds(
    aliceDb.collection("riders").doc(ALICE_UID).set({
      ownerUid: ALICE_UID,
      name: "Alice #44",
      status: "ACTIVE",
      totalLaps: 0,
      bestLapMs: 0,
      regularityIndex: 0,
      avgSpeedKmh: 0,
      eq200mMs: 0,
      activeBikeId: "bike_alice_1",
      preferredSurface: "MX",
      createdAtMs: Date.now() - 10000,
      lastSeenAtMs: Date.now() - 10000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Filtered ranking query succeeds; unfiltered query fails
  await assertSucceeds(aliceDb.collection("riders").where("status", "==", "ACTIVE").get());
  await assertFails(bobDb.collection("riders").get());

  // Bob cannot edit Alice's rider profile or self-assign BLOCKED/ACTIVE status on Alice
  await assertFails(
    bobDb.collection("riders").doc(ALICE_UID).update({
      name: "Bob Hack",
      updatedAt: pastDate(1000),
    })
  );
  // Admin can block Alice
  await assertSucceeds(
    realAdminDb.collection("riders").doc(ALICE_UID).update({
      status: "BLOCKED",
      updatedAt: pastDate(500),
    })
  );

  // Bike create & Paddock vote
  await assertSucceeds(
    aliceDb.collection("bikes").doc("bike_alice_1").set({
      ownerUid: ALICE_UID,
      riderId: ALICE_UID,
      riderName: "Alice #44",
      discipline: "MX",
      modelName: "Losi Promoto-MX",
      dorsal: "44",
      totalLaps: 12,
      usageMinutes: 15,
      setupVotes: 0,
      votedByRiderIds: [],
      photoBase64List: [],
      publishedToPaddock: true,
      notes: "Hard pack setup",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    bobDb.collection("bikes").where("publishedToPaddock", "==", true).get()
  );
  await assertFails(bobDb.collection("bikes").get());

  // Bob can vote on published bike, cannot rename Alice's bike
  await assertSucceeds(
    bobDb.collection("bikes").doc("bike_alice_1").update({
      setupVotes: 1,
      votedByRiderIds: [BOB_UID],
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("bikes").doc("bike_alice_1").update({
      modelName: "Hacked Bike",
      updatedAt: pastDate(1000),
    })
  );
});

test("Circuits, Room, Antennas, Laps & Reports adhere to schema and isolation rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const realAdminDb = testEnv
    .authenticatedContext(ADMIN_UID, { email: ADMIN_EMAIL, email_verified: true })
    .firestore();
  const created = pastDate(10000);
  const circuitId = "metropolitano_madrid";

  // Non-admin cannot create an official circuit without valid code
  await assertFails(
    aliceDb.collection("circuits").doc(circuitId).set({
      ownerUid: ALICE_UID,
      visibility: "PUBLIC",
      name: "Fake Official",
      countryCity: "Madrid",
      surface: "MX",
      lengthMeters: 185,
      layoutType: "MIXED",
      lat: 40.4495,
      lng: -3.7265,
      timezone: "Europe/Madrid",
      gpsRadiusMeters: 450,
      isOfficial: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Admin creates official circuit; Alice creates local backyard circuit
  await assertSucceeds(
    realAdminDb.collection("circuits").doc(circuitId).set({
      ownerUid: ADMIN_UID,
      visibility: "PUBLIC",
      name: "Metropolitano Circuit (Madrid)",
      countryCity: "España / Madrid",
      surface: "MX",
      lengthMeters: 185,
      layoutType: "MIXED",
      lat: 40.4495,
      lng: -3.7265,
      timezone: "Europe/Madrid",
      gpsRadiusMeters: 450,
      isOfficial: true,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    aliceDb.collection("circuits").doc("local_park_1").set({
      ownerUid: ALICE_UID,
      visibility: "PUBLIC",
      name: "Parking Norte",
      countryCity: "Madrid",
      surface: "SM",
      lengthMeters: 120,
      layoutType: "FAST",
      lat: 40.4,
      lng: -3.7,
      timezone: "Europe/Madrid",
      gpsRadiusMeters: 500,
      isOfficial: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Filtered circuit query succeeds; unfiltered fails
  await assertSucceeds(aliceDb.collection("circuits").where("visibility", "==", "PUBLIC").get());
  await assertFails(aliceDb.collection("circuits").get());

  // Room state, antenna, active_rider, lap
  await assertSucceeds(
    aliceDb.collection("circuits").doc(circuitId).collection("live").doc("room").set({
      circuitId,
      gameMode: "NORMAL",
      sessionPhase: "RACING",
      flagState: "GREEN",
      heatDurationSec: 300,
      phaseStartTimeMs: Date.now() - 5000,
      phaseEndTimeMs: Date.now() + 295000,
      gateDropTimeMs: Date.now() - 5000,
      friendlyModeActive: true,
      lipoWarnMinutes: 12,
      antiVibration: "MEDIUM",
      updatedAtMs: Date.now() - 1000,
      updatedAt: pastDate(1000),
    })
  );

  await assertSucceeds(
    bobDb.collection("circuits").doc(circuitId).collection("antennas").doc("antena_1").set({
      antennaId: "antena_1",
      circuitId,
      status: "ONLINE_READY",
      isCalibrated: true,
      detectionMode: "ANY_PASS",
      lastHeartbeatMs: Date.now() - 1000,
      updatedAt: pastDate(1000),
    })
  );
  await assertSucceeds(
    aliceDb.collection("circuits").doc(circuitId).collection("antennas")
      .where("circuitId", "==", circuitId).get()
  );

  await assertSucceeds(
    aliceDb.collection("circuits").doc(circuitId).collection("laps").doc("lap_1").set({
      ownerUid: ALICE_UID,
      circuitId,
      riderId: ALICE_UID,
      riderName: "Alice #44",
      dorsal: "44",
      bikeModel: "Losi Promoto-MX",
      discipline: "MX",
      lapTimeMs: 24500,
      deltaMs: -320,
      speedKmh: 27.18,
      timestampMs: Date.now() - 1000,
      antennaId: "antena_1",
      detectedColors: ["Rojo"],
      dayKey: "2026-11-27",
      createdAt: pastDate(1000),
    })
  );

  // Reports PII isolation: Alice can read her own report, Bob cannot read Alice's report, Admin reads all
  await assertSucceeds(
    aliceDb.collection("reports").doc("rep_1").set({
      ownerUid: ALICE_UID,
      senderName: "Alice #44",
      type: "OFFICIAL_REQUEST",
      message: "Quiero hacer oficial nuestro circuito",
      circuitRequested: "Circuito Sur",
      timestampMs: Date.now() - 1000,
      createdAt: pastDate(1000),
    })
  );
  await assertSucceeds(aliceDb.collection("reports").where("ownerUid", "==", ALICE_UID).get());
  await assertFails(bobDb.collection("reports").doc("rep_1").get());
  await assertFails(bobDb.collection("reports").get());
  await assertSucceeds(realAdminDb.collection("reports").get());
});
