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
