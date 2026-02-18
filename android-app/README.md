# AdbFlow Android App

This folder contains a native Android replacement for your Python ADB runner.

## What it does
- Parses and executes your command DSL: `LABEL`, `GOTO`, `CALL`, `RETURN`, `JUMP`, `SLEEP`, `CHECK_COLOR`, `EXIT`.
- Supports your existing ADB-style lines for input actions:
  - `adb shell input tap x y`
  - `adb shell input swipe x1 y1 x2 y2 [duration]`
  - `adb shell input keyevent KEYCODE_BACK|KEYCODE_HOME|KEYCODE_SLEEP`
- Performs pixel color checks using `AccessibilityService.takeScreenshot` (Android 11+).

## Important differences vs Python+ADB
- This runs **on-device**, not from your computer.
- You must enable the app's Accessibility Service manually.
- `adb devices` is ignored (not needed on-device).
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
