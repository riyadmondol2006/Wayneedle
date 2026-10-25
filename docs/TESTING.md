# Testing

This document describes how Wayneedle is tested, what the automated checks enforce, the policy for testing on
hardware, and how results are recorded. Nothing here claims a result that is not in the [device log](#device-log).

## Strategy at a glance

| Layer | What it proves | Runs where | Command |
|---|---|---|---|
| Compiler | No warnings (`allWarningsAsErrors`) | Dev machine, CI | any build |
| JVM unit tests | Tracking engine, trip rules, unit conversion, fixtures | Dev machine, CI | `./gradlew testDebugUnitTest` |
| Architecture and hygiene tests | Layering, one GPS owner, no network, Kotlin only, pinned versions | Dev machine, CI | part of `testDebugUnitTest` |
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
| No `http://` or `https://` string literals in main sources (allowlist in `ArchitectureRules.allowedUrlPrefixes`, empty) | `ArchitectureTest` |
| No single-line log or print call that mentions a coordinate or a whole fix (best-effort tripwire) | `ArchitectureTest` |
| No `.java` and no Groovy build scripts anywhere | `ArchitectureTest` |
| No `android.permission.INTERNET` in any source-set manifest, or in the merged manifests when a build has produced them | `RepositoryHygieneTest` |
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

`scripts/verify-pixel-6-pro.sh` lists attached devices (`adb devices -l`) and, for each device in the `device`
state that is not an `emulator-*` entry, reads `ro.product.manufacturer`, `ro.product.device` and
`ro.product.model`, stopping at the first mismatch. Nothing else is ever run on a device that does not match.
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

The scripts have been checked with `bash -n` and ShellCheck. They have not been executed in the course of writing
them, so the first real run on the device is also their first test: run `scripts/verify-pixel-6-pro.sh` alone first
and confirm that it prints the expected line. For Gradle tasks, safety rests on the Android Gradle Plugin honouring
`ANDROID_SERIAL`; if there is any doubt, unplug the other phones before running a `connected*` task.

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

## Device log

Append a row for every device run. Record the commit, the scenario and the outcome, including failures. Do not
record device serial numbers, coordinates or GPS tracks.

| Date | Commit | Device and Android build | Suite or scenario | Result | Notes |
|---|---|---|---|---|---|
| | | | | | No device runs have been recorded yet. |

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main` and on pull requests, with read-only permissions, JDK 17
(Temurin) and every action pinned to a full commit SHA. It runs
`./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug` and uploads the reports when a step fails. It
does not start an emulator and does not run instrumentation tests. The same Gradle command was run locally on
JDK 17; the workflow itself has been linted but has not yet run on GitHub.
