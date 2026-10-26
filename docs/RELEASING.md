# Releasing Wayneedle

Every push to `main` builds, verifies, signs and publishes a **stable GitHub Release** automatically
(`.github/workflows/release.yml`). This page explains the one-time signing setup and what each release contains.

## What a release run does

1. Fails at once if any of the four signing secrets below is missing.
2. Runs the JVM tests, Android Lint and the ktlint check. Nothing is released if one fails.
3. Builds the minified release APK with `versionCode = <workflow run number>` and
   `versionName = 1.0.<workflow run number>`, so every build is a higher version than the one before.
4. Signs it with your key, verifies the signature with `apksigner`, and writes a SHA-256 checksum.
5. Publishes a release tagged `v1.0.<run>` with `Wayneedle-1.0.<run>.apk`, the checksum file and
   release notes generated from the commits, marked as the latest stable release.

Installed apps find the new release with their daily update check (Settings > Updates) and offer it.

## One-time setup: the signing key

Android only installs an update if it is signed with **the same key** as the installed app. Keep the key
safe and backed up: if it is lost, users must uninstall and reinstall to get updates, and their trips are lost
with the old app.

### 1. Create the key (once, on your computer)

```sh
keytool -genkeypair -v \
  -keystore wayneedle-release.jks \
  -alias wayneedle \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` comes with any JDK. It asks for a keystore password, your name and organisation (shown in the
certificate) and a key password (pressing Enter reuses the keystore password). Never commit the `.jks` file;
`.gitignore` already excludes `*.jks` and `*.keystore`.

### 2. Encode it for GitHub

```sh
# Linux
base64 -w 0 wayneedle-release.jks > wayneedle-release.jks.base64
# macOS
base64 -i wayneedle-release.jks -o wayneedle-release.jks.base64
```

### 3. Add four repository secrets

On GitHub: **Settings > Secrets and variables > Actions > New repository secret**.

| Secret name | Value |
|---|---|
| `WAYNEEDLE_KEYSTORE_BASE64` | the whole content of `wayneedle-release.jks.base64` |
| `WAYNEEDLE_KEYSTORE_PASSWORD` | the keystore password |
| `WAYNEEDLE_KEY_ALIAS` | `wayneedle` (the `-alias` used above) |
| `WAYNEEDLE_KEY_PASSWORD` | the key password (the keystore password if you pressed Enter) |

With the GitHub CLI instead:

```sh
gh secret set WAYNEEDLE_KEYSTORE_BASE64 --repo riyadmondol2006/Wayneedle < wayneedle-release.jks.base64
gh secret set WAYNEEDLE_KEYSTORE_PASSWORD --repo riyadmondol2006/Wayneedle
gh secret set WAYNEEDLE_KEY_ALIAS --repo riyadmondol2006/Wayneedle --body wayneedle
gh secret set WAYNEEDLE_KEY_PASSWORD --repo riyadmondol2006/Wayneedle
```

Then delete `wayneedle-release.jks.base64` and keep `wayneedle-release.jks` and its passwords somewhere safe
(a password manager and an offline backup).

### 4. Allow the workflow to create releases

**Settings > Actions > General > Workflow permissions**: the workflow asks for `contents: write` itself, which
works with the default settings. If your account or organisation restricts workflow permissions, choose
"Read and write permissions" there.

## Building a signed release locally (optional)

The build reads the same variables as the workflow:

```sh
export WAYNEEDLE_KEYSTORE_FILE=/path/to/wayneedle-release.jks
export WAYNEEDLE_KEYSTORE_PASSWORD=...
export WAYNEEDLE_KEY_ALIAS=wayneedle
export WAYNEEDLE_KEY_PASSWORD=...
./gradlew assembleRelease
```

Without these variables a release build is unsigned, and the version stays `0.1.0` (code 1).

## Changing the version scheme

The workflow sets `WAYNEEDLE_VERSION_NAME: 1.0.${{ github.run_number }}`. To start a 1.1 or 2.0 series,
change the `1.0` prefix in `release.yml`. Keep `versionCode` on the run number: it must only ever grow.
