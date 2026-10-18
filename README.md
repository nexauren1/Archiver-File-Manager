# Archiver – File Manager

A lightweight Android file manager project focused on practical file operations and dependable archive handling.

## First milestone

- Browse folders chosen by the user with Android's Storage Access Framework.
- Navigate folders and sort by name, size, date, or type.
- Search recursively by filename, including wildcard patterns such as `*.zip`.
- Show or hide dot-prefixed files; optionally show size and modification date.
- Choose detailed, compact, or grid views.
- Optional on-device thumbnails for images, PDF pages, video frames, music album art, and APK icons.
- Create folders and empty files, open/share files, and inspect item information.
- Rename, recursively delete, copy, move, and paste files/folders, with safeguards against recursive copies and name collisions.
- Create ZIP archives with store, fast, normal, or ultra compression.
- Create and extract 7z archives; test ZIP and 7z contents for common read/CRC errors.
- Optionally delete source items only after archive creation succeeds.
- Extract supported archives here or into a new folder while blocking unsafe parent-directory paths.
- Remember the selected folder and preferences.

## Known limitations

- This preview focuses on ZIP and 7z. RAR, TAR and other formats are not yet implemented.
- Password-protected archive creation and password prompts are not yet wired into the UI.
- Split volumes and Android/data or Android/obb access are not implemented.
- PDF/video previews are disabled by default because they can use additional CPU and memory.
- Large archive operations currently use app-cache temporary files; ensure the device has sufficient free storage.
- The app has been compiled and released via GitHub Actions, but still requires hands-on testing on real Android devices. Test with disposable sample files before using it on important data.

## Build and release

GitHub Actions builds the debug APK with JDK 17, Gradle 8.13 and Android Gradle Plugin 8.13.2. Pushes to `main` upload an Actions artifact and publish a GitHub prerelease.

## Roadmap

1. Validate ZIP and 7z creation/extraction on device, including large files and low storage.
2. Add archive listing/preview and improve progress, cancellation, and overwrite handling.
3. Evaluate RAR extraction and TAR/GZIP support, including licensing and APK-size cost.
4. Add automated tests and only then consider stable release status.
