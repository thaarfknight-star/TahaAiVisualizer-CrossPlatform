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
