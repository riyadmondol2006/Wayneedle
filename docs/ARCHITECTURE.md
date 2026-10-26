# Wayneedle architecture

Single Gradle module (`:app`), strict package layering. Pure-Kotlin layers have **no `android.*` /
`androidx.*` imports** so they run in fast JVM unit tests (an architecture test enforces this).

```
app.wayneedle
├── domain/            CONTRACTS (maintainer-owned): models + interfaces. Pure Kotlin. Do not edit.
│   └── model/
├── tracking/          GPS + trip engine. Pure Kotlin, except tracking/platform/ (LocationManager adapter, SystemTimeSource)
├── data/              Room database, DataStore settings, repositories, checkpoint store
├── alert/             Speed alert: crossing detection and vibration/beep, shared by the dashboard and the service
├── update/            The daily update check: the only code allowed to use the network
├── permissions/       Permission status checker; AppPermissions (contract constants, maintainer-owned)
├── service/           Foreground service, notification, TripForegroundLauncher implementation
├── ui/                Compose UI, theme, view models, navigation, HUD mode, previews
├── AppContainer.kt    Manual DI (maintainer-owned). WayneedleApplication holds the container.
```

## Package ownership (no two workstreams edit the same file)

| Package / path | Area |
|---|---|
| `domain/**`, `AppContainer.kt`, `WayneedleApplication.kt`, `AndroidManifest.xml`, all `*.gradle.kts`, `gradle/**`, `docs/ARCHITECTURE.md`, `AGENTS.md` | Maintainers |
| `tracking/**`, `src/test/.../tracking/**` | GPS & trip engine |
| `ui/**`, `res/**` (except below), `src/androidTest/.../ui/**` | UI and design system |
| `data/**`, `src/test/.../data/**`, `src/androidTest/.../data/**` | Database and settings |
| `service/**`, `alert/**`, `permissions/` (except `AppPermissions.kt`), `res/drawable/ic_notification*`, `src/test/.../service/**`, `src/test/.../alert/**` | Background tracking and Android integration |
| `update/**`, `src/test/.../update/**`, `.github/workflows/release.yml` | Maintainers (network and release) |
| `README.md`, `LICENSE`, `CONTRIBUTING.md`, `PRIVACY.md`, `.gitignore`, `.editorconfig`, `config/**`, `.github/**`, `docs/**` (except ARCHITECTURE.md), `src/test/.../testing/Fixtures.kt`, architecture tests | Quality, tests and documentation |

If you need a change in a file you do not own (e.g. a missing field in a contract), **do not edit it** —
describe the exact change you need in your final report; the maintainers decide and apply it.

## The one-GPS-owner rule

Exactly **one** component subscribes to `LocationSource`: the `TrackingController` implementation
(`tracking.TripTracker`). UI, service and notification only *observe* `TrackingController.state` and
*send commands*. This guarantees no duplicate location subscriptions.

The tracker holds the single GPS listener while **either**
(a) the UI is visible (`setUiVisible(true)`) — live speed works with no trip — **or**
(b) the trip phase is `RUNNING` (also in the background, kept alive by the foreground service).
Otherwise (invisible and not running, including `PAUSED` in the background) the listener is released to save battery.

## Foreground service

`TripForegroundLauncher.start()` is called by the tracker when a trip starts; `stop()` when the trip is
finished/discarded. The service (`TrackingService`, type `location`) only hosts the notification and process
priority; it observes `TrackingController.state` and sends Pause/Resume commands from notification actions.
It never talks to `LocationManager`.

* The service is started **only** from a user action while the app is visible (Android 12+ background-start limits).
* `start()` returns whether Android accepted the request; a refusal inside the service arrives through
  `TrackingController.reportBackgroundRecordingUnavailable()`. Either way `TrackingState.backgroundRecordingUnavailable`
  makes the dashboard warn that the trip records only while the app is on screen; Resume retries.
* Notification updates: at most one per second, one per 30 s while the screen is off, phase changes at once; after a
  swipe (Android 14+) none until the phase changes.
* After process death a trip is recovered as `PAUSED` (`recovered = true`) from the checkpoint and the service is
  **not** auto-started from the background; the user reopens the app and taps Resume or Finish.
* The unobserved gap after process death is never added to duration or distance.

## Trip statistics rules

* Duration, distance and max speed change **only while `RUNNING`**.
* Distance accumulates between consecutive *accepted* fixes. After start **and after resume** the first
  accepted fix is only an anchor (no distance); `awaitingFix = true` until it arrives.
