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

const billId = 'monthly-rent';
const billDate = '2026-11-30';
const billOccurrenceId = `${billId}_${billDate}`;
const billScheduleRef = (client = db) => doc(client, root, 'schedules', billId);
const billOccurrenceRef = (client = db, id = billOccurrenceId) => doc(client, root, 'occurrences', id);

function monthlyBillPair() {
  const common = {
    kind: 'COMMITMENT', timeZone: 'Asia/Colombo', currencyCode: 'LKR',
    expectedAmountMinor: 3_400_000, categoryId: 'food', commitmentClass: 'COMMITTED',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0,
  };
  return {
    schedule: {
      ...common, title: 'Rent', frequency: 'MONTHLY', interval: 1,
      anchorDate: billDate, activeFrom: billDate, amountMode: 'FIXED', paused: false, reminderLeadDays: 0,
    },
    occurrence: {
      ...common, scheduleId: billId, nominalDate: billDate, effectiveDueDate: billDate,
      titleSnapshot: 'Rent', state: 'OPEN',
    },
  };
}

function writeMonthlyBill({ schedule, occurrence }, client = db, occurrenceId = billOccurrenceId) {
  const batch = writeBatch(client);
  batch.set(billScheduleRef(client), schedule);
  batch.set(billOccurrenceRef(client, occurrenceId), occurrence);
  return batch.commit();
}

test('owner can create a fixed monthly bill and its first due occurrence together', async () => {
  const before = (await getDocs(collection(db, root, 'transactions'))).size;
  await assertSucceeds(writeMonthlyBill(monthlyBillPair()));
  const schedule = (await getDoc(billScheduleRef())).data();
  const occurrence = (await getDoc(billOccurrenceRef())).data();
  assert.equal(schedule.frequency, 'MONTHLY');
  assert.equal(occurrence.state, 'OPEN');
  assert.equal(occurrence.effectiveDueDate, billDate);
  assert.equal(occurrence.expectedAmountMinor, 3_400_000);
  assert.ok(schedule.createdAt instanceof Timestamp);
  assert.deepEqual(schedule.createdAt, occurrence.createdAt);
  assert.equal((await getDocs(collection(db, root, 'transactions'))).size, before);
});

test('bill rules reject incomplete pairs, altered snapshots and unowned categories', async () => {
  const { schedule, occurrence } = monthlyBillPair();
  await assertFails(setDoc(billScheduleRef(), schedule));
  await assertFails(setDoc(billOccurrenceRef(), occurrence));
  await assertFails(writeMonthlyBill({ schedule, occurrence }, db, 'wrong-occurrence-id'));
  for (const overrides of [
    { expectedAmountMinor: 1 },
    { effectiveDueDate: '2026-12-01' },
    { titleSnapshot: 'Different title' },
  ]) {
    await assertFails(writeMonthlyBill({ schedule, occurrence: { ...occurrence, ...overrides } }));
  }
  await assertFails(writeMonthlyBill({
    schedule: { ...schedule, categoryId: 'missing' },
    occurrence: { ...occurrence, categoryId: 'missing' },
  }));
  assert.equal((await getDocs(collection(db, root, 'schedules'))).size, 0);
  assert.equal((await getDocs(collection(db, root, 'occurrences'))).size, 0);
});

test('bill pairs are owner-only and immutable after creation', async () => {
  const other = environment.authenticatedContext('other-user').firestore();
  await assertFails(writeMonthlyBill(monthlyBillPair(), other));
  await assertSucceeds(writeMonthlyBill(monthlyBillPair()));
  await assertFails(getDoc(billScheduleRef(other)));
  await assertFails(getDoc(billOccurrenceRef(other)));
  await assertFails(updateDoc(billScheduleRef(), { title: 'Edited' }));
  await assertFails(updateDoc(billOccurrenceRef(), { state: 'CONFIRMED' }));
  await assertFails(deleteDoc(billScheduleRef()));
  await assertFails(deleteDoc(billOccurrenceRef()));
});

function billPayment(id, amount) {
  const expense = event(id, 'EXPENSE', -amount);
  return {
    ...expense,
    occurrenceId: billOccurrenceId,
    components: [{ ...expense.components[0], commitmentClass: 'COMMITTED' }],
  };
}

function billProgress(expenseId, paidAmountMinor, revision) {
  return { lastBillExpenseId: expenseId, paidAmountMinor, revision, updatedAt: serverTimestamp() };
}

