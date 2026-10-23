# PocketPath project instructions

PocketPath helps Kavindu understand income, spending and savings goals. Older references call the product Kavindu Finance; use PocketPath in the app.

- Use Kotlin, Kotlin DSL, Compose and Material 3; no XML screen layouts. Keep one activity and Navigation Compose. Minimum SDK is 26; target SDK must remain 34 or higher. Preserve `com.pocketpath` as namespace and application ID.
- Work within the existing `app` module. Keep template tool versions unless a verified compatibility need requires a change. Manage pinned dependencies through `gradle/libs.versions.toml`; use Hilt with KSP for injection.
- `com.pocketpath`: Application and Activity entry points. `navigation`: shared typed routes and app shell. `ui.theme` and `ui.components`: reusable presentation. Reserve `di` for real Hilt modules when dependencies exist.
- `feature.home`, `feature.activity`, `feature.plan`, `feature.goals`, `feature.transaction`, `feature.accounts`, `feature.settings`: feature screens and future feature ViewModels. Add `feature.income` and `feature.spending` when those flows arrive. Do not add empty layer classes.
- Block 2 will add `domain.model` and `domain.repository` for shared models and contracts. Later `data.repository` implementations own persistence and data access. Composables render state and emit events; ViewModels expose immutable StateFlow and use coroutines for asynchronous work. Keep data access and financial calculations outside Composables.
- Sugeerthan owns Income and Accounts. Tharukshan owns Spending and Planning. Hithush owns Goals and Financial Overview. Coordinate shared model, repository and navigation changes across all three; Hithush coordinates integration.
- Financial amounts require exact arithmetic under the SRS: integer minor units for fiat and exact decimal/atomic representations where specified. Never use Float/Double for money. Agree precision, rounding and overflow rules with shared contracts.
- Block 1 contains only DI, theme, organization and navigation placeholders. Firebase, authentication, financial models, repositories, storage and financial feature implementation belong to later blocks.
- Before edits, inspect Git branch/status and preserve all existing staged, unstaged and untracked user changes. Do not reset, overwrite unrelated work, commit or push unless requested. Intended foundation branch: `feature/p01-foundation`.
- Build with the existing wrapper: `.\gradlew.bat :app:assembleDebug :app:lintDebug`. Run `.\gradlew.bat :app:testDebugUnitTest` when meaningful unit tests change. Use Android Studio's Gradle JDK (daemon criteria specify JDK 21). See `Docs/P01-Block1.md` for manual checks and environment results.
- Keep SDK paths in ignored `local.properties` and machine configuration. Keep signing keys, passwords and service-account credentials local and ignored. Never commit credentials. Preserve wrapper scripts, properties and `gradle-wrapper.jar`.
