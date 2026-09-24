# Touch Clean — Cursor Handoff

## What this package contains

- A complete editable Android Studio/Gradle project from Touch Clean 0.13.1.
- The tested Touch Clean 0.14.2 APK in `reference-apk/`. Treat this APK as the behavioral and visual reference; do not edit it.
- `recovered-from-0.14.2-apk/review_bin_background.png`, an exact PNG recovered losslessly from the newer APK because it was not present in the 0.13.1 source tree.
- The original design assets and Gradle wrapper.

## Important source-version limitation

The editable source is version 0.13.1 (`versionCode 19`). The supplied APK is version 0.14.2 and contains later compiled changes. An APK is not the original source project, so do not replace the clean Java source with bulk decompiled code. Use the APK on an Android device as the reference for any behavior added after 0.13.1, then implement required changes cleanly in this project.

## Project facts

- Package/application ID: `com.touchclean.pinkpoc`
- Main source: `app/src/main/java/com/touchclean/pinkpoc/MainActivity.java`
- Minimum Android SDK: 23
- Target/compile SDK: 35
- Android Gradle Plugin: 8.6.1
- Gradle wrapper: 8.7
- Build command: `./gradlew assembleDebug`
- Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`

The project is currently compact: most behavior is in one `MainActivity.java` file. Avoid a large architectural rewrite until the existing behavior is verified.

## First prompt to paste into Cursor

```text
Open and inspect this Touch Clean Android project, especially README.md,
app/build.gradle, AndroidManifest.xml, and MainActivity.java. Do not make any
changes yet. The editable source is 0.13.1, while
reference-apk/Touch-Clean-0.14.2.apk is the newer working behavioral and visual
reference. First: (1) confirm the project structure, (2) build the current
source, (3) report any build errors without rewriting the project, and (4) give
me a short plain-English summary of what currently works. I do not code, so
explain exactly what I need to click or do. Work in small checkpoints and ask
before starting the next checkpoint.
```

## Safe four-pass workflow

### Pass 1 — Baseline (0–25%)

Build and run the untouched source. Install the supplied 0.14.2 reference APK separately if needed. Record the differences that matter. Do not refactor.

### Pass 2 — Highest-priority fixes (25–50%)

Make one grouped set of related fixes. Build a debug APK and let Matthew test it before continuing.

### Pass 3 — Remaining categories and polish (50–75%)

Complete the agreed punch-list items while preserving established swipe directions, button placement, state rules, and Photoshop-first visuals.

### Pass 4 — Release readiness (75–100%)

Regression-test every category, permissions, deletion/uninstall confirmations, rotation rules, and state persistence. Update version information and create a final source backup plus APK.

At the end of every pass, Cursor should provide:

1. A debug APK for testing.
2. A short list of exactly what changed.
3. Any known issue or untested behavior.
4. A source checkpoint/commit before beginning the next pass.

## Non-negotiable product behavior

- Photoshop-created screens and transparent controls are the visual source of truth.
- Swipe directions: right = Trash, left = Keep, up = Lock/Protect, down = Later.
- Step Back is tap-only.
- Keep items may reappear after restarting the app.
- Lock/Protect persists until Global Reset.
- Later items return on the next scan.
- Trash deletes only after appropriate Android confirmation where required.
- App removal must use Android's uninstall confirmation.
- Downloads should be newest first.
- Big Files uses the planned 100 MB threshold.
- Avoid system apps in the Apps review category.
- Do not casually rename the package ID; changing it can affect upgrades and Play Store identity.

## Build note

The source files and wrapper are present. A build attempt in the packaging environment reached the Gradle 8.7 download step but could not access the Gradle server because that environment had no outbound network route. This was an environment limitation, not a reported Java/Gradle compilation failure. Cursor should perform the baseline build on a normal internet-connected computer.