function writeBillPayment(expense, total, revision, client = db) {
  const batch = writeBatch(client);
  batch.set(eventRef(expense.operationId, client), expense);
  batch.update(billOccurrenceRef(client), billProgress(expense.operationId, total, revision));
  return batch.commit();
}

test('bill payment posts once, allows partial and full settlement, and an atomic void restores the outstanding amount', async () => {
  await assertSucceeds(writeMonthlyBill(monthlyBillPair()));
  const first = billPayment('rent-partial', 1_000_000);
  await assertSucceeds(writeBillPayment(first, 1_000_000, 1));
  const partial = (await getDoc(billOccurrenceRef())).data();
  assert.equal(partial.expectedAmountMinor - partial.paidAmountMinor, 2_400_000);
  assert.deepEqual((await getDoc(eventRef(first.operationId))).data().movements, first.movements);
  await assertFails(writeBillPayment(first, 2_000_000, 2));
  await assertSucceeds(writeBillPayment(billPayment('rent-remainder', 2_400_000), 3_400_000, 2));
  assert.equal((await getDoc(billOccurrenceRef())).data().paidAmountMinor, 3_400_000);
  await assertFails(updateDoc(eventRef(first.operationId), voidPatch()));

  const batch = writeBatch(db);
  batch.update(eventRef(first.operationId), voidPatch());
  batch.update(billOccurrenceRef(), billProgress(first.operationId, 2_400_000, 3));
  await assertSucceeds(batch.commit());
  assert.equal((await getDoc(billOccurrenceRef())).data().paidAmountMinor, 2_400_000);
  assert.equal((await getDoc(eventRef(first.operationId))).data().status, 'VOIDED');
  await assertFails(updateDoc(billOccurrenceRef(), billProgress(first.operationId, 1_400_000, 4)));
});

test('bill payment denies unpaired writes, invented totals, overpayments and another user', async () => {
  await assertSucceeds(writeMonthlyBill(monthlyBillPair()));
  const expense = billPayment('unapplied-rent', 1_000_000);
  await assertFails(setDoc(eventRef(expense.operationId), expense));
  await assertFails(updateDoc(billOccurrenceRef(), billProgress(expense.operationId, 1_000_000, 1)));
  await assertFails(writeBillPayment(expense, 999_999, 1));
  await assertFails(writeBillPayment(billPayment('overpayment', 3_400_001), 3_400_001, 1));
  const other = environment.authenticatedContext('other-user').firestore();
  await assertFails(writeBillPayment(expense, 1_000_000, 1, other));
  assert.equal((await getDoc(billOccurrenceRef())).data().revision, 0);
  assert.equal((await getDoc(eventRef(expense.operationId))).exists(), false);
});

test('later bills use the original monthly anchor and leave the unpaid first occurrence and ledger alone', async () => {
  const { schedule, occurrence } = monthlyBillPair();
  const anchor = '2027-01-31';
  const anchorRef = billOccurrenceRef(db, `${billId}_${anchor}`);
  const onDate = (date) => ({ ...occurrence, nominalDate: date, effectiveDueDate: date });
  await assertSucceeds(writeMonthlyBill({
    schedule: { ...schedule, anchorDate: anchor, activeFrom: anchor },
    occurrence: onDate(anchor),
  }, db, anchorRef.id));

  const feb = billOccurrenceRef(db, `${billId}_2027-02-28`);
  const mar = billOccurrenceRef(db, `${billId}_2027-03-31`);
  await assertFails(setDoc(billOccurrenceRef(db, `${billId}_2027-02-27`), onDate('2027-02-27')));
  await assertFails(setDoc(billOccurrenceRef(db, `${billId}_2027-03-28`), onDate('2027-03-28')));
  await assertFails(setDoc(feb, { ...onDate('2027-02-28'), expectedAmountMinor: 1 }));
  await assertSucceeds(setDoc(feb, onDate('2027-02-28')));
  await assertSucceeds(setDoc(mar, onDate('2027-03-31')));
  await assertFails(setDoc(feb, onDate('2027-02-28')));
  assert.equal((await getDoc(anchorRef)).data().revision, 0);
  assert.equal((await getDocs(collection(db, root, 'occurrences'))).size, 3);
  assert.equal((await getDocs(collection(db, root, 'transactions'))).size, 1);
});

