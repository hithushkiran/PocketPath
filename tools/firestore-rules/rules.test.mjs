import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import {
  collection, deleteDoc, deleteField, doc, getDoc, getDocs, serverTimestamp,
  setDoc, setLogLevel, Timestamp, updateDoc, writeBatch,
} from 'firebase/firestore';

const projectId = 'demo-pocketpath-pr3';
const owner = 'owner-pr3';
const root = `users/${owner}`;
const date = '2026-11-05';
const audit = Timestamp.fromDate(new Date('2026-11-05T06:00:00.123Z'));
let environment;
let db;

// Refuse standalone/cloud execution. No credentials or production project are used.
before(async () => {
  const address = process.env.FIRESTORE_EMULATOR_HOST;
  assert.ok(address, 'Run through firebase emulators:exec, never against production.');
  const match = /^(127\.0\.0\.1|localhost|\[::1\]):([0-9]+)$/.exec(address);
  assert.ok(match, `Refusing non-loopback emulator host: ${address}`);
  for (const name of ['GCLOUD_PROJECT', 'GOOGLE_CLOUD_PROJECT']) {
    if (process.env[name]) assert.equal(process.env[name], projectId, `${name} must name the demo project`);
  }
  setLogLevel('silent'); // Expected permission denials remain assertions, not noisy SDK logs.
  environment = await initializeTestEnvironment({
    projectId,
    firestore: {
      host: match[1] === '[::1]' ? '::1' : match[1],
      port: Number(match[2]),
      rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
    },
  });
  db = environment.authenticatedContext(owner).firestore(); // Default database only.
});

after(async () => { if (environment) await environment.cleanup(); });

function profile(time = serverTimestamp()) {
  return {
    baseCurrency: 'LKR', timeZone: 'Asia/Colombo', weekStartsOn: 1,
    onboardingCompleted: false, hideBalances: false, remindersEnabled: true,
    schemaVersion: 1, createdAt: time, updatedAt: time,
  };
}

function account(id = 'bank', openingId = 'opening-bank', time = serverTimestamp()) {
  return {
    name: id, type: 'BANK', currencyCode: 'LKR', assetClass: 'FIAT', scale: 2,
    includeInEverydayPool: true, openingTransactionId: openingId, openedOn: date,
    createdAt: time, updatedAt: time, revision: 0,
  };
}

function payment(time = serverTimestamp()) {
  // FoundationCodec.expectedPaymentData: all amounts are exact LKR minor units.
  return {
    incomeSourceId: 'source', projectLabel: 'PR3 fixture', currencyCode: 'LKR',
    amountDueMinor: 6_000_000, dueDate: '2026-11-15', state: 'ACTIVE', receivedAmountMinor: 0,
    createdAt: time, updatedAt: time, revision: 0,
  };
}

function event(id, kind = 'INCOME', amountMinor = 2_000_000, accountId = 'bank', time = serverTimestamp()) {
  const components = kind === 'OPENING' ? [] : kind === 'EXPENSE'
    ? [{ nature: 'SPENDING', amountLkrMinor: -amountMinor, categoryId: 'food', commitmentClass: 'DISCRETIONARY' }]
    : [{ nature: 'EARNING', amountLkrMinor: amountMinor, incomeSourceId: 'source' }];
  return {
    operationId: id, kind, status: 'POSTED', occurredAt: audit, occurredLocalDate: date,
    recordedTimeZone: 'Asia/Colombo', clientCreatedAt: audit, createdAt: time,
    schemaVersion: 1, revision: 0, accountIds: [accountId],
    categoryIds: kind === 'EXPENSE' ? ['food'] : [], goalIds: [],
    incomeSourceIds: kind === 'INCOME' ? ['source'] : [],
    movements: [{ accountId, currencyCode: 'LKR', assetClass: 'FIAT', amountMinor }],
    components, reservationEffects: [], valuations: [],
  };
}

function receiptId(revision) {
  // StableIds.expectedPaymentReceipt uses UTF-8 SHA-256 of length:paymentId:revision.
  return `expected-receipt_${createHash('sha256').update(`7:payment:${revision}`, 'utf8').digest('hex')}`;
}

