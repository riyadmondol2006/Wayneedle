# Wayneedle architecture

Single Gradle module (`:app`), strict package layering. Pure-Kotlin layers have **no `android.*` /
`androidx.*` imports** so they run in fast JVM unit tests (an architecture test enforces this).

```
app.wayneedle
├── domain/            CONTRACTS (lead-owned): models + interfaces. Pure Kotlin. Do not edit.
│   └── model/
├── tracking/          GPS + trip engine. Pure Kotlin, except tracking/platform/ (LocationManager adapter, SystemTimeSource)
├── data/              Room database, DataStore settings, repositories, checkpoint store
├── permissions/       Permission status checker; AppPermissions (contract constants, lead-owned)
├── service/           Foreground service, notification, TripForegroundLauncher implementation
├── ui/                Compose UI, theme, view models, navigation, previews
├── AppContainer.kt    Manual DI (lead-owned). WayneedleApplication holds the container.
```

## Package ownership (no two workstreams edit the same file)

| Package / path | Owner |
|---|---|
| `domain/**`, `AppContainer.kt`, `WayneedleApplication.kt`, `AndroidManifest.xml`, all `*.gradle.kts`, `gradle/**`, `docs/ARCHITECTURE.md`, `AGENTS.md` | Lead |
| `tracking/**`, `src/test/.../tracking/**` | Agent 1 — GPS & trip engine |
| `ui/**`, `res/**` (except below), `src/androidTest/.../ui/**` | Agent 2 — Material 3 Expressive UI |
| `data/**`, `src/test/.../data/**`, `src/androidTest/.../data/**` | Agent 3 — database & settings |
| `service/**`, `permissions/` (except `AppPermissions.kt`), `res/drawable/ic_notification*`, `src/test/.../service/**` | Agent 4 — background & Android integration |
| `README.md`, `LICENSE`, `CONTRIBUTING.md`, `PRIVACY.md`, `.gitignore`, `.editorconfig`, `config/**`, `.github/**`, `docs/**` (except ARCHITECTURE.md), `src/test/.../testing/Fixtures.kt`, architecture tests | Agent 5 — quality & docs |

If you need a change in a file you do not own (e.g. a missing field in a contract), **do not edit it** —
describe the exact change you need in your final report; the lead decides and applies it.

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
* After process death a trip is recovered as `PAUSED` (`recovered = true`) from the checkpoint and the service is
  **not** auto-started from the background; the user reopens the app and taps Resume or Finish.
* The unobserved gap after process death is never added to duration or distance.

## Trip statistics rules

* Duration, distance and max speed change **only while `RUNNING`**.
* Distance accumulates between consecutive *accepted* fixes. After start **and after resume** the first
  accepted fix is only an anchor (no distance); `awaitingFix = true` until it arrives.
* Average speed = distance ÷ active duration (`TripStats.averageSpeedMps`).
* Live speed is shown whenever a reliable fix exists, even when IDLE/PAUSED, but is *not* added to stats while not RUNNING.

## GPS fix acceptance (guidance; Agent 1 owns exact thresholds and documents them in KDoc)

Reject a fix when any applies: speed missing / NaN / negative; accuracy missing or worse than ~25 m;
`speedAccuracyMps` present and worse than ~3 m/s; older than ~5 s relative to `TimeSource`;
non-increasing timestamp; speed above a physical cap (~150 m/s); implied acceleration spike
(>~12 m/s² vs previous accepted fix); reported speed grossly inconsistent with the displacement between fixes.
Treat tiny speeds (<~0.5 m/s) as 0 and ignore displacement below the accuracy noise floor so a parked
phone does not accumulate distance. Never fabricate a reading: no fix → `LiveSpeed.Unavailable`.
Conversion: km/h = m/s × 3.6 (`SpeedUnit`).

## Concurrency

Kotlin coroutines + `StateFlow`. `TripTracker` receives an application `CoroutineScope` and serialises all
state mutation (single writer). `TrackingController` commands are thread-safe.

## Persistence

Room (`data/db`) with exported schemas in `app/schemas/`; DataStore Preferences for settings;
`TripCheckpointStore` persists the in-progress trip about every 5 s of active time and on every phase change.
`TripRepository.saveTrip` is idempotent by trip id.

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
* Instrumentation tests — **only** on the verified Google Pixel 6 Pro, **only run by the lead**.
