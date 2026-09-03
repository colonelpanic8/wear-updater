# Ivan Wear Updater

A small, allowlisted updater for Ivan's personal Wear OS apps. It checks signed
release metadata for Mova, SmartPlus Unlock, Paseo, and itself. Before handing
an APK to Android's installer it verifies the package name, expected version,
release digest when available, and the app's pinned signing-certificate hash.

The app checks every 12 hours and posts a notification when an update is
available. Android still requires a tap on its native install confirmation.

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
