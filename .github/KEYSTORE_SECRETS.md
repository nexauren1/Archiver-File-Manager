# Assinatura Android no GitHub Actions

O repositório usa um único fluxo de compilação, **Archiver — Single Signed APK**. Ele produz exclusivamente o APK de produção assinado. O fluxo não gera uma variante debug nem recorre a uma compilação sem assinatura.

A mesma identidade de assinatura deve ser usada em todas as versões para permitir atualizações. Nunca coloques ficheiros `.jks`/`.keystore`, passwords ou chaves privadas no repositório.

## Secrets obrigatórios

Em GitHub: **Settings → Secrets and variables → Actions → New repository secret**. Configura estes quatro secrets protegidos:

- `ANDROID_KEYSTORE_BASE64`: conteúdo Base64 do ficheiro keystore permanente.
- `ANDROID_KEYSTORE_PASSWORD`: password do keystore.
- `ANDROID_KEY_ALIAS`: alias da chave de assinatura.
- `ANDROID_KEY_PASSWORD`: password da chave.

Para gerar o Base64 localmente:

- Linux: `base64 -w 0 minha-chave.jks`
- macOS: `base64 minha-chave.jks | tr -d '\\n'`
- PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes('minha-chave.jks'))`

Copia apenas a saída Base64 para `ANDROID_KEYSTORE_BASE64`. Nunca publiques o keystore, o Base64, as passwords ou o ficheiro original no repositório.

Se algum secret estiver em falta, o workflow falha e não publica APK algum; não existe fallback para debug ou APK sem assinatura.

## Onde obter o único APK

Abre **Actions → Archiver — Single Signed APK → execução concluída → Artifacts**. O artefacto `Archiver-<versão>-signed` contém apenas `Archiver-<versão>.apk` e o respetivo `.sha256`.

Para criar uma GitHub Release, publica uma tag `vX.Y.Z` que corresponda a `versionName` em `app/build.gradle`, ou executa manualmente o workflow e marca `Publish a GitHub Release from this same signed APK`. O ficheiro anexado ao release é o mesmo APK produzido e verificado pelo workflow.

## Se uma chave já foi exposta

Apagar um ficheiro da branch não o apaga do histórico Git. Se uma chave privada já esteve num repositório público, considera-a comprometida e planeia uma rotação de assinatura antes de distribuir atualizações. Não troques a chave de assinatura de uma aplicação publicada sem uma estratégia de atualização compatível.
