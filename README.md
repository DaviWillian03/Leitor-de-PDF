# Leitor de Documentos

App Android gratuito para abrir **PDF**, **Word (.docx)** e **Excel (.xlsx)** recebidos pelo WhatsApp
(ou qualquer outro app) e fazer **edição básica**. Sem servidor, sem anúncios, sem internet.

## O que faz

| Formato | Abrir | Editar |
|---|---|---|
| PDF | sim (rola as páginas) | desenhar com caneta/marca-texto, desfazer, salvar cópia |
| Word `.docx` | lista os parágrafos | editar texto de cada parágrafo, adicionar parágrafo no final |
| Excel `.xlsx` | grade com várias abas | editar o valor de qualquer célula |

Como receber do WhatsApp: toque no arquivo → **Abrir com** → *Leitor de Documentos* (ou compartilhe para o app).
Ao editar, o app salva uma **cópia** (“nome-editado”) no local que você escolher; reenvie pelo WhatsApp.

## Limitações conhecidas

- **PDF editado vira imagem**: o texto da cópia deixa de ser selecionável. Não edita o texto original do PDF.
- **Word**: não mostra a formatação/layout, só o texto dos parágrafos. Editar um parágrafo mantém a formatação do seu
  primeiro trecho. Imagens e tabelas são preservadas no arquivo.
- **Excel**: datas aparecem como número de série; editar uma fórmula a troca pelo valor digitado; o Excel recalcula
  as demais ao abrir. Gráficos e estilos são preservados.
- Não abre `.doc`/`.xls` antigos nem PDF com senha (avisa o usuário).
- Arquivos até 100 MB.

## Compilar

Não é preciso instalar nada: o GitHub Actions (`.github/workflows/build.yml`) roda os testes e gera o APK de
depuração. Baixe em **Actions → última execução → Artifacts → leitor-de-documentos-debug-apk**.
No celular, permita “instalar apps de fontes desconhecidas” para instalar o `.apk`.

Localmente (precisa de Android SDK): `gradle :app:testDebugUnitTest :app:assembleDebug` (Gradle 8.9, JDK 17).
