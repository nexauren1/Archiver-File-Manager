# Archiver — Gestor de ficheiros Android

Archiver is a **native Android application written in Kotlin**. It is not a website, web page, or embedded web app. The interface uses Android views and the app runs directly on the device.

## Storage access

- On Android 11 and newer, Archiver declares `MANAGE_EXTERNAL_STORAGE` and sends the user to Android's special **Allow access to manage all files** settings page.
- On older Android versions, it requests the necessary shared-storage runtime permissions.
- Once access is granted, the initial location is the device's shared internal storage; the user is not limited to selecting one folder at every launch.
- Android still restricts certain private directories such as other apps' `Android/data` folders. All-files access does not override those operating-system restrictions.
- Distribution through Google Play is subject to Google's all-files-access policy for file managers.

## Implemented functionality in this preview

- Browse shared storage and navigate into/above folders.
- Multi-select files and folders.
- Create folders and empty files; rename, delete, copy, move, and paste.
- Open and share files through Android's content-URI provider.
- Sort by name, size, date, or type; hide/show dot-prefixed files.
- Detailed, compact, and grid views, with configurable image, PDF, video, album-art, and APK thumbnails.
- Recursive name search and persistent favorites.
- Create and extract ZIP archives; choose store/fast/normal/ultra compression.
- Create and extract 7z archives and test ZIP/7z contents.
- Browse ZIP/7z entries in-app and extract an individual file.
- Guard against common unsafe archive paths and avoid overwriting existing files in copy/extraction operations.
- Show progress feedback for long-running work.
- Custom Archiver launcher icon.

## Current limitations

- RAR, TAR, split-volume archives, and password-protected archive workflows are not yet implemented.
- Access to protected Android system/app directories remains subject to Android restrictions.
- This is a preview build. It has passed automated compilation, but its file operations and permission flow still need hands-on testing on multiple Android devices. Test with copies of non-critical files first.

## Build

- Android Gradle Plugin: 8.13.2
- Kotlin Gradle Plugin: 2.3.20
- JDK: 17
- Gradle: 8.13

GitHub Actions builds the debug APK and publishes an automated preview release from pushes to `main`.