function receipt(amount = 2_000_000, revision = 0) {
  const id = receiptId(revision);
  return { ...event(id, 'INCOME', amount), expectedPaymentId: 'payment' };
}

const paymentRef = (client = db) => doc(client, root, 'expectedPayments', 'payment');
const eventRef = (id, client = db) => doc(client, root, 'transactions', id);

function receiptUpdate(id, received, revision) {
  return { receivedAmountMinor: received, lastReceiptOperationId: id, updatedAt: serverTimestamp(), revision };
}

function receiptBatch(amount = 2_000_000, revision = 0, alreadyReceived = 0, client = db) {
  const batch = writeBatch(client);
  batch.set(eventRef(receiptId(revision), client), receipt(amount, revision));
  batch.update(paymentRef(client), receiptUpdate(receiptId(revision), alreadyReceived + amount, revision + 1));
  return batch;
}

function cancelPatch(revision = 1) {
  return { state: 'CANCELLED', cancelledAt: serverTimestamp(), cancellationOperationId: 'cancel-payment', updatedAt: serverTimestamp(), revision };
}

beforeEach(async () => {
  await environment.clearFirestore();
  await environment.withSecurityRulesDisabled(async (context) => {
    const admin = context.firestore();
    const batch = writeBatch(admin);
    batch.set(doc(admin, 'users', owner), profile(audit));
    batch.set(doc(admin, root, 'accounts', 'bank'), account('bank', 'opening-bank', audit));
    batch.set(doc(admin, root, 'incomeSources', 'source'), {
      name: 'Salary', kind: 'SALARY', defaultCurrencyCode: 'LKR', createdAt: audit, updatedAt: audit, revision: 0,
    });
    batch.set(doc(admin, root, 'categories', 'food'), {
      name: 'Food', defaultCommitmentClass: 'DISCRETIONARY', sortOrder: 0, createdAt: audit, updatedAt: audit, revision: 0,
    });
    batch.set(paymentRef(admin), payment(audit));
    batch.set(eventRef('existing-manual', admin), event('existing-manual', 'INCOME', 100_000, 'bank', audit));
    await batch.commit();
  });
});

async function state(ids) {
  return {
    payment: (await getDoc(paymentRef())).data(),
    receipts: await Promise.all(ids.map(async (id) => (await getDoc(eventRef(id))).data() ?? null)),
  };
}

async function deniedUnchanged(write, ids = [receiptId(0)]) {
  const before = await state(ids);
  await assertFails(write());
  assert.deepEqual(await state(ids), before, 'Denied writes must leave receipts and payment totals unchanged');
}

test('owner can read and query own expected payments and receipts', async () => {
  assert.equal((await assertSucceeds(getDoc(paymentRef()))).data().amountDueMinor, 6_000_000);
  assert.equal((await assertSucceeds(getDoc(eventRef('existing-manual')))).data().kind, 'INCOME');
  assert.equal((await assertSucceeds(getDocs(collection(db, root, 'expectedPayments')))).size, 1);
  assert.equal((await assertSucceeds(getDocs(collection(db, root, 'transactions')))).size, 1);
});

for (const identity of ['other-user', null]) {
  test(`${identity ?? 'unauthenticated'} cannot read or write owner's payments/receipts`, async () => {
    const client = identity ? environment.authenticatedContext(identity).firestore() : environment.unauthenticatedContext().firestore();
    await assertFails(getDoc(paymentRef(client)));
    await assertFails(getDoc(eventRef('existing-manual', client)));
    await assertFails(getDocs(collection(client, root, 'expectedPayments')));
    await assertFails(getDocs(collection(client, root, 'transactions')));
    await deniedUnchanged(() => setDoc(doc(client, root, 'expectedPayments', 'new-payment'), payment()));
    assert.equal((await getDoc(doc(db, root, 'expectedPayments', 'new-payment'))).exists(), false);
    await deniedUnchanged(() => setDoc(eventRef('new-income', client), event('new-income')), ['new-income']);
    await deniedUnchanged(() => receiptBatch(2_000_000, 0, 0, client).commit());
    await deniedUnchanged(() => updateDoc(paymentRef(client), cancelPatch()));
    await deniedUnchanged(() => deleteDoc(paymentRef(client)));
    await deniedUnchanged(() => deleteDoc(eventRef('existing-manual', client)), ['existing-manual']);
  });
}

