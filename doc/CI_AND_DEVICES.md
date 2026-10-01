# CI quality checks and shared Android devices

## Reproduce GitHub verification

Use JDK 21 and Android SDK 36. No Android device or AI provider key is needed:

```powershell
.\gradlew.bat --no-daemon build testCoverage
```

`build` compiles, runs unit tests, and runs lint across all four modules. The Android
Gradle plugin generates one report per module, including
`phone-app/build/reports/coverage/test/debug/report.xml`. Sonar receives the
absolute paths for all four reports so they resolve from every Gradle subproject.
Do not register a replacement task by looking up `testDebugUnitTest` during
initial configuration: Android registers its variant tasks later.

With `SONAR_TOKEN` supplied through the environment:

```powershell
.\gradlew.bat --no-daemon sonar '-Dsonar.qualitygate.wait=true'
```

The workflow waits for Sonar's quality gate and propagates a failed gate to GitHub
Actions. Build, lint, test, and coverage reports are uploaded even when a check
fails. Pull requests without access to `SONAR_TOKEN` still run the build/tests,
but cannot perform authenticated Sonar analysis. Generated source exclusions and
the existing Compose UI coverage exclusion are retained; provider/service logic
remains in scope. The configured new-code baseline and 80% threshold are unchanged.

## Unit test patterns

Run a single class while iterating:

```powershell
.\gradlew.bat --no-daemon :phone-app:testDebugUnitTest --tests 'com.example.rokidphone.service.SystemTextToSpeechTest'
```

### Do not mock a `Result`-returning function that has default arguments

Kotlin compiles a call that omits a default argument into the synthetic static
`fn$default` bridge. MockK intercepts the instance method, not the bridge, and the
bridge boxes the returned value class a second time. For a function returning
`Result<T>` the caller then sees `Result(Result(value))`: `onSuccess` hands back a
`Result` where a `T` is expected, the cast fails, and the production fallback path
runs instead of the one under test — with no mock failure to point at it.

`EdgeTtsClient.synthesize(text, voice, rate, pitch, volume = "+0%")` is such a
function. Drive it through its injected `WebSocket.Factory` instead of stubbing it
(`EdgeTtsClientTest`, `SystemTextToSpeechTest`); the fake transport also exercises
the client's own frame parsing. Stubbing a `Result` function is fine when the call
site passes every argument, so `RecordingRepository.stopRecording()` and
`EnhancedAIService.quickChat(message)` are mocked directly.

### Observing a replay-less SharedFlow

`ServiceBridge`, `BluetoothPhotoReceiver` and `PhotoRepository` publish through
`MutableSharedFlow(replay = 0)`, so an emission with no subscriber attached is lost.
Subscribe with `async(start = CoroutineStart.UNDISPATCHED) { flow.first() }` before
triggering the emission, and await the value.

Do not subscribe from `backgroundScope`: `advanceUntilIdle()` does not run background
coroutines — that is what keeps them from holding a test open — so the collector never
gets to register and the assertion sees nothing, with no hint as to why.

### Substituting dispatchers and scopes

`TextToSpeechService` and `LiveAudioManager` expose their `CoroutineScope` and
main-thread dispatcher as fields so tests can inject a `TestScope` and
`Dispatchers.Unconfined` by reflection. ViewModels use `Dispatchers.setMain` with a
`StandardTestDispatcher`. Nothing in the unit test suite touches a real device, a
real socket, or an AI provider.

### Keep production files on their existing LF line endings

Some editors rewrite a whole file to CRLF the moment you touch one line. Git then
records every line as changed, `git blame` attributes the entire file to that
commit, and Sonar counts all of it as **new code** — which tanks the new-code
coverage percentage even though nothing really changed. Six files were rewritten
this way once and inflated the diff by ~2,165 lines.

Before committing a change to anything under `src/main`, check that the diff is
only what you meant to change:

```powershell
git diff --cached --numstat -- '*/src/main/*'
git diff --cached --ignore-cr-at-eol --numstat -- '*/src/main/*'
```

If the two disagree, the file was re-encoded. Strip the carriage returns and
re-stage before committing:

```powershell
$p = 'path/to/File.kt'
[IO.File]::WriteAllText($p, ([IO.File]::ReadAllText($p) -replace "`r`n", "`n"))
```

### Never run two Gradle builds against this project at once

The Kotlin incremental compiler keeps a per-module cache under
`<module>/build/kotlin/<task>/cacheable/`. A second concurrent build on the same
module fails with `Storage for [...source-to-classes.tab] is already registered`,
and it can leave the JaCoCo exec data partly written, so a coverage report taken
straight afterwards reads far lower than the truth. Let each build finish before
starting the next; if a report looks impossibly bad, re-run `clean testCoverage`
on its own before believing it.

### Untestable in JVM unit tests

Some classes cannot be covered by Robolectric tests at all, and attempting it
wastes time:

- Anything whose static initializer loads a native library, e.g. `CXRServiceBridge`
  (`cxr-bridge-jni`) — the class fails to load with `UnsatisfiedLinkError`.
- `EncryptedSharedPreferences`: the Android Keystore is unavailable, so creation
  throws. `SettingsRepository` survives this because it catches the failure and
  falls back to an in-memory store; a class that rethrows instead is untestable.
- `FileProvider` on Windows, which cannot resolve its synthetic data directory.

## Windows Java loopback startup failure

If Gradle fails before configuration with `Unable to establish loopback connection`
and `UnixDomainSockets.connect0`, use a short, writable directory outside the
Windows app's virtualized temporary directory for this shell only:

```powershell
$rokidTemp = Join-Path $env:USERPROFILE '.gradle/rokid-tmp'
New-Item -ItemType Directory -Force -Path $rokidTemp | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$rokidTemp -Djava.io.tmpdir=$rokidTemp"
```

Select a local JDK 21 using `JAVA_HOME`; do not commit a machine-specific JDK path.

## Shared devices / 共用 Android 裝置

Builds and JVM unit tests do not need ADB. While another project owns a device,
finish these checks first. Before any install, launch, or instrumentation run,
coordinate availability with that project's operator. A serial selector prevents
wrong-device access; it is not an exclusive device lock.

Observed on 2026-09-11 (verify again with `adb devices -l` before use):

| Serial | Model | Role |
| --- | --- | --- |
| `1901092544022855` | `RG_glasses` | Rokid glasses / 眼鏡 |
| `eeaas88ts4kn6l8t` | `21091116UG` | Xiaomi phone / 手機 |

Once the device is available, explicitly select it for every operation:

```powershell
# Install only the intended APK; do not run an unqualified installDebug.
adb -s eeaas88ts4kn6l8t install -r phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s 1901092544022855 install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk

# Gradle instrumentation: restrict this invocation, then restore the caller's selector.
$previousSerial = $env:ANDROID_SERIAL
try {
    $env:ANDROID_SERIAL = 'eeaas88ts4kn6l8t'
    .\gradlew.bat :phone-app:connectedDebugAndroidTest
} finally {
    $env:ANDROID_SERIAL = $previousSerial
}
```

Do not use `adb kill-server`, reboot devices, clear global logcat, or stop/uninstall
another project's application to free a device. 本專案與其他專案共用裝置時，先完成不需
裝置的建置及單元測試；取得使用時段後，每次 ADB 操作均加上 `-s`，Gradle 實機測試則
設定 `ANDROID_SERIAL`。指定序號無法防止兩個專案同時操作同一台裝置，仍須協調時段。