function goalDocument(overrides = {}) {
  return {
    name: 'Laptop', targetLkrMinor: 49_000_000,
    startDate: '2026-11-01', deadline: '2027-10-31', firstContributionDate: '2026-11-05',
    state: 'ACTIVE', createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0,
    ...overrides,
  };
}

const goalRef = (client = db) => doc(client, root, 'goals', 'laptop');
const backingRef = (client = db) => doc(client, root, 'accounts', 'bank');

function goalReservation(id, overrides = {}) {
  return {
    ...event(id, 'GOAL_RESERVE'), movements: [], components: [],
    accountIds: ['bank'], categoryIds: [], goalIds: ['laptop'],
    reservationEffects: [{ goalId: 'laptop', backingAccountId: 'bank', amountLkrMinor: 2_000_000 }],
    ...overrides,
  };
}

function reserveAccountPatch(id, revision) {
  return { lastGoalReserveId: id, revision, updatedAt: serverTimestamp() };
}

function writeGoalReservation(reservation, revision, client = db) {
  const batch = writeBatch(client);
  batch.set(eventRef(reservation.operationId, client), reservation);
  batch.update(backingRef(client), reserveAccountPatch(reservation.operationId, revision));
  return batch.commit();
}

test('goals allow owner creation with valid dates and reject mutable saved balances', async () => {
  const other = environment.authenticatedContext('other-user').firestore();
  await assertFails(setDoc(goalRef(other), goalDocument()));
  await assertFails(setDoc(goalRef(), goalDocument({ firstContributionDate: '2027-11-01' })));
  await assertFails(setDoc(goalRef(), goalDocument({ savedLkrMinor: 2_000_000 })));
  await assertSucceeds(setDoc(goalRef(), goalDocument()));
  await assertFails(getDoc(goalRef(other)));
  await assertFails(updateDoc(goalRef(), { savedLkrMinor: 2_000_000 }));
  await assertFails(deleteDoc(goalRef()));
});

test('goal contribution and backing revision must commit together under one owner', async () => {
  const reservation = goalReservation('allocate-laptop');
  await assertFails(writeGoalReservation(reservation, 1));
  await assertSucceeds(setDoc(goalRef(), goalDocument()));
  await assertFails(setDoc(eventRef(reservation.operationId), reservation));
  await assertFails(updateDoc(backingRef(), reserveAccountPatch(reservation.operationId, 1)));
  await assertFails(writeGoalReservation({ ...reservation, goalIds: ['other'] }, 1));
  await assertFails(writeGoalReservation({ ...reservation, movements: event('wrong').movements }, 1));
  await assertFails(writeGoalReservation(reservation, 1, environment.authenticatedContext('other-user').firestore()));

  await assertSucceeds(writeGoalReservation(reservation, 1));
  await assertFails(writeGoalReservation(reservation, 2));
  await assertFails(writeGoalReservation(goalReservation('stale-allocation'), 1));
  await assertFails(deleteDoc(eventRef(reservation.operationId)));
  const recorded = (await getDoc(eventRef(reservation.operationId))).data();
  assert.deepEqual(recorded.movements, []);
  assert.deepEqual(recorded.components, []);
  assert.deepEqual(recorded.reservationEffects, reservation.reservationEffects);
  const backing = (await getDoc(backingRef())).data();
  assert.equal(backing.revision, 1);
  assert.equal(backing.openingTransactionId, 'opening-bank');
  assert.equal(backing.lastGoalReserveId, reservation.operationId);
});

async function createAdSenseSource() {
  await assertSucceeds(setDoc(doc(db, root, 'incomeSources', 'adsense'), {
    name: 'AdSense', kind: 'ADSENSE', defaultCurrencyCode: 'LKR',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0,
  }));
}

function convertedUsdEvent(id, credit = 301, overrides = {}) {
  const receipt = event(id, 'INCOME', credit);
  return {
    ...receipt,
    incomeSourceIds: ['adsense'],
    components: [{
      ...receipt.components[0],
      incomeSourceId: 'adsense',
      originalCurrency: 'USD', originalAmountMinor: 1,
      valuationRate: '300.500000', rateSource: 'MANUAL_ACTUAL',
      ...overrides,
    }],
  };
}

