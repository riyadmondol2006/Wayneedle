# Wayneedle

Wayneedle is an open-source GPS speedometer and trip tracker for Android. It is written entirely in Kotlin with
Jetpack Compose, works fully offline, and keeps everything on the device.

* No accounts, no analytics, no crash reporters, no ads, no third-party SDKs.
* No `INTERNET` permission. The app cannot talk to a network, and an automated test keeps it that way.
* Package `app.wayneedle`, licensed under the [Apache License 2.0](LICENSE).

The reference device is a Google Pixel 6 Pro running Android 17. See [Status and verification](#status-and-verification)
for what has and has not been checked.

## Features

* **Live speedometer** in km/h or mph, available whenever the app is on screen, with or without a trip running.
* **Trips**: start, pause, resume and finish. Each trip records distance, active duration, top speed and average
  speed. Paused time is excluded from every figure.
* **History** of finished trips, stored locally.
* **GPS status**: signal quality, accuracy and an explicit "no fix" state. A missing reading is never shown as
  0 km/h.
* **Keeps recording** with the screen off while a trip is running, through a foreground service and an ongoing
  notification with Pause/Resume actions.
* **Crash and process-death recovery**: an interrupted trip comes back paused; the time the app was not running is
  never counted.
* **Material 3 Expressive** interface with dynamic (Material You) colour on Android 12+, light and dark themes,
  and an optional keep-screen-on setting.

## Screenshots

<!-- SCREENSHOTS-PLACEHOLDER
     Add real screenshots captured on a Google Pixel 6 Pro here (docs/images/ is a suitable location).
     Do not add mock-ups or edited images. -->

_Screenshots have not been added yet._

## How speed is measured

Wayneedle reads the speed that the phone's GPS receiver reports for each fix (`Location.getSpeed()`, in metres per
second) and multiplies by 3.6 to get km/h (mph uses 2.2369). That value is the speed the receiver itself reports (on
most GNSS chips it is derived from the Doppler shift of the satellite signals), not the distance between two
positions divided by the time between them, so it responds quickly and does not jitter while you stand still.

Not every fix is trusted. A fix is ignored when, for example, its position accuracy is poor, it is stale or out of
order, it carries no speed, or the jump from the previous fix is physically implausible. Very small speeds are
treated as standing still, and tiny position changes inside the receiver's noise level do not add distance, so a
phone lying on a table does not collect kilometres. When no reliable fix exists the speedometer says so instead of
guessing. The exact thresholds are documented in the tracking engine's source and summarised in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

Trip distance is the sum of the distances between consecutive accepted fixes while the trip is running. After
starting or resuming, the first accepted fix only sets the starting point. Average speed is total distance divided
by active (not paused) duration.

Speed measured this way needs the **precise** location permission. If you grant only approximate location, the app
tells you that it cannot measure speed.

## Requirements

* A device running Android 8.0 (API 26) or newer with a GPS receiver. The app installs without GPS hardware and then
  reports that it has no GPS.
