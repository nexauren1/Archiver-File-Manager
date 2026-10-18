# Archiver 0.5.0 — Android File Manager

**Archiver** is a native Android file manager written in Kotlin, inspired by classic archive managers such as ZArchiver. The Android app runs locally; the website under `docs/` is a separate project site.

## What's new in 0.5.0

- Faster selection: selection changes the selected row in place instead of rebuilding the entire file list.
- Faster browser rendering: child counts for folders are fetched asynchronously and cached.
- Search is scoped to the current folder by default. Optional subfolder search, wildcard matching with `*` and `?`, file-type filters, cancelable scanning, progress status, and actionable results show each item's full parent path, metadata, and context actions.
- Completed file operations automatically dismiss the progress panel and clear the ongoing/completed operation notification after a brief result message.
- Eight UI languages: Portuguese, English, Spanish, French, Simplified Chinese, Hindi, Arabic, and Russian. The most common navigation, search, settings, and file-operation labels are translated; translations continue to be refined.
- Theme selector with visual previews for Dark, OLED black, and Light; accent colors display swatches and descriptions instead of unexplained names.
- Redesigned Archiver app icon.
- SHA-256 checksum for files, with selectable/copyable result.
- Folder storage analysis: recursive size/count summary and largest files, with cancellation and a shareable report.
- Optional, one-time request to share Archiver after successful use. Sharing is never forced.

## Core features

- Shared-storage explorer with Android's special **Allow access to manage all files** permission, clickable breadcrumbs, parent-folder navigation, favorites, sorting, hidden-file toggle, multi-selection, and detailed/compact/grid views.
- File actions: open/share, create, rename, copy, move, paste, delete, file information, and per-folder color choices.
- Type-aware icons for folders, text/code, documents, spreadsheets, archives (ZIP, 7Z, RAR, TAR), images, audio, video, APKs, PDFs and 3D-model files.
- Image thumbnails, optional PDF/video/music/APK previews, and folder thumbnails when a folder contains an image. Folder item counts load in the background.
- ZIP and 7Z create, browse, test and extract; ZIP supports ZipCrypto/AES-256 passwords and split volumes. Compression levels are configurable.
- Operation feedback includes a progress bar, percentage, current path/item, counts where available, and a final success/error state. Ongoing/final notifications are used when Android notification permission is allowed.
- Built-in UTF-8 text editor for common text/code files up to 4 MiB, with save confirmation for unsaved changes.
- GLB/GLTF metadata inspector for scene/node/mesh/material/texture/animation counts; interactive 3D rendering is delegated to a compatible external viewer.
- UI preferences: eight languages, dark/OLED-black/light themes, seven accent colors, custom folder colors, thumbnails and sorting preferences.
- Bilingual project website and privacy page under `docs/`.

## Supported formats and limits

The current archive engine focuses on ZIP and 7Z. ZIP creation supports encryption and split volumes; these features are not implemented for 7Z. The app can display icons for RAR/TAR and other formats, but that does **not** mean every such archive can be opened or extracted in this version. Root/Shizuku access, NAS/FTP/SFTP/SMB/WebDAV connections, a plugin system, and embedded interactive 3D rendering are not implemented in this release.

Android's all-files permission does not bypass operating-system restrictions on other apps' private storage. The user must explicitly grant access in Android settings.

## Website

GitHub Pages is configured to publish `docs/` from `main`. When Pages is enabled for the repository, the expected URL is:

https://nexauren1.github.io/Archiver-File-Manager/

The website is separate from the Android app; the native app does not use the website as its user interface.

## Build and validation

- JDK 17
- Gradle 8.13
- Android Gradle Plugin 8.13.2
- Kotlin
- `gradle --no-daemon assembleDebug`

GitHub Actions builds a debug APK and uploads it as the `Archiver-debug-APK` workflow artifact. A successful build verifies compilation and packaging, but it does not replace on-device testing of file permissions, compression, notifications, search, and theme contrast.

## Privacy and signing

File operations are performed locally by the app. Folder analysis and checksums read files only when the user invokes those features. The app does not need network access for ordinary local file management.

A release keystore must be generated and backed up securely by the project owner. Do not commit keystore files, passwords, key aliases, or private signing material to this repository. CI signing values should be stored as GitHub Actions secrets, and the keystore backup should be kept offline.

## Project status

Archiver is an independent project and is not affiliated with ZArchiver. Archiver 0.5.0 is a preview release; please test with disposable sample files before using destructive actions on important data.
