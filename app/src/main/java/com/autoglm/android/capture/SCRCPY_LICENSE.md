# Screen Capture - scrcpy License Attribution

This project's screen capture functionality is inspired by and partially derived from the **scrcpy** project.

## scrcpy Project Information

- **Repository**: https://github.com/Genymobile/scrcpy
- **License**: Apache License, Version 2.0
- **Copyright**: 
  - Copyright (C) 2018 Genymobile
  - Copyright (C) 2018-2024 Romain Vimont

## What We Derived from scrcpy

The following concepts and techniques are derived from scrcpy:

1. **SurfaceControl Hidden API Access** (`SurfaceControlCompat.kt`)
   - Using reflection to call `android.view.SurfaceControl.screenshot()` methods
   - Display token acquisition via `SurfaceControl.getBuiltInDisplay()`/`getInternalDisplayToken()`
   - Handling different API signatures across Android versions (API 26-34)

2. **Hidden API Exemption Bypass** (`SurfaceControlCompat.kt`)
   - Using `dalvik.system.VMRuntime.setHiddenApiExemptions()` to bypass Android's hidden API restrictions
   - This technique is documented in scrcpy's `Workarounds.java`

3. **Display Information Retrieval** (`DisplayInfoCompat.kt`)
   - Using `DisplayManagerGlobal` hidden APIs to get display metrics
   - Similar approach to scrcpy's `DisplayManager` wrapper

## Modifications from scrcpy

Our implementation differs from scrcpy in several ways:

1. **Single-Frame Capture**: We only capture single screenshots, not video streams
2. **Kotlin Implementation**: Original scrcpy server is in Java; we use Kotlin
3. **Shizuku Integration**: We run in Shizuku's UserService instead of a standalone server
4. **Fallback Strategy**: We maintain a shell `screencap` fallback for maximum compatibility
5. **Image Encoding**: We encode to JPEG/PNG immediately rather than streaming raw frames

## Apache License 2.0 Notice

```
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Files Containing scrcpy-Derived Code

- `app/src/main/java/com/autoglm/android/capture/SurfaceControlCompat.kt`
- `app/src/main/java/com/autoglm/android/capture/DisplayInfoCompat.kt`
- `app/src/main/java/com/autoglm/android/shizuku/ScreenCaptureService.kt`

Each of these files contains a header comment with proper attribution to the scrcpy project.
