# Manifestos de atualização

O TV LDZ 2.2.12 consulta manifestos JSON estáticos em `raw.githubusercontent.com`; isso substitui consultas a `api.github.com` e evita a cota REST compartilhada por IP. Cada manifesto deve corresponder exatamente ao APK da release e ficar na raiz do branch principal do respectivo repositório.

## Estrutura

```json
{
  "schema": 1,
  "version": "2.2.12",
  "tag": "v2.2.12",
  "apk": "TV-LDZ.apk",
  "size": 22854603,
  "sha256": "sha256 completo em hexadecimal, 64 caracteres",
  "package": "io.freemytv"
}
```

O atualizador só aceita nomes de APK definidos para o app, repositório e tag esperados. Ele confere tamanho e SHA-256, depois confirma o pacote e o certificado de assinatura fixado no app. O Android ainda pede confirmação para instalar. Nunca coloque senhas ou chaves privadas no manifesto.

## Gerar ao publicar uma versão

Depois de assinar o APK final, rode o script contra o arquivo exato que será anexado à release:

```powershell
pwsh -File .\tools\Write-UpdateManifest.ps1 -ApkPath .\outputs\2.2.12\TV-LDZ.apk -Version 2.2.12 -Tag v2.2.12 -PackageName io.freemytv -OutputPath .\update.json
```

Para Controle LDZ, Quadro LDZ e Tempo LDZ, use o mesmo script apontando para o APK final e o pacote correto, salvando `update.json` na raiz do projeto correspondente. Publique o manifesto na branch principal e anexe o APK sob o nome exato nele indicado.

## Ordem de publicação e migração

1. Gere e publique o manifesto na branch principal.
2. Publique a release, usando exatamente a mesma tag, nome, tamanho e APK.
3. Confirme que `https://raw.githubusercontent.com/ludezam/<repo>/main/update.json` mostra a nova versão.

A TV LDZ 2.2.11 ainda usa a API antiga para encontrar a 2.2.12. Depois de instalar a 2.2.12, o próprio atualizador usa o manifesto. Os cards de Outros Apps passam a usar imediatamente os manifestos de seus projetos.