* To build: JDK 17 or newer and the Android SDK (see [Building](#building)).

## Toolchain

| Component | Version |
|---|---|
| Gradle (wrapper) | 9.8.2 |
| Android Gradle Plugin | 9.4.1, using its built-in Kotlin support (`org.jetbrains.kotlin.android` is not applied) |
| Kotlin | 2.4.21 (the Compose compiler plugin uses the same version) |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 |
| Material 3 | 1.4.0 (Expressive APIs behind documented opt-ins) |
| Room | 2.8.5 |
| DataStore | 1.3.0 |
| Kotlin coroutines | 1.11.0 |
| JDK | 17 or newer (bytecode target 17; CI uses Temurin 17) |
| Min SDK | 26 |
| Target and compile SDK | 37 |
| Style checks | ktlint 1.8.0 through Spotless 8.10.4 |

Every version is an exact, stable release; the version catalog is [gradle/libs.versions.toml](gradle/libs.versions.toml).

## Building

1. Install a JDK, version 17 or newer, and make sure `java -version` finds it.
2. Install the Android SDK with Platform 37 (minor version 2, package `platforms;android-37.2`) and accept its
   licences, for example with Android Studio's SDK Manager or
   `sdkmanager "platforms;android-37.2"` and `sdkmanager --licenses`. Gradle can download missing components once
   the licences are accepted.
3. Tell Gradle where the SDK is: set `ANDROID_HOME`, or create `local.properties` in the repository root
   (it is git-ignored):

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

4. Build the debug APK:

   ```sh
   ./gradlew assembleDebug
   ```

   The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The debug build uses the application id
   `app.wayneedle.debug` so it can sit next to a release build.

Release builds enable R8 shrinking but ship with no signing configuration; supply your own keystore locally and
never commit it.

The wrapper downloads Gradle 9.8.2 and verifies its checksum. The first build downloads dependencies from Google's
Maven repository and Maven Central, so it needs a network connection even though the app itself does not.

## Testing

```sh
./gradlew testDebugUnitTest   # JVM unit and architecture tests (no device needed)
./gradlew lintDebug           # Android Lint, warnings are errors
./gradlew staticAnalysis      # ktlint style check (Spotless) plus Android Lint
./gradlew spotlessApply       # fix formatting problems automatically
```

* The Kotlin compiler also treats warnings as errors.
* **JVM tests** cover the tracking engine, the domain rules and the repository's own hygiene rules (no `INTERNET`
  permission, no Java sources, layering, pinned stable versions, and more). They run in seconds on any machine.
* **Instrumentation tests** (`connectedDebugAndroidTest`) are device tests. The maintainers verify on a
  **Google Pixel 6 Pro** and nowhere else: no emulator is used for the project's own validation, and other
  phones attached to a maintainer's machine are never touched. The procedure, the safety scripts and the results log
  are in [docs/TESTING.md](docs/TESTING.md).

### Status and verification

This section is only updated with results that are recorded in [docs/TESTING.md](docs/TESTING.md).

* Verified by the commands above on a development machine: the debug sources compile with warnings as errors, the
  JVM tests pass, Android Lint and the ktlint check pass.
* **Not yet verified on a device**: GPS behaviour, accuracy, the foreground service over long trips, battery use,
  and Android 17 specifics. Until a result appears in the device log in `docs/TESTING.md`, treat device behaviour
  as untested.
* No other device models, Android versions or emulators have been tested.

## Project structure

```
app/
  src/main/kotlin/app/wayneedle/
    domain/        Contracts: models and interfaces. Pure Kotlin.
    tracking/      GPS and trip engine. Pure Kotlin except tracking/platform (the LocationManager adapter).
    data/          Room database, DataStore settings, repositories, trip checkpoint store.
    permissions/   Location and notification permission handling.
    service/       Foreground service and notification.
    ui/            Compose UI, theme, view models, navigation.
    AppContainer.kt, WayneedleApplication.kt   Manual dependency wiring.
  src/test/        JVM unit tests, architecture tests, test fixtures and fakes.
  src/androidTest/ Device tests (run only on the reference device).
  schemas/         Exported Room schemas (tracked).
docs/              Architecture and testing documentation.
scripts/           Device-safety scripts for the maintainers' Pixel 6 Pro.
.editorconfig      Formatting and ktlint rules.
```

## Architecture

A single Gradle module with strict package layering. The domain and tracking packages contain no Android imports
so the engine is tested on the JVM. Exactly one component, the `TrackingController` implementation, ever subscribes
to the GPS; the UI, the service and the notification only observe its state and send it commands. Details,
including the trip state machine and the persistence rules, are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy

Location is used on the device to compute speed and trip statistics and for nothing else. Trips are stored
locally, excluded from cloud backup, and nothing is ever sent anywhere because the app has no network permission.
The full explanation, including why a notification is shown while a trip records and how to delete your data, is in
[PRIVACY.md](PRIVACY.md).

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) first; the main rules are Kotlin only, no
network access, no analytics, and tests for behaviour changes. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md).
Report security problems as described in [SECURITY.md](SECURITY.md).

## License

Copyright 2026 Wayneedle contributors.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
