# Archiver – File Manager

A lightweight Android file manager project focused on practical file operations and dependable ZIP handling.

## First milestone

- Browse folders chosen by the user with Android's Storage Access Framework.
- Navigate folders and sort by name, size, date, or type.
- Show or hide dot-prefixed files; optionally show size and modification date.
- Create folders and empty files, open files in compatible installed apps, and inspect item information.
- Rename, recursively delete, copy, move, and paste files and folders.
- Create ZIP archives with store, fast, normal, or ultra compression.
- Extract ZIP files here or into a new folder, with checks against unsafe parent-directory paths.
- Remember the selected folder and settings.

## Known limitations in this first build

The initial implementation uses Java's built-in ZIP support to keep the code and dependency footprint small. It does not yet support RAR or 7z extraction, TAR-family formats, encrypted/password-protected archives, split volumes, PDF/video thumbnails, or access to Android/data. ZIP password encryption is not available in this baseline. Please test with disposable sample files before using it on important data; overwrite/error handling still needs a dedicated test pass.

## Build and release

GitHub Actions builds the debug APK using JDK 17, Gradle 8.13, and Android Gradle Plugin 8.13.2. Each push to main uploads an Actions artifact and publishes an automated GitHub prerelease for testing.

## Roadmap

1. Get the automated Android build green and validate the APK.
2. Test ZIP creation/extraction, copy/move, permissions, and error cases on a real Android device.
3. Improve collision and overwrite handling.
4. Add broader archive-format support only after measuring package size and device compatibility.
