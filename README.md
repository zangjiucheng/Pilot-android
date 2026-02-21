# Pilot-android

This repository contains two automation implementations:

## `android-app/`
- Native Android app (`Kotlin`) that runs scripts directly on the phone.
- Uses Accessibility Service for gesture automation.
- Supports template scripts from `android-app/app/src/main/assets/default-scripts/`.
- Build APK:
  ```bash
  cd android-app
  ./gradlew assembleDebug
  ```

## `pyversion/`
- Python-based automation tools for desktop + ADB workflows.
- Main entry script: `pyversion/run_adb.py`.
- Typical use: run scripts from your computer while controlling a connected Android device.

## Quick Start
1. If you want fully on-device execution, use `android-app/`.
2. If you want computer-driven ADB automation, use `pyversion/`.

