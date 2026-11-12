#!/bin/bash
# Installs the release build on the TV and grants everything it needs over adb.
#
# Usage: ./deploy.sh <tv-ip>[:port] [apk] [--replace-signature]
#   apk defaults to app/build/outputs/apk/release/app-release.apk (an older backup APK works too, for a rollback)
#   --replace-signature: if the installed copy was signed by another machine, uninstall it first. That wipes the
#   launcher's saved favourites order, so it is never done without the flag.
set -u
if [ $# -lt 1 ]; then
  echo "Usage: $0 <tv-ip>[:port] [apk] [--replace-signature]"
  exit 1
fi
case "$1" in *:*) S="$1" ;; *) S="$1:5555" ;; esac
shift
APK="$(dirname "$0")/app/build/outputs/apk/release/app-release.apk"
REPLACE=0
for arg in "$@"; do
  case "$arg" in
    --replace-signature) REPLACE=1 ;;
    *) APK="$arg" ;;
  esac
done
PKG=me.prince.launcher
SERVICE=$PKG/$PKG.TvPowerService
a() { adb -s "$S" shell -n "$@"; }

adb connect "$S" >/dev/null
if ! out=$(adb -s "$S" install -r "$APK" 2>&1); then
  echo "$out"
  if [[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* && $REPLACE == 1 ]]; then
    # Uninstall and install back to back: the TV has no Home app for these few seconds
    adb -s "$S" uninstall "$PKG" && adb -s "$S" install "$APK" || exit 1
  else
    [[ "$out" == *INSTALL_FAILED_UPDATE_INCOMPATIBLE* ]] && echo "Signed by another machine; re-run with --replace-signature (resets favourites)"
    exit 1
  fi
fi

# Wi-Fi name + weather; Recent row and "what's on screen"; CEC log; screensaver toggle; opening the input from the background
a pm grant $PKG android.permission.ACCESS_COARSE_LOCATION
a pm grant $PKG android.permission.READ_LOGS
a pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
a appops set $PKG GET_USAGE_STATS allow
a appops set $PKG SYSTEM_ALERT_WINDOW allow
# Only needed with HANDS_FREE_OK_GOOGLE = true
a pm grant $PKG android.permission.RECORD_AUDIO

# Enable the power service without dropping any other accessibility service. Re-writing the setting also rebinds
# the service after a reinstall.
current=$(a settings get secure enabled_accessibility_services | tr -d '\r')
case "$current" in
  null|"") list=$SERVICE ;;
  *"$SERVICE"*) list=$current ;;
  *) list="$current:$SERVICE" ;;
esac
a settings put secure enabled_accessibility_services "$list"
a settings put secure accessibility_enabled 1

a cmd package compile -m speed -f $PKG >/dev/null
a cmd package set-home-activity $PKG/.MainActivity
echo "Installed $(a dumpsys package $PKG | grep -m1 versionName | tr -d ' \r'); power service: $(a dumpsys accessibility | grep -c "$SERVICE") binding(s)"
