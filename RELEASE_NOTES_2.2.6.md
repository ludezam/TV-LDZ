# TV LDZ 2.2.6

## Download e transição do nome
- O download principal é **TV-LDZ.apk**.
- **FreeMyTV-LDZ.apk** é uma cópia idêntica, mantida nesta release para o atualizador das versões anteriores, incluindo a 2.2.5.
- Instale somente um dos dois: são o mesmo aplicativo. Depois de atualizar para a 2.2.6, a TV passa a preferir TV-LDZ.apk e continua aceitando o nome antigo.
- O identificador Android io.freemytv e o certificado LDZ foram preservados para permitir atualização sobre as versões instaladas.

## Alterações
- O fundo da home no modo clima usa a última condição conhecida, combinada com dia/noite calculado pelo horário atual e pelos horários solares salvos. Sem previsão disponível, usa 6h/18h como referência. Não depende da próxima consulta à internet para corrigir o período ao acordar.
- Mais informações mostra Desempenho e Fluidez e detalhes técnicos diretamente, com explicações, indicação de ausência de medição e unidade correta em milissegundos para a primeira imagem. O histórico e a memória usada pela home saíram dessa tela.
- A opção em Sistema passa a se chamar Atualizações via GitHub.
- O Controle LDZ 1.5.1 permanece como download separado e opcional; a integração existente continua disponível.

## Verificações e limites
- Compilação Java concluída; 14 casos isolados passaram para seleção do APK e cálculo local de dia/noite, com trechos extraídos do código de produção.
- Comparação normalizada do DEX com a 2.2.5: mesmas 180 classes, com diferenças apenas em HomeView, SettingsActivity.SettingsView e Updates.
- Imagens, ícones, fonte e os demais recursos são idênticos aos da 2.2.5. Alterados apenas manifesto/versão, código, catálogo pt.json e metadados de assinatura.
- Assinatura APK v2/v3 validada, com o mesmo certificado LDZ: 36a1f3a1af9cb3716ea08f87711a24937a345d95a219a856879175a4fa994a74.
- Validação visual e de atualização no aparelho ainda pendente. Esta publicação não modifica a limpeza de apps da TCL nem os ajustes de energia da TV.

SHA-256 dos dois arquivos TV-LDZ.apk e FreeMyTV-LDZ.apk:
42D5033FC6E96D973ECFDB4A43FF1725E8E084DE74DFF0C4CDB2ED2B0C42C738