* Average speed = distance ÷ active duration (`TripStats.averageSpeedMps`), shown as "Avg, no pauses".
* **Moving time** (`movingDurationMillis`) is the time of the segments that counted as distance, so stops while
  recording are left out; **moving average** = distance ÷ moving time. Null for trips saved before schema 2.
* **Ascent/descent** come from GPS altitude of fixes taken while moving with vertical accuracy ≤ 20 m, in hysteresis
  steps of max(5 m, the two fixes' vertical accuracies). Approximate; null when the receiver gave no altitude.
* Max speed is the speed held across two consecutive accepted fixes (the lower of the pair), where the second was
  cross-checked against the first (`Corroboration.PREVIOUS_FIX`). One glitched reading never sets the record.
* A segment longer than `DistanceConfig.GAP_MILLIS` (a GPS gap) is held until the next fix confirms its end, then
  counted; a glitched end adds nothing. A stationary fix after a gap does not void the gap ("parked" needs speed 0
  at both ends, or a segment that is not a gap).
* **Total time** is a second figure, derived rather than stored: wall-clock time from the trip's start to now (or to
  the saved end time), paused time included, never less than the active duration
  (`TripStats.totalDurationMillis`), so it needs no database column. While a trip is `RUNNING` or
  `PAUSED` the dashboard view model ticks a clock once a second, since the tracker publishes nothing while paused.
  The gap after process death is not active time but does count toward total time, as paused time.
* Live speed is shown whenever a reliable fix exists, even when IDLE/PAUSED, but is *not* added to stats while not RUNNING.

## GPS fix acceptance (guidance; the exact thresholds live in `FixFilterConfig` and are documented in KDoc there)

Reject a fix when any applies: speed missing / NaN / negative; accuracy missing or worse than ~25 m;
`speedAccuracyMps` present and worse than ~3 m/s; older than ~5 s relative to `TimeSource`;
non-increasing timestamp; speed above a physical cap (~150 m/s); implied acceleration spike
(>~12 m/s² vs previous accepted fix); reported speed grossly inconsistent with the displacement between fixes.
Treat tiny speeds (<~0.5 m/s) as 0 and ignore displacement below the accuracy noise floor so a parked
phone does not accumulate distance. A fix that fails the relative checks against the reference but agrees with
the fix rejected just before it is accepted (`Corroboration.REJECTED_FIX`): two agreeing fixes outvote one, so a
glitch that became the reference after a gap cannot block honest fixes for the whole relative window. Never fabricate a reading: no fix → `LiveSpeed.Unavailable`.
Conversion: km/h = m/s × 3.6 (`SpeedUnit`).

## Concurrency

Kotlin coroutines + `StateFlow`. `TripTracker` receives an application `CoroutineScope` and serialises all
state mutation (single writer). `TrackingController` commands are thread-safe.

## Persistence

Room (`data/db`) with exported schemas in `app/schemas/`; DataStore Preferences for settings;
`TripCheckpointStore` persists the in-progress trip about every 5 s of active time and on every phase change.
`TripRepository.saveTrip` is idempotent by trip id.

* `CheckpointWriter` never silently replaces another trip's checkpoint: before the first save of a trip it hands
  any other checkpoint to the tracker, which files it into History (finished at its last checkpoint) unless it is
  already stored or was discarded. This covers Start tapped before recovery ran, and recovery failing to read.
* A failed checkpoint delete is retried before every later operation; until it succeeds, loading reports no
  checkpoint, so a finished or discarded trip cannot come back in the same process.
* `RoomTripCheckpointStore.load` deletes only a row it cannot decode; a locked or failing database propagates
  and keeps the row. Recovery keeps the checkpoint when it cannot tell whether the trip is already in History.
* Screens never crash on database errors: History shows "Couldn't read your trips", deletes report failure.
* The settings flow retries after an I/O error (growing delay) instead of completing.
* `DatabaseSchemaPolicyTest` (JVM) fails when a version bump lacks its exported schema or its migration.

## UI behaviour worth knowing

* **Double taps.** Trip buttons swap places when the phase changes, so `DashboardViewModel` ignores trip commands
  for `DOUBLE_TAP_GUARD_MILLIS` (600 ms) after a phase change it made. Navigation ignores taps on a destination
  that is no longer resumed, so a double tap on Back cannot pop the dashboard.
* **Configuration changes** keep the GPS listener (`MainActivity` skips `setUiVisible(false)` while
  `isChangingConfigurations`).
* **Location permission gate.** "Blocked" (only Settings can help) is reported only once Android has stopped
  asking: the rationale flag was true at some point and is false again, or a request returned without a dialog.