test('converted AdSense receipt credits only correctly rounded LKR and retains immutable USD details', async () => {
  await createAdSenseSource();
  const ref = eventRef('converted-adsense');
  await assertSucceeds(setDoc(ref, convertedUsdEvent(ref.id)));
  const saved = (await getDoc(ref)).data();
  assert.equal(saved.movements.length, 1);
  assert.equal(saved.movements[0].currencyCode, 'LKR');
  assert.equal(saved.movements[0].amountMinor, 301);
  assert.equal(saved.components[0].originalAmountMinor, 1);
  assert.equal(saved.components[0].valuationRate, '300.500000');
  assert.equal(saved.components[0].rateSource, 'MANUAL_ACTUAL');
  await assertFails(updateDoc(ref, { components: convertedUsdEvent(ref.id, 301, { valuationRate: '300.600000' }).components }));
});

test('USD metadata cannot bypass source, ownership, precision, credit or receipt-link checks', async () => {
  await createAdSenseSource();
  const receipt = convertedUsdEvent('rejected-conversion');
  const component = receipt.components[0];
  const { rateSource, ...partial } = component;
  for (const invalid of [
    convertedUsdEvent(receipt.operationId, 300),
    { ...receipt, components: [partial] },
    { ...receipt, incomeSourceIds: ['source'], components: [{ ...component, incomeSourceId: 'source' }] },
    convertedUsdEvent(receipt.operationId, 301, { valuationRate: '300.5000001' }),
    convertedUsdEvent(receipt.operationId, 301, { valuationRate: '0300.500000' }),
    convertedUsdEvent(receipt.operationId, 301, { valuationRate: '9999.999999', originalAmountMinor: 100_000_000_000 }),
    { ...receipt, expectedPaymentId: 'payment' },
  ]) {
    await assertFails(setDoc(eventRef(receipt.operationId), invalid));
  }
  const other = environment.authenticatedContext('other-user').firestore();
  await assertFails(setDoc(eventRef(receipt.operationId, other), receipt));
  assert.equal((await getDoc(eventRef(receipt.operationId))).exists(), false);
  assert.equal((await getDoc(paymentRef())).data().receivedAmountMinor, 0);
});

const walletId = 'wallet-ETH';
const walletOpeningId = 'opening-wallet-ETH';
const positionRef = (client = db) => doc(client, root, 'cryptoPositions', walletId);
const ethMovement = (quantityAtomic) => ({
  accountId: walletId, currencyCode: 'ETH', assetClass: 'CRYPTO', scale: 8, quantityAtomic,
});

function createEthWallet(quantity = '0') {
  const batch = writeBatch(db);
  batch.set(doc(db, root, 'accounts', walletId), {
    ...account(walletId, walletOpeningId), type: 'CRYPTO_WALLET', currencyCode: 'ETH', assetClass: 'CRYPTO',
    scale: 8, includeInEverydayPool: false,
  });
  batch.set(eventRef(walletOpeningId), {
    ...event(walletOpeningId, 'OPENING', 0, walletId), movements: [ethMovement(quantity)],
  });
  return batch.commit();
}

function cryptoEvent(id, kind, quantity, gross, fee, allocated) {
  const sell = kind === 'CRYPTO_SELL';
  const realized = sell ? gross - fee - allocated : null;
  return {
    ...event(id, kind), accountIds: ['bank', walletId], categoryIds: fee > 0 ? ['food'] : [],
    movements: [
      ethMovement(String(sell ? -quantity : quantity)),
      { accountId: 'bank', assetClass: 'FIAT', currencyCode: 'LKR', amountMinor: (sell ? gross : -gross) - fee },
    ],
    components: [
      ...(fee > 0 ? [{
        nature: 'SPENDING', amountLkrMinor: fee, categoryId: 'food', commitmentClass: 'DISCRETIONARY',
      }] : []),
      ...(sell ? [{ nature: 'REALIZED_CRYPTO', amountLkrMinor: realized }] : []),
    ],
    cryptoTrade: {
      assetAccountId: walletId, quoteAccountId: 'bank', quantityAtomic: String(quantity),
      grossQuoteMinor: gross, feeLkrMinor: fee, costMethod: 'MOVING_AVERAGE_V1',
      ...(sell ? { allocatedCostLkrMinor: allocated, realizedPnlLkrMinor: realized } : {}),
    },
  };
}

function cryptoPosition(id, quantity, cost, version) {
  return {
    currencyCode: 'ETH', scale: 8, quantityAtomic: String(quantity), costLkrMinor: cost,
    lastTransactionId: id, version, costMethod: 'MOVING_AVERAGE_V1', lastTradeAt: audit, updatedAt: serverTimestamp(),
  };
}

