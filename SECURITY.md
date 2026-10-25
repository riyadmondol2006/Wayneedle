# Security policy

## Supported versions

Wayneedle is pre-release software. Only the latest commit on `main` and the most recent tagged release receive
security fixes.

## Reporting a vulnerability

Please report suspected vulnerabilities **privately**, not in a public issue or pull request.

Use GitHub's private vulnerability reporting for this repository: open the **Security** tab and choose
**Report a vulnerability**. If that option is not available, open a public issue that says only that you have a
security report and asks for a private contact, without any details.

Include what you can of the following:

* A description of the problem and why you think it is a vulnerability.
* The version or commit, the Android version and the device.
* Steps to reproduce, or a proof of concept.
* Whether you plan to publish your findings, and when.

This is a volunteer project. Reports are acknowledged on a best-effort basis, usually within a week, and fixed and
disclosed in coordination with the reporter.

## What counts

In scope:

* Any way for the app to transmit data off the device, including a network permission appearing in the merged
  manifest.
* Exposure of location, trip or settings data to other apps or to backups.
* Components that are exported or reachable by other apps in ways they should not be (the foreground service,
  notification actions, intents).
* Flaws in the build or release configuration that could ship a tampered artifact.

Out of scope: vulnerabilities in Android itself or in other apps, attacks that need a rooted or already
compromised device, and GPS spoofing, which no app can detect reliably.

## Design measures

* The manifest has no `INTERNET` permission and the app has no networking or analytics dependencies. Automated tests
  fail the build if either changes.
* App data is private to the app, and cloud backup is disabled.
* Dependencies use exact, stable versions; the Gradle wrapper checksum is pinned and validated in CI.
* CI workflows run with read-only repository permissions, and third-party actions are pinned by commit SHA.
