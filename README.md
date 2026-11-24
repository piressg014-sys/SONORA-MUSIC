# SONORA MUSIC 3.5 - Android APK project

This project wraps the SONORA 3.5 HTML player in an Android WebView.

## Build on GitHub (phone-friendly)
1. Create a new GitHub repository named `SONORA-MUSIC`.
2. Upload the **contents** of this folder to the repository root (keep `.github/workflows/build-apk.yml`).
3. Open the repository's **Actions** tab.
4. Select **Build SONORA MUSIC APK** and tap **Run workflow**.
5. When the run finishes successfully, open it and download the artifact **SONORA-MUSIC-APK**.
6. Extract the ZIP and install `app-debug.apk` on Android. If Android asks, allow installation from that browser/file manager.

The app requires internet for automatic lyrics lookup. Audio and artist photos are selected from the device through Android's file picker.
