# Assinatura Android no GitHub Actions

O repositório não deve conter ficheiros .jks/.keystore, passwords ou chaves privadas. O workflow continua a produzir o APK debug sem estas variáveis; quando as quatro variáveis abaixo existem, também gera um APK release assinado.

## Criar os secrets

Em GitHub: Settings → Secrets and variables → Actions → New repository secret. Cria estes quatro secrets (não os coloques em Variables públicas):

- ANDROID_KEYSTORE_BASE64: conteúdo Base64 do ficheiro keystore.
- ANDROID_KEYSTORE_PASSWORD: password do keystore.
- ANDROID_KEY_ALIAS: alias da chave de assinatura.
- ANDROID_KEY_PASSWORD: password da chave.

Para gerar o Base64 localmente:

- Linux: base64 -w 0 minha-chave.jks
- macOS: base64 minha-chave.jks | tr -d '\n'
- PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes('minha-chave.jks'))

Copia apenas a saída Base64 para o secret ANDROID_KEYSTORE_BASE64. Nunca publiques o keystore, Base64, passwords ou o ficheiro original no repositório.

## Se já foram expostas chaves

Apagar o ficheiro do branch não apaga o histórico Git. Se uma chave privada já esteve num repositório público, considera-a comprometida: gera uma nova chave e planeia a rotação de assinatura antes de distribuir atualizações.

## Onde obter os APKs

Abre Actions → Android APK → execução concluída → Artifacts. O APK debug é sempre gerado. O APK release aparece apenas quando todos os quatro secrets estão configurados.