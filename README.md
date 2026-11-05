# PocketPath

PocketPath is a personal finance app for Kavindu, a junior software engineer in Colombo who receives a variable salary, irregular freelance payments and USD income. The app brings recorded income, everyday expenses, bills and progress toward a MacBook savings goal into one place.

The app has four main tabs. **Home** shows recorded funds and a purchase preview. **Activity** lists transactions. **Plan** tracks bills, spending limits and spending by commitment type. **Goals** shows confirmed reservations and the pace needed to reach a target. The Add shortcut opens expense entry. Accounts provides access to manual income, sources, expected payments and in-app recurring income reminders.

## Build requirements

| Component | Project setting |
| --- | --- |
| Android application ID | `com.pocketpath` |
| Android SDK | Minimum API 26, target API 36, compile SDK 36.1 |
| Gradle and JDK | Gradle wrapper 9.5.0, Gradle JDK 21 |
| Android Gradle Plugin and Kotlin | AGP 9.3.3, Kotlin 2.2.10 |
| Firebase | Firebase BoM 34.14.1, Email/Password Auth and default Cloud Firestore database |

The Gradle wrapper and version catalog define the tool versions. Select JDK 21 as the Gradle JDK. Install Android SDK Platform 36.1, Platform-Tools and the Build-Tools revision requested by Gradle sync.

## Firebase setup

1. Open the repository root in Android Studio.
2. Obtain the Android client configuration for the existing `com.pocketpath` registration in the team's `pocketpathfirebase` project from an authorized project maintainer. Save it locally as `app/google-services.json`.
3. Use the project's Email/Password provider and default Firestore database. Create a disposable user through the app for assessment. Firebase Console access is separate from app sign-in.
4. Sync Gradle and run the app on an API 26+ device or emulator.

Keep `google-services.json`, `local.properties`, signing material and service-account credentials out of Git. The root `firebase.json` references `firestore.rules` and `firestore.indexes.json`. A maintainer coordinates the deployment of the release rules and verifies the active indexes in the live Firebase project.

## Build and run

From the repository root on Windows:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

On macOS or Linux:

```bash
./gradlew --version
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it using Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

| Layer | Responsibility |
| --- | --- |
| `MainActivity`, `navigation/`, `feature/` | One activity hosts the Compose Material 3 UI and typed Navigation Compose destinations. Screens render state and dispatch events. |
| ViewModels under `feature/` | Session and screen state via immutable StateFlow, with coroutine work scoped to the ViewModel. |
| `domain/` | Financial records, validation, repository contracts and exact calculations for money, savings, bills and ledger totals. |
| `data/repository/`, `data/firebase/` and `di/` | Hilt supplies Firebase dependencies. Repositories bind financial reads and writes to the authenticated UID. Firestore listeners feed the dashboard. |
| `data/local/` | Device-local draft, retry and coverage records. The Firestore client provides its own cache and pending-write transport. |

The canonical ledger lives under `users/{uid}/transactions`. Its native movements feed account balances, report components feed income and spending totals, and reservation effects feed confirmed goal progress. Expected payments remain separate from recorded receipts. A posted USD receipt preserves its native amount and historical LKR valuation.

## Verification

- Run the JVM command above to exercise domain and state tests.
- Use [the Firestore rules tests](tools/firestore-rules/README.md) for local emulator verification. Use the live Firebase configuration for the submitted APK.
- Install the exact submission APK and verify sign-in, an expense, a partial freelance receipt, a bill payment, goal progress and the purchase preview. Check a cached expense across offline entry and reconnection, and confirm separate users see separate data.
- Record the Git commit, APK SHA-256, device/API, live rules version and observed results in the release evidence.