* **Speed alert.** `alert.SpeedAlertMonitor`: one alert per crossing, 1 m/s hysteresis, unchanged through GPS gaps.
  While the app is visible (`TrackingState.uiVisible`) the dashboard alerts (red dial, limit mark, status pill,
  vibration and optional beep); otherwise the foreground service alerts during a `RUNNING` trip. Returning to the app
  while still above the limit alerts once more, because the dashboard starts its own monitor.
* **Closing the Battery Saver warning** hides it while Battery Saver keeps stopping GPS (saved in
  `UiPreferences`); once the app sees Battery Saver off, the choice is forgotten, so turning it on again later warns
  again (`keepBatterySaverDismissal`). The other warnings cannot be closed: each one means the trip is not recording
  properly.
* **Keep screen on** (`KeepAwakePolicy`): only while moving (≥ 1 m/s) and for 5 minutes after the last movement or
  a trip start/resume, so a phone left on the dashboard reaches its normal screen timeout.
* **HUD mode**: full-screen white-on-black speed, optionally mirrored for windscreen reflection, immersive, keeps the
  screen on while shown.

## Battery

* One GPS listener, released whenever the app is hidden and no trip is running.
* The tracker's duration ticker runs every second while the UI is visible and every 5 s in the background.
* The notification is updated at most once a second, once per 30 s with the screen off.
* The activity is never kept awake while the phone is still (see Keep screen on).

## Network: the update check

The app's only network access is `update.GitHubReleaseSource`: release builds ask
`https://api.github.com/repos/riyadmondol2006/Wayneedle/releases/latest` at most once a day when the app opens
(`UpdateChecker`, failures retried on the next open), and offer a newer, non-draft, non-prerelease version in a
dialog that opens the release page in the browser. The request has a 10 s timeout, the response is capped at 1 MB,
and only release pages of this repository are accepted. Settings › Updates switches it off; debug builds never check.
`ArchitectureTest` fails the build if networking APIs appear outside `app.wayneedle.update` or another URL appears
in main sources, and `RepositoryHygieneTest` allows `INTERNET` only in the main manifest.

## Known limitations (reviewed, not fixed)

* **Wall-clock jumps.** Total time and a trip's end time use the wall clock; a manual clock change or a large
  network correction during a trip skews total time (never below active time) and History order.
* **Provider state while the GPS listener is released.** `refreshEnvironment()` re-reads the permission only;
  whether Location is on is learned from the next listener event, so a Start in the first moment after returning
  to the app can see a stale value.
* **Battery saver location modes** that stop GPS with the screen off are detected and the dashboard warns, but the
  trip still keeps counting time without distance while the screen is off. The warning has not been seen on a device.
* **History day headers** ("Today") are computed when the list is drawn and do not roll over at midnight while
  the screen stays open.
* **First frame.** Screens start from default settings until DataStore has been read (a few milliseconds), so an
  mph user can see "km/h" for one frame.
* **Speed alert** in the background needs a running trip (the foreground service); with no trip it works only while
  the app is on screen.

## Material 3 Expressive

In the stable Material 3 **1.4.0** artifact the Expressive API is not usable: `ExperimentalMaterial3ExpressiveApi`,
`MaterialExpressiveTheme`, `MotionScheme` and `MaterialTheme.motionScheme` are `internal` (the compiler rejects
them), and `ButtonGroup`, `ToggleButton`, `SplitButton`, `LoadingIndicator`, `FloatingToolbar`, `MaterialShapes`
and `ButtonDefaults.shapes()` are absent. They first become public in the 1.5 pre-releases, which the project
rules exclude.

The UI is therefore *Expressive-inspired* and built from stable parts: `ui/theme` provides a hand-crafted brand
palette with Material You dynamic colour, large expressive shapes and heavier type, and `WayneedleMotion`
reproduces the Expressive spring tokens (spatial 0.8/380, fast spatial 0.6/800, effects 1.0/1600) and collapses
to `snap()` when animations are disabled. `ui/components` holds the shape-morphing button, button row and
needle loading indicator. `ShortNavigationBar` (stable) is used for navigation. There are no Expressive opt-ins;
the only `@OptIn` is `ExperimentalMaterial3Api` for the top app bar, confined to `ui/components/TitleScaffold.kt`.
Moving to the library implementation when Material 3 1.5 is stable is a contained change in those two packages.

## Testing

* JVM unit tests (`./gradlew testDebugUnitTest`) — allowed everywhere, run on the dev machine.
* Instrumentation tests — **only** on the verified Google Pixel 6 Pro, **only run by the maintainers**.
