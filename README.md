# PocketPath

PocketPath helps Kavindu understand income, spending and savings goals. The implemented P01 baseline provides Email/Password authentication, password reset and logout; Home, Activity, Plan and Goals navigation; LKR cash-account creation with an opening event; initial categories; ordinary LKR expense entry; account balances and Activity; device-local drafts and Firestore-managed offline expense writes.

## Set up and run

1. Clone this repository using its shared repository URL and open the repository root in Android Studio.
2. Use an Android Studio version compatible with the pinned Android Gradle Plugin 9.3.3. Select JDK 21 as the Gradle JDK (daemon criteria also specify 21). Java source/target compatibility remains 11. Install Android SDK Platform 36.1, the build tools required by the pinned plugin, and Android SDK Platform-Tools through SDK Manager. The app targets API 36 and supports devices from API 26. Keep the SDK path in ignored `local.properties`.
3. Authorized teammates obtain the Firebase Android configuration for the registered package `com.pocketpath` and place `google-services.json` under `app/`. Keep this file local and ignored; do not paste its contents or private credentials into source or documentation.
4. In the authorized Firebase project, enable Email/Password Authentication and provision the default Cloud Firestore database. Confirm the connected project's deployed rules match this repository's `firestore.rules`. App builds and Gradle sync do not deploy rules.
5. Sync Gradle, select the `app` run configuration and an API 26+ device, then run from Android Studio. Use the existing Gradle wrapper; no global Gradle installation is required. Preserve the pinned catalog/tool versions, including Firebase BoM **34.14.1**, Kotlin 2.2.10 and Gradle 9.5.0.

From the repository root in Android Studio's terminal on Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Optional lint, when its tooling is available:

```powershell
.\gradlew.bat :app:lintDebug --console=plain
```

Keep SDK paths, signing keys/passwords and service-account credentials local. Never commit them. See [AGENTS.md](AGENTS.md) for collaboration constraints and [the final handoff](Docs/P01-Final-Handoff.md) for verification evidence and security blockers.

## Data and offline behavior

- Production repositories obtain the authenticated UID and use `users/{uid}` paths. Session changes cancel financial observers and reset presentation state. Drafts and receipts are stored separately per UID on the device.
- Fiat uses exact `Long` minor units (LKR/USD scale 2), with checked bounds/overflow. Domain crypto quantities use `BigInteger` and rates use `BigDecimal`; no floating-point financial arithmetic. Current production posting supports only LKR OPENING and ordinary EXPENSE.
- Account balances derive from complete effective movement history. Opening funds do not count as income; report components and reservations are separate from movement balances. Incomplete cached history cannot produce full balances. Cached figures are stale, not proof of current cloud state.
- Callers retain an operation ID and frozen request through uncertain retries. Conflicting reuse fails. Expense receipts are durable before Firestore handoff; Firestore owns pending-write transport. Restart reconciliation checks receipts read-only and never automatically submits them again.
- Offline expenses require previously cached account/category references. Account creation is online-only. Drafts are device-local, excluded from backup, and lost if app data is cleared or the app is uninstalled; they do not synchronize between devices.
- Queries use a **5,000-document guard**, fetching up to 5,001 to detect overflow and fail rather than publish incomplete totals. Activity currently fetches the bounded history, sorts/filters it, then paginates in memory; this is not scalable server-side pagination. Activity pages must never become balance inputs.

## Firebase configuration and manual rules deployment

`firebase.json` points to the checked-in rules and `firestore.indexes.json`. Current P01 queries use automatic single-field indexes, so the index file contains no composite indexes or field overrides. Do not deploy this empty definition over an existing project's indexes without comparing the definitions first. No project alias is included.

An authorized maintainer with an already configured Firebase CLI may manually deploy **rules only**, after resolving the handoff's blockers and checking the intended project:

```text
firebase deploy --only firestore:rules --project <project-id>
```

This command is documentation, not a deployment performed by the app. Source inspection and successful builds do not establish deployed-rule parity or complete security verification.

## Placeholders and deferred work

Plan and Goals remain placeholders; Settings currently provides authentication/logout controls rather than financial preference editing. Income, expected payments, schedules/commitments, budgets, goal funding/calculations, transfers, FX/crypto flows, refunds/corrections, reminders and account/category editing or archival are deferred. Shared domain models/contracts and samples for later work are not production feature implementations. The current rules deny profile/account/category updates and ledger updates/deletes.
