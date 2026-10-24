# PocketPath

PocketPath helps Kavindu understand income, spending and savings goals. The implemented P01 baseline provides Email/Password authentication, password reset and logout; Home, Activity, Plan and Goals navigation; LKR cash-account creation with an opening event; initial categories; ordinary LKR expense entry; account balances and Activity; device-local drafts and Firestore-managed offline expense writes.

## First-time setup for Sugeerthan and Tharukshan

Use **Android Studio Quail 2 | 2026.1.2 Patch 1**, the team's tested IDE version confirmed by Hithush. Match it for initial setup. Do not independently accept IDE suggestions to upgrade Gradle, AGP, Kotlin, plugins or dependencies.

These versions come from `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml` and the app build script:

| Component | Existing project version/configuration |
| --- | --- |
| Gradle wrapper | **9.5.0** |
| Android Gradle Plugin (AGP) | **9.3.3** |
| Kotlin / Compose compiler / serialization plugins | **2.2.10** |
| Hilt | **2.60.1** |
| KSP | **2.3.10** |
| Firebase BoM | **34.14.1** |
| Google Services plugin | **4.5.0** |
| Gradle runtime JDK | **21** |
| Java source/target compatibility | **11** (separate from the Gradle runtime JDK) |
| Compile SDK | **36.1** (`release(36)` with `minorApiLevel = 1`) |
| Target / minimum SDK | **36 / 26** |

1. **Clone and open the existing project.** Copy this repository's clone URL from its repository page (or obtain repository access from Hithush), then clone it with Git or Android Studio's clone action. For a terminal clone, use `git clone <PocketPath-repository-URL> PocketPath`. In Android Studio choose **Open** and select the cloned `PocketPath` root containing `settings.gradle.kts`, `gradlew.bat` and `app/`. Do **not** create a new Android project or open only the `app/` folder. The shared foundation branch is `feature/p01-foundation`; agree with Hithush which branch to use before starting feature work. Preserve any local changes when switching branches.

2. **Configure JDK and SDK locally.** In Android Studio's Settings → Build, Execution, Deployment → Build Tools → Gradle, select a **JDK 21** installation as the Gradle JDK and use the project's wrapper. `gradle/gradle-daemon-jvm.properties` already declares `toolchainVersion=21`; keep this existing daemon JVM criterion. Configure the terminal's `JAVA_HOME` to a JDK 21 installation too. Through SDK Manager install **Android SDK Platform 36.1**, **Android SDK Platform-Tools**, **Android SDK Command-line Tools**, and the **SDK Build-Tools revision requested by the pinned AGP during sync** (the build script does not explicitly pin a Build-Tools revision). Accept the required SDK licenses. For an emulator, also install Android Emulator and a suitable API 26+ system image; these are unnecessary when using a physical phone. Let Android Studio create/update ignored `local.properties` for your own SDK location. Never copy another machine's SDK path into tracked configuration.

3. **Obtain the matching Firebase Android configuration.** Use the existing Firebase project **`pocketpathfirebase`** and its existing Android registration for application ID **`com.pocketpath`** (also the namespace). Obtain the matching `google-services.json` from Hithush or, if already authorized in Firebase Console, download it from that existing Android registration. Place it exactly at **`app/google-services.json`**, not the repository root. It is ignored by Git and must remain local. Do not create another Firebase project, database or Android registration. Do not use a configuration for a different package/project, and do not share Google-account passwords or service-account private keys.

4. **Use the existing Firebase services and rules.** Email/Password Authentication is already configured; use the existing default Firestore database in `pocketpathfirebase`. Firebase Console membership grants project-management access and is **separate from app login**. Sugeerthan and Tharukshan should each register their own test user through the app; neither needs Hithush's Google-account password or app credentials. Console membership is only needed for authorized project administration/configuration retrieval. Hithush coordinates shared rules deployment and checks deployed-rule parity. Git merging, Gradle sync and app builds **do not deploy rules**. New feature writes require coordinated schema/repository/rules changes before they can work; do not bypass permission errors by weakening rules.

5. **Sync and check the wrapper.** Sync Gradle in Android Studio after placing the configuration file. Resolve missing SDK components using the existing project's requested versions. In Android Studio's terminal at the repository root, run the version command below and confirm Gradle **9.5.0** and daemon JVM **21**. This uses the checked-in wrapper; a global Gradle installation is unnecessary.

   ```powershell
   .\gradlew.bat --version
   ```

