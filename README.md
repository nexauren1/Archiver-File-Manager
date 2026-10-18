# Archiver 0.4.0 — Android File Manager

**Archiver** is a native Android file manager written in Kotlin, inspired by classic archive managers such as ZArchiver. It is not a website wrapped in an app. Website and app are separate: the Android app runs on-device, while the website is published from `docs/`.

## Features

- Shared-storage explorer with Android's special **Allow access to manage all files** permission, breadcrumb navigation, parent-folder entry, search, favorites, sorting, hidden-file toggle, multi-selection, and detailed/compact/grid views.
- File actions: open/share, create, rename, copy, move, paste, delete, information, and per-folder color choices.
- Type-aware icons for folders, text/code, documents, spreadsheets, archives, images, audio, video, APKs, PDFs and 3D-model files.
- Image thumbnails, optional PDF/video/music/APK previews, and folder thumbnails when a folder contains an image.
- ZIP and 7Z create, browse, test and extract; ZIP supports ZipCrypto/AES-256 passwords and split volumes. Compression levels are configurable.
- Operation feedback: percentage, horizontal progress bar, current path/item, counts where available, in-app completion/error state and an ongoing/final notification when Android notification permission is allowed.
- Built-in UTF-8 text editor for common text/code files up to 4 MiB, with save confirmation for unsaved changes.
- GLB/GLTF metadata inspector for scene/node/mesh/material/texture/animation counts; interactive 3D rendering is delegated to a compatible external viewer.
- UI preferences: Portuguese/English, dark/OLED-black/light themes, seven accent colors, custom folder colors, thumbnails and sorting preferences.
- Two-language public project site under `docs/`, with a privacy page.

## Supported archive formats and limits

The current archive engine focuses on ZIP and 7Z. ZIP creation supports encryption and split volumes; these options are not implemented for 7Z. RAR, TAR-family formats, root/Shizuku access, a plugin system, and embedded interactive 3D rendering are not implemented in this release. Android can still restrict private app directories such as `Android/data`, even when all-files access is granted.

## Website

The GitHub Pages deployment workflow publishes `docs/` from `main`. If Pages is enabled for this repository, the site is available at:

https://nexauren1.github.io/Archiver-File-Manager/

The site is independent from the APK; it is not used as the app UI.

## Build

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin
- `gradle --no-daemon assembleDebug`

GitHub Actions builds a debug APK and uploads it as the `Archiver-debug-APK` workflow artifact. A successful build validates compilation only; it does not replace physical-device testing.

## Storage permission and privacy

Android 11+ opens Android's special all-files-access settings page. The user must grant it explicitly. The permission does **not** grant root and does not bypass operating-system restrictions on other apps' private storage. Google Play also reviews broad storage access and requires an appropriate policy justification for distribution.

Files are processed locally by the app. Before destructive actions or encryption/extraction, test with disposable sample files and verify that the selected paths and password are correct.

## License and project status

Archiver is an independent project and is not affiliated with ZArchiver. This is an early preview release; feature coverage and device behavior should be tested before important data is used.
