# CamSRT: celular como câmera do OBS via SRT (grátis)

App Android que transmite a câmera do celular para o OBS pela rede Wi-Fi
usando o protocolo **SRT** (aberto, sem custo, sem plugin pago).

Funciona assim: o app disca (caller) para o PC e o OBS escuta (listener).
Latência típica em Wi-Fi 5 GHz bom: 150 a 400 ms. Zero absoluto não existe
em Wi-Fi: sempre há codificação H.264 + rede + decodificação no caminho.

## Requisitos

- Celular com Android 8.0 ou superior
- PC e celular na **mesma rede Wi-Fi** (5 GHz recomendado)
- OBS Studio 30 ou superior (SRT já vem embutido, nada para instalar)

## Instalar

1. Copie `app/build/outputs/apk/debug/app-debug.apk` para o celular e instale
   (autorize "instalar apps desconhecidos" quando o Android pedir).
2. Conceda câmera e microfone na primeira abertura.

## Usar

1. No PC, descubra o IP local (ex. `192.168.0.10`).
2. No OBS: **Fontes > + > Fonte de Mídia**. Desmarque **Arquivo local** e em
   **Entrada** coloque (ajuste porta e latência iguais às do app):

   `srt://0.0.0.0:9998?mode=listener&latency=120`

   Confirme. A fonte fica preta até o celular conectar.
3. No app, escolha a qualidade (padrão **1080p30 Estável**), digite o
   **IP do PC**, confira porta (9998), latência (120) e bitrate
   (8 Mbps). Sem saber a porta? Digite o IP e toque em **Procurar
   SRT** (o campo porta aceita faixa, ex. `9900-9910`). Sem saber o
   IP (ex. Mac com compartilhamento de rede)? Deixe o IP vazio e
   toque em **Procurar SRT**: o app varre a rede local em IPv4 e
   IPv6 nas portas 9990-9999 e lista o que achar. Toque em
   **Iniciar**.
4. O vídeo aparece no OBS em poucos segundos. **Trocar câmera** alterna entre
   traseira e frontal sem derrubar a conexão.
5. O app roda em pé ou deitado. O botão de tela cheia no canto do
   preview amplia a imagem para monitorar (com stats sobre ela);
   BACK ou o botão de sair volta.
   A orientação trava sozinha durante a live para não mudar o
   enquadramento, e destrava ao parar.

## Guia para 2 horas sem travar

Checklist antes de apertar Iniciar:

1. **Qualidade 1080p30** (padrão). 1080p60 dobra o calor e o tráfego.
   Rede fraca: 720p30 com 4 Mbps.
2. **Carregador ligado** e celular **sem capa**, em local ventilado.
   Carregar esquenta; capa presa piora muito.
3. Desligue o interruptor **Preview na tela** e ligue **Tela escura**
   após conferir o enquadramento. A transmissão continua normal e o
   aparelho esquenta bem menos.
4. **Permitir em 2º plano**: toque no botão do app e confirme, para o
   Android não matar a live.
5. Wi-Fi 5 GHz, perto do roteador. Feche outros apps pesados no celular.

O que o app faz sozinho durante a live:

- Serviço em primeiro plano com WakeLock + Wi-Fi lock (sem Doze).
- **Reconexão automática** se a rede cair (backoff 1s até 30s).
- **Guarda térmica**: se esquentar, reduz o bitrate sem cortar o
  stream; se esquentar muito, sai do full, desliga o preview e
  escurece a tela.
- Linha de stats com tempo, resolução, teto de bitrate, temperatura e RAM.
- Último estado salvo e restaurado: IP, porta, latência, bitrate,
  áudio, qualidade, preview, brilho e modo economia.
- Inputs travados com live no ar (nada ali vale durante o stream) e
  guarda contra toque duplo no Iniciar.

## Bateria

Do maior para o menor corte de consumo:

1. **Botão de energia**: com a live no ar, pode apagar a tela que a
   transmissão continua (serviço em 1º plano, sem auto-stop).
2. **Modo economia**: um toque liga 720p30 + teto 4 Mbps + preview
   off + tela escura + stats de 5 em 5s. Desligar restaura tudo
   (a qualidade volta na mão).
3. **Wi-Fi econômico automático**: streams até 4 Mbps usam
   `WIFI_MODE_FULL` em vez de high-perf. Acima disso, high-perf.
4. Preview desligado e tela escura manuais continuam disponíveis.

## Dicas de desempenho

- Fique perto do roteador, prefira 5 GHz e evite micro-ondas e redes lotadas.
- Travou ou atrasou: baixe o bitrate para 6 ou 4 Mbps, ou suba a latência SRT
  para 200 ms no app e no OBS (os dois lados precisam do mesmo valor).
