param(
    [string]$DeviceSerial = $env:PR3_ANDROID_DEVICE_SERIAL,
    [string]$ResultsDirectory = $env:PR3_ANDROID_RESULTS_DIR,
    [string]$TestMethod = $env:PR3_ANDROID_TEST_METHOD
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $projectRoot

if ($env:GCLOUD_PROJECT -ne 'demo-pocketpath-pr3') {
    throw 'Run only through firebase emulators:exec --project demo-pocketpath-pr3.'
}
if ($env:FIRESTORE_EMULATOR_HOST -notmatch '^(127\.0\.0\.1|localhost):8080$' -or
    $env:FIREBASE_AUTH_EMULATOR_HOST -notmatch '^(127\.0\.0\.1|localhost):9099$') {
    throw 'Both local emulators must be ready at Firestore 8080 / Auth 9099.'
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { throw 'Set ANDROID_HOME to this machine''s existing Android SDK directory.' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "ADB not found: $adb" }
$deviceOutput = & $adb devices
if ($LASTEXITCODE -ne 0) { throw 'Unable to list Android devices.' }
$ready = @($deviceOutput | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (-not $DeviceSerial) {
    if ($ready.Count -ne 1) { throw 'Specify DeviceSerial or PR3_ANDROID_DEVICE_SERIAL when multiple devices are connected.' }
    $DeviceSerial = $ready[0]
}
if ($DeviceSerial -notin $ready) { throw "Selected device is unavailable or unauthorized: $DeviceSerial" }
$emulatorHostAddress = if ($DeviceSerial -match '^emulator-\d+$') { '10.0.2.2' } else { '127.0.0.1' }
if (-not $ResultsDirectory) { $ResultsDirectory = Join-Path $projectRoot 'app\build\outputs\pr3-repository-verification' }
[void](New-Item -ItemType Directory -Path $ResultsDirectory -Force)
Write-Output "Selected device: $DeviceSerial; Firebase emulator host: $emulatorHostAddress"
$focusedClass = 'com.pocketpath.data.repository.FirebaseIncomeRepositoryEmulatorTest'
$expectedTests = 4
if ($TestMethod) {
    if ($TestMethod -ne 'competingClientsAtOneRevisionPostOnlyOneReceipt') { throw 'Only the existing race method is supported for a single-test rerun.' }
    $focusedClass += "#$TestMethod"
    $expectedTests = 1
}

function Install-PreservingData([string]$ApkPath, [string]$Label) {
    # -r replaces the package while retaining its data. Never uninstall, clear or downgrade.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $adb -s $DeviceSerial install -r -t $ApkPath 2>&1)
        $installExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $output | Tee-Object -FilePath (Join-Path $ResultsDirectory "install-$Label.log")
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($installExitCode -ne 0 -or $text -notmatch '(?m)^Success\s*$') {
        if ($text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures?.*match|signature.*incompatible|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES') {
            throw 'Signing conflict. STOPPED: do not uninstall or clear either package. Existing app data must be preserved.'
        }
        throw "Package installation failed ($Label). No uninstall or data clearing will be attempted."
    }
}

$newReversePorts = @()
try {
    if ($emulatorHostAddress -eq '127.0.0.1') {
        $mappings = @(& $adb -s $DeviceSerial reverse --list)
        if ($LASTEXITCODE -ne 0) { throw 'Could not inspect existing reverse mappings.' }
        foreach ($port in @(9099, 8080)) {
            $existing = @($mappings | Where-Object { $_ -match "\stcp:$port\s" })
            if ($existing.Count -gt 0) {
                if ($existing.Count -ne 1 -or $existing[0] -notmatch "\stcp:$port\s+tcp:$port\s*$") {
                    throw "Port $port has an unrelated reverse mapping; preserve it and resolve the conflict explicitly."
                }
            } else {
                & $adb -s $DeviceSerial reverse "tcp:$port" "tcp:$port"
                if ($LASTEXITCODE -ne 0) { throw "Could not reverse emulator port $port." }
                $newReversePorts += $port
            }
        }
    }

    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed; no package installation attempted.' }
    $appApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $testApk = Join-Path $projectRoot 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
    Install-PreservingData $appApk 'app'
    Install-PreservingData $testApk 'android-test'

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $adb -s $DeviceSerial shell am instrument -w -r `
            -e class $focusedClass `
            -e pr3DemoProject demo-pocketpath-pr3 `
            -e pr3EmulatorHost $emulatorHostAddress `
            -e disableAnalytics true `
            com.pocketpath.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
        $instrumentExitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $output | Tee-Object -FilePath (Join-Path $ResultsDirectory 'instrumentation.log')
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    if ($instrumentExitCode -ne 0 -or $text -match 'FAILURES!!!|INSTRUMENTATION_FAILED' -or $text -notmatch "OK \($expectedTests tests?\)") {
        throw "Focused Android repository tests did not report OK ($expectedTests tests). See instrumentation.log; app data was not cleared."
    }
    [ordered]@{ device = $DeviceSerial; host = $emulatorHostAddress; project = 'demo-pocketpath-pr3'; tests = $expectedTests; passed = $expectedTests; failed = 0; method = $TestMethod; installation = 'adb install -r -t'; appDataCleared = $false } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDirectory 'summary.json') -Encoding UTF8
} finally {
    # Remove only mappings this run created, retaining pre-existing routes and both packages/data.
    foreach ($port in $newReversePorts) {
        $currentMappings = @(& $adb -s $DeviceSerial reverse --list)
        if (@($currentMappings | Where-Object { $_ -match "\stcp:$port\s+tcp:$port\s*$" }).Count -eq 1) {
            & $adb -s $DeviceSerial reverse --remove "tcp:$port"
        }
    }
}
