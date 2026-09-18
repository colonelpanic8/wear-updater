# Ivan Wear Updater

A small, allowlisted updater for Ivan's personal Wear OS apps. It checks signed
release metadata for Mova, SmartPlus Unlock, Paseo, Tile Wear, Live Translate,
and itself. Before handing
an APK to Android's installer it verifies the package name, expected version,
release digest when available, and the app's pinned signing-certificate hash.

The app checks every 12 hours and posts a notification when an update is
available. The first update of an existing app may require Android's native
confirmation. Once Wear Updater is that app's installer of record, Android can
apply later updates without the second confirmation step; the receiver still
handles a confirmation screen when the OS requires one.

Each app shows its own live status — checking, downloading with a byte count and
progress bar, verifying, installing, and the final installer result — and the
screen scrolls with the rotary crown or bezel. **Update all** installs every
pending update in sequence, leaving Wear Updater itself for last because
installing it restarts the app.

Downloads retry with backoff and resume over HTTP `Range` when the watch drops a
connection mid-transfer, which is common on Bluetooth-proxied networks
(`software caused connection abort`).

## One-time watch setup

Build or download `wear-updater.apk`, connect to the watch once with wireless
ADB, and run:

```sh
adb install -r wear-updater.apk
adb shell appops set com.ivanmalison.wearupdater REQUEST_INSTALL_PACKAGES allow
```

Wireless debugging can be disabled afterward. Future app releases are fetched
directly by the watch over HTTPS.

## Local verification

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```
