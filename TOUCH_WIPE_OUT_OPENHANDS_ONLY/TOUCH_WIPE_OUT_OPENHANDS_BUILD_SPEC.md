# Touch Wipe Out — OpenHands Build Specification

## 1. Mission

Build a complete Android app named **Touch Wipe Out** from a blank project.

This document is the only product and implementation direction. Use the approved visual asset list supplied with this document. Do not use, inspect, copy, import, decompile, or reference any other app, source code, APK, architecture, artwork, layout, or historical implementation.

Return a working portrait-mode debug APK when all requirements below are implemented.

## 2. Visual source of truth

Use only the approved Touch Wipe Out asset sheet and approved assets supplied with this handoff.

The design canvas is fixed at **836 × 1882 pixels** in portrait orientation.

Every supplied full-canvas or transparent asset must be rendered at its native design coordinates. Preserve its native proportions. Scale the complete design canvas proportionally to the device; never independently stretch, crop, or reposition individual assets.

Do not create substitute rectangles, replacement buttons, guessed artwork, or programmatic visual approximations when an approved asset exists.

The asset sheet controls exact placement, size, spacing, color, and order.

## 3. Required screens

### Start screen

Use the approved start-screen artwork and assets exactly as supplied.

The start screen contains:

- Approved full background artwork
- Touch Wipe Out title artwork
- Approved Reset button
- Four approved center pentagon plate assets
- Approved complete bottom tray asset
- Status text area above the system-safe bottom region

The start screen must remain visually identical to the approved asset sheet. Code supplies touch zones and behavior only.

### Media mode

Media mode has four selectable categories:

- Photos
- Similar
- Videos
- Screenshots

Use the approved visual placement sheet for the exact center positions and bottom-tray positions. Labels and icons must remain inside their assigned visual zones. Keep the approved color order locked to slot positions.

### Storage mode

Storage mode has four selectable categories:

- Big Files
- Old Files
- Downloads
- Apps

Use the clean approved background and the approved Storage visual assets at their exact sheet coordinates. Do not allow any Media artwork to remain visible behind Storage controls.

When Media and Storage switch, the slot colors stay locked to the positions. Only the labels and icons change.

## 4. Interaction model

All controls must support direct tap interaction.

The bottom tray switches between Media and Storage mode.

The Reset button opens a confirmation dialog before resetting the app state.

The four center controls open their assigned review category.

Do not use swipe gestures on the dashboard for mode switching.

## 5. Review behavior

Each review category displays one item at a time in a full-screen review view.

Swipe decisions:

- Swipe right: Trash
- Swipe left: Keep
- Swipe up: Protect
- Swipe down: Later

Display a short visible confirmation for each decision.

Step Back is tap-only and reverses the most recent decision.

The review counter updates after every decision.

Protect persists until Global Reset. Keep is session-only. Later returns on a later scan. Trash remains in Review Bin until the user explicitly confirms deletion.

## 6. Category requirements

### Photos

Scan user photos only. Exclude system and active-app data.

### Similar

Find conservative groups of near-identical user photos. Never automatically delete any item.

### Videos

Scan user videos and show an appropriate video review state.

### Screenshots

Scan screenshots only, using reliable screenshot evidence from the media name or location.

### Big Files

Show user files at or above 100 MB. Exclude system and active-app data.

### Old Files

Show user files older than 12 months.

### Downloads

Show user files in Downloads, newest first.

### Apps

Show user-installed, launchable apps. Exclude the Touch Wipe Out app itself and system/OS apps.

## 7. Safety requirements

- Never delete automatically.
- Every deletion requires an explicit user action.
- Media deletion must use the appropriate Android confirmation flow.
- App removal must open Android’s uninstall confirmation.
- Cancelled deletion leaves the item in Review Bin.
- Cancelled uninstall leaves the app in Review Bin.
- Global Reset clears decisions and empties Review Bin, but cannot restore files already deleted or apps already uninstalled.

## 8. Permissions and privacy

Request permissions only when the user opens a category that needs them.

Explain permission purpose in plain language.

All scanning, decisions, and review state remain on-device. Do not add accounts, cloud uploads, analytics, or unnecessary network access.

## 9. Orientation and system UI

- Portrait mode only.
- Use immersive full-screen presentation.
- Do not allow Android navigation controls to cover the status area or app controls.
- Reserve a safe bottom region for status text and any required banner space.

## 10. Build requirements

- Android debug APK
- Portrait orientation
- Compact app size
- App name: Touch Wipe Out
- Use a newly written project and newly written application code.
- Do not copy or adapt code from another app.
- Build, install-check, and smoke-test the APK before delivery.

## 11. Required smoke test before delivery

Verify:

1. Start screen matches the approved asset sheet.
2. Reset opens confirmation and resets safely.
3. Media and Storage switch cleanly.
4. No artwork from the inactive mode remains visible.
5. All eight category controls respond.
6. Photos review opens when permission is granted.
7. All four swipes produce the correct decision.
8. Step Back reverses the latest decision.
9. Review Bin retains Trash items.
10. Deletion and uninstall require explicit Android confirmation.
11. APK opens in portrait mode without navigation overlap.

## 12. Delivery

Return:

- Completed debug APK
- Short list of implemented features
- Short list of anything genuinely untested or unavailable in the build environment

Do not return an APK described as complete if any required item above is missing.
