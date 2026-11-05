# PocketPath PR #3 local Firestore rules tests

This Node test suite loads the repository's `firestore.rules` and uses only the default database of the local `demo-pocketpath-pr3` emulator. It refuses execution without a loopback `FIRESTORE_EMULATOR_HOST`; it never uses application credentials or the production Firebase project.

Use Node 22 and JDK 21. Dependencies are exact-pinned in `package.json` and `package-lock.json`: `@firebase/rules-unit-testing` 5.0.0 and Firebase JavaScript SDK 12.4.0. The testing package declares Firebase `^12.0.0` compatibility. No Android dependencies are changed.

From the repository root, with JDK 21 selected in `JAVA_HOME` and its `bin` directory on PATH:

```powershell
npm --prefix tools/firestore-rules ci --ignore-scripts --no-audit --no-fund
npx --yes --package=firebase-tools@15.34.2 firebase --version
npx --yes --package=firebase-tools@15.34.2 firebase emulators:exec --only firestore --project demo-pocketpath-pr3 "npm --prefix tools/firestore-rules test"
```

The Firebase CLI uses its npx cache. Test dependencies stay in this directory's ignored `node_modules/`. No Firebase login or deployment is needed. The checked-in root `firebase.json` selects the root rules and index files; the suite also explicitly loads the same root rules through the test environment.

Fixtures mirror `FoundationCodec`'s supported shapes: LKR integer minor units, one FIAT movement, exact query-ID arrays, `POSTED` events, schema 1, revision 0, matching report components, explicit financial timestamps/date/zone and server-generated audit timestamps. Expected-payment receipts use the same SHA-256 ID derivation as `StableIds.expectedPaymentReceipt`. The worked payment is LKR 60,000 with a LKR 20,000 partial receipt.

Each test starts with isolated fixture documents seeded with rules disabled. All operations under test use authenticated or unauthenticated client SDKs with rules enabled. Positive opening and expense controls establish that their linked negative variants are otherwise valid. Negative receipt operations compare the server-visible receipt documents and complete payment record before and after denial, including counters and revisions.

Coverage includes owner/non-owner/unauthenticated access, expected-payment creation, manual income, forbidden links on opening/expense, paired receipt updates, individual-write denial, overpayment, post-cancellation receipts, immutable receipt IDs, cancellation, revision rollback, reactivation and deletion. It does not replace Android repository/device acceptance or establish deployed-rule parity.

`results/command.txt` records the emulator command. Actual run logs and the result summary are saved under `results/`. Historical failures, if any, are kept separate from later successful runs. The preceding 134-test JVM suite and debug-build evidence is reused; this task does not rerun Android checks.
