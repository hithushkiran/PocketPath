# Local Android income repository verification

`FirebaseIncomeRepositoryEmulatorTest` has four focused AndroidJUnit4 tests using the existing AndroidJUnitRunner, Firebase SDKs and coroutine dependencies. It uses two uniquely named `FirebaseApp` instances for `demo-pocketpath-pr3`, with dummy options/accounts. Both Auth and the default Firestore database are routed before requests to `10.0.2.2` for an AVD or to `127.0.0.1` through `adb reverse` for a USB phone. The production/default app bindings and Firebase configuration are not used or changed.

The class creates a fresh local Auth user for each test, signs both clients into that same UID, creates a valid profile/account/opening batch through the checked-in codec, and creates the source/payment through the actual production repository. There are no administrative bypass writes. Server reads explicitly use `Source.SERVER`.

Tests cover partial receipt/payment atomicity, identical replay from the second client, changed-payload conflict, competing commands at one original revision, existing/absent read-only inspection after cancellation, and signed-out inspection rejection. Linked ledger counts, payment counters/revisions and frozen command identity are checked from the server.

Use Node 22, JDK 21 and one authorized API 26+ Android virtual device or USB-connected phone. Set `JAVA_HOME` to the existing JDK 21 installation, put its `bin` on PATH, and set `ANDROID_HOME` to the existing SDK directory. Start an AVD and wait for boot, or connect and authorize a USB phone. If more than one device is ready, set `PR3_ANDROID_DEVICE_SERIAL` to the intended device serial from `adb devices -l`. The runner selects that device explicitly; it never runs the class on other devices.

Run from the repository root:

```powershell
npx --yes --package=firebase-tools@15.34.2 firebase emulators:exec --only "auth,firestore" --project demo-pocketpath-pr3 --config firebase.pr3-emulators.json "powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/android-repository/run-focused.ps1"
```

The host script refuses a non-demo project, missing/non-local emulator endpoints or an unavailable/ambiguous device selection. For USB it creates `adb reverse tcp:9099 tcp:9099` and `tcp:8080 tcp:8080`, uses the explicit instrumentation host `127.0.0.1`, and afterwards removes only mappings it created. Existing unrelated reverse mappings are never overwritten. AVDs continue to use `10.0.2.2`. The test class also requires its explicit `pr3DemoProject` instrumentation argument. The separate root-level `firebase.pr3-emulators.json` opts into local Auth/Firestore endpoints and references the checked-in root rules/indexes; the root `firebase.json` and rules remain unchanged. No login, deployment or production service is involved.

The runner builds both APKs, installs them with `adb install -r -t` and invokes the normal AndroidJUnitRunner directly. It never uninstalls a package, clears app data, downgrades it or enables package-data clearing. Any signing conflict stops installation without an uninstall/clear workaround. Both packages are left installed to retain existing phone app data. Set `PR3_ANDROID_RESULTS_DIR` to save install/instrumentation logs in a specific directory; otherwise they go to ignored `app/build/outputs/pr3-repository-verification/`.

Debug cleartext permission is limited to `10.0.2.2` and `127.0.0.1`; all other hosts retain the default cleartext prohibition. Release/main configuration is unchanged.

If no AVD is available, compile without claiming execution:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest --console=plain
```

Compilation proves neither emulator connectivity nor repository correctness. Preserve actual connected-test XML and emulator/Gradle logs when the class is eventually executed. The existing JavaScript rules suite under `tools/firestore-rules` is separate and unchanged.

To rerun only the receipt race, set `PR3_ANDROID_TEST_METHOD=competingClientsAtOneRevisionPostOnlyOneReceipt` before the same emulator command. Leave it unset for the four-test class. The race checks the saved count, exact payment delta/revision and losing-client server visibility; it reports the loser error code separately.
