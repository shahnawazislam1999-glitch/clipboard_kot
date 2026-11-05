# PinClip – floating smart clipboard (Kotlin, native Android)

Push this folder to GitHub (branch `main`). The **Build APK** workflow runs automatically;
download the APK from *Actions → latest run → Artifacts → PinClip-debug-apk*.

## First run on the phone
1. Install the APK, open **PinClip**.
2. Switch on **Show bubble** → allow *Display over other apps*, switch on again.
3. **Instant paste** card → open Accessibility settings → PinClip → On
   (greyed out? App info → ⋮ → *Allow restricted settings*).
4. **Auto detect** → allow photo access (for screenshots).

## Requirements
Android 8.0+ (API 26). No Gradle wrapper needed: the workflow installs Gradle 8.7 itself.

## GitHub permissions
The workflow needs write access to publish a Release with the APK. It already declares
`permissions: contents: write`. If a run still fails with "Resource not accessible by integration":
**Repo → Settings → Actions → General → Workflow permissions → Read and write permissions → Save.**

## Signing
`app/pinclip.keystore` is a fixed key, so every build is signed identically and updates install over the old
APK without uninstalling (this is what keeps Accessibility switched on). Uninstall the OLD build once before
installing the first build made with this key.

## Never lose Accessibility
Run once from a PC with USB debugging: `adb shell pm grant com.pinclip.app android.permission.WRITE_SECURE_SETTINGS`.
After that PinClip re-enables its own accessibility service whenever it gets switched off.

## Backup
Home screen → Library → **Backup (export)** writes one `.zip` (cards, groups, images). **Restore (import)** can
merge it into the current data or replace everything.