6. **Run the JVM checks and assemble the debug app.** From the same terminal:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
   ```

   Optional lint when its tooling is available:

   ```powershell
   .\gradlew.bat :app:lintDebug --console=plain
   ```

7. **Run and check the first launch.** Choose the `app` run configuration and a connected API 26+ phone (enable USB debugging and authorize the computer) or configured emulator, then press Run. Start online. Register your own app test user, sign in and confirm Home and the four bottom tabs open. Open Accounts, create one test LKR cash account with opening funds, record one small expense through Add, and confirm its Activity entry and updated balance. Check logout and sign back into your own user. Plan and Goals are expected placeholders. Record any failure and send its error/context to Hithush; successful assembly alone does not prove Firebase access or deployed-rule parity. Use clearly disposable test data and avoid duplicate saves while a result is uncertain.

On macOS/Linux use `./gradlew` in place of `.\gradlew.bat`. Keep SDK paths, signing keys/passwords, configuration files and service-account credentials local. Never commit them. See [AGENTS.md](AGENTS.md) for collaboration constraints and [the final handoff](Docs/P01-Final-Handoff.md) for verification evidence and remaining security limitations.

## Setup troubleshooting

| Symptom | Focused action |
| --- | --- |
| IDE cannot load the project, AGP compatibility error, wrong Java/JVM | Match Android Studio Quail 2 2026.1.2 Patch 1; select JDK 21 and the project wrapper. Check the daemon criterion and `JAVA_HOME` against `gradlew.bat --version`. Keep Java source/target 11. Do not fix this by upgrading/downgrading project versions. |
| SDK/platform/build tools missing or SDK path invalid | Install Platform 36.1 and the requested Build-Tools revision using SDK Manager, check licenses and your local SDK path. Keep `local.properties` machine-local. |
| `google-services.json` missing or no matching client for package | Obtain the file for `pocketpathfirebase` → existing `com.pocketpath` registration from Hithush/authorized Console access; put it under `app/`. Do not create a replacement Firebase project or registration. |
| Dependency, wrapper or JDK download fails | Check network/proxy/firewall access to the URLs/repositories shown in the error, including Gradle distribution/toolchain downloads and Google/Maven repositories. If needed, put machine-specific proxy settings in your local user Gradle configuration. Retain the original error and coordinate with Hithush; do not change versions or add arbitrary repositories. |
| Firestore `PERMISSION_DENIED` / insufficient permissions | Confirm app sign-in with your own test user, matching Firebase configuration and ownership of the requested data. Ask Hithush to compare deployed rules and the agreed schema. Account/category edits and future feature writes are currently denied. Console membership does not grant an app user broader data access; do not weaken rules. |
| App registration/sign-in fails or Console access is denied | Use the app's Email/Password flow with your own test account. Ask Hithush for a Console invitation only if project administration is required; this is unrelated to app registration. Never exchange Google-account passwords/private keys. |

## Data and offline behavior

- Production repositories obtain the authenticated UID and use `users/{uid}` paths. Session changes cancel financial observers and reset presentation state. Drafts and receipts are stored separately per UID on the device.
- Fiat uses exact `Long` minor units (LKR/USD scale 2), with checked bounds/overflow. Domain crypto quantities use `BigInteger` and rates use `BigDecimal`; no floating-point financial arithmetic. Current production posting supports only LKR OPENING and ordinary EXPENSE.
- Account balances derive from complete effective movement history. Opening funds do not count as income; report components and reservations are separate from movement balances. Incomplete cached history cannot produce full balances. Cached figures are stale, not proof of current cloud state.
- Callers retain an operation ID and frozen request through uncertain retries. Conflicting reuse fails. Expense receipts are durable before Firestore handoff; Firestore owns pending-write transport. Restart reconciliation checks receipts read-only and never automatically submits them again.
- Offline expenses require previously cached account/category references. Account creation is online-only. Drafts are device-local, excluded from backup, and lost if app data is cleared or the app is uninstalled; they do not synchronize between devices.
- Queries use a **5,000-document guard**, fetching up to 5,001 to detect overflow and fail rather than publish incomplete totals. Activity currently fetches the bounded history, sorts/filters it, then paginates in memory; this is not scalable server-side pagination. Activity pages must never become balance inputs.

## Firebase configuration and manual rules deployment

`firebase.json` points to the checked-in rules and `firestore.indexes.json`. Current P01 queries use automatic single-field indexes, so the index file contains no composite indexes or field overrides. Do not deploy this empty definition over an existing project's indexes without comparing the definitions first. No project alias is included.

Hithush coordinates deployment. An authorized maintainer with an already configured Firebase CLI may manually deploy **rules only**, after completing the handoff's pre-publication checks, resolving or explicitly accepting its remaining limitations, and confirming the intended project:

```text
firebase deploy --only firestore:rules --project pocketpathfirebase
```

This command is documentation, not a deployment performed by the app. Source inspection and successful builds do not establish deployed-rule parity or complete security verification.

## Placeholders and deferred work

Plan and Goals remain placeholders; Settings currently provides authentication/logout controls rather than financial preference editing. Income, expected payments, schedules/commitments, budgets, goal funding/calculations, transfers, FX/crypto flows, refunds/corrections, reminders and account/category editing or archival are deferred. Shared domain models/contracts and samples for later work are not production feature implementations. The current rules deny profile/account/category updates and ledger updates/deletes.
