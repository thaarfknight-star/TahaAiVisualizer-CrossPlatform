# TahaAi Visualizer — One-ZIP Android Builder

This repository is designed so you can upload the TahaAi Visualizer project ZIP and GitHub Actions will automatically:

1. detect the ZIP
2. extract it
3. locate the Capacitor project
4. install Node dependencies
5. create the Android platform
6. sync Capacitor
7. build the APK
8. publish the APK under GitHub Actions → Artifacts

## First setup

Create a GitHub repository and upload this repository's `.github` folder and `README.md`.

Then upload your application ZIP to the **root of the GitHub repository**.

For example:

```text
TahaAiVisualizer-Builder/
├── .github/
│   └── workflows/
│       └── android-zip-builder.yml
├── README.md
└── TahaAiVisualizer-CrossPlatform-Enhanced-GitHub.zip
```

As soon as the ZIP is pushed, the workflow starts automatically.

## Important

The uploaded ZIP must contain the Capacitor project with:

```text
package.json
capacitor.config.ts
www/
```

The TahaAi Visualizer ZIP supplied with this project already has that structure.

## Get the APK

Open:

**GitHub → Actions → Build Android from ZIP → latest run → Artifacts**

Download:

`TahaAi-Visualizer-Android`

Inside it is:

`app-debug.apk`

## Rebuilding

To build a new version, replace the ZIP in the repository with the new ZIP and commit/push it. The workflow will run again.

You can also start it manually from **Actions → Build Android from ZIP → Run workflow**.

## Note

This workflow creates a debug APK for personal installation. It does not publish to Google Play and does not create a signed release APK.
