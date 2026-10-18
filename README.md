# Archiver – File Manager

A lightweight Android file manager project focused on practical file operations and dependable ZIP handling.

## First milestone

- Browse folders chosen by the user with Android's Storage Access Framework.
- Navigate folders and sort by name, size, date, or type.
- Search recursively by filename, including wildcard patterns such as `*.zip`.
- Show or hide dot-prefixed files; optionally show size and modification date.
- Create folders and empty files, open and share files in compatible apps, and inspect item information.
- Rename, recursively delete, copy, move, and paste files and folders.
- Avoid copying folders into themselves and choose another name instead of overwriting on copy/extraction collisions.
- Create ZIP archives with store, fast, normal, or ultra compression.
- Optionally delete source items only after ZIP creation succeeds.
- Extract ZIP files here or into a new folder, with checks against unsafe parent-directory paths.
- Test ZIP contents by reading each entry to detect common read/CRC errors.
- Remember the selected folder and preferences.

## Known limitations in this first milestone

The initial implementation uses Java's built-in ZIP support to keep dependencies small. It does not yet support RAR or 7z extraction, TAR-family formats, encrypted/password-protected archives, split volumes, PDF/video thumbnails, or full Android/data access. ZIP password encryption is not available in this baseline. The first APK must still pass an automated build and on-device functional testing before it can be considered stable. Please test with disposable sample files before using it on important data.

## Build and release

GitHub Actions is configured to build the debug APK with JDK 17, Gradle 8.13, and Android Gradle Plugin 8.13.2. Pushes to `main` are configured to upload an Actions artifact and publish a GitHub prerelease for testing.

## Roadmap

1. Confirm the automated build and validate the APK.
2. Test ZIP creation/extraction, copy/move, permissions, large files, and error cases on an Android device.
3. Add automated tests and improve progress/cancellation feedback.
4. Add broader archive-format support only after measuring package size and compatibility.
