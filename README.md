# Archiver 0.9.3 — Android File Manager

Archiver is a native Android file manager written in Kotlin. It is an independent project inspired by classic archive managers such as ZArchiver; it is not a website wrapped in an app.

## Archiver 0.9.3

- Reduce directory-change detection overhead by calculating the folder signature without sorting all entries.
- Accelerate Shizuku/Sui advanced browsing by caching file metadata and selecting the first 2,000 displayed entries without sorting the entire directory.
- Improve advanced copy and extraction throughput with 128 KiB transfer buffers and without forcing a disk sync after every copied or extracted file.
- Keep explicit Shizuku authorization, Android/data and Android/obb access attempts, and Root-mode safeguards.
- Version code: 14.

## Archiver 0.9.2

- Fix release builds by including XZ for Java explicitly, allowing R8 to resolve the optional classes used by Apache Commons Compress.
- Version code: 13.

## Highlights

- Shared-storage browsing with breadcrumb navigation, favorites, sorting, hidden files, multi-selection and detailed, compact or grid views.
- Fast in-place selection: selecting an item does not redraw the entire visible file list.
- Scoped, bounded search starts in the current folder and never searches outside it. Searching subfolders is optional. Results include the file name, parent path and size; wildcard patterns such as `*.zip` are supported. Recursive scans stop after 10,000 visited entries or 100 matches to keep the interface responsive.
- File actions include create, open/share, rename, copy, move, delete, information, copy path, favorites, folder colours and SHA-256 checksums.
- Archive creation, browsing, verification and extraction for ZIP, 7Z, TAR, TAR.GZ and TAR.BZ2. TAR formats support long file names. ZIP supports ZipCrypto/AES-256 password protection and split volumes; compression levels are configurable where supported.
- Archive browsing is hierarchical: open virtual directories and extract one file or the current archived folder without extracting the whole archive.
- Operation progress with percentage, current path/item and status. The completion dialog and operation notification are removed automatically when an operation ends.
- Built-in PDF reader with page navigation, basic raster-image viewer and audio playback for device-supported formats. If an embedded reader cannot handle a particular file, an installed compatible app remains available from the viewer.
- File-type icons, image thumbnails and optional PDF/video/music/APK previews.
- Built-in UTF-8 text editor for common text and code files up to 4 MiB, including HTML, CSS, Java, Kotlin, JavaScript, JSON, XML, Python, SQL and more. The editor provides text editing, not a full IDE or code execution environment.
- GLB/GLTF metadata inspector; interactive 3D rendering is handed off to a compatible external viewer.
- Twelve selectable interface languages: Portuguese, English, Spanish, French, German, Simplified Chinese, Hindi, Arabic, Japanese, Russian, Bengali and Indonesian. The selected language is shown by its native name; Arabic uses a right-to-left layout. Translations are provided in twelve languages; rare platform-generated messages may still depend on Android or third-party components.
- Theme presets (Dark, OLED black and Light), accent-color selection, custom folder colours and thumbnail preferences. A user-controlled “Smooth animations” option adds short, lightweight file-list entrance effects; touch ripples indicate selection and taps. Animations are skipped for large lists to preserve responsiveness.

## Format and platform limits

- Archive operations are built in for ZIP, 7Z, RAR/RAR5 extraction, TAR, TAR.GZ, TAR.BZ2 and TAR.XZ. RAR/RAR5 is read/extract only; this app does not create RAR archives. ISO, CAB, ARJ, LZH, DMG, WIM and many additional ZArchiver formats are not yet supported for extraction. RAR/RAR5 is supported for browsing, testing, and extraction, including encrypted archives when their password is entered in the archive's action menu. A file icon does not imply that the format can be decompressed.
- ZIP supports encryption and split volumes. Password protection and split volumes are not implemented for 7Z or TAR-family archives.
- Advanced browsing uses Shizuku/Sui after explicit authorization. ADB/Shell mode can attempt `Android/data` and `Android/obb` and transfer selected items to/from shared storage. If the service's effective UID is 0 (ROOT backend), it can browse `/`, `/data`, shared storage, `Android/data`, `Android/obb`, `/system` and additional system paths. It supports folder/file creation, metadata, text editing up to 4 MiB, cross-folder copy/move and recursive deletion in Root mode. Root is never granted by Archiver itself; Android/OEM restrictions still apply.
- A plugin system is not implemented in this version.
- PDF rendering uses Android's `PdfRenderer`; audio playback depends on the codecs supported by the Android device. SVG, Office documents and unsupported image/audio/video formats can be opened through a compatible installed app.
- Android may restrict access to private app directories such as `Android/data`, even when broad shared-storage access is granted. All-files permission does not grant root and does not bypass other apps' private storage protections.

## Website

The website is separate from the app and is published from `docs/` through GitHub Pages when repository Pages settings enable the deployment:

https://nexauren1.github.io/Archiver-File-Manager/

## Build and release

- JDK 17, Gradle 8.13, Android Gradle Plugin 8.13.2, and Kotlin.
- The single GitHub Actions workflow is **Archiver — Single Signed APK**. Every push to `main` builds one signed production APK. A matching version tag (for example `v0.9.3`) builds that same release variant and attaches the APK and its SHA-256 checksum to the GitHub Release.
- The production workflow runs `assembleRelease`, checks that exactly one APK exists, verifies its signature with `apksigner`, and checks ZIP alignment with `zipalign`. A separate pull-request compile check builds a temporary debug APK solely to detect source/build errors; it does not upload or publish that APK. Resource shrinking and R8 code shrinking are disabled in the release variant to keep all app code/resources in the production package.
- The downloadable workflow artifact contains one APK plus its checksum file; the GitHub Release attaches that exact APK and checksum. The checksum is a text file, not a second application.
- A permanent signing identity is mandatory. If any required signing secret is missing, the workflow fails rather than publishing an unsigned or debug build. Configure the four protected secrets described in [`.github/KEYSTORE_SECRETS.md`](.github/KEYSTORE_SECRETS.md).

## Signing

The release keystore and passwords must never be committed to the repository. GitHub Actions reads the long-term release identity from protected repository secrets and uses that same identity for every build, allowing signed releases to be upgraded without changing their signing certificate.

## Storage and privacy

The user must grant Android's special all-files-access permission when requested. This does not grant root and does not bypass OS restrictions on other apps' private storage. The Shizuku/Sui advanced view requires separate authorization. Full filesystem browsing and recursive operations require a root-backed service running as UID 0. Treat recursive deletion inside system/app directories as a destructive operation. Files are processed locally by the app.

Archiver is an independent project and is not affiliated with ZArchiver.


## Melhorias de experiência e assinatura (0.7.0)

- O botão flutuante passa a mostrar **Colar** quando existe conteúdo copiado/recortado; tocar nele executa a ação na pasta atual.
- O seletor de idioma apresenta cada idioma como uma opção tocável, com indicação da seleção atual.
- A opção de cores permite escolher uma subpasta e aplicar/remover uma cor visual.
- O leitor de áudio tem barra de progresso, navegação temporal e transferência explícita para reprodução em segundo plano, com notificação para parar.
- O workflow Actions unificado produz apenas um APK de produção assinado; não compila nem publica variantes debug/teste.
- Configuração de assinatura: consulte [`.github/KEYSTORE_SECRETS.md`](.github/KEYSTORE_SECRETS.md). Nunca carregue keystores ou passwords para o repositório.