test('owner creates a codec-shaped expected payment', async () => {
  const reference = doc(db, root, 'expectedPayments', 'new-payment');
  await assertSucceeds(setDoc(reference, payment()));
  const saved = (await getDoc(reference)).data();
  assert.equal(saved.receivedAmountMinor, 0);
  assert.equal(saved.revision, 0);
});

test('ordinary manual income succeeds without an expected-payment link', async () => {
  await assertSucceeds(setDoc(eventRef('manual'), event('manual')));
  assert.equal((await getDoc(eventRef('manual'))).data().movements[0].amountMinor, 2_000_000);
  assert.equal((await getDoc(paymentRef())).data().receivedAmountMinor, 0);
});

function openingBatch(linked = false) {
  const batch = writeBatch(db);
  batch.set(doc(db, root, 'accounts', 'new-bank'), account('new-bank', 'new-opening'));
  const opening = event('new-opening', 'OPENING', 1_000_000, 'new-bank');
  batch.set(eventRef('new-opening'), linked ? { ...opening, expectedPaymentId: 'payment' } : opening);
  return batch;
}

test('otherwise valid account/opening pair succeeds', async () => {
  await assertSucceeds(openingBatch().commit());
  assert.equal((await getDoc(eventRef('new-opening'))).data().kind, 'OPENING');
});

test('otherwise valid opening rejects an expected-payment link atomically', async () => {
  await deniedUnchanged(() => openingBatch(true).commit(), ['new-opening']);
  assert.equal((await getDoc(doc(db, root, 'accounts', 'new-bank'))).exists(), false);
});

test('otherwise valid ordinary expense succeeds', async () => {
  await assertSucceeds(setDoc(eventRef('expense'), event('expense', 'EXPENSE', -85_000)));
});

test('otherwise valid expense rejects an expected-payment link', async () => {
  await deniedUnchanged(() => setDoc(eventRef('expense'), {
    ...event('expense', 'EXPENSE', -85_000), expectedPaymentId: 'payment',
  }), ['expense']);
});

test('partial receipt and matching payment update succeed together', async () => {
  await assertSucceeds(receiptBatch().commit());
  const saved = await state([receiptId(0)]);
  assert.equal(saved.payment.receivedAmountMinor, 2_000_000);
  assert.equal(saved.payment.revision, 1);
  assert.equal(saved.payment.lastReceiptOperationId, receiptId(0));
  assert.equal(saved.receipts[0].movements[0].amountMinor, 2_000_000);
  assert.equal(saved.receipts[0].expectedPaymentId, 'payment');
});

test('receipt write alone is denied with no partial state', async () => {
  await deniedUnchanged(() => setDoc(eventRef(receiptId(0)), receipt()));
});

test('payment receipt-update alone is denied with no partial state', async () => {
  await deniedUnchanged(() => updateDoc(paymentRef(), receiptUpdate(receiptId(0), 2_000_000, 1)));
});

test('overpayment pair is denied and preserves the preceding partial receipt', async () => {
  await assertSucceeds(receiptBatch().commit());
  await deniedUnchanged(() => receiptBatch(4_000_001, 1, 2_000_000).commit(), [receiptId(0), receiptId(1)]);
});

test('receipt after cancellation is denied without changing the cancellation', async () => {
  await assertSucceeds(updateDoc(paymentRef(), cancelPatch()));
  await deniedUnchanged(() => receiptBatch(2_000_000, 1).commit(), [receiptId(1)]);
});

test('second write to a receipt ID and deletion cannot change prior totals', async () => {
  await assertSucceeds(receiptBatch().commit());
  await deniedUnchanged(() => setDoc(eventRef(receiptId(0)), receipt()));
  const rewrite = writeBatch(db);
  rewrite.set(eventRef(receiptId(0)), receipt());
  rewrite.update(paymentRef(), receiptUpdate(receiptId(0), 4_000_000, 2));
  await deniedUnchanged(() => rewrite.commit());
  await deniedUnchanged(() => deleteDoc(eventRef(receiptId(0))));
});