function cryptoBatch(trade, position, client = db) {
  const batch = writeBatch(client);
  batch.set(eventRef(trade.operationId, client), trade);
  batch.set(positionRef(client), position);
  return batch.commit();
}

test('crypto buy, partial sale and full disposal preserve exact holdings and cash', async () => {
  await assertSucceeds(createEthWallet());
  await assertSucceeds(cryptoBatch(
    cryptoEvent('buy', 'CRYPTO_BUY', 200_000_000, 10_000_000, 100_000),
    cryptoPosition('buy', 200_000_000, 10_100_000, 1),
  ));
  await assertSucceeds(cryptoBatch(
    cryptoEvent('sell', 'CRYPTO_SELL', 50_000_000, 3_000_000, 50_000, 2_525_000),
    cryptoPosition('sell', 150_000_000, 7_575_000, 2),
  ));
  const sold = (await assertSucceeds(getDoc(eventRef('sell')))).data();
  assert.equal(sold.cryptoTrade.realizedPnlLkrMinor, 425_000);
  assert.equal(sold.movements[1].amountMinor, 2_950_000);
  assert.equal((await assertSucceeds(getDoc(positionRef()))).data().quantityAtomic, '150000000');
  await assertSucceeds(cryptoBatch(
    cryptoEvent('close', 'CRYPTO_SELL', 150_000_000, 7_000_000, 0, 7_575_000),
    cryptoPosition('close', 0, 0, 3),
  ));
});

test('crypto rules reject an unpaired trade, a nonzero opening, forged cost and stale versions', async () => {
  await assertFails(createEthWallet('1'));
  await assertSucceeds(createEthWallet());
  const buy = cryptoEvent('buy', 'CRYPTO_BUY', 200_000_000, 10_000_000, 100_000);
  const opened = cryptoPosition('buy', 200_000_000, 10_100_000, 1);
  await assertFails(setDoc(eventRef('buy'), buy));
  await assertFails(setDoc(positionRef(), opened));
  const other = environment.authenticatedContext('other').firestore();
  await assertFails(cryptoBatch(buy, opened, other));
  await assertSucceeds(cryptoBatch(buy, opened));
  await assertFails(cryptoBatch(
    cryptoEvent('forged', 'CRYPTO_SELL', 50_000_000, 3_000_000, 50_000, 2_524_999),
    cryptoPosition('forged', 150_000_000, 7_575_001, 2),
  ));
  await assertFails(cryptoBatch(
    cryptoEvent('oversell', 'CRYPTO_SELL', 200_000_001, 3_000_000, 0, 10_100_000),
    cryptoPosition('oversell', -1, 0, 2),
  ));
  await assertFails(cryptoBatch(
    cryptoEvent('stale', 'CRYPTO_SELL', 50_000_000, 3_000_000, 50_000, 2_525_000),
    cryptoPosition('stale', 150_000_000, 7_575_000, 1),
  ));
  const unchanged = (await assertSucceeds(getDoc(positionRef()))).data();
  assert.equal(unchanged.lastTransactionId, 'buy');
  assert.equal(unchanged.costLkrMinor, 10_100_000);
});

test('crypto allocation rounds an exact half minor unit upward', async () => {
  await assertSucceeds(createEthWallet());
  await assertSucceeds(cryptoBatch(
    cryptoEvent('buy', 'CRYPTO_BUY', 999_999_999_998, 100_000_000_000 - 1, 0),
    cryptoPosition('buy', 999_999_999_998, 100_000_000_000 - 1, 1),
  ));
  const sold = 499_999_999_999;
  await assertFails(cryptoBatch(
    cryptoEvent('wrong', 'CRYPTO_SELL', sold, 30_000, 0, 49_999_999_999),
    cryptoPosition('wrong', sold, 50_000_000_000, 2),
  ));
  await assertSucceeds(cryptoBatch(
    cryptoEvent('rounded', 'CRYPTO_SELL', sold, 30_000, 0, 50_000_000_000),
    cryptoPosition('rounded', sold, 49_999_999_999, 2),
  ));
});

const reminderRef = (id = 'salary', client = db) => doc(client, root, 'schedules', id);
const reminderOccurrenceRef = (id, client = db) => doc(client, root, 'occurrences', id);

