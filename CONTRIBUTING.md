# Contributing to Wayneedle

Thank you for helping. Wayneedle is a small, offline, Kotlin-only app, and the rules below keep it that way.
By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Your contributions are licensed under
the [Apache License 2.0](LICENSE), the same as the project.

## Non-negotiable rules

* **100 % Kotlin.** No Java sources and no Groovy build scripts. Gradle files use the Kotlin DSL.
* **No network access.** Do not add the `INTERNET` permission, an HTTP client, or any hard-coded URL that the app
  would open itself.
* **No analytics, crash reporters, ads or third-party SDKs.** Dependencies are limited to Kotlin, AndroidX and
  Compose. A new dependency needs a clear reason in the pull request.
* **Never log or persist raw coordinates.** Location samples are processed in memory; only trip summaries are stored.
* **Exact stable versions only.** No `+`, ranges, `latest`, snapshots, alpha, beta, rc or similar in the version
  catalog or build scripts.
* **Warnings are errors.** The Kotlin compiler and Android Lint both fail on warnings. Fix the cause; suppress a
  warning only with a comment that explains why.

Several of these are enforced by tests in `app/src/test/kotlin/app/wayneedle/architecture/`, so a violation fails
the build with a file and line.

## Setup

1. Install JDK 17 or newer and the Android SDK (Platform 37.2). See [Building in the README](README.md#building).
2. Clone the repository and open it in Android Studio, or work from the command line.
3. Run the full check once to confirm your setup:

   ```sh
   ./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug
   ```

## Branches and commits

* Branch from `main`. Use a short prefix: `feature/`, `fix/`, `docs/`, `refactor/`, `test/`, for example
  `fix/resume-distance-anchor`.
* Keep one logical change per commit. Write the subject in the imperative mood, at most about 72 characters:
  `Add speed fix acceptance filter`, not `Added` or `Adds`. Add a body when the reason is not obvious.
* Do not rewrite shared history. Rebase your own branch on `main` before opening a pull request.

## Code style

* Kotlin official code style, 4-space indentation, trailing commas on multi-line lists, lines of at most 120
  characters. `.editorconfig` holds the rules and ktlint enforces them.
* Write KDoc on public API and on any non-obvious threshold or decision. Do not add comments that narrate what the
  next line does.
* Keep layers apart (see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)): `domain` and `tracking` have no Android
  imports (except `tracking/platform`), the UI and the service talk to `TrackingController` and never to
  `tracking` internals, and only `AndroidLocationSource` touches `LocationManager`.
* Compose conventions apply: composable functions are PascalCase, take a `modifier` parameter, and long parameter
  lists are fine. Experimental Material 3 Expressive APIs are opted into at the use site, only in `ui/theme` and
  `ui/components`, never module-wide.

## Running the checks

| Command | What it does |
|---|---|
| `./gradlew testDebugUnitTest` | JVM unit tests, including the architecture and hygiene tests |
| `./gradlew lintDebug` | Android Lint (warnings are errors) |
| `./gradlew spotlessCheck` | ktlint style check |
| `./gradlew spotlessApply` | Fix formatting problems automatically (long lines and wildcard imports need a manual fix) |
| `./gradlew staticAnalysis` | `spotlessCheck` and `lintDebug` together |
| `./gradlew assembleDebug` | Build the debug APK |

The continuous-integration workflow runs the same tasks: `./gradlew testDebugUnitTest lintDebug staticAnalysis
assembleDebug`. It never runs an emulator or instrumentation tests.

## Testing policy

* Every behaviour change comes with a JVM unit test. Bug fixes start with a test that fails without the fix.
* Tests are deterministic: inject `TimeSource` and `LocationSource` instead of reading the clock or the GPS, and use
  the helpers in `app/src/test/kotlin/app/wayneedle/testing/` (`aSample`, `scriptedRoute`, the in-memory fakes).
* Use `kotlin.test`, `kotlinx-coroutines-test` and Turbine, which are already dependencies.
* Device (instrumentation) tests live in `app/src/androidTest/`. Writing them is welcome, but the project's own
  validation runs them only on a Google Pixel 6 Pro, executed by the maintainers through the scripts in
  `scripts/`. If you run them on your own hardware, say which device in the pull request. Do not rely on
  emulators for GPS behaviour. See [docs/TESTING.md](docs/TESTING.md).

## Pull request checklist

Copy this into the description (the template does it for you):

- [ ] The change is Kotlin only and keeps the layering rules.
- [ ] No network access: no `INTERNET` permission, HTTP client or hard-coded URL; no analytics or tracking.
- [ ] No new dependency, or the new dependency is justified, an exact stable version, and from AndroidX/Kotlin.
- [ ] No coordinates are logged or stored.
- [ ] Tests added or updated, and `./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug` passes.
- [ ] User-visible strings are in `strings.xml`; UI works in light, dark and large-font settings.
- [ ] Documentation updated where behaviour, permissions or privacy claims changed (README, PRIVACY.md,
  docs/).
- [ ] If the change was tested on a device, the device and Android version are stated. If it was not, the
  description says so.

## Reporting bugs and ideas

Use the issue templates. Do not attach GPS tracks or screenshots that reveal where you live. Report security
problems privately as described in [SECURITY.md](SECURITY.md), not in a public issue.