test('valid cancellation succeeds and preserves the received counter', async () => {
  await assertSucceeds(receiptBatch().commit());
  await assertSucceeds(updateDoc(paymentRef(), cancelPatch(2)));
  const saved = (await getDoc(paymentRef())).data();
  assert.equal(saved.state, 'CANCELLED');
  assert.equal(saved.revision, 2);
  assert.equal(saved.receivedAmountMinor, 2_000_000);
  assert.equal(saved.cancellationOperationId, 'cancel-payment');
});

test('revision rollback during cancellation is denied', async () => {
  await assertSucceeds(receiptBatch().commit());
  await deniedUnchanged(() => updateDoc(paymentRef(), cancelPatch(0)));
});

test('reactivation of a cancelled payment is denied', async () => {
  await assertSucceeds(updateDoc(paymentRef(), cancelPatch()));
  await deniedUnchanged(() => updateDoc(paymentRef(), {
    state: 'ACTIVE', revision: 2, updatedAt: serverTimestamp(),
    cancelledAt: deleteField(), cancellationOperationId: deleteField(),
  }));
});

test('owner cannot delete an expected payment', async () => {
  await deniedUnchanged(() => deleteDoc(paymentRef()));
});


// PR #4: immutable budgets and one-way expense voids, matching FoundationCodec.
function budget(overrides = {}) {
  return {
    scope: 'OVERALL_DISCRETIONARY', periodType: 'MONTHLY', limitLkrMinor: 5_000_000,
    effectiveFrom: date, rolloverEnabled: false, state: 'ACTIVE',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0, ...overrides,
  };
}
function voidPatch(overrides = {}) {
  return {
    status: 'VOIDED', voidMetadata: { voidedAt: audit, voidReason: 'Duplicate entry' },
    updatedAt: serverTimestamp(), revision: 1, ...overrides,
  };
}
async function postedExpense() {
  await assertSucceeds(setDoc(eventRef('void-target'), event('void-target', 'EXPENSE', -50_000)));
  return (await getDoc(eventRef('void-target'))).data();
}
async function deniedVoid(patch, before, client = db) {
  await assertFails(updateDoc(eventRef('void-target', client), patch));
  assert.deepEqual((await getDoc(eventRef('void-target'))).data(), before);
}

test('owner creates both codec-shaped budget scopes with server audit data', async () => {
  for (const [id, overrides] of [
    ['overall', {}], ['category', { scope: 'CATEGORY_DISCRETIONARY', categoryId: 'food', periodType: 'WEEKLY', effectiveTo: '2026-12-31' }],
  ]) {
    const ref = doc(db, root, 'budgets', id);
    await assertSucceeds(setDoc(ref, budget(overrides)));
    const saved = (await getDoc(ref)).data();
    assert.ok(saved.createdAt instanceof Timestamp);
    assert.deepEqual(saved.createdAt, saved.updatedAt);
    assert.equal(saved.revision, 0);
    await assertFails(updateDoc(ref, { limitLkrMinor: 10 }));
    await assertFails(deleteDoc(ref));
    assert.deepEqual((await getDoc(ref)).data(), saved);
  }
});

test('invalid budgets and forged audit fields are denied without creating records', async () => {
  const invalid = [
    { limitLkrMinor: 0 }, { limitLkrMinor: -1 }, { limitLkrMinor: 1.001 }, { limitLkrMinor: 100000000001 },
    { scope: 'OTHER' }, { periodType: 'DAILY' }, { rolloverEnabled: true }, { state: 'ARCHIVED' },
    { categoryId: 'food' }, { scope: 'CATEGORY_DISCRETIONARY' },
    { scope: 'CATEGORY_DISCRETIONARY', categoryId: 'missing' },
    { scope: 'CATEGORY_DISCRETIONARY', categoryId: 123 },
    { effectiveFrom: '2026-02-30' }, { effectiveTo: '2026-01-01' }, { effectiveTo: null },
    { revision: 1 }, { createdAt: audit }, { updatedAt: audit }, { extra: true },
  ];
  for (const [i, patch] of invalid.entries()) {
    const ref = doc(db, root, 'budgets', `invalid-${i}`);
    await assertFails(setDoc(ref, budget(patch)));
    assert.equal((await getDoc(ref)).exists(), false);
  }
});

