# TV LDZ 2.2.12

- O atualizador do TV LDZ consulta `update.json` público no repositório `TV-LDZ`, sem depender do endpoint REST de releases.
- Os cards de Controle LDZ, Quadro LDZ e Tempo LDZ consultam `update.json` nos respectivos repositórios.
- Antes de baixar, confere tamanho e SHA-256; antes de instalar, confere pacote, Android mínimo e certificado de assinatura fixado no TV LDZ.
- Requer Android 9 ou posterior. A versão 2.2.11 pode recebê-la pelo atualizador atual; as versões seguintes usam o manifesto.