- Libere a porta no firewall do PC se o OBS não receber nada.
- IPv6: a procura acha o listener em IPv4 e IPv6, e a transmissão
  funciona nos dois. Como a biblioteca SRT só disca IPv4, alvo só
  IPv6 passa por um relay UDP local automático (o status mostra
  "Transmitindo via IPv6"). No OBS, listener IPv6 usa `srt://[::]:9998?mode=listener&latency=120`
  em vez de `0.0.0.0`.
- A procura primeiro lista portas UDP candidatas por eliminação de
  ICMP, depois confirma cada uma com um handshake SRT de verdade e
  mostra só quem responde como SRT (sem duplicadas de porta
  filtrada). Com o IP vazio ela vira
  sniff de rede: testa quem está vivo no /24 e sonda IPv4 e IPv6
  (gateway, DNS e vizinhos, onde o Mac aparece) nas portas
  9990-9999; varrer IPv6 por força bruta é inviável, por isso o app
  sonda os endereços conhecidos em vez do espaço todo.

## Compilar

Precisa de JDK 17 ou 21 (JDK 26 quebra o Kotlin DSL do Gradle) e
Android SDK com plataforma 36:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.
Testes de unidade: `./gradlew :app:testDebugUnitTest`.

Stack: Kotlin, StreamPack 3.1.2 (câmera + H.264 por hardware + SRT),
Material 3 (material:1.12.0), AGP 8.13.2, Gradle 8.14.5.

## Limites (1.1.0)

- Qualidade padrão 1080p30 a 8 Mbps (H.264 Baseline, GOP 2s), com
  seletor de 720p30 até 1080p60 e recuo automático se o aparelho não
  suportar.
- Sem autenticação nem criptografia: use só em rede doméstica confiável.
- Reconexão automática com backoff; após ~2h de tentativas o app
  desiste e pede para tocar em Iniciar de novo.

## Verificação feita em 01/10/2026

Build `assembleDebug` compila limpo (AGP 8.13.2, Gradle 8.14.5,
Kotlin 2.2.21, StreamPack 3.1.2). Testado num Galaxy A32 real com
receptor SRT de referência no PC, além de emulador:

- Câmera abre em 1080p30 na traseira com recuo automático para 720p30
  na frontal, que não passa de 720p30.
- Iniciar conecta via SRT e entrega H.264 + AAC decodificáveis:
  59 MB contínuos em 75 s de transmissão real (~6 Mbps recebidos,
  regulador adaptativo ajustando os 12 Mbps pedidos à Wi-Fi real).
- Troca de câmera funciona sem derrubar a transmissão.
- Parar desliga limpo em ~300 ms e mostra "Parado".
- Campos de IP, porta, latência e bitrate aceitos e aplicados.
- Falha de conexão e queda no meio do stream mostram mensagem
  ("Falha ao iniciar", "Conexão perdida") e não travam o app.
- Bitrate adaptativo evita estouro de memória quando a Wi-Fi não
  acompanha o bitrate pedido (achado testando: sem ele o app morria
  em ~20 s nessa Wi-Fi).

Na Wi-Fi testada (comum, congestionada), a perda SRT exigiu
retransmissões e a fluidez recebida ficou abaixo dos 30 fps.
Com Wi-Fi 5 GHz bom e perto do roteador, a latência esperada fica
entre 150 e 400 ms. Zero absoluto não existe em Wi-Fi.

## Verificação do modo 2h em 01/10/2026

Emulador Pixel (Android 17, câmera virtual) dirigindo a UI de verdade
via adb, com receptor SRT de referência no PC
(`srt-live-transmit` listener na porta 9999 + gravação ffmpeg):

- 1080p30 transmite H.264 1920x1080 + AAC 44100 mono decodificáveis,
  vários MB contínuos, preview ao vivo (screenshots diferem).
- 720p30 transmite H.264 1280x720 + AAC; 1080p60 recua sozinho para
  1080p30 no aparelho que não suporta 60 fps e transmite normal.
- Trocar câmera no meio da live não derruba o stream.
- Desligar preview e escurecer tela mantêm o stream; labels alternam.
- Guarda térmica simulada (override SEVERE): teto cai para 2 Mbps com
  preview off e tela escura automáticos, sem cortar; ao normalizar,
  restaura 8 Mbps. Timer contínuo o tempo todo.
- Queda de rede (relay morto): entra em retry e retoma sozinho ao
  religar, sem nenhum toque. BACK com live no ar minimiza e a live
  continua em 2º plano (serviço câmera+mic + locks).
- Parar mostra "Parado", encerra o serviço e congela o arquivo.
- RAM estável em 84 a 104 MB durante os streams, 112 MB PSS total
  após 5 sessões. Nenhum crash após os fixes (um FATAL de Spinner e
  um BACK destruindo a Activity foram achados e corrigidos no teste).
