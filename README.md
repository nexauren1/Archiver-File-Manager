# Archiver 0.5.0 — Android File Manager

Archiver is a native Android file manager written in Kotlin. It is an independent project inspired by classic archive managers such as ZArchiver; it is not a website wrapped in an app.

## Highlights

- Shared-storage browsing with breadcrumb navigation, favorites, sorting, hidden files, multi-selection and detailed, compact or grid views.
- Fast in-place selection: selecting an item no longer redraws the entire visible file list.
- Scoped search starts in the current folder and never searches outside it. Searching subfolders is optional. Filters cover folders, documents, images, video, audio, archives and apps; wildcard patterns such as `*.zip` are supported.
- Search results show the file/folder name, parent path and size, with tap-to-open and contextual actions.
- File actions include create, open/share, rename, copy, move, delete, information, copy path, favorites and SHA-256 checksum calculation.
- ZIP and 7Z creation, browsing, testing and extraction. ZIP supports ZipCrypto/AES-256 password protection and split volumes; compression levels are configurable.
- Operation progress with percentage, current path/item and status. The completion dialog and operation notification are removed automatically when the operation ends.
- File-type icons, image thumbnails and optional PDF/video/music/APK previews.
- Built-in UTF-8 text editor for common files up to 4 MiB.
- GLB/GLTF metadata inspector; interactive 3D rendering is handed off to a compatible external viewer.
- Nine selectable interface languages: Portuguese, English, Spanish, French, German, Simplified Chinese, Hindi, Arabic and Japanese. Arabic uses a right-to-left layout. Translation coverage is being expanded screen by screen.
- Theme presets with a visual preview (Dark, OLED black and Light), clearer accent-color selection, custom folder colors and thumbnail preferences.
- Optional one-time invitation asking the user to share the app.

## Archive formats and current limits

The built-in archive engine focuses on ZIP and 7Z. ZIP supports encryption and split volumes; those features are not implemented for 7Z. RAR/TAR-family operations, root/Shizuku access, a plugin system, and embedded interactive 3D rendering are not implemented in this preview.

Android may restrict access to private app directories such as `Android/data`, even when broad shared-storage access is granted.

## Website

The website is separate from the app and is published from `docs/` through GitHub Pages when repository Pages settings enable the deployment:

https://nexauren1.github.io/Archiver-File-Manager/

## Build

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin
- `gradle --no-daemon assembleDebug`

GitHub Actions builds a debug APK and publishes it as a workflow artifact. A successful build validates compilation, not physical-device behavior; test destructive and archive operations with disposable sample files first.

## Signing

No keystore or signing secrets belong in the repository. For permanent release signing, keep the keystore outside version control and configure the signing passwords, key alias and keystore file through protected release secrets or a secure local environment.

## Storage and privacy

The user must grant Android's special all-files-access permission when requested. This does not grant root and does not bypass OS restrictions on other apps' private storage. Files are processed locally by the app.

Archiver is an independent project and is not affiliated with ZArchiver.
