# AdbFlow Android App

This folder contains an on-device Android automation app (no computer/ADB required at runtime).

## What it does
- Parses and executes your command DSL: `LABEL`, `GOTO`, `CALL`, `RETURN`, `JUMP`, `SLEEP`, `CHECK_COLOR`, `EXIT`.
- Native action commands:
  - `TAP x y`
  - `SWIPE x1 y1 x2 y2 [durationMs]`
  - `BACK`, `HOME`, `LOCK`
- Legacy `adb shell input ...` lines are auto-converted for compatibility.
- Performs pixel color checks using `AccessibilityService.takeScreenshot` (Android 11+).

## Runtime model
- Runs fully **on-device** after install.
- No USB debugging or computer connection is needed to execute scripts.
- You must enable the app's Accessibility Service manually.
- Works best for gesture/back/home/lock automation.

## Build
1. Open `android-app` in Android Studio.
2. Let Gradle sync.
3. Build and install to device.

## Run
1. Open the app.
2. Tap **Enable Accessibility Service** and enable `AdbFlow`.
3. Paste/edit your script.
4. Tap **Run**.

## Compatibility
- `minSdk = 30` (Android 11), because `CHECK_COLOR` depends on screenshot APIs available from Android 11.