for (const identity of ['other-user', null]) {
  test(`${identity ?? 'unauthenticated'} cannot access budgets or void an owner's expense`, async () => {
    const client = identity ? environment.authenticatedContext(identity).firestore() : environment.unauthenticatedContext().firestore();
    await setDoc(doc(db, root, 'budgets', 'private'), budget());
    const ref = doc(client, root, 'budgets', 'private');
    await assertFails(getDoc(ref));
    await assertFails(getDocs(collection(client, root, 'budgets')));
    await assertFails(setDoc(doc(client, root, 'budgets', 'new'), budget()));
    await assertFails(updateDoc(ref, { limitLkrMinor: 1 }));
    await assertFails(deleteDoc(ref));
    assert.equal((await getDoc(doc(db, root, 'budgets', 'new'))).exists(), false);
    const before = await postedExpense();
    await deniedVoid(voidPatch(), before, client);
  });
}

test('expense void saves Firestore timestamps and revision one, preserving every original financial and creation field', async () => {
  const before = await postedExpense();
  await assertSucceeds(updateDoc(eventRef('void-target'), voidPatch()));
  const saved = (await getDoc(eventRef('void-target'))).data();
  assert.ok(saved.voidMetadata.voidedAt instanceof Timestamp);
  assert.ok(saved.updatedAt instanceof Timestamp);
  assert.deepEqual(saved.voidMetadata, { voidedAt: audit, voidReason: 'Duplicate entry' });
  assert.equal(saved.revision, 1);
  assert.equal(saved.status, 'VOIDED');
  const { status, revision, updatedAt, voidMetadata, ...unchanged } = saved;
  assert.deepEqual(unchanged, Object.fromEntries(Object.entries(before).filter(([key]) => !['status', 'revision'].includes(key))));
  await deniedVoid(voidPatch({ revision: 2 }), saved);
  await deniedVoid({ status: 'POSTED', revision: 2, updatedAt: serverTimestamp(), voidMetadata: deleteField() }, saved);
  await assertFails(deleteDoc(eventRef('void-target')));
  assert.deepEqual((await getDoc(eventRef('void-target'))).data(), saved);
});

test('expense void rejects invalid lifecycle and audit data and any mutation/removal of original fields', async () => {
  const before = await postedExpense();
  for (const patch of [
    { revision: 0 }, { revision: 2 }, { updatedAt: audit }, { updatedAt: deleteField() },
    { voidMetadata: { voidedAt: '2026-11-05', voidReason: 'reason' } },
    { voidMetadata: { voidedAt: audit, voidReason: '   ' } },
    { voidMetadata: { voidedAt: audit, voidReason: 'x'.repeat(1001) } },
    { voidMetadata: { voidedAt: audit, voidReason: 'reason', extra: true } },
    { voidMetadata: deleteField() }, { status: 'POSTED' }, { unknown: true },
  ]) await deniedVoid(voidPatch(patch), before);
  // This includes all monetary movements/components, query IDs, operation ID,
  // schema version, original date/time, and createdAt/clientCreatedAt audit data.
  for (const key of Object.keys(before).filter(key => !['status', 'revision'].includes(key))) {
    await deniedVoid(voidPatch({ [key]: deleteField() }), before);
  }
  await deniedVoid(voidPatch({ movements: [{ ...before.movements[0], amountMinor: -1 }] }), before);
  await deniedVoid(voidPatch({ components: [{ ...before.components[0], amountLkrMinor: 1 }] }), before);
  await deniedVoid(voidPatch({ createdAt: serverTimestamp() }), before);
});

test('income receipts and openings cannot be voided and already-voided expenses cannot be created', async () => {
  await assertFails(updateDoc(eventRef('existing-manual'), voidPatch()));
  await assertSucceeds(receiptBatch().commit());
  await assertFails(updateDoc(eventRef(receiptId(0)), voidPatch()));
  await assertSucceeds(openingBatch().commit());
  await assertFails(updateDoc(eventRef('new-opening'), voidPatch()));
  await assertFails(setDoc(eventRef('born-void'), { ...event('born-void', 'EXPENSE', -50_000), ...voidPatch() }));
  assert.equal((await getDoc(eventRef('born-void'))).exists(), false);
});
