# Testing

This document describes how Wayneedle is tested, what the automated checks enforce, the policy for testing on
hardware, and how results are recorded. Nothing here claims a result that is not in the [device log](#device-log).

## Strategy at a glance

| Layer | What it proves | Runs where | Command |
|---|---|---|---|
| Compiler | No warnings (`allWarningsAsErrors`) | Dev machine, CI | any build |
| JVM unit tests | Tracking engine, trip rules, recovery, unit conversion, update check, chaos test | Dev machine, CI | `./gradlew testDebugUnitTest` |
| Architecture and hygiene tests | Layering, one GPS owner, network only in the update check, Kotlin only, pinned versions | Dev machine, CI | part of `testDebugUnitTest` |
| Android Lint | Android API and resource problems (warnings are errors) | Dev machine, CI | `./gradlew lintDebug` |
| ktlint through Spotless | Code style | Dev machine, CI | `./gradlew spotlessCheck` |
| Instrumentation tests | Behaviour on real Android: Room, permissions, service, UI | **Google Pixel 6 Pro only** | `scripts/run-on-pixel-6-pro.sh connectedDebugAndroidTest` |
| Manual field tests | Real GPS accuracy, battery, background behaviour | **Google Pixel 6 Pro only** | see [checklist](#manual-device-checklist) |

`./gradlew staticAnalysis` runs `spotlessCheck` and `lintDebug`. The CI command is
`./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug`.

## JVM tests

Logic lives in pure Kotlin (`domain`, `tracking`) so it can be tested without Android. Conventions:

* Use `kotlin.test`, `kotlinx-coroutines-test` (`runTest`) and Turbine for flows.
* Never read the real clock or GPS in a test. Inject `TimeSource` (`FakeTimeSource`) and `LocationSource`
  (`FakeLocationSource`).
* Name tests with backticked sentences that state the behaviour.
* A bug fix starts with a test that fails without the fix.

**Chaos test.** `TripTrackerChaosTest` plays 150 seeded simulated users through 250 random steps each against the
real `TripTracker` with fake GPS and storage: tapping commands in any order and several times in a row, Finish
twice while the save is slow, Location switched off and on, permission revoked (which kills the process), the
process dying at any moment (also with Start tapped before recovery ran), storage failing, and GPS glitches (speed
spikes, stale, duplicated, future, malformed fixes, position jumps) between honest fixes from a vehicle that
accelerates, brakes and parks. After every step it checks the invariants: no NaN or negative statistics; a trip's
statistics never go backwards and change only while RUNNING; never more distance, active time or top speed than
the simulated vehicle really produced while recording; rejected commands change nothing; a finished trip is stored
exactly once and its checkpoint never comes back; an interrupted trip is never silently lost; the foreground
service runs exactly while a trip needs it; at most one GPS listener. Coverage guards make it fail if the
interesting situations stop happening. A failure prints the seed and the recent steps.

### Fixtures and fakes

Shared helpers are in `app/src/test/kotlin/app/wayneedle/testing/`:

| Helper | Purpose |
|---|---|
| `aSample(...)` | A good-quality `LocationSample`; override only what the test cares about. |
| `latitudeAfterNorth`, `longitudeAfterEast` | Move a coordinate by metres. |
| `scriptedRoute { ... }` | Build a sequence of fixes from specs: `fix`, `leg`, `cruise`, `stationary`, `silence`, `heading`. |
| `distanceMeters`, `routeLengthMeters` | Haversine distance between fixes, to assert expected geometry. |
| `FakeTimeSource` | Manually advanced clock. |
| `InMemoryTripRepository` | Idempotent, newest-first, injectable save failure, call counters. |
| `InMemoryTripCheckpointStore` | Single slot, remembers every write, injectable save failure. |
| `FakeLocationSource` | Honours the `LocationSource` contract; counts concurrent collectors to prove a single GPS owner. |
| `FakeTripForegroundLauncher`, `FakeLocationPermissionChecker`, `FakeSettingsRepository` | Small recording fakes. |
| `FakeTrackingController` | For UI and service tests: push states in, read commands out. |

A drive with a tunnel in the middle:

```kotlin
val route = scriptedRoute {
    fix(speedMps = 0f)                                      // first fix, standing still
    cruise(steps = 20, metersPerStep = 10.0)                // 20 s at 10 m/s
    silence(millis = 8_000L, travelMeters = 80.0)           // no fixes for 8 s
    leg(steps = 5, metersPerStep = 10.0, speedMps = 40f)    // reported speed disagrees with displacement
}
```

### Architecture and hygiene tests

These live in `app/src/test/kotlin/app/wayneedle/architecture/`. Each failure names the file and line. Missing
directories pass vacuously, so the tests hold while code is still being added. `RuleSelfTest` runs every rule
against synthetic violations so a rule cannot go green by matching nothing.

| Rule | Where |
|---|---|
| `domain/**` and `tracking/**` (except `tracking/platform/**`) import nothing from `android.*` or `androidx.*`; `domain` imports no implementation layer; `tracking` imports no `data`, `ui`, `service` or `permissions` code | `ArchitectureTest` |
| `data/` and `ui/` do not use `service/` or `tracking/`; `ui/` does not use `androidx.room`; `service/` does not use `tracking/` | `ArchitectureTest` |
| Only `tracking/platform/AndroidLocationSource` references `android.location`, `LocationManager` or location-update APIs | `ArchitectureTest` |
| `ui/`, `service/`, `data/` and `permissions/` never reference `LocationSource` (the one-GPS-owner rule) | `ArchitectureTest` |
| No `http://` or `https://` string literals in main sources except this repository's GitHub addresses (allowlist in `ArchitectureRules.allowedUrlPrefixes`) | `ArchitectureTest` |
| Networking APIs (`java.net`, `javax.net`, `HttpURLConnection`, sockets) only in `app.wayneedle.update` | `ArchitectureTest` |
| No single-line log or print call that mentions a coordinate or a whole fix (best-effort tripwire) | `ArchitectureTest` |
| No `.java` and no Groovy build scripts anywhere | `ArchitectureTest` |
| `android.permission.INTERNET` only in `src/main/AndroidManifest.xml` (for the update check), in no other source-set manifest | `RepositoryHygieneTest` |
| Every Room database version has an exported schema and a registered migration | `DatabaseSchemaPolicyTest` |
| `android:allowBackup="false"` in the main manifest | `RepositoryHygieneTest` |
| Version catalog and build scripts: no `+`, ranges, `latest`, snapshot, alpha, beta, rc, dev, eap or milestone versions | `RepositoryHygieneTest` |
| `org.jetbrains.kotlin.android` is not applied | `RepositoryHygieneTest` |
| No analytics, crash-reporting, networking or Play Services dependencies (denylist in `ArchitectureRules.deniedGroups`) | `RepositoryHygieneTest` |
| `LICENSE` is the unmodified Apache License 2.0 (checked by SHA-256 of the official text) | `RepositoryHygieneTest` |

If a rule is wrong for a legitimate reason, change the rule in the same pull request and explain why. Do not work
around it.

## Static analysis

### What runs

* **Kotlin compiler** with `allWarningsAsErrors` (main, test and Android test sources).
* **Android Lint** with `warningsAsErrors` and `abortOnError`.
* **ktlint 1.8.0 through Spotless 8.10.4** over `app/src/**/*.kt` and the Gradle Kotlin-DSL scripts. The rules are
  in the root `.editorconfig`:
  * `ktlint_code_style = intellij_idea`, which is the Kotlin official coding convention, with a 120 character limit
    and trailing commas allowed.
  * `@Composable` functions may be PascalCase; star imports are allowed for `androidx.compose.**` only.
  * `function-signature` and `class-signature` are disabled so hand-written one-parameter-per-line declarations
    and long Compose signatures are not collapsed or reflowed.
  * `domain/TrackingController.kt` has an `import-ordering` override because its imports were committed unsorted;
    delete the override in `.editorconfig` once they are sorted.
* `spotlessApply` fixes what can be fixed automatically. It refuses to run while a non-fixable finding exists
  (a line over 120 characters, a star import outside Compose); fix those by hand first.
* The configuration cache stays enabled (`org.gradle.configuration-cache.problems=fail`) and is reused across runs.

The ktlint setup was checked with the full CI command on JDK 17 (Temurin 17.0.20.1) and on JDK 25.

### Tools evaluated and not used

| Tool | Decision | Reason |
|---|---|---|
| detekt 1.23.8 (`io.gitlab.arturbosch.detekt`) | Not used | It is the last stable 1.x release; the 2.x line (`dev.detekt`) only has alpha releases (2.0.0-alpha.6 on 2026-10-25), which the project does not allow. On JDK 25 the task fails twice: the plugin passes the JDK version as `--jvm-target` (`Invalid value (25) passed to --jvm-target, must be one of [1.6 ... 22]`), and with `jvmTarget` forced to 17 the embedded Kotlin 2.0.21 compiler still crashes in `JavaVersion.parse` with `IllegalArgumentException: 25.0.4.1`. On JDK 17 with `jvmTarget = "17"` it does run, but it parses with a Kotlin 2.0 front end while the project uses Kotlin 2.4, so newer syntax would not parse. The type-resolution tasks were not evaluated. A check that only works on some JDKs is not a reliable gate. |
| ktlint Gradle plugin (`org.jlleitschuh.gradle.ktlint`) | Not evaluated | Spotless already provides ktlint with configuration-cache support. |

If detekt publishes a stable 2.x release that supports Kotlin 2.4 and JDK 25, revisit this decision.

## Device testing policy

Only a **Google Pixel 6 Pro** is used to validate the project's own behaviour, and the checks are strict because
other phones may be attached to the same machine.

* The device must report `ro.product.manufacturer=Google`, `ro.product.device=raven` and
  `ro.product.model=Pixel 6 Pro`. Anything else is off limits and is never sent a command.
* **No emulators or AVDs, no Firebase Test Lab or other cloud device farms.** Emulated GPS says nothing about real
  GPS behaviour, and CI never runs instrumentation tests.
* Device runs are done by the maintainers through the scripts below, never with a bare `adb` or
  `./gradlew connected...` when other devices are attached.
* Contributors are not required to own the device. If you test on other hardware, say so in your pull request; that
  evidence is welcome but is not recorded as the project's validation.

### Scripts

Both scripts are in `scripts/`. They are plain Bash and need `adb` on the `PATH` (or `ADB=/path/to/adb`).

`scripts/verify-pixel-6-pro.sh` lists attached devices (`adb devices -l`). A device becomes a candidate only if it is
in the `device` state, is not an `emulator-*` entry, and its listing row says `device:raven`; every other device is
skipped without a single command being sent to it. For each candidate it reads `ro.product.manufacturer`,
`ro.product.device` and `ro.product.model`, stopping at the first mismatch.
It prints one line, `<serial><TAB><Android release><TAB><SDK level>`, for the verified device, and exits:

| Exit code | Meaning |
|---|---|
| 0 | Exactly one verified Pixel 6 Pro |
| 1 | No verified device |
| 2 | More than one verified device (disconnect all but one) |
| 3 | `adb` missing, not responding, or the Android version could not be read |

It never prints the serial of a device that does not match, only a count of ignored devices.

`scripts/run-on-pixel-6-pro.sh <gradle-task|adb-subcommand...>` calls the verifier first and refuses to continue on
any non-zero exit. Then:

* A Gradle device task (`connected*`, `install*`, `uninstall*`, optionally prefixed `:app:`) runs the wrapper from
  the repository root with `ANDROID_SERIAL` set to the verified serial. The Android Gradle Plugin uses that variable
  to pick the device. Set `WAYNEEDLE_GRADLE` to change the command, for example `WAYNEEDLE_GRADLE="./gradlew --no-daemon"`.
* An adb subcommand (`install`, `install-multiple`, `uninstall`, `logcat`, `shell`, `push`, `pull`, `bugreport`) runs
  as `adb -s <verified serial> ...`.
* Anything else, including `adb` global options such as `-s`, is refused.

```sh
scripts/verify-pixel-6-pro.sh
scripts/run-on-pixel-6-pro.sh installDebug
scripts/run-on-pixel-6-pro.sh connectedDebugAndroidTest
scripts/run-on-pixel-6-pro.sh connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=app.wayneedle.data
scripts/run-on-pixel-6-pro.sh logcat -d
scripts/run-on-pixel-6-pro.sh shell dumpsys location
```

The scripts have been checked with `bash -n` and ShellCheck. `verify-pixel-6-pro.sh` has been run against a real
Pixel 6 Pro with a second, different Pixel attached and correctly reported only the Pro. `run-on-pixel-6-pro.sh`
has **not** been executed yet: the 2026-10-26 device run below used `adb -s <verified serial>` directly. Run the
verifier alone first and confirm that it prints the expected line. For Gradle tasks, safety rests on the Android
Gradle Plugin honouring `ANDROID_SERIAL`; with other phones attached prefer `am instrument` through the `shell`
subcommand (below), which names the device explicitly, or unplug the other phones before a `connected*` task.

### Running the instrumentation tests

The suite lives in `app/src/androidTest` (96 tests: Room DAOs and migrations, the notification, every UI screen
including HUD mode, the end-to-end location tests, recording in deep Doze, and parsing of GitHub release responses). With another phone attached, run it with an explicit serial instead of
`connectedDebugAndroidTest`:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
scripts/run-on-pixel-6-pro.sh install -r -t app/build/outputs/apk/debug/app-debug.apk
scripts/run-on-pixel-6-pro.sh install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
scripts/run-on-pixel-6-pro.sh shell am instrument -w -r app.wayneedle.debug.test/androidx.test.runner.AndroidJUnitRunner
# one class: add  -e class app.wayneedle.tracking.GpsPipelineInstrumentedTest
```

Things the suite needs, all learned on the device:

* **Screen on and unlocked.** Compose tests need a resumed activity; with the display off they fail with "No
  compose hierarchies found". Keep it awake while plugged in (`svc power stayon true`, undone with
  `svc power stayon false`) and dismiss the keyguard (`wm dismiss-keyguard`). A dialog from another app on top of
  the test activity has the same effect.
* **Mock location for the end-to-end tests.** `GpsPipelineInstrumentedTest` and `TrackingServiceEndToEndTest`
  inject scripted fixes with speed through the platform's test provider. They are *skipped* unless the debug app
  has the app-op: `shell appops set app.wayneedle.debug android:mock_location allow`, and afterwards
  `... default`. The `adb shell cmd location providers` commands cannot be used instead: they are denied for the
  shell user and cannot set a speed anyway.
* **A visible app.** "While using the app" location is only delivered while the app is visible, which is why those
  tests keep a resumed activity (the service test then closes it to prove the foreground service keeps GPS alive).
* **No trip in progress.** The end-to-end and Doze tests skip when a trip is already running or paused on the
  phone, and discard only the trips they started, so a real trip is never touched. Finish or discard it first.
* **Deep Doze.** `BackgroundRecordingDozeTest` turns the screen off, forces deep Doze
  (`dumpsys deviceidle force-idle`) through the instrumentation's shell, checks that fixes still reach the trip, and
  restores the device (`deviceidle unforce`, screen on) afterwards.
* **Locale.** Numbers are formatted for the device locale (a phone set to `en-PT` prints `0,12 km`). Tests must
  not hard-code a decimal separator; use `localizedDecimal(...)` from `UiTestUtils`.
* **The fixes are scripted, not satellite measurements.** These tests prove the pipeline (Android location stack,
  adapter, filter, trip engine, Room, foreground service, notification) behaves correctly. They say nothing about
  real-world GPS accuracy; that needs the field checklist below.

### Procedure for a device run

1. Check out the commit to test; note its hash (`git rev-parse --short HEAD`) and that the tree is clean.
2. Run `scripts/verify-pixel-6-pro.sh` and confirm exactly one device is reported.
3. `./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug` must pass before any device run.
4. Run `scripts/run-on-pixel-6-pro.sh connectedDebugAndroidTest` and, for field tests, `installDebug` and the
   [checklist](#manual-device-checklist).
5. Record every run, passing or failing, in the device log.

### Manual device checklist

These scenarios need real GPS and cannot be judged on the JVM. Each is run on the Pixel 6 Pro and recorded in the log.

* **Permissions**: first run (deny, approximate only, precise); revoke while a trip runs; "only this time".
* **GPS off**: start a trip with the provider disabled (expect the "location disabled" result); switch it off
  mid-trip and back on.
* **Standing still outdoors for 2 minutes**: speed reads 0 and distance stays at about 0.
* **Walk about 500 m with a known distance** (for example a measured track): distance within a few percent, speed
  around walking pace.
* **Drive or cycle**: top speed is plausible compared with the vehicle's own speedometer, with no spikes at start,
  in tunnels or under tree cover.
* **Pause and resume**: distance travelled while paused is not counted; the first fix after resume only anchors.
* **Screen off for 10 minutes or more** with a trip running: the notification stays, the trip keeps counting, and
  Pause/Resume from the notification works.
* **Process death**: kill the app mid-trip (`scripts/run-on-pixel-6-pro.sh shell am kill app.wayneedle.debug` after
  sending it to the background); on reopening the trip is paused, marked recovered, and resumable or finishable.
* **Battery**: leave the app with no trip running and confirm the GPS listener is released
  (`scripts/run-on-pixel-6-pro.sh shell dumpsys location`).
* **UI**: rotation, light and dark themes, dynamic colour on and off, large font scale, km/h and mph.

### Checklist status (2026-10-26)

Covered by the automated device runs (with scripted fixes): pause/resume accounting, first fix after resume only
anchoring, GPS listener released when the app is hidden and no trip runs, Pause/Resume from the notification, the
trip surviving the activity being closed because of the foreground service, finish stopping the service, and
recording with the screen off in forced deep Doze.

Covered by hand (see the log): standing still for 2 minutes with a real satellite fix (0.00 km), a 5-minute
screen-off trip (battery measurement), rotation, km/h, recovery after a force-stop and after an app update.

**Not done yet, and needing a person with the phone outdoors**: everything that depends on real satellites while
moving (a measured walk, drive or cycle, tunnels), screen off for 10 minutes or more in natural (not forced) Doze,
process death by `am kill` mid-trip, Task Manager "Stop", swipe-dismissing the notification on Android 17,
permission revocation mid-trip, GPS switched off mid-trip, and the update popup with a real published release.
Nothing in this project claims those have been verified.

## Device log

Append a row for every device run. Record the commit, the scenario and the outcome, including failures. Do not
record device serial numbers, coordinates or GPS tracks.

| Date | Commit | Device and Android build | Suite or scenario | Result | Notes |
|---|---|---|---|---|---|
| 2026-10-26 | adb4420 | Pixel 6 Pro, Android 17 (API 37.1), build CP3A.261005.002.A1, locale en-PT | Full instrumentation suite (`am instrument`), 86 tests | Pass (86/86) | Indoors, no satellite fix. Location was mock-driven; see "Running the instrumentation tests". |
| 2026-10-26 | adb4420 | same | Manual: first launch, permission flow, idle/running/paused/finished dashboard, History, Settings, delete-all, with mock-driven fixes | Pass | Visual check by screenshot. Real GNSS status was live ("0 of 4 satellites") but no fix indoors. |
| 2026-10-26 | 2c7c796 | same | Full instrumentation suite (`am instrument`), 86 tests | Pass (86/86) | Same scripted-fix setup as above. Two UI tests gained a total-time assertion. |
| 2026-10-26 | 2c7c796 | same | Manual: Start, 10 s running, Pause, wait, Finish, trip details, delete; also force-stop while paused (recovery) | Pass | No GPS fix indoors, so distance stayed 0. Active time froze at the Pause tap while total time kept counting, and the saved total stayed fixed. Found and fixed a clipped total-time tile in the paused layout. |
| 2026-10-26 | 42a7eb3 | same | Full instrumentation suite, 86 tests | **Fail (70/86)** | 16 Compose UI tests failed with "No compose hierarchies found": the display had gone to sleep and locked during the run, so no activity was resumed. An environment problem, not an app failure. |
| 2026-10-26 | 42a7eb3 | same | The 41 tests in the failing classes, then the full suite, screen kept awake and unlocked | Pass (41/41, then 86/86) | |
| 2026-10-26 | 42a7eb3 + uncommitted tree | same | Manual with scripted fixes: speed alert (red dial, limit mark, one vibration per crossing in the vibrator log), CSV export through the system file picker (file content checked, then deleted), History totals, double-tap on Start, notifications-off warning and its "Turn on" button, rotation | Pass | Rotation did not re-register the GPS listener (location event log). Rotation settings restored afterwards. |
| 2026-10-26 | 42a7eb3 + uncommitted tree | same | **Real satellites**: trip with the phone lying still for 2 minutes | Pass | Accuracy 8 to 9 m, 11 to 13 satellites used. Distance 0,00 km, top speed 0 after 2 min 0 s. |
| 2026-10-26 | e993d8d (before commit) | same | `TrackingServiceEndToEndTest` | Fail, then pass | Found a race introduced by an uncommitted change: a stale "Paused" notification could reappear after Finish. Fixed (250 ms settle after a phase change and an explicit cancel on stop); the test then passed 5 times in a row. |
| 2026-10-26 | e993d8d + uncommitted tree | same | Keep screen on; 5-minute screen-off trip with `batterystats` reset first | Pass | `KEEP_SCREEN_ON` absent while idle with the phone still, present after Start. Over the trip Android attributed 1.65 mAh to the app (debug build), with 3 notification posts and about 12.5 s CPU. |
| 2026-10-26 | 09a4a80 (before commit) | same | App update over the previous build with two saved trips and a trip in progress (database schema 1 to 2) | Pass | Both trips kept with their figures; the in-progress trip came back as "Recovered trip" and resumable. |
| 2026-10-26 | 09a4a80 (before commit) | same | Full instrumentation suite, 90 tests | **Fail (89/90)** | `BackgroundRecordingDozeTest` met the recovered, paused trip left by the app-update check above and could not start. The end-to-end tests now skip when a trip is in progress and discard only their own trips. |
| 2026-10-26 | 09a4a80 | same | Full instrumentation suite, 90 tests | Pass (90/90) | Includes recording with the screen off in forced deep Doze. |
| 2026-10-26 | da16377 (before commit) | same | `GitHubReleaseParsingTest`; Settings › Updates › "Check now" | Pass (3/3) | The real request reached GitHub and reported "You have the latest version" (no release published yet). The popup for a newer release has not been seen on a device. |
| 2026-10-26 | f866d40 (before commit) | same | Full instrumentation suite, 96 tests | Pass (96/96) | |
| 2026-10-26 | f866d40 (before commit) | same | Manual with scripted fixes: heading after the unit, GPS details dialog, HUD mode (mirror, full screen, exit), speed alert from the background service | Pass | Screenshots and the vibrator log. With the app in the background the alert vibrated once on crossing, and once more on returning to the app while still above the limit. |

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests, with read-only permissions, JDK 17
(Temurin) and every action pinned to a full commit SHA. It runs
`./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug` and uploads the reports when a step fails. It
does not start an emulator and does not run instrumentation tests. The same Gradle command was run locally on
JDK 17; the workflow itself has been linted but has not yet run on GitHub.

`.github/workflows/release.yml` runs on every push to `main`: the same tests and checks, then a signed release
build published as a GitHub Release ([RELEASING.md](RELEASING.md)). The signing and version steps were checked
locally with a throwaway key (`apksigner verify` passed, version code and name taken from the run number); the
workflow has not yet run on GitHub.
