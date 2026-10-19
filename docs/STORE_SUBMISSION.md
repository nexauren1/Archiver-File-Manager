# Archiver 0.8.0 — Store submission preparation

This document prepares the same signed Android APK for APKPure, Uptodown, and HUAWEI AppGallery. It is a submission checklist, not confirmation that any store has approved or published the app.

## Release identity

- Display name: Archiver — File Manager
- Package ID: `com.nexauren.archiver`
- Version: `0.8.0`
- Android version code: `10`
- Minimum Android: API 23 (Android 6.0)
- Target Android: API 35
- Category: Tools / Utilities
- Official website: https://nexauren1.github.io/Archiver-File-Manager/
- Privacy policy: https://nexauren1.github.io/Archiver-File-Manager/privacy.html
- Terms of use: https://nexauren1.github.io/Archiver-File-Manager/terms.html

## Suggested short description (English)

Manage files locally, create and extract ZIP, 7Z and TAR archives, search folders, view PDFs and customize your file browser.

## Suggested short description (Portuguese)

Gere ficheiros localmente, crie e extraia arquivos ZIP, 7Z e TAR, pesquise pastas, leia PDFs e personalize o explorador.

## Full description (English)

Archiver is a native Android file manager for organizing and working with files directly on your device.

Features:
- Browse shared storage with breadcrumbs, favorites, sorting, hidden files, multi-selection, and detailed, compact, or grid views.
- Search from the current folder, optionally including subfolders, with filename patterns and file-type filters.
- Copy, move, rename, delete, share, inspect, and calculate SHA-256 checksums for files.
- Create, browse, verify, and extract ZIP, 7Z, TAR, TAR.GZ, and TAR.BZ2 archives.
- Optional, user-authorized advanced browsing for `Android/data` and `Android/obb` via Shizuku/Sui on compatible devices. It supports folder browsing, directory creation, renaming, deleting individual files or empty folders, and editing small UTF-8 text files. Access varies by Android version and manufacturer and does not include other apps' private `/data/data` directories.
- Browse archive folders and extract individual files or a selected archived folder.
- Use ZIP password protection with ZipCrypto or AES-256, split ZIP volumes, and configurable compression levels where supported.
- Read PDFs, view common raster images, play supported audio, edit small UTF-8 text files, and inspect GLB/GLTF metadata.
- Customize themes, accent colours, folder colours, folder icons, thumbnails, and interface language.
- Choose from 12 interface languages.

Archive limitations:
- RAR, ISO, CAB, ARJ, and other formats are not currently supported for extraction.
- Password protection and split volumes are available for ZIP only, not 7Z or TAR-family archives.
- Unsupported media and document types may be opened with a compatible app installed on the device.
- Storage access remains subject to Android's own restrictions; all-files access does not grant root access or access to other apps' private directories.

Privacy:
Archiver processes files locally. The current app manifest does not request Internet access and the app does not implement accounts, advertising, analytics, cloud sync, or file uploads. The user must choose whether to grant broad file access so the file manager can operate.

## Full description (Português)

O Archiver é um gestor de ficheiros Android nativo para organizar e trabalhar com ficheiros diretamente no dispositivo.

Funcionalidades:
- Explorar o armazenamento partilhado com navegação por caminho, favoritos, ordenação, ficheiros ocultos, seleção múltipla e vistas detalhada, compacta ou em grelha.
- Pesquisar a partir da pasta atual, com opção de incluir subpastas, padrões de nomes e filtros por tipo.
- Copiar, mover, renomear, eliminar, partilhar, consultar informações e calcular SHA-256 de ficheiros.
- Criar, navegar, verificar e extrair arquivos ZIP, 7Z, TAR, TAR.GZ e TAR.BZ2.
- Navegação avançada opcional e autorizada pelo utilizador em `Android/data` e `Android/obb` via Shizuku/Sui, em dispositivos compatíveis. Inclui navegação, criação de pastas, renomeação, eliminação de ficheiros individuais ou pastas vazias e edição de pequenos ficheiros de texto UTF-8. O acesso varia com a versão do Android e o fabricante e não inclui as pastas privadas `/data/data` de outras aplicações.
- Navegar pelas pastas dentro de arquivos e extrair ficheiros individuais ou uma pasta selecionada.
- Proteger ZIP com palavra-passe usando ZipCrypto ou AES-256, criar volumes ZIP divididos e ajustar a compressão quando suportado.
- Ler PDF, visualizar imagens comuns, reproduzir áudio suportado, editar pequenos ficheiros de texto UTF-8 e consultar metadados GLB/GLTF.
- Personalizar temas, cores de destaque, cores e ícones de pastas, miniaturas e idioma da interface.
- Escolher entre 12 idiomas da interface.

