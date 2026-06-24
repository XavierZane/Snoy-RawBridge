<div align="center">
  <p>
    <img
      src="./icon.svg"
      alt="Snoy-RawBridge icon"
      width="132"
      style="border-radius: 50%;"
    />
  </p>
  <h1>Snoy-RawBridge</h1>
  <p>
    <a href="./README_EN.md">English</a> |
    <a href="./README.md">简体中文</a>
  </p>
</div>

Snoy-RawBridge is an Android app for browsing Sony camera media over USB and importing selected RAW / JPEG originals on demand.

It is built around a wired workflow with thumbnail-first browsing, MTP as the primary path, PTP as a compatibility path, and no full-card mirroring to the phone.

## Overview

Snoy-RawBridge is designed for photographers who want a direct, selective import workflow from a Sony camera to Android.

Instead of copying everything up front, the app first opens a USB browse session, enumerates camera-side media, renders thumbnails or embedded previews, and only transfers original files after the user explicitly selects them.

This keeps the experience closer to a utility tool:
- connect the camera
- browse what is on the card
- select only what matters
- import the original files when needed

## Features

- Wired USB workflow for Android USB Host / OTG devices
- Sony camera oriented media browsing and import flow
- MTP-first design with PTP compatibility retained
- Thumbnail-first gallery rendering for RAW and JPEG media
- Multi-select, filtered selection, and batch import
- Import progress, stop action, and history records
- Configurable save root, date folders, and RAW / JPEG split storage
- Session thumbnail cache cleared when the session ends or disconnects
- Light, dark, and follow-system theme support

## How It Works

- The app detects the connected camera through Android USB Host APIs.
- It requests USB permission and probes the available browse mode.
- It enumerates media objects from the camera instead of copying the whole card.
- It shows JPEG thumbnails or RAW embedded previews first.
- It transfers original RAW / JPEG files only after the user selects them and starts import.
- Imported files are written through MediaStore so they are visible to system gallery and file apps.

## Quick Start

Requirements:
- Android 8.0+ (`minSdk 26`)
- Android device with USB Host / OTG support
- Sony camera connected with a data-capable USB cable

Build debug:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Build release:

```powershell
.\gradlew.bat :app:assembleRelease
.\gradlew.bat :app:bundleRelease
```

Install to device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\release\app-release.apk
```

## Documentation

- [使用文档.md](使用文档.md)

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3
- Android USB Host APIs
- MTP / PTP browse and import flow
- Room
- DataStore
- Coil

## Project Structure

```text
app/                Android UI app
transfer-backend/   USB session, import pipeline, storage, settings, history
gradle/             Gradle wrapper and version catalog
icon.svg            Source icon asset
```

## Compatibility Notes

- MTP is the primary and recommended path.
- PTP is retained as a compatibility path and may vary by camera model or USB mode.
- Large media cards still depend on camera-side enumeration speed, even with incremental thumbnail publishing.

## Author

- GitHub: [XavierZane / Snoy-RawBridge](https://github.com/XavierZane/Snoy-RawBridge)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
