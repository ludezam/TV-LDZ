# TV LDZ 2.2.8

## Home e Ajustes

- O modo **TV LDZ Fast** usa fundo preto sem imagem e desativa animações. Os Ajustes mostram corretamente quando esse modo está ativo.
- A opção Wi-Fi retorna à home do TV LDZ, e os textos principais mantêm contraste sobre diferentes fundos.
- Remove o oval por trás da TV na marca dos Ajustes e organiza as colunas de Mais informações.

## Outros Apps

Adiciona **Outros Apps** abaixo de Sistema nos Ajustes. Os cards permitem consultar e baixar as versões estáveis mais recentes de:

- Controle LDZ — [repositório e releases](https://github.com/ludezam/Controle-LDZ)
- Quadro LDZ — [repositório e releases](https://github.com/ludezam/Quadro-LDZ)
- Tempo LDZ — [repositório e releases](https://github.com/ludezam/tempoLDZ)

O TV LDZ busca o APK na release mais recente, confere tamanho, SHA-256, nome do pacote, assinatura e versão mínima do Android. Depois, abre o instalador do Android para que a pessoa confirme. Os apps são baixados separadamente e não estão incluídos no APK do TV LDZ.

**Tempo LDZ:** ainda não havia uma release pública contendo um APK quando esta versão foi preparada. O card mostrará essa situação e poderá localizar uma release futura.

## Atualizador do TV LDZ

O atualizador integrado consulta primeiro `ludezam/TV-LDZ` e procura `TV-LDZ.apk`. Para compatibilidade com instalações em transição, também aceita o repositório antigo `ludezam/FreeMyTV-LDZ` e o arquivo `FreeMyTV-LDZ.apk`. O APK da atualização continua sendo verificado e o Android pede confirmação antes da instalação.

## Verificação desta compilação

- Versão: **2.2.8 (código 41)**
- APK: `TV-LDZ.apk` (22.850.507 bytes)
- SHA-256: `FAF2F961346C65C654B74929E5A3D19891012C90A79E03725E937DAFA720D543`
- Assinatura: chave de distribuição LDZ já usada pelas versões anteriores.
- Instalada por ADB em uma TV TCL com Android 9. Foram conferidos os três cards e consultas reais às releases: Controle LDZ 1.5.1 e Quadro LDZ 1.1.3 foram reconhecidos como atuais; Tempo LDZ informou que ainda não há APK publicado.
- O fluxo de instalação completa de um app ausente ainda não foi verificado na TV. O app opcional não foi instalado nem substituído durante esta validação.

Esta release altera o menu e adiciona somente as artes dos três cards; as demais imagens, fontes e símbolos da 2.2.7 foram preservados.
