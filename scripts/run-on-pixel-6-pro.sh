#!/usr/bin/env bash
# Runs a device task against the verified Google Pixel 6 Pro and nowhere else.
#
# Usage:
#   scripts/run-on-pixel-6-pro.sh connectedDebugAndroidTest [gradle arguments...]
#   scripts/run-on-pixel-6-pro.sh installDebug
#   scripts/run-on-pixel-6-pro.sh logcat -d
#   scripts/run-on-pixel-6-pro.sh shell dumpsys activity services app.wayneedle.debug
#
# The first argument selects the mode:
#   * a Gradle device task: connected*, install*, uninstall* (optionally prefixed with ":app:"). It runs the
#     Gradle wrapper from the repository root with ANDROID_SERIAL set to the verified device, which makes the
#     Android Gradle Plugin use that device only. Remaining arguments go to Gradle unchanged.
#   * an adb subcommand: install, install-multiple, uninstall, logcat, shell, push, pull, bugreport. It runs
#     "adb -s <verified serial> <arguments>".
# Anything else is refused. The verifier (verify-pixel-6-pro.sh) runs first; if it does not find exactly one
# Pixel 6 Pro, nothing runs.
#
# Environment: ADB overrides the adb binary; WAYNEEDLE_GRADLE overrides the Gradle command
# (default ./gradlew, e.g. "./gradlew --no-daemon").

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "$script_dir/.." && pwd)
readonly script_dir repo_root
readonly ADB_BIN="${ADB:-adb}"

err() {
  printf '%s\n' "$*" >&2
}

usage() {
  err "usage: $0 <gradle device task | adb subcommand> [arguments...]"
  err "  gradle device tasks: connected*, install*, uninstall* (for example connectedDebugAndroidTest)"
  err "  adb subcommands:     install, install-multiple, uninstall, logcat, shell, push, pull, bugreport"
}

if [ "$#" -lt 1 ]; then
  usage
  exit 64
fi

mode=""
gradle_task_pattern='^(:app:)?(connected|install|uninstall)[A-Z][A-Za-z]*$'
case "$1" in
  install | install-multiple | uninstall | logcat | shell | push | pull | bugreport) mode="adb" ;;
  *)
    if [[ "$1" =~ $gradle_task_pattern ]]; then
      mode="gradle"
    fi
    ;;
esac

if [ -z "$mode" ]; then
  err "Refusing to run '$1': not a supported device task or adb subcommand."
  usage
  exit 64
fi

status=0
verified=$("$script_dir/verify-pixel-6-pro.sh") || status=$?
if [ "$status" -ne 0 ]; then
  err "Refusing to run: there is no single verified Google Pixel 6 Pro (verifier exit code $status)."
  exit "$status"
fi

if [ "$(printf '%s\n' "$verified" | wc -l | tr -d ' ')" -ne 1 ]; then
  err "Refusing to run: unexpected verifier output."
  exit 70
fi

serial=""
release=""
sdk=""
IFS=$'\t' read -r serial release sdk <<<"$verified"
if [ -z "$serial" ]; then
  err "Refusing to run: the verifier returned no serial."
  exit 70
fi

err "Verified Google Pixel 6 Pro (Android $release, API $sdk). Running '$1' on that device only."

if [ "$mode" = "adb" ]; then
  exec "$ADB_BIN" -s "$serial" "$@"
fi

gradle_cmd=()
read -r -a gradle_cmd <<<"${WAYNEEDLE_GRADLE:-./gradlew}"
cd "$repo_root"
# ANDROID_SERIAL is set explicitly, overriding anything inherited, so Gradle's connected tasks use this device only.
export ANDROID_SERIAL="$serial"
exec "${gradle_cmd[@]}" "$@"
