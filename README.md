# SITTA Prototype (Android)

This is a multi-module Android prototype for the SITAA contactless fingerprint workflow. It runs fully offline and stores session artifacts under internal storage.
All build and runtime inputs live under `app/`; reference repositories are not used at build time.

## Modules
- `app`: Compose navigation, app shell, dependency wiring
- `core/common`: Models, JSON contracts, config defaults
- `core/data`: Session storage, config loader, fake auth/tenants, settings
- `core/domain`: Interfaces and domain contracts
- `core/vision`: OpenCV + SourceAFIS adapters
- `feature/track_a`: Capture + quality gating
- `feature/track_b`: Enhancement pipeline UI
- `feature/track_c`: Matching UI
- `feature/track_d`: Liveness settings UI

## Build
Open `app/` in Android Studio, or run from the command line:

```bash
./gradlew assembleDebug
```

To run tests:

```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Runtime notes
- Camera permission is required for Track A.
- OpenCV is initialized lazily by `core/vision/OpenCvUtils` on first use.
- All session artifacts are stored under `Context.filesDir/sessions/<tenant>/<sessionId>/`.
- Thresholds are loaded from `core/common/src/main/assets/config.json` at startup.
- Finger detection uses MediaPipe Hand Landmarker with `app/src/main/assets/hand_landmarker.task`.

## Prerequisites
- JDK 17 (`JAVA_HOME` should point to `/usr/lib/jvm/java-17-openjdk-amd64` in this environment).
- Android SDK with `platforms;android-34` and `build-tools;34.0.0`.
- `local.properties` should contain `sdk.dir=/path/to/Android/Sdk`.

## Track usage
1. **Track A (Capture)**: Live preview with ROI + quality gating. Capture unlocks only after all metrics pass for 500ms.
2. **Track B (Enhance)**: Load the last capture, run grayscale → CLAHE → bilateral denoise → sharpen.
3. **Track C (Match)**: Select probe + candidates and run SourceAFIS matching.
4. **Track D (Liveness)**: Toggle liveness checking for Track A.

## Acceptance checker
Validate a sessions folder (device pull or local export) with:

```bash
./scripts/acceptance_check.sh /path/to/sessions
```