Limitações dos arquivos:
- RAR, ISO, CAB, ARJ e outros formatos ainda não são suportados para extração.
- Proteção por palavra-passe e volumes divididos estão disponíveis apenas para ZIP, não para 7Z ou TAR.
- Tipos de documentos e multimédia não suportados podem ser abertos com uma aplicação compatível instalada no dispositivo.
- O acesso ao armazenamento continua sujeito às restrições do Android; a permissão de acesso a todos os ficheiros não concede root nem acesso às pastas privadas de outras aplicações.

Privacidade:
O Archiver processa os ficheiros localmente. O manifesto atual não pede acesso à Internet e a aplicação não implementa contas, publicidade, análise de utilização, sincronização na nuvem nem envio de ficheiros. O utilizador decide se concede acesso amplo aos ficheiros para o gestor funcionar.

## Required before submission

- [ ] Configure the four protected GitHub Actions secrets described in [KEYSTORE_SECRETS.md](../.github/KEYSTORE_SECRETS.md). Never commit the keystore or passwords.
- [ ] Use the single **Archiver — Single Signed APK** workflow; for a stable release, push a matching `v0.8.0` tag (or run it manually with release publication enabled). Require the signed build, signature verification, ZIP alignment verification, and attached SHA-256 checksum.
- [ ] Download the sole APK attached to the stable GitHub release and compare its checksum to the attached `.sha256` file. The current workflow does not create new debug/preview APKs.
- [ ] Confirm that the release keystore is the intended long-term signing identity and back it up securely. A signing-key mismatch can prevent upgrades.
- [ ] Add real screenshots captured from the release build on a supported Android device. Do not use mockups that imply unverified app behavior.
- [ ] Export a square store icon from the app artwork at the resolution requested by the selected store.
- [ ] Add the publisher's legal/display name and a monitored support contact to the website privacy/terms pages. The current legal pages explicitly note this outstanding commercial-distribution step.
- [ ] Complete each store's developer verification, content rating, privacy declarations, country/region availability, and permission disclosures.
- [ ] Test a clean install and upgrade path on physical devices, including storage permission denial/grant, archive extraction, password-protected ZIP files, copy/move/delete, PDF/audio readers, and Arabic RTL layout.
- [ ] Test the optional Shizuku/Sui mode when the service is absent, denied, started, and stopped; confirm standard browsing still works and Android/data/obb access failures are explained rather than treated as app crashes.
- [ ] Check the public APK's package ID, version name/code, certificate, and SHA-256 before uploading the same artifact to each store.

## Store-specific path

### APKPure
Submit the stable signed APK through the publisher submission process. Include the icon, screenshots, accurate feature description, version notes, and privacy policy URL. Confirm publisher rights to distribute the app.

### Uptodown
Use the Developers Console and the **Add new app** flow. Upload the APK itself (not a Google Play URL), then complete the app information and submit it for editorial review. Uptodown's own criteria call out application functionality, distribution rights, a valid icon, sufficient information, and acceptable malware/privacy behavior.

### HUAWEI AppGallery
Create the app in AppGallery Connect with a verified HUAWEI Developer account, upload the signed APK, localize the app name/description/screenshots, add the privacy policy URL and release details, then submit for review. Validate installation and file access on Huawei devices without assuming Google Mobile Services are present; the current app does not depend on Google Mobile Services by declaration, but device testing is still required.

## Audit notes

- The archive extraction code already rejects absolute paths and `..`/dot path components and checks canonical destination containment.
- Recursive copy, measuring, and delete operations now explicitly avoid following symbolic links.
- The single production workflow builds only the signed release variant, checks that there is exactly one APK output, does not persist checkout credentials, and verifies the APK signature, alignment, and SHA-256 before creating a stable release.
- CI compilation and static workflow checks do not replace device testing, store review, or an independent malware scan of the final APK.
- No store upload or store approval is performed by this repository workflow.
