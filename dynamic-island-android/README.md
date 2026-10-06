# Ilha Assistente: ilha lateral para Android

Uma "ilha" presa na **borda da tela** de qualquer Android 8+, que também é sua assistente de voz.

- **Alcinha:** uma marquinha discreta na borda. Arraste para cima ou para baixo para mudar de lugar.
  A cor indica: verde = música tocando, roxo = mensagem nova.
- **Coluna de status** (toque ou puxe a alcinha para dentro): hora, sinal, Wi-Fi, brilho, volume, bateria,
  calendário com o dia de hoje, lanterna, música e microfone.
- **Cartões que saem da borda:**
  - **Agenda:** compromissos de hoje e dos próximos dias, "+ Novo" e "Marcar por voz".
  - **Controles:** barras de brilho e volume, lanterna, modo vibrar, Wi-Fi e Bluetooth.
  - **Mensagens:** WhatsApp, Instagram etc. aparecem num cartão com botão "Abrir".
  - **Música:** capa, nome e ⏮ ⏯ ⏭.
  - **Aviso de compromisso** 10 minutos antes de cada evento (também falado).
  - **Assistente:** o que você falou e a resposta.

## Tela de bloqueio premium

Quando a tela apaga, a ilha prepara uma tela de bloqueio própria que aparece **por cima** do
bloqueio do Android (como apps de despertador). Ela **não substitui** a segurança: para abrir
o celular continua sendo preciso o PIN, a digital ou o rosto.

- Relógio grande com a data, sobre o seu papel de parede.
- Widgets: anel de bateria e o próximo compromisso.
- Calendário da semana (hoje em vermelho, bolinha nos dias com compromisso) e a agenda de hoje.
- **Só prévias das mensagens**: quem mandou e uma linha do texto. Dá para esconder o texto também.
- Lanterna e câmera nos cantos, "Deslize para cima para abrir".
- Desbloqueou pela digital ou pelo rosto? Ela sai da frente sozinha.

Liga e desliga em **Ajustes → Tela de bloqueio**. A ilha da borda não aparece sobre o
bloqueio (o Android não permite janelas flutuantes ali); é essa tela que cumpre esse papel.

## Design

Inspirado no modo escuro da Apple:
- Preto profundo com um fio de luz na borda, cantos contínuos e tipografia **Inter**
  (a fonte aberta mais parecida com o SF Pro), com números tabulares no relógio.
- Paleta de cores do sistema do iOS (azul, verde, laranja, vermelho...).
- **Central de Controle**: botões redondos de Wi-Fi, Bluetooth, Lanterna e Vibrar, e barras
  grossas de brilho e volume que se enchem de branco, como no iPhone.
- **Orbe animado** da assistente (estilo Siri), que reage à sua voz e gira enquanto pensa.
- Animações com **mola**, botões que "afundam" ao toque e vibração leve.
- Tela de configuração no estilo dos **Ajustes do iPhone**, com interruptores do iOS.

Fonte Inter: SIL Open Font License (`INTER_FONT_LICENSE.txt`).

## O que a assistente faz

| Diga | O que acontece |
|---|---|
| "marca dentista sexta às 10", "me lembra de pagar a conta dia 15", "tirar o bolo daqui a 40 minutos" | Cria o compromisso na sua agenda (com aviso) |
| "o que eu tenho hoje / amanhã / essa semana?", "qual meu próximo compromisso?" | Lê a agenda |
| "liga para a Maria" | Abre o telefone com o número do contato |
| "manda mensagem pro João dizendo já estou chegando" | Abre o WhatsApp com a mensagem pronta (ou "manda sms...") |
| "aumenta o volume", "volume em 30", "muta" | Volume da mídia |
| "brilho em 50", "diminui o brilho" | Brilho da tela |
| "modo vibrar" / "modo normal" | Toque do celular |
| "liga o Wi-Fi", "abre o Bluetooth" | Abre o painel do sistema |
| "liga a lanterna", "que horas são", "como está a bateria" | Na hora |
| "timer de 5 minutos", "alarme às 7 e 30" | App Relógio |
| "pausa a música", "próxima", "o que está tocando" | Player |
| "abre o Instagram" | Abre qualquer app |
| "lê minhas notificações" | Lê as 3 últimas |

**Com uma chave da API do Claude**, ela responde qualquer pergunta e **busca na internet**
(clima, notícias, resultados, preços). Coloque também sua cidade no app para o clima.
Crie a chave em [console.anthropic.com](https://console.anthropic.com) → *API Keys* (uso pago).

## Como instalar

1. Baixe `IlhaAssistente.apk` em **Releases → "Ilha Assistente (APK mais recente)"**.
2. Se o **Play Protect** bloquear: Play Store → foto do perfil → Play Protect → ⚙ →
   desative "Verificar apps", instale e **ative de novo** depois.
3. Abra o app e siga os passos:
   1. **Mostrar sobre outros apps** (obrigatório).
   2. **Microfone**.
   3. **Agenda e contatos**.
   4. **Mensagens e música** (opcional; se aparecer "configuração restrita", use o botão
      "Abrir informações do app" → ⋮ → "Permitir configurações restritas").
   5. **Brilho** (opcional).
4. Ligue a chave **"Ilha ligada"**. A alcinha aparece na borda da tela.

A ilha fica ligada com um aviso fixo na barra de notificações (o Android exige isso).
Ela volta sozinha depois de reiniciar o celular. Para desligar, use o app ou o botão "Desligar" do aviso.

> Versão 2.0 em diante: os APKs são assinados sempre com a mesma chave, então as atualizações
> instalam por cima. Quem tinha a versão 1.0 precisa desinstalar uma vez antes.

## Privacidade

- Comandos do celular (agenda, ligações, volume...) não saem do aparelho. O reconhecimento de voz é o do Google/Android.
- Só o que você pergunta e não é comando local vai para a API do Claude, e só se você colocou uma chave.
  Mensagens, contatos e agenda nunca são enviados.
- A chave fica salva só no aparelho.

## Compilar

Precisa do Android SDK e JDK 17: `./gradlew assembleRelease`.
O workflow `.github/workflows/ilha-assistente-apk.yml` compila e publica o APK a cada push nesta pasta.

## Estrutura

| Arquivo | Função |
|---|---|
| `IslandService.kt` | Serviço que mantém a ilha na tela |
| `IslandController.kt` | A ilha: alcinha, coluna de status, cartões, animações, música, avisos de agenda |
| `DeviceStatus.kt` | Bateria, rede, sinal, brilho, volume, lanterna, modo vibrar |
| `CalendarRepo.kt` / `WhenParser.kt` | Agenda e entendimento de datas faladas |
| `Contacts.kt` | Busca de contatos para ligar e mandar mensagem |
| `Assistant.kt` | Comandos locais, voz (TTS) e envio para o Claude |
| `ClaudeClient.kt` | API do Claude (SDK `anthropic-java`) com busca na internet |
| `NotificationWatcher.kt` | Mensagens e controle de música |
| `VoiceActivity.kt` | Escuta a voz / barra de digitar |
| `Ui.kt` / `Components.kt` | Sistema de design: cores, fontes, molas, barras, botões, orbe, interruptor |
| `LockActivity.kt` | Tela de bloqueio premium |
| `MainActivity.kt` | Tela de configuração |
| `BootReceiver.kt` | Religa a ilha ao reiniciar |
