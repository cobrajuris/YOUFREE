# Ilha Assistente: ilha dinâmica para Android

Uma "Dynamic Island" estilo iPhone para qualquer Android 8+, que também é a sua assistente de voz.
Ela fica por cima da câmera frontal e:

- **Em repouso:** é uma pílula preta sobre a câmera.
- **Notificações:** cresce e mostra o ícone do app, quem mandou e a mensagem. Toque para ver tudo e use "Abrir ›" para ir à conversa.
- **Música:** mostra a capa e o nome da música com um equalizador animado. Expandida, ganha botões ⏮ ⏯ ⏭ (funciona com Spotify, YouTube Music e outros players).
- **Carregador:** avisa quando você conecta ou desconecta, com a porcentagem.
- **Assistente:** **segure a ilha** (ou toque em *Falar*) e fale. Ela entende, responde no balão e fala em voz alta.

## O que a assistente faz

Sem nenhuma configuração extra:

| Diga | O que acontece |
|---|---|
| "que horas são", "que dia é hoje" | Responde |
| "como está a bateria" | Porcentagem e se está carregando |
| "liga a lanterna" / "desliga a lanterna" | Lanterna |
| "timer de 5 minutos" | Cria o timer no app Relógio |
| "alarme às 7 e 30" | Cria o alarme |
| "pausa a música", "próxima", "volta", "toca" | Controla o player |
| "o que está tocando" | Nome e artista |
| "abre o WhatsApp" | Abre qualquer app instalado |
| "lê minhas notificações" | Lê as 3 últimas |
| "nova conversa" | Apaga a memória da conversa |

**Com uma chave da API do Claude**, ela também responde qualquer outra pergunta, com respostas curtas pensadas para serem faladas.
Crie a chave em [console.anthropic.com](https://console.anthropic.com) → *API Keys* e cole no app. O uso da API é cobrado pela Anthropic.
O modelo padrão é `claude-opus-5-5` e pode ser trocado no app.

## Como instalar no celular

1. Abra **Releases → "Ilha Assistente (APK mais recente)"** neste repositório pelo celular
   (ou *Actions → Ilha Assistente (APK Android) → último run → Artifacts*) e baixe `IlhaAssistente.apk`.
2. Toque no arquivo e permita "instalar apps desconhecidos" quando o Android pedir.
3. Abra o app **Ilha Assistente** e siga os 3 passos da tela:
   1. **Microfone:** permitir.
   2. **Notificações:** ative "Ilha Assistente" na lista.
   3. **Acessibilidade:** toque em "Ilha Assistente" e ative. É isso que desenha a ilha.
4. Se o passo 2 ou 3 aparecer cinza / "configuração restrita" (Android 13+ com APK baixado):
   *Configurações → Apps → Ilha Assistente → ⋮ → Permitir configurações restritas*, e tente de novo.
5. Use os controles **Distância do topo / Largura / Altura** para encaixar a ilha certinho na câmera do seu modelo.

> Atualizando para uma versão nova: se o Android disser que o pacote é incompatível, desinstale a versão antiga primeiro.
> Cada build da nuvem é assinado com uma chave temporária diferente.

## Por que serviço de acessibilidade?

Janelas de sobreposição comuns ficam **embaixo** da barra de status, e tocar nelas puxa a cortina de notificações.
O serviço de acessibilidade é o único jeito de um app desenhar **acima** da barra de status.
A ilha não lê o conteúdo da sua tela (`canRetrieveWindowContent=false`).

## Privacidade

- Comandos locais (hora, lanterna, timer, música, abrir apps...) não saem do celular. O reconhecimento de voz é o do Google/Android.
- Só o que você pergunta e não é comando local vai para a API do Claude, e só se você colocou uma chave.
  O conteúdo das notificações nunca é enviado.
- A chave fica salva só no aparelho (preferências privadas do app).

## Compilar você mesmo

Precisa do Android SDK (Android Studio) e JDK 17:

```bash
cd dynamic-island-android
./gradlew assembleRelease
# APK em app/build/outputs/apk/release/app-release.apk
```

O workflow `.github/workflows/ilha-assistente-apk.yml` compila e publica o APK automaticamente a cada push que mexe nesta pasta.

## Estrutura

| Arquivo | Função |
|---|---|
| `IslandAccessibilityService.kt` | Liga e desliga a ilha |
| `IslandController.kt` | A ilha: estados (repouso / compacta / expandida), animações, música, carregador |
| `NotificationWatcher.kt` | Recebe notificações e libera o controle de música |
| `Assistant.kt` | Comandos locais, voz (TTS) e envio para o Claude |
| `ClaudeClient.kt` | Chamada à API do Claude pelo SDK oficial `anthropic-java` |
| `VoiceActivity.kt` | Escuta a voz / caixa de texto por cima de qualquer app |
| `MainActivity.kt` | Tela de configuração |
| `WaveView.kt` | Barrinhas animadas (equalizador / nível da voz) |
