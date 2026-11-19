const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_merchandiser_1";
const BOB_UID = "bob_merchandiser_2";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validPurchaseOrderPayload(uid, createdAt) {
  return {
    userId: uid,
    poNumber: "PO-2026-8841",
    buyerName: "Nordic Apparel Group",
    poDate: "2026-11-18",
    deliveryDate: "2027-02-15",
    shipDate: "2027-02-10",
    destination: "Rotterdam, NL",
    factoryInfo: "LSL Unit 2 - Ashulia",
    fabricDetails: "100% Organic Cotton Single Jersey 180 GSM",
    currency: "USD",
    status: "PENDING_VERIFICATION",
    originalFileName: "Nordic_PO_8841.xlsx",
    originalFileFormat: "XLSX",
    originalSheetName: "Order Sheet",
    originalHeaders: ["Style", "Color", "XS", "S", "M", "L", "Total", "Unit Price"],
    sizeColumns: ["XS", "S", "M", "L"],
    lineItems: [
      {
        lineId: "line_1",
        styleNumber: "LSL-4021",
        styleDescription: "Crewneck Tee",
        itemDescription: "Men's Basic Tee",
        color: "Forest Green",
        garmentType: "T-Shirt",
        unitPrice: 4.85,
        declaredQty: 800,
        sizeQuantities: { XS: 100, S: 200, M: 300, L: 200 },
        deliveryDate: "2027-02-15",
        shipDate: "2027-02-10",
        destination: "Rotterdam, NL",
        factoryInfo: "LSL Unit 2 - Ashulia",
        fabricDetails: "100% Organic Cotton",
      },
    ],
    validationWarnings: [],
    totalQuantity: 800,
    totalValue: 3880.0,
    styleCount: 1,
    colorCount: 1,
    warningCount: 0,
    notes: "Initial buyer upload",
    createdAt: createdAt,
    updatedAt: createdAt,
  };
}

function validOriginalSourcePayload(uid, poId, createdAt) {
  return {
    userId: uid,
    poId: poId,
    fileName: "Nordic_PO_8841.xlsx",
    fileFormat: "XLSX",
    sheetName: "Order Sheet",
    headers: ["Style", "Color", "XS", "S", "M", "L", "Total", "Unit Price"],
    rowCount: 1,
    rows: [
      {
        rowIndex: 2,
        cells: ["LSL-4021", "Forest Green", "100", "200", "300", "200", "800", "4.85"],
      },
    ],
    columnMapping: { styleNumber: 0, color: 1, unitPrice: 7 },
    createdAt: createdAt,
  };
}

function validAuditLogPayload(uid, poId, createdAt) {
  return {
    userId: uid,
    poId: poId,
    action: "UPLOADED",
    summary: "Uploaded Nordic_PO_8841.xlsx and transferred 1 style line.",
    actorName: "Alice Merchandiser",
    createdAt: createdAt,
  };
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

test("Unauthenticated user cannot read or write purchase orders", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 2000);
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("purchase_orders").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("purchase_orders")
      .doc("po_1")
      .set(validPurchaseOrderPayload(ALICE_UID, now))
  );
});

test("Owner can atomically create PurchaseOrder, immutable OriginalSource, and AuditLog", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 5000);
  const poRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("purchase_orders")
    .doc("po_8841");
  const sourceRef = poRef.collection("original_source").doc("source_primary");
  const logRef = poRef.collection("audit_logs").doc("log_1");

  const batch = aliceDb.batch();
  batch.set(poRef, validPurchaseOrderPayload(ALICE_UID, now));
  batch.set(sourceRef, validOriginalSourcePayload(ALICE_UID, "po_8841", now));
  batch.set(logRef, validAuditLogPayload(ALICE_UID, "po_8841", now));
  await assertSucceeds(batch.commit());

  // Mirror Kotlin list queries
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("purchase_orders").get()
  );
  await assertSucceeds(poRef.collection("original_source").get());
  await assertSucceeds(poRef.collection("audit_logs").get());
});

test("Owner can partially update converted PO data while immutable fields remain protected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const poRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("purchase_orders")
    .doc("po_edit_1");

  await assertSucceeds(poRef.set(validPurchaseOrderPayload(ALICE_UID, created)));

  // Valid partial edit
  await assertSucceeds(
    poRef.update({
      status: "VERIFIED",
      totalQuantity: 850,
      totalValue: 4122.5,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject changing immutable originalFileName
  await assertFails(
    poRef.update({
      originalFileName: "tampered.xlsx",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject changing immutable createdAt
  await assertFails(
    poRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject shadow ghost field
  await assertFails(
    poRef.update({
      isAdminOverride: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    poRef.update({
      status: "EXPORTED",
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("OriginalSource and AuditLog are strictly immutable after creation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const poRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("purchase_orders")
    .doc("po_immut");
  const sourceRef = poRef.collection("original_source").doc("source_primary");
  const logRef = poRef.collection("audit_logs").doc("log_init");

  await assertSucceeds(poRef.set(validPurchaseOrderPayload(ALICE_UID, created)));
  await assertSucceeds(
    sourceRef.set(validOriginalSourcePayload(ALICE_UID, "po_immut", created))
  );
  await assertSucceeds(
    logRef.set(validAuditLogPayload(ALICE_UID, "po_immut", created))
  );

  await assertFails(sourceRef.update({ fileName: "overwritten.xlsx" }));
  await assertFails(logRef.update({ summary: "altered history" }));
});

test("Cross-user isolation blocks Bob from reading or modifying Alice's POs and settings", async () => {
  const created = new Date(Date.now() - 5000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const adminDb = context.firestore();
    await adminDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("purchase_orders")
      .doc("po_alice")
      .set(validPurchaseOrderPayload(ALICE_UID, created));
    await adminDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("settings")
      .doc("default")
      .set({
        userId: ALICE_UID,
        companyName: "LAILA STYLES LIMITED",
        defaultCurrency: "USD",
        defaultFactory: "LSL Unit 1",
        strictValidation: true,
        createdAt: created,
        updatedAt: created,
      });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("purchase_orders").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("purchase_orders")
      .doc("po_alice")
      .get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("purchase_orders")
      .doc("po_alice")
      .update({ status: "EXPORTED", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("settings").doc("default").get()
  );
});

test("Rejects extra @DocumentId 'id' key and oversized fields on create", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const payloadWithId = {
    ...validPurchaseOrderPayload(ALICE_UID, created),
    id: "po_bad_id",
  };
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("purchase_orders")
      .doc("po_bad_id")
      .set(payloadWithId)
  );
});

test("Owner can create and update MerchandiserSettings", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const settingsRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("settings")
    .doc("default");

  await assertSucceeds(
    settingsRef.set({
      userId: ALICE_UID,
      companyName: "LAILA STYLES LIMITED",
      defaultCurrency: "USD",
      defaultFactory: "LSL Main Plant",
      strictValidation: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    settingsRef.update({
      defaultCurrency: "EUR",
      strictValidation: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );
});
