#!/usr/bin/env bash
# Verifies that exactly one attached Android device is a Google Pixel 6 Pro and prints its serial.
#
# Output (stdout, only on success): <serial> TAB <Android release> TAB <SDK level>, e.g. "1A2B3C4D<TAB>17<TAB>37".
# Everything else goes to stderr. The serials of devices that do not match are never printed.
#
# Exit status: 0 exactly one verified device; 1 no verified device; 2 more than one verified device;
#              3 adb is missing or does not respond.
#
# Safety rules:
#   * Only devices in the plain "device" state are considered. Offline, unauthorized and emulator-* entries are
#     skipped without issuing any command.
#   * For every other candidate the only commands run are reads of ro.product.manufacturer, ro.product.device and
#     ro.product.model (stopping at the first mismatch). Nothing else ever runs on a non-matching device.
#   * Android release and SDK level are read from the verified device only.
#
# A device is verified when ro.product.manufacturer is "Google", ro.product.device is "raven" and
# ro.product.model is "Pixel 6 Pro".
#
# Environment: ADB overrides the adb binary (default: adb on PATH).

set -euo pipefail

readonly ADB_BIN="${ADB:-adb}"
readonly EXPECTED_MANUFACTURER="Google"
readonly EXPECTED_DEVICE="raven"
readonly EXPECTED_MODEL="Pixel 6 Pro"
readonly ADB_TIMEOUT_SECONDS=10
readonly MIN_SDK_LEVEL=37

err() {
  printf '%s\n' "$*" >&2
}

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
  err "adb not found. Install Android platform-tools, add it to PATH, or set ADB=/path/to/adb."
  exit 3
fi

# adb_run <adb arguments...>: runs adb with a timeout when the platform has one. Stdin is detached because
# "adb shell" would otherwise swallow the device list that the loop below is reading.
adb_run() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "$ADB_TIMEOUT_SECONDS" "$ADB_BIN" "$@" </dev/null
  else
    "$ADB_BIN" "$@" </dev/null
  fi
}

# read_prop <serial> <property>: prints the property value without carriage returns or newlines.
read_prop() {
  adb_run -s "$1" shell getprop "$2" 2>/dev/null | tr -d '\r\n'
}

# is_pixel_6_pro <serial>: succeeds only if all three identity properties match. Stops at the first mismatch.
is_pixel_6_pro() {
  local serial=$1 value
  value=$(read_prop "$serial" ro.product.manufacturer) || return 1
  [ "$value" = "$EXPECTED_MANUFACTURER" ] || return 1
  value=$(read_prop "$serial" ro.product.device) || return 1
  [ "$value" = "$EXPECTED_DEVICE" ] || return 1
  value=$(read_prop "$serial" ro.product.model) || return 1
  [ "$value" = "$EXPECTED_MODEL" ] || return 1
  return 0
}

listing=$(adb_run devices -l 2>/dev/null) || {
  err "'adb devices -l' failed or timed out."
  exit 3
}

verified_serial=""
verified_count=0
ignored_count=0

while read -r serial state _; do
  # Skip the header, daemon messages and blank lines; accept only "<serial> <known state>" rows.
  case "$state" in
    device | offline | unauthorized | authorizing | recovery | sideload | bootloader | rescue | host | connecting) ;;
    *) continue ;;
  esac
  case "$serial" in
    '' | '*' | List) continue ;;
    *[!A-Za-z0-9._:-]*) continue ;;
  esac

  if [ "$state" != "device" ]; then
    ignored_count=$((ignored_count + 1))
    continue
  fi
  case "$serial" in
    emulator-*)
      ignored_count=$((ignored_count + 1))
      continue
      ;;
  esac

  if is_pixel_6_pro "$serial"; then
    verified_count=$((verified_count + 1))
    verified_serial=$serial
  else
    ignored_count=$((ignored_count + 1))
  fi
done <<EOF_DEVICES
$listing
EOF_DEVICES

if [ "$verified_count" -eq 0 ]; then
  err "No verified Google Pixel 6 Pro found ($ignored_count other attached device(s) ignored and left untouched)."
  err "Expected ro.product.manufacturer=$EXPECTED_MANUFACTURER, ro.product.device=$EXPECTED_DEVICE," \
    "ro.product.model=$EXPECTED_MODEL. Connect the phone, enable USB debugging and accept the prompt."
  exit 1
fi

if [ "$verified_count" -gt 1 ]; then
  err "$verified_count verified Pixel 6 Pro devices are attached; refusing to guess. Disconnect all but one."
  exit 2
fi

release=$(read_prop "$verified_serial" ro.build.version.release) || release=""
sdk=$(read_prop "$verified_serial" ro.build.version.sdk) || sdk=""
if [ -z "$release" ] || [ -z "$sdk" ]; then
  err "Could not read the Android version from the verified device."
  exit 3
fi

case "$sdk" in
  *[!0-9]*) ;;
  *)
    if [ "$sdk" -lt "$MIN_SDK_LEVEL" ]; then
      err "warning: the device runs Android $release (API $sdk); the project targets API $MIN_SDK_LEVEL."
    fi
    ;;
esac

printf '%s\t%s\t%s\n' "$verified_serial" "$release" "$sdk"
