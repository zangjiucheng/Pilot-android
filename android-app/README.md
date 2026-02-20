# FlowPilot Android App

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

## Build (Command Line, no Android Studio)
From the repo root:

```bash
cd android-app
./gradlew assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install to a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If build fails with `SDK location not found`, configure Android SDK first:

1. Install Android SDK command-line tools and platform packages.
2. Set one of:
   - `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or
   - `android-app/local.properties` with:

```properties
sdk.dir=/path/to/Android/sdk
```

## Run
1. Open the app.
2. Tap **Enable Accessibility Service** and enable `FlowPilot`.
3. Paste/edit your script.
4. Tap **Run**.

Useful command-line checks:

```bash
adb devices
adb shell pm list packages | rg flowpilot
adb shell am start -n com.example.adbflow/.ui.MainActivity
```

## Compatibility
- `minSdk = 30` (Android 11), because `CHECK_COLOR` depends on screenshot APIs available from Android 11.