function incomeReminder(frequency = 'MONTHLY', anchorDate = date) {
  return {
    kind: 'INCOME_REMINDER', title: 'Salary', frequency, interval: 1, anchorDate, activeFrom: anchorDate,
    timeZone: 'Asia/Colombo', expectedAmountMinor: 12_000_000, currencyCode: 'LKR', amountMode: 'ESTIMATE',
    incomeSourceId: 'source', preferredAccountId: 'bank', paused: false, reminderLeadDays: 0,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0,
  };
}

function reminderOccurrence(nominalDate = date, scheduleId = 'salary') {
  return {
    scheduleId, nominalDate, effectiveDueDate: nominalDate, kind: 'INCOME_REMINDER', titleSnapshot: 'Salary',
    expectedAmountMinor: 12_000_000, currencyCode: 'LKR', timeZone: 'Asia/Colombo', sourceId: 'source',
    state: 'OPEN', createdAt: serverTimestamp(), updatedAt: serverTimestamp(), revision: 0,
  };
}

function scheduledIncome(id, amount = 13_500_000, accountId = 'bank') {
  const receiptId = `receipt_${id}`;
  return { ...event(receiptId, 'INCOME', amount, accountId), occurrenceId: id };
}

test('recurring income creates a deterministic receipt and occurrence together on first confirmation', async () => {
  const id = `salary_${date}`;
  const receipt = scheduledIncome(id);
  const confirmed = {
    ...reminderOccurrence(), state: 'CONFIRMED', confirmedTransactionId: receipt.operationId,
    confirmedAmountMinor: 13_500_000,
  };
  await assertSucceeds(setDoc(reminderRef(), incomeReminder()));
  await assertFails(setDoc(eventRef(receipt.operationId), receipt));
  await assertFails(setDoc(reminderOccurrenceRef(id), confirmed));
  const batch = writeBatch(db);
  batch.set(eventRef(receipt.operationId), receipt);
  batch.set(reminderOccurrenceRef(id), confirmed);
  await assertSucceeds(batch.commit());
  const saved = (await getDoc(reminderOccurrenceRef(id))).data();
  assert.equal(saved.state, 'CONFIRMED');
  assert.equal(saved.expectedAmountMinor, 12_000_000);
  assert.equal(saved.confirmedAmountMinor, 13_500_000);
  assert.equal((await getDoc(eventRef(receipt.operationId))).data().movements[0].amountMinor, 13_500_000);
  await assertFails(setDoc(eventRef(receipt.operationId), receipt));
});

test('recurring income adjustment and snooze do not post money and an adjusted prompt can be confirmed', async () => {
  const id = `salary_${date}`;
  const ref = reminderOccurrenceRef(id);
  const receipt = scheduledIncome(id, 12_900_000);
  await assertSucceeds(setDoc(reminderRef(), incomeReminder('WEEKLY')));
  await assertSucceeds(setDoc(ref, {
    ...reminderOccurrence(), expectedAmountMinor: 12_900_000, effectiveDueDate: '2026-11-06',
  }));
  await assertSucceeds(updateDoc(ref, {
    snoozedUntil: Timestamp.fromMillis(Date.now() + 86_400_000), updatedAt: serverTimestamp(), revision: 1,
  }));
  assert.equal((await getDoc(eventRef(receipt.operationId))).exists(), false);
  const patch = {
    state: 'CONFIRMED', confirmedTransactionId: receipt.operationId, confirmedAmountMinor: 12_900_000,
    snoozedUntil: deleteField(), updatedAt: serverTimestamp(), revision: 2,
  };
  await assertFails(updateDoc(ref, patch));
  const batch = writeBatch(db);
  batch.set(eventRef(receipt.operationId), receipt);
  batch.update(ref, patch);
  await assertSucceeds(batch.commit());
  const data = (await getDoc(ref)).data();
  assert.equal(data.revision, 2);
  assert.equal(data.effectiveDueDate, '2026-11-06');
  assert.equal('snoozedUntil' in data, false);
});

