# Privacy

Wayneedle is built so that your location and your trips never leave your phone. This page says what the app uses, what it
stores, and how you can delete it. Every statement below describes how the app is designed and built; where a
statement is checked automatically, that is noted.

**Summary**

* Your location is used on the device, only to show your speed and to record trips you start.
* The only network request is an optional daily update check that asks GitHub for this project's latest release.
  It sends no location, trip data or identifier, and you can switch it off.
* There are no accounts, no analytics, no crash reporting, no advertising and no third-party SDKs.
* Trip summaries are stored on the device and are excluded from cloud backup.
* You can delete your data at any time (see [Deleting your data](#deleting-your-data)).

## What the app does with your location

* **Live speed.** While the app is on screen, Wayneedle reads GPS fixes from Android's `LocationManager` (the GPS
  provider, not Google Play services) to show your current speed. When you leave the app and no trip is running, the
  GPS listener is released.
* **Trips.** When you press Start, the app keeps reading fixes, including with the screen off, and accumulates
  distance, active duration and top speed. Pause stops the accumulation. Finish saves the trip.
* **In memory only.** Individual fixes (latitude, longitude, speed) are processed in memory to compute those
  numbers. The app does not write coordinates to its database, to files or to the system log, and it does not
  record the route you travelled. If that ever changes, this page will change first.

## Permissions

| Permission | Why |
|---|---|
| Precise location (`ACCESS_FINE_LOCATION`) | Required to use the GPS provider and measure speed. |
| Approximate location (`ACCESS_COARSE_LOCATION`) | Android asks for it together with precise location so you can choose. Approximate location alone is not enough to measure speed, and the app tells you so. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION` | Lets a running trip keep recording while the screen is off or another app is in front. |
| Notifications (`POST_NOTIFICATIONS`, Android 13 and newer) | Shows the ongoing notification described below. |
| Vibration (`VIBRATE`) | The optional speed alert vibrates briefly when you go above your limit. Granted at install; it gives no access to any data. |
| Internet (`INTERNET`) | Only for the update check described below. Granted at install. |

The app does **not** request contacts, storage, camera, microphone or background location.

## The foreground-service notification

Android requires an app that uses location while it is not on screen to run a foreground service and to show an
ongoing notification. Wayneedle starts that service only when you start a trip, and stops it when you finish or
discard the trip. The notification shows that a trip is recording, its current figures, and Pause/Resume actions.
If you deny the notification permission,
Android may hide the notification; the trip still records, and you can see the running service in Android's
Task Manager.

If the app is killed while a trip is running, it restores the trip as paused the next time you open it. Nothing is
recorded while the app is not running, and that gap is not added to the trip.

## What is stored on the device

| Data | Where | Contents |
|---|---|---|
| Finished trips | Room (SQLite) database in the app's private storage | Trip id, start and end time, distance, active duration, top speed. No coordinates. |
| In-progress trip | Same database, a single checkpoint | The same summary figures plus the phase (running or paused). Written about every few seconds of active time and on every phase change, so a trip can be recovered after a crash. Deleted when the trip is finished or discarded. |
| Settings | DataStore (Preferences) in the app's private storage | Speed unit, theme, dynamic colour, keep-screen-on, speed alert on/off, limit and sound, update check on/off. |
| Update check | SharedPreferences in the app's private storage | When the last check succeeded and a version you chose to skip. |
| Permission prompts | SharedPreferences in the app's private storage | Whether the location and notification dialogs were shown before, so the app knows when Android has stopped asking. |

All of this lives in the app's private storage, which other apps cannot read on an unmodified device.

## Network, backup and third parties

* **One network request: the update check.** Release builds ask GitHub at most once a day, when you open the app,
  for the latest release of this repository (`https://api.github.com/repos/riyadmondol2006/Wayneedle/releases/latest`),
  and offer a newer version in a popup. The request carries only the app name and version in its `User-Agent`
  header. It contains no location, trip data, settings or device identifier; like any web request, GitHub sees your
  IP address ([GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement)).
  "Download" opens the release page in your browser; the app never downloads or installs anything itself. Switch the
  check off in Settings › Updates. Debug builds never check.
* **Nothing else uses the network.** Automated tests fail the build if networking code appears outside the update
  check (`app.wayneedle.update`), if a URL other than this repository's GitHub addresses appears in the app's code,
  or if the `INTERNET` permission appears in any manifest other than the app's own (one added by a dependency, for
  example).
* **No third-party SDKs.** Dependencies are Kotlin, AndroidX and Jetpack Compose libraries. An automated test fails
  the build if analytics, crash-reporting, advertising or networking libraries are added to the build files.
* **No cloud backup.** The manifest sets `allowBackup="false"` and the data-extraction rules exclude all app data
  from cloud backup; an automated test checks `allowBackup`. Android's device-to-device transfer (setting up a new
  phone from an old one) is governed by the system's extraction rules, which currently exclude cloud backup only.
* **Export only when you ask.** "Export trips as CSV" in History writes your trip summaries (times, distance,
  durations, speeds; no coordinates) to a file in a place you pick with Android's file picker. Nothing is sent
  anywhere by the app; what happens to the file afterwards is up to you. There is no other export or share feature.

## Deleting your data

* **One trip or all trips:** delete trips from the History screen.
* **Everything, including settings:** Android Settings, Apps, Wayneedle, Storage & cache, Clear storage.
* **Uninstalling** the app removes all of its data.
* A trip that is still recording is deleted by choosing Discard.

## Changes to this page

Changes are made in this repository and are visible in its history. A change that affects what is collected or
stored is made in the same pull request as the code change.

## Questions

Open an issue in the repository. For a security or privacy vulnerability, follow [SECURITY.md](SECURITY.md) instead
of opening a public issue.
