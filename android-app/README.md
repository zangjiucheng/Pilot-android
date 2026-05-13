# FlowPilot Android App

This folder contains an on-device Android automation app (no computer/ADB required at runtime).

## What it does
- Parses and executes your command DSL: `LABEL`, `GOTO`, `CALL`, `RETURN`, `JUMP`, `SLEEP`, `CHECK_COLOR`, `CHECK_COLOR_LINE`, `CHECK_OCR`, `EXIT`.
  - Control flow semantics:
    - `CALL <label>` enters a subroutine and pushes return address.
    - `RETURN` pops the call stack and returns to the caller.
    - `GOTO <label>` and `JUMP <label>` are non-stack jumps for loops/branching.
  - `CHECK_COLOR_LINE` supports:
    - `CHECK_COLOR_LINE <x> <y1> <y2> <color> [tolerance] [step] [minRun] ...` (fixed x, scan y)
    - `CHECK_COLOR_LINE Y <y> <x1> <x2> <color> [tolerance] [step] [minRun] ...` (fixed y, scan x)
    - `minRun` is the minimum continuous matched distance (in pixels) required to treat as a hit.
    - On hit, sets script variables `${LAST_X}` and `${LAST_Y}` for later commands.
    - Variables support numeric offsets, e.g. `${LAST_Y-120}`, `${LAST_Y+180}`.
    - Variables also support defaults, e.g. `${LAST_Y:-2000}`.
- Native action commands:
  - `TAP x y`
  - `SWIPE x1 y1 x2 y2 [durationMs]`
  - `SWIPEPATH x1 y1 x2 y2 [x3 y3 ...] [durationMs]` (single continuous hold gesture, auto-duration by path length when omitted)
  - `ZOOM_IN centerX centerY distancePx [durationMs]` (two-finger spread)
  - `ZOOM_OUT centerX centerY distancePx [durationMs]` (two-finger pinch)
  - `BACK`, `HOME`, `LOCK`
- Built-in operation recorder:
  - Open **Record** page and tap **Start Record**.
  - Recorder uses the same delay seconds configured in Automation page.
  - In precise mode, a touch-capture overlay records raw touch paths and forwards gestures back via accessibility gestures.
  - During forwarding, recorder briefly releases the overlay and restores it after forwarding completes.
  - Volume Up/Down stops recording immediately when recorder is active.
  - When recording is stopped by volume key, recorded commands are queued and auto-inserted into the script editor.
  - Each touch session (finger down -> finger up) generates one command (`TAP`, `SWIPE`, or `SWIPEPATH`).
  - Do manual taps/back/home/scroll operations on device.
  - Scroll path points are sampled at about 3 FPS and exported as `SWIPEPATH`.
  - If only one scroll sample is captured, recorder falls back to a single directional `SWIPE`.
  - Recorder inserts `SLEEP <seconds>` between operations to preserve timing.
  - Return to app and tap **Stop & Insert** to append recorded commands into script editor.
- Legacy `adb shell input ...` lines are auto-converted for compatibility.
- `CHECK_COLOR` and `CHECK_OCR` are supported only on rooted devices.
- Bundled example scripts are stored in app assets: `app/src/main/assets/default-scripts/`.
- File page operations use the Storage app picker (`Load Script` / `Save Script`).

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
- `minSdk = 30` (Android 11).