test('recurring income clamps yearly dates, validates weekly dates and preserves future prompts after skip', async () => {
  await assertSucceeds(setDoc(reminderRef('annual'), incomeReminder('YEARLY', '2024-02-29')));
  await assertSucceeds(setDoc(reminderOccurrenceRef('annual_2025-02-28'), {
    ...reminderOccurrence('2025-02-28', 'annual'), state: 'SKIPPED',
  }));
  await assertFails(setDoc(reminderOccurrenceRef('annual_2026-03-29'), reminderOccurrence('2026-03-29', 'annual')));
  await assertSucceeds(setDoc(reminderOccurrenceRef('annual_2026-02-28'), reminderOccurrence('2026-02-28', 'annual')));
  await assertSucceeds(setDoc(reminderRef('weekly'), incomeReminder('WEEKLY')));
  await assertFails(setDoc(reminderOccurrenceRef('weekly_2026-11-13'), reminderOccurrence('2026-11-13', 'weekly')));
  await assertSucceeds(setDoc(reminderOccurrenceRef('weekly_2026-11-12'), reminderOccurrence('2026-11-12', 'weekly')));
  assert.equal((await getDoc(eventRef('receipt_annual_2025-02-28'))).exists(), false);
  const skipped = reminderOccurrenceRef('annual_2025-02-28');
  await assertFails(updateDoc(skipped, {
    state: 'OPEN', expectedAmountMinor: 99, updatedAt: serverTimestamp(), revision: 1,
  }));
  await assertSucceeds(updateDoc(skipped, { state: 'OPEN', updatedAt: serverTimestamp(), revision: 1 }));
  assert.equal((await getDoc(skipped)).data().state, 'OPEN');
});

test('recurring income rejects a cross-user schedule, stale revision, forged amount and competing links', async () => {
  const id = `salary_${date}`;
  const other = environment.authenticatedContext('other').firestore();
  await assertFails(setDoc(reminderRef('salary', other), incomeReminder()));
  await assertFails(setDoc(reminderRef(), { ...incomeReminder(), preferredAccountId: 'missing' }));
  await assertSucceeds(setDoc(reminderRef(), incomeReminder()));
  await assertSucceeds(setDoc(reminderOccurrenceRef(id), reminderOccurrence()));
  await assertFails(updateDoc(reminderOccurrenceRef(id), {
    expectedAmountMinor: 99, updatedAt: serverTimestamp(), revision: 0,
  }));
  for (const [amount, extra] of [[1, {}], [12_000_000, { expectedPaymentId: 'payment' }]]) {
    const receipt = scheduledIncome(id, amount);
    const batch = writeBatch(db);
    batch.set(eventRef(receipt.operationId), { ...receipt, ...extra });
    batch.update(reminderOccurrenceRef(id), {
      state: 'CONFIRMED', confirmedTransactionId: receipt.operationId, confirmedAmountMinor: 12_000_000,
      updatedAt: serverTimestamp(), revision: 1,
    });
    await assertFails(batch.commit());
  }
  assert.equal((await getDoc(reminderOccurrenceRef(id))).data().state, 'OPEN');
  assert.equal((await getDoc(eventRef(`receipt_${id}`))).exists(), false);
});


function utilityPair() {
  const pair = monthlyBillPair();
  return {
    schedule: { ...pair.schedule, title: 'Electricity', expectedAmountMinor: 800_000, amountMode: 'ESTIMATE' },
    occurrence: { ...pair.occurrence, titleSnapshot: 'Electricity', expectedAmountMinor: 800_000, amountMode: 'ESTIMATE' },
  };
}
function actualPatch(actualAmountMinor, revision) {
  return { actualAmountMinor, updatedAt: serverTimestamp(), revision };
}

