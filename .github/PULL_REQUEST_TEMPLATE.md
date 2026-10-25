## What and why

<!-- What does this change, and why? Link the issue it closes, if any. -->

## How it was tested

<!-- Commands you ran. If you tested on a device, name the device and Android version; if not, say so. -->

## Checklist

- [ ] The change is Kotlin only and keeps the layering rules in `docs/ARCHITECTURE.md`.
- [ ] No network access: no `INTERNET` permission, HTTP client or hard-coded URL; no analytics or tracking.
- [ ] No new dependency, or the new dependency is justified, an exact stable version, and from AndroidX/Kotlin.
- [ ] No coordinates are logged or stored.
- [ ] Tests added or updated, and `./gradlew testDebugUnitTest lintDebug staticAnalysis assembleDebug` passes.
- [ ] User-visible strings are in `strings.xml`; the UI works in light, dark and large-font settings.
- [ ] Documentation updated where behaviour, permissions or privacy claims changed.
