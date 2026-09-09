# TahaAi Visualizer — Android + iOS

Cross-platform Capacitor wrapper around the existing HTML/CSS/JS visualizer.

## GitHub
1. Create a new GitHub repository.
2. Upload everything in this folder.
3. Ensure `.github/workflows/android.yml` and `ios.yml` are present.
4. Open **Actions**.

### Android
`Build Android APK` runs on pushes to `main` and can also be started with **Run workflow**.
After it finishes: open the workflow run → **Artifacts** → download `TahaAi-Visualizer-Android-debug`.

### iOS
`Build iOS Simulator` is manually triggered. It creates an unsigned Simulator app for CI validation.
A real iPhone/TestFlight/App Store build needs Apple signing credentials, certificates and provisioning profiles.

## Local
```bash
npm install
npx cap add android
npx cap add ios
npx cap sync
```

## iOS: what you need

### Just build/test
The GitHub iOS workflow builds an iOS Simulator app on a macOS runner. This is useful for CI, but it is **not an installable iPhone IPA**.

### Install on a real iPhone / TestFlight
You need an Apple Developer account. A free Apple Account can be used for personal-device development through Xcode, but distribution through TestFlight/App Store requires Apple Developer Program membership. You will need to configure Apple signing credentials/secrets in GitHub Actions.

Recommended path:
1. Join Apple Developer Program.
2. Create an App ID for `com.tahdigi.visualizer`.
3. Create the app record in App Store Connect.
4. Configure signing/certificates for GitHub Actions.
5. Build an archive on a macOS GitHub runner.
6. Upload the signed build to App Store Connect.
7. Install through TestFlight.

The web app now also exposes Media Session controls when the platform/WebView supports them: Play/Pause, Previous, Next, Seek Forward and Seek Backward. This improves lock-screen/headset controls, but true guaranteed background playback requires native iOS/Android audio-session/service handling and is not promised by a plain WebView.

## Build fix
The GitHub Actions workflow does not require an existing `package-lock.json` for Node setup. It installs dependencies first, so uploading this project without a lockfile no longer causes the `Dependencies lock file is not found` failure.

## Android build workflow fix
The Android workflow now:
- does not require `package-lock.json`
- installs with `npm install --no-package-lock --legacy-peer-deps`
- finds the Capacitor project automatically
- installs Java 21
- only runs `cap add android` when the Android folder does not already exist
- builds with Gradle stack traces enabled

### Latest Android build fix
TypeScript is now included because `capacitor.config.ts` requires it when Capacitor initializes the project.

## Android Phone Audio
The Android build includes a native `SystemAudioCapture` Capacitor plugin. On Android 10+ it requests Android's MediaProjection consent and captures eligible device playback audio (`MEDIA`/`GAME`) for the visualizer. Some apps can block playback capture, and protected/DRM audio cannot be captured. This is device/app-policy dependent.

The mobile UI provides separate **Music Files** and **Phone Audio** sources.
