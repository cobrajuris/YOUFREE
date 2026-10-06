# Ilha Assistente: Ilha Dinâmica para Android

Uma Ilha Dinâmica no estilo do iPhone para Android 8+, que **flutua em volta da câmera frontal**
e é, ao mesmo tempo, uma assistente de voz que atende quando você diz **"Oi assistente"**.

## A ilha

O app lê a posição exata do furo da câmera (DisplayCutout) e centraliza e dimensiona a pílula
nele. **Nada é desenhado em cima da câmera**: o conteúdo fica sempre dos dois lados dela.

| Estado | Como fica |
|---|---|
| Repouso | Só a pílula preta em volta da câmera |
| Ao vivo (compacta) | Conteúdo à esquerda e à direita da câmera: timer (ícone · 4:59), música (capa · ondas na cor do álbum), próximo compromisso (calendário · 15 min), carregando (raio · 76%) |
| Expandida | Ao tocar, cresce num cartão arredondado. A primeira linha fica na altura da câmera (coisas à esquerda e à direita); o resto desce embaixo |

**Gestos:** tocar = expandir · segurar = falar · puxar para baixo = expandir ·
puxar para cima ou tocar fora = recolher.

**Cartões expandidos:**
- **Timers** (estilo Live Activity): tempo grande, anel de progresso, pausar, +1 / +5 min, parar.
  Vários timers ao mesmo tempo. Quando acaba, toca o alarme e vibra.
- **Música:** capa, título, artista, barra de progresso com tempos, ⏮ ⏯ ⏭.
- **Próximo compromisso:** "Em 15 min", horário, local, barra de contagem, "Ver evento".
  Aparece sozinho 10 minutos antes.
- **Mensagens:** ícone do app, quem mandou, prévia, "Abrir".
- **Assistente (estilo Siri):** "Ouvindo" com onda colorida que reage à voz, o que você
  disse e a resposta.
- **Início** (tocar sem nada ao vivo): hora, bateria, saudação, próximo compromisso e atalhos
  para Falar, Agenda, Notas e Controles.
- **Controles:** Wi-Fi, Bluetooth, Lanterna, Vibrar e barras de brilho e volume.
- **Agenda:** semana com os dias ocupados, hoje, próximos e "Salvar por voz".
- **Notas:** bloco de notas da assistente.

Animações com mola, desfoque na troca de conteúdo (Android 12+), tipografia Inter e cores
do iOS.

## "Oi assistente"

Com a tela ligada, diga **"Oi assistente"** e a ilha abre ouvindo. O reconhecimento da frase
é **100% no celular** com o [Vosk](https://github.com/alphacep/vosk-api) (código aberto): o áudio
não sai do aparelho. A voz em português (31 MB) é baixada uma vez em Ajustes → Oi assistente.

## O que a assistente faz

| Diga | O que acontece |
|---|---|
| "salva dia 29 eu vou viajar", "marca dentista sexta às 10" | Salva na agenda (com aviso) |
| "anota comprar pão e leite", "minhas notas" | Bloco de notas |
| "timer de 10 minutos para o macarrão", "para o timer" | Timer ao vivo na ilha |
| "o que eu tenho amanhã?", "qual meu próximo compromisso?" | Lê a agenda |
| "liga para a Maria" | Abre o telefone com o número |
| "manda mensagem pro João dizendo já estou chegando" | WhatsApp com a mensagem pronta (ou SMS) |
| "me leva para o shopping", "como chego na rodoviária" | Rota no Google Maps |
| "chama um Uber para o aeroporto", "pede um 99" | Abre o app de corrida |
| "toca Coldplay no Spotify", "toca jazz no YouTube" | Música pela busca |
| "quanto é 15% de 200?", "quanto é 37 vezes 12" | Conta na hora |
| "aumenta o volume", "brilho em 50", "modo vibrar", "liga a lanterna" | Controles |
| "liga o Wi-Fi", "abre o Bluetooth" | Painéis do sistema |
| "alarme às 7 e 30", "abre o Instagram", "lê minhas notificações" | Na hora |

**Com uma chave da API do Claude**, ela responde qualquer pergunta e busca na internet
(clima, notícias, resultados). Crie a chave em [console.anthropic.com](https://console.anthropic.com).

## Tela de bloqueio

- **Com "Toque perfeito" (acessibilidade) ligado**, a própria ilha aparece na tela de bloqueio,
  mais restrita: só prévia de uma linha das mensagens (ou nenhuma, se escolher esconder),
  sem controles e sem assistente até desbloquear.
- A tela de bloqueio premium (relógio grande, widgets, semana e prévias) continua disponível
  em Ajustes → Tela de bloqueio.

## Instalação

1. Baixe `IlhaAssistente.apk` em **Releases → "Ilha Assistente (APK mais recente)"**.
2. Se o Play Protect bloquear: Play Store → perfil → Play Protect → ⚙ → desative
   "Verificar apps", instale e ative de novo.
3. No app, ligue **"Ilha ligada"** e dê as permissões:
   - **Mostrar sobre outros apps** (obrigatório);
   - **Toque perfeito na ilha** (recomendado: sem ele, tocar bem no topo abre a cortina de
     notificações, porque o Android deixa a barra de status por cima);
   - Microfone, Agenda e contatos, Mensagens e música, Brilho.
4. Se algo aparecer cinza ("configuração restrita"): **Configurações restritas** → ⋮ →
   "Permitir configurações restritas".
5. Em **Oi assistente**, toque em "Voz em português" para baixar.

## Privacidade

- "Oi assistente" é reconhecido no celular. Comandos do celular não saem do aparelho.
- Só perguntas livres vão para a API do Claude, e só se você colocou uma chave.
  Mensagens, contatos, notas e agenda nunca são enviados.

## Compilar

Android SDK + JDK 17: `./gradlew assembleRelease`. O workflow
`.github/workflows/ilha-assistente-apk.yml` compila e publica o APK a cada push.

## Estrutura

| Arquivo | Função |
|---|---|
| `IslandController.kt` | A ilha: geometria da câmera, estados, atividades ao vivo, cartões, gestos, animações |
| `IslandService.kt` | Serviço em primeiro plano: hospeda a ilha, timers e "Oi assistente" |
| `IslandAccessibilityService.kt` | Modo "toque perfeito" (ilha acima da barra de status) |
| `WakeWord.kt` | "Oi assistente" offline com Vosk + download do modelo |
| `Timers.kt` / `Notes.kt` | Timers ao vivo e bloco de notas |
| `Assistant.kt` / `WhenParser.kt` | Comandos, datas faladas, Claude e voz |
| `ClaudeClient.kt` | API do Claude (SDK `anthropic-java`) com busca na internet |
| `CalendarRepo.kt` / `Contacts.kt` / `DeviceStatus.kt` | Integrações com o celular |
| `Ui.kt` / `Components.kt` / `StatusViews.kt` | Sistema de design e componentes |
| `LockActivity.kt` / `VoiceActivity.kt` / `MainActivity.kt` | Telas |
