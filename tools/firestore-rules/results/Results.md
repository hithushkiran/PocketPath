# PR3 local Firestore rules verification

Branch: integration/income-pr3
HEAD: ee807e18a4a86392d26ef5a4a54c477aab4d8771
Baseline working tree was clean. All changes in this task are new files under tools/firestore-rules/; tracked application, configuration, rules and Android dependencies are unchanged.

The exact root command is in command.txt. emulator-attempt-1.log is the original successful CLI/node:test console output. firestore-debug.log is the actual Firestore emulator diagnostic log, including expected denials exercised by negative tests.

Result: **19 tests passed; 0 failed, cancelled, skipped or todo.** Firestore emulator 1.22.0 started using Firebase CLI 15.34.2 and loaded the checked-in rules successfully. The test environment explicitly loaded the same root rules for demo-pocketpath-pr3's default database. No startup/rules-loading error or valid-fixture rules defect was observed. No firestore.rules changes or rerun were necessary. SIGINT in the shutdown log is the CLI's normal successful emulator shutdown.

Covered: owner access; non-owner and unauthenticated read/query/write denial; expected-payment creation; ordinary manual income; positive opening/expense controls and rejection of expected-payment links on those kinds; atomic partial receipts; denial of either write alone, overpayment, post-cancellation receipts and receipt-ID rewrites/deletion; valid cancellation; rollback/reactivation/payment deletion denial. Denied financial writes were checked against unchanged receipt documents and full payment records, including counters and revisions.

Versions are recorded in tool-versions.txt. Direct devDependencies and transitive dependencies are locked by package-lock.json. node_modules is ignored and contained inside tools/firestore-rules/. npx uses its external npm cache for the explicitly pinned CLI.

Setup history: the first install invocation with --prefix after install unexpectedly looked for the repository-root package.json and failed ENOENT. Its actual npm log is preserved as setup-prefix-failure.log when available. Installing from the test directory succeeded. The root-form prefix command used by the emulator then worked. An optional later version-recording command was interrupted after stalling in the restricted context; tool-versions-interrupted.txt preserves its partial output. The completed version capture used the already downloaded CLI cache offline and is saved separately. Neither issue affected the successful emulator test execution.

The preceding 134-test full JVM run and debug build are reused historical evidence; neither was rerun here. These are local JavaScript SDK/rules tests, not execution of Android repositories or device acceptance, and they do not establish deployed-rule parity. No Firebase login, deployment or production Firebase access was performed. Changes remain uncommitted.
