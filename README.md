# Archiver 0.8.0 — Android File Manager

Archiver is a native Android file manager written in Kotlin. It is an independent project inspired by classic archive managers such as ZArchiver; it is not a website wrapped in an app.

## Archiver 0.8.0

- Adds per-folder icon presets alongside folder colors.
- Adds customizable file-list backgrounds, a storage-usage indicator, and lightweight automatic refresh of the current folder.
- Updates the public website and terms, and improves release readiness.
- Version code: 10.

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
- Twelve selectable interface languages: Portuguese, English, Spanish, French, German, Simplified Chinese, Hindi, Arabic, Japanese, Russian, Bengali and Indonesian. The selected language is shown by its native name; Arabic uses a right-to-left layout. Some legacy/rare strings may still use English fallback where a translation has not been added.
- Theme presets (Dark, OLED black and Light), accent-color selection, custom folder colours and thumbnail preferences. A user-controlled “Smooth animations” option adds short, lightweight file-list entrance effects; touch ripples indicate selection and taps. Animations are skipped for large lists to preserve responsiveness.

## Format and platform limits

- Archive operations are built in for ZIP, 7Z, TAR, TAR.GZ and TAR.BZ2. RAR, ISO, CAB, ARJ and other formats may be identified by their file icons but are not yet supported for archive extraction. A file icon does not imply that the format can be decompressed.
- ZIP supports encryption and split volumes. Password protection and split volumes are not implemented for 7Z or TAR-family archives.
- Root/Shizuku access and a plugin system are not implemented and are intentionally not presented as available settings.
- PDF rendering uses Android's `PdfRenderer`; audio playback depends on the codecs supported by the Android device. SVG, Office documents and unsupported image/audio/video formats can be opened through a compatible installed app.
- Android may restrict access to private app directories such as `Android/data`, even when broad shared-storage access is granted. All-files permission does not grant root and does not bypass other apps' private storage protections.

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


## Melhorias de experiência e assinatura (0.7.0)

- O botão flutuante passa a mostrar **Colar** quando existe conteúdo copiado/recortado; tocar nele executa a ação na pasta atual.
- O seletor de idioma apresenta cada idioma como uma opção tocável, com indicação da seleção atual.
- A opção de cores permite escolher uma subpasta e aplicar/remover uma cor visual.
- O leitor de áudio tem barra de progresso, navegação temporal e transferência explícita para reprodução em segundo plano, com notificação para parar.
- O workflow Actions produz sempre um APK debug e, quando os quatro secrets de assinatura estão configurados, também gera um APK release assinado.
- Configuração de assinatura: consulte [`.github/KEYSTORE_SECRETS.md`](.github/KEYSTORE_SECRETS.md). Nunca carregue keystores ou passwords para o repositório.