test('utility actual changes only this bill, posts no money, pays the actual through the existing linked expense, and preserves next estimate', async () => {
  const pair = utilityPair();
  await assertSucceeds(writeMonthlyBill(pair));
  const schedule = (await getDoc(billScheduleRef())).data();
  const accountBefore = (await getDoc(doc(db, root, 'accounts', 'bank'))).data();
  const ledgerBefore = (await getDocs(collection(db, root, 'transactions'))).docs.map(item => item.data());
  await assertSucceeds(updateDoc(billOccurrenceRef(), actualPatch(950_000, 1)));
  const actual = (await getDoc(billOccurrenceRef())).data();
  assert.equal(actual.actualAmountMinor, 950_000);
  assert.equal(actual.expectedAmountMinor, 800_000);
  assert.equal(actual.paidAmountMinor, undefined);
  assert.deepEqual((await getDoc(billScheduleRef())).data(), schedule);
  assert.deepEqual((await getDoc(doc(db, root, 'accounts', 'bank'))).data(), accountBefore);
  assert.deepEqual((await getDocs(collection(db, root, 'transactions'))).docs.map(item => item.data()), ledgerBefore);
  await assertFails(writeBillPayment(billPayment('utility-overpayment', 950_001), 950_001, 2));
  await assertSucceeds(writeBillPayment(billPayment('utility-payment', 950_000), 950_000, 2));
  const paid = (await getDoc(billOccurrenceRef())).data();
  assert.equal(paid.actualAmountMinor - paid.paidAmountMinor, 0);
  assert.equal((await getDoc(eventRef('utility-payment'))).data().movements[0].amountMinor, -950_000);
  assert.equal((await getDocs(collection(db, root, 'transactions'))).size, ledgerBefore.length + 1);
  await assertFails(writeBillPayment(billPayment('utility-payment', 950_000), 950_000, 3));
  await assertFails(updateDoc(billOccurrenceRef(), actualPatch(949_999, 3)));
  assert.deepEqual((await getDoc(billOccurrenceRef())).data(), paid);
  const nextDate = '2026-12-30';
  const next = billOccurrenceRef(db, `${billId}_${nextDate}`);
  await assertSucceeds(setDoc(next, { ...pair.occurrence, nominalDate: nextDate, effectiveDueDate: nextDate }));
  assert.equal((await getDoc(next)).data().expectedAmountMinor, 800_000);
  assert.equal((await getDoc(next)).data().actualAmountMinor, undefined);
  assert.deepEqual((await getDoc(billScheduleRef())).data(), schedule);
  // A void still uses the existing paired ledger/paid-total checks after actual entry.
  const batch = writeBatch(db);
  batch.update(eventRef('utility-payment'), voidPatch());
  batch.update(billOccurrenceRef(), billProgress('utility-payment', 0, 3));
  await assertSucceeds(batch.commit());
  assert.equal((await getDoc(billOccurrenceRef())).data().actualAmountMinor, 950_000);
});

test('utility actual edits enforce owner, exact positive amount, revision, immutable snapshot and paid-history floor', async () => {
  await assertSucceeds(writeMonthlyBill(utilityPair()));
  const initial = (await getDoc(billOccurrenceRef())).data();
  for (const patch of [
    actualPatch(0, 1), actualPatch(-1, 1), actualPatch(1.001, 1), actualPatch(100000000001, 1),
    actualPatch(950_000, 0), actualPatch(950_000, 2),
    { ...actualPatch(950_000, 1), updatedAt: audit },
    { ...actualPatch(950_000, 1), paidAmountMinor: 1 },
    { ...actualPatch(950_000, 1), expectedAmountMinor: 950_000 },
    { ...actualPatch(950_000, 1), amountMode: 'FIXED' },
    { ...actualPatch(950_000, 1), createdAt: serverTimestamp() },
    { ...actualPatch(950_000, 1), categoryId: 'other-category' },
  ]) {
    await assertFails(updateDoc(billOccurrenceRef(), patch));
    assert.deepEqual((await getDoc(billOccurrenceRef())).data(), initial);
  }
  for (const context of [environment.authenticatedContext('other-user'), environment.unauthenticatedContext()]) {
    await assertFails(updateDoc(billOccurrenceRef(context.firestore()), actualPatch(950_000, 1)));
  }
  await assertSucceeds(writeBillPayment(billPayment('utility-partial', 300_000), 300_000, 1));
  await assertFails(updateDoc(billOccurrenceRef(), actualPatch(299_999, 2)));
  // A real bill may be below the estimate, provided it covers all prior payments.
  await assertSucceeds(updateDoc(billOccurrenceRef(), actualPatch(700_000, 2)));
  assert.equal((await getDoc(billOccurrenceRef())).data().paidAmountMinor, 300_000);
  await assertSucceeds(writeBillPayment(billPayment('utility-rest', 400_000), 700_000, 3));
  assert.equal((await getDoc(billOccurrenceRef())).data().paidAmountMinor, 700_000);
});

test('saved fixed bills remain fixed and retain their payment history', async () => {
  await assertSucceeds(writeMonthlyBill(monthlyBillPair()));
  await assertFails(updateDoc(billOccurrenceRef(), actualPatch(950_000, 1)));
  await assertFails(updateDoc(billOccurrenceRef(), { ...actualPatch(950_000, 1), amountMode: 'ESTIMATE' }));
  await assertSucceeds(writeBillPayment(billPayment('fixed-payment', 3_400_000), 3_400_000, 1));
  assert.equal((await getDoc(billOccurrenceRef())).data().paidAmountMinor, 3_400_000);
});
