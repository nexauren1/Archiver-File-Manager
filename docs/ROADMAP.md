# Archiver — Product Roadmap

This roadmap separates work that is already present in **0.10.0** from candidates that need design, implementation and device testing. These are proposals, not promises of delivery dates.

## Delivered in 0.10.0

- **Storage Analyzer:** finds the largest accessible files using a bounded scan; it does not delete files automatically.
- **Batch rename:** applies sequential names to selected files and folders, preserves file extensions, checks destination conflicts, and attempts to roll back a partial failure.
- **Version clarity:** the About screen reports the installed package version.

## Recommended next priorities

1. **Safer file operations:** introduce automated tests around archive path traversal, rename collisions, failed copy/move operations and partial extraction; add a cancellable operation queue with clearer recovery information.
2. **Recoverable deletion:** a user-controlled Trash with restore and permanent-delete actions. This needs an explicit storage model and must clearly explain cases where Android permissions or filesystem behavior prevent recovery.
3. **Duplicate-file finder:** group candidates by size, then compare SHA-256 only within matching groups. Show matches for review; never delete automatically.
4. **Productivity workspace:** navigation tabs or a dual-pane mode for transfers between folders.
5. **More storage providers:** investigate Android Storage Access Framework support for removable volumes and USB drives before adding network providers such as SMB, SFTP or WebDAV. Provider support should appear only when the required permissions and dependencies are available.

## Research references

- [Amaze File Manager — feature overview and source links](https://f-droid.org/en/packages/com.amaze.filemanager/)
- [Voyager — Material Design file manager with SAF and network protocols](https://cloudflare.f-droid.org/en/packages/com.voyagerfiles/)
- [Android storage access framework](https://developer.android.com/guide/topics/providers/document-provider)
- [Android app-specific and shared storage](https://developer.android.com/training/data-storage)

## Release checklist

- Build with the production release configuration and the existing long-term signing identity.
- Verify the APK signature and ZIP alignment, and preserve the SHA-256 checksum next to the APK.
- Install the release over the previous signed version on a real device; test upgrade compatibility and core archive operations.
- Test both Portuguese and English UI, with light/dark themes and a large directory.
- Update the store listing and privacy-policy metadata to match the exact APK that is published.
