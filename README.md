# Archiver — Android File Manager

Archiver is a native Android application written in **Kotlin**. It is not a website. The interface is built inside the Android app and uses Android file APIs, with a lightweight dark explorer layout inspired by common file-manager conventions.

## Current capabilities

- Browse shared internal storage after granting Android's special **Allow access to manage all files** permission.
- Navigate across shared-storage folders without having to choose each folder individually.
- Native vector icons for folders, archives, images, audio, video, APKs and PDF files.
- Detailed, compact and grid modes; file size, modified date/time and configurable thumbnails.
- Create folders and empty files, open/share, rename, delete, copy, move and paste items.
- Multi-select, recursive filename search, sorting, hidden-file visibility, persistent favorites and explorer settings.
- Create, browse, extract and test ZIP and 7Z archives.
- ZIP/7Z extraction blocks unsafe paths and avoids overwriting conflicting file names in supported operations.

## Storage permissions

On Android 11 and later, the application opens the system's special all-files-access settings screen. The user must enable it manually. This is needed for a file manager that operates across shared-storage folders. Android still restricts some app-private locations, including most of `Android/data`; this permission does not bypass those operating-system restrictions. Google Play also reviews broad storage access, so publication to Play Store requires an appropriate policy justification.

## Build and release

GitHub Actions builds a debug APK using JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2 and Kotlin. Pushes to `main` publish a GitHub prerelease.

## Testing status

A successful CI build confirms compilation, not that every operation has been exercised on a physical device. Test destructive operations with disposable sample files before using the app on important data. The UI and storage workflow are still being refined toward the intended feature set.
