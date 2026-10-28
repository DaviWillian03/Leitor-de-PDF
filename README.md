# Leitor de Documentos

App Android gratuito para abrir **PDF**, **Word (.docx)** e **Excel (.xlsx)** recebidos pelo WhatsApp
(ou qualquer outro app) e fazer **edição básica**. Sem servidor, sem anúncios, sem internet.

## O que faz

**Arquivos recentes** — a tela inicial lista os últimos 30 arquivos abertos, com filtro por tipo
(PDF / Word / Excel) e busca pelo nome. Cada um pode ser reaberto mesmo depois de fechar o WhatsApp
(o app guarda uma cópia só neste aparelho; “Remover” e “Limpar lista” apagam as cópias).

| Formato | Ver | Editar |
|---|---|---|
| PDF | rolar as páginas; **buscar texto** (Android 15+) | caneta (3 cores) e marca-texto, **texto digitado**, **assinatura** (pode ficar guardada), desfazer |
| Word `.docx` | lista de parágrafos; **buscar com destaque** | editar, **excluir**, **inserir abaixo**, adicionar no final |
| Excel `.xlsx` | grade com várias abas; **filtro e ordenação por coluna** (como o Autofiltro); cabeçalho fixo | editar célula; **inserir/excluir linhas** |

Como receber do WhatsApp: toque no arquivo → **Abrir com** → *Leitor de Documentos* (ou compartilhe para o app).
Ao editar, o app salva uma **cópia** (“nome-editado”) no local que você escolher; reenvie pelo WhatsApp.

## Limitações conhecidas

- **PDF editado vira imagem**: o texto da cópia deixa de ser selecionável. Não edita o texto original do PDF.
- **Busca em PDF** usa o leitor de PDF do sistema: só funciona no Android 15 ou com a atualização equivalente do
  sistema; em aparelhos sem suporte o campo aparece desativado, com aviso.
- **Word**: não mostra a formatação/layout, só o texto dos parágrafos. Editar um parágrafo mantém a formatação do seu
  primeiro trecho; inserir abaixo copia o estilo do parágrafo. Imagens e tabelas são preservadas.
- **Excel**: datas aparecem como número de série; editar uma fórmula a troca pelo valor digitado.
  Filtro e ordenação só mudam a tela, não o arquivo.
  Inserir/excluir linhas ajusta células, fórmulas da própria aba, mesclagens e formatação condicional, mas **não**
  fórmulas de outras abas nem nomes definidos (a tela avisa). Recusa (com aviso) em abas com Tabela formatada e ao
  excluir a linha-mestre de uma fórmula compartilhada, para não corromper o arquivo.
- Não abre `.doc`/`.xls` antigos nem PDF com senha (avisa o usuário).
- Arquivos até 100 MB.

## Compilar

Não é preciso instalar nada: o GitHub Actions (`.github/workflows/build.yml`) roda os testes e gera o APK de
depuração. Baixe em **Actions → última execução → Artifacts → leitor-de-documentos-debug-apk**.
No celular, permita “instalar apps de fontes desconhecidas” para instalar o `.apk`.

Localmente (precisa de Android SDK): `gradle :app:testDebugUnitTest :app:assembleDebug` (Gradle 8.9, JDK 17).
