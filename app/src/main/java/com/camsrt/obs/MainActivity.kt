package com.camsrt.obs

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.util.Size
import android.content.res.ColorStateList
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.cameraManager
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.configuration.BitrateRegulatorConfig
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.ext.srt.regulator.DefaultSrtBitrateRegulator
import io.github.thibaultbee.streampack.ext.srt.regulator.controllers.DefaultSrtBitrateRegulatorController
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import io.github.thibaultbee.streampack.ui.views.PreviewView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CamSRT endurecido para lives longas (2h+ sem travar).
 *
 * Estratégia térmica e de memória:
 * - 1080p30 @ 8 Mbps, H.264 Baseline, GOP 2s por padrão (metade do
 *   custo de 1080p60, esquenta bem menos e a Wi-Fi acompanha).
 * - Foreground service com WakeLock + Wi-Fi high-perf (sem Doze).
 * - Guarda térmica: reduz o teto do bitrate ao esquentar, sem
 *   reiniciar o stream (ajuste dinâmico no encoder).
 * - Reconexão automática com backoff se a rede cair.
 * - Preview desligável + tela escurecível (menos GPU e calor).
 * - Liberação completa no destroy (sem vazamento entre sessões).
 * - Stream sobrevive ao app ir para 2º plano (sem auto-stop no
 *   onPause; BACK com live no ar só minimiza, não derruba).
 * - Retrato e paisagem, preview fullscreen, orientação travada
 *   durante a live. Modo economia (720p30, teto 4M, Wi-Fi econômico).
 * - Procura de SRT por porta/faixa, estado visual persistido,
 *   inputs travados no ar e guarda contra toque duplo no Iniciar.
 */
class MainActivity : ComponentActivity() {

    private data class Quality(
        val label: String,
        val size: Size,
        val fps: Int,
        val defaultBitrateMbps: Int
    ) {
        override fun toString(): String = label
    }

    private val qualities = listOf(
        Quality("1080p30 Estável (recomendado 2h)", Size(1920, 1080), 30, 8),
        Quality("720p30 Leve (rede fraca / menos calor)", Size(1280, 720), 30, 4),
        Quality("720p60 Médio", Size(1280, 720), 60, 6),
        Quality("1080p60 Alto (esquenta mais)", Size(1920, 1080), 60, 12)
    )

    private lateinit var preview: PreviewView
    private lateinit var previewContainer: android.view.View
    private lateinit var previewCard: MaterialCardView
    private lateinit var controlsScroll: android.view.View
    private lateinit var topAppBar: android.view.View
    private lateinit var rootView: android.view.View
    private lateinit var fullscreenButton: MaterialButton
    private lateinit var fullscreenStats: TextView
    private lateinit var statusChip: Chip
    private lateinit var economySwitch: SwitchMaterial
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var localIpText: TextView
    private lateinit var hostLayout: TextInputLayout
    private lateinit var portLayout: TextInputLayout
    private lateinit var latencyLayout: TextInputLayout
    private lateinit var bitrateLayout: TextInputLayout
    private lateinit var qualityLayout: TextInputLayout
    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var latencyInput: EditText
    private lateinit var bitrateInput: EditText
    private lateinit var audioCheck: SwitchMaterial
    private lateinit var qualityInput: AutoCompleteTextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var switchButton: Button
    private lateinit var previewSwitch: SwitchMaterial
    private lateinit var dimSwitch: SwitchMaterial
    private lateinit var batteryButton: Button
    private lateinit var scanButton: Button

    private lateinit var prefs: SharedPreferences

    private var streamer: SingleStreamer? = null
    // Sem StreamerActivityLifeCycleObserver de propósito: o observer
    // padrão derruba o stream no onPause. Aqui a live continua no
    // serviço em 1º plano e só para por ação do usuário ou destroy.
    private var isStreaming = false
    private var cameraIds: List<String> = emptyList()
    private var cameraIndex = 0
    private var collectJob: Job? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var reconnectAttempt = 0
    private var scanJob: Job? = null
    private var connecting = false

    private var videoSize = Size(1920, 1080)
    private var videoFps = 30
    private var userStopped = false
    private var statusSeq = 0L
    private var streamStartRealtime = 0L
    private var userMaxBitrate = 8_000_000
    private var thermalCeiling = 0 // 0 = sem teto térmico
    private var previewOn = true
    private var dimmed = false
    private var fullscreen = false
    private var savedCardHeight = 0
    private var savedContainerHeight = 0
    private var qualityPosition = 0
    private var economyMode = false
    private var lastDescriptor: SrtMediaDescriptor? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            onPermissionsGranted()
        } else {
            setStatus("Permissões de câmera e microfone negadas")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cor dinâmica no Android 12+; nos demais vale o esquema do tema.
        DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("camsrt", Context.MODE_PRIVATE)

        rootView = findViewById(R.id.root)
        topAppBar = findViewById(R.id.topAppBar)
        preview = findViewById(R.id.preview)
        previewCard = findViewById(R.id.previewCard)
        previewContainer = findViewById(R.id.previewContainer)
        controlsScroll = findViewById(R.id.controlsScroll)
        fullscreenButton = findViewById(R.id.fullscreenButton)
        fullscreenStats = findViewById(R.id.fullscreenStats)
        statusChip = findViewById(R.id.statusChip)
        economySwitch = findViewById(R.id.economySwitch)
        statusText = findViewById(R.id.statusText)
        statsText = findViewById(R.id.statsText)
        localIpText = findViewById(R.id.localIpText)
        hostLayout = findViewById(R.id.hostLayout)
        portLayout = findViewById(R.id.portLayout)
        latencyLayout = findViewById(R.id.latencyLayout)
        bitrateLayout = findViewById(R.id.bitrateLayout)
        qualityLayout = findViewById(R.id.qualityLayout)
        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        latencyInput = findViewById(R.id.latencyInput)
        bitrateInput = findViewById(R.id.bitrateInput)
        audioCheck = findViewById(R.id.audioCheck)
        qualityInput = findViewById(R.id.qualityInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        switchButton = findViewById(R.id.switchButton)
        previewSwitch = findViewById(R.id.previewSwitch)
        dimSwitch = findViewById(R.id.dimSwitch)
        batteryButton = findViewById(R.id.batteryButton)
        scanButton = findViewById(R.id.scanButton)

        restoreFields()
        setupQualityDropdown()

        localIpText.text = "IP deste celular: ${deviceIp()} (informe no app o IP do PC)"
        startButton.setOnClickListener { startStream() }
        stopButton.setOnClickListener { stopStream() }
        switchButton.setOnClickListener { switchCamera() }
        previewSwitch.setOnClickListener { togglePreview() }
        dimSwitch.setOnClickListener { toggleDim() }
        fullscreenButton.setOnClickListener { toggleFullscreen() }
        economySwitch.setOnClickListener { toggleEconomy() }
        batteryButton.setOnClickListener { requestBatteryExemption() }
        scanButton.setOnClickListener { scanSrtPorts() }
        // A linha inteira alterna o switch (alvo de toque maior).
        findViewById<android.view.View>(R.id.audioRow).setOnClickListener {
            audioCheck.performClick()
        }
        findViewById<android.view.View>(R.id.previewRow).setOnClickListener {
            previewSwitch.performClick()
        }
        findViewById<android.view.View>(R.id.dimRow).setOnClickListener {
            dimSwitch.performClick()
        }
        findViewById<android.view.View>(R.id.economyRow).setOnClickListener {
            economySwitch.performClick()
        }
        audioCheck.setOnClickListener { saveFields() }
        hostInput.doOnTextChanged { _, _, _, _ -> hostLayout.error = null }
        updateBatteryButton()
        updateStatusChip()
        registerThermalListener()
        registerBackHandler()
        restoreUiState()

        collectCameraIds()
        requestPermissionsIfNeeded()
    }

    // ---------- Configuração e preferências ----------

    private fun setupQualityDropdown() {
        // Menu suspenso M3 (sem filtro: inputType=none, posição = índice).
        val adapter = ArrayAdapter(this, R.layout.list_item_dropdown, qualities)
        qualityInput.setAdapter(adapter)
        qualityPosition = prefs.getInt("quality", 0).coerceIn(qualities.indices)
        qualityInput.setText(qualities[qualityPosition].label, false)
        // Menu não editável: sem teclado (alguns IMEs ignoram
        // inputType=none) e abre a lista a cada toque no campo.
        qualityInput.showSoftInputOnFocus = false
        qualityInput.setOnClickListener { qualityInput.showDropDown() }
        qualityInput.setOnItemClickListener { _, _, position, _ ->
            qualityPosition = position
            // Só sugere bitrate se o usuário não editou manualmente.
            val q = qualities[position]
            if (!prefs.getBoolean("bitrateTouched", false)) {
                bitrateInput.setText(q.defaultBitrateMbps.toString())
            }
            saveFields()
            // Se a câmera já abriu com outra qualidade, reabre na nova
            // (só quando parado; com stream ativo vale na próxima).
            if (!isStreaming && streamer != null &&
                (videoSize != q.size || videoFps != q.fps)
            ) {
                reopenCamera(q.size, q.fps)
            }
        }
        bitrateInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.edit().putBoolean("bitrateTouched", true).apply()
                saveFields()
            }
        }
    }

    private fun selectedQuality(): Quality =
        qualities[qualityPosition.coerceIn(qualities.indices)]

    private fun restoreFields() {
        hostInput.setText(prefs.getString("host", "") ?: "")
        portInput.setText(prefs.getString("port", "9998") ?: "9998")
        latencyInput.setText(prefs.getString("latency", "120") ?: "120")
        bitrateInput.setText(prefs.getString("bitrate", "8") ?: "8")
        audioCheck.isChecked = prefs.getBoolean("audio", true)
    }

    private fun saveFields() {
        prefs.edit()
            .putString("host", hostInput.text.toString().trim())
            .putString("port", portInput.text.toString().trim())
            .putString("latency", latencyInput.text.toString().trim())
            .putString("bitrate", bitrateInput.text.toString().trim())
            .putBoolean("audio", audioCheck.isChecked)
            .putInt("quality", qualityPosition)
            .putBoolean("previewOn", previewOn)
            .putBoolean("dimmed", dimmed)
            .putBoolean("economy", economyMode)
            .apply()
    }

    /** Restaura o último estado visual (preview, brilho, economia). */
    private fun restoreUiState() {
        if (!prefs.getBoolean("previewOn", true) && previewOn) togglePreview()
        if (prefs.getBoolean("dimmed", false) && !dimmed) toggleDim()
        economyMode = prefs.getBoolean("economy", false)
        economySwitch.isChecked = economyMode
    }

    // ---------- Permissões ----------

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(
            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updateBatteryButton() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        batteryButton.isEnabled = !ignoring
        batteryButton.text = getString(
            if (ignoring) R.string.action_background_ok
            else R.string.action_background_allow
        )
    }

    private fun requestBatteryExemption() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = android.content.Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (_: Throwable) {
            setStatus("Não foi possível abrir a isenção de bateria neste aparelho")
        }
    }

    // ---------- Procura de SRT na rede ----------

    /**
     * Procura ouvintes SRT no IP digitado. SRT roda sobre UDP, que não
     * tem handshake de "porta aberta": a sonda envia 1 byte e observa.
     * Porta que responde com erro ICMP (PortUnreachable) está fechada;
     * as demais são candidatas (abertas ou filtradas) e entram na lista.
     */
    private fun scanSrtPorts() {
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            setStatus("Informe o IP do PC para procurar")
            return
        }
        if (scanJob?.isActive == true) return
        val ports = parseScanPorts(portInput.text.toString().trim())
        if (ports.isEmpty()) {
            setStatus("Nada para procurar")
            return
        }
        val seq = ++statusSeq
        setStatus("Procurando SRT em $host (${ports.size} portas)…")
        scanButton.isEnabled = false
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val found = ports.map { port ->
                    async { port to udpProbe(host, port) }
                }.awaitAll().filter { it.second }.map { it.first }.sorted()
                runOnUiThread {
                    if (!isStreaming && !connecting) scanButton.isEnabled = true
                    if (found.isEmpty()) {
                        setStatusIfLatest(
                            seq,
                            "Nenhum SRT achado em $host. Confira o IP e a " +
                                "porta listener no OBS."
                        )
                    } else {
                        setStatusIfLatest(
                            seq,
                            "Achados: ${found.joinToString()} (toque para usar)."
                        )
                        if (seq == statusSeq) showScanResults(host, found)
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    if (!isStreaming && !connecting) scanButton.isEnabled = true
                    setStatusIfLatest(seq, "Falha na procura.")
                }
            }
        }
    }

    private fun parseScanPorts(text: String): List<Int> = SrtPorts.parseScanPorts(text)

    private fun udpProbe(host: String, port: Int): Boolean {
        var socket: java.net.DatagramSocket? = null
        return try {
            socket = java.net.DatagramSocket()
            socket.soTimeout = 350
            socket.connect(java.net.InetSocketAddress(host, port))
            socket.send(java.net.DatagramPacket(ByteArray(1), 1))
            try {
                val buf = ByteArray(64)
                socket.receive(java.net.DatagramPacket(buf, buf.size))
                true
            } catch (_: java.net.SocketTimeoutException) {
                true
            } catch (_: java.net.PortUnreachableException) {
                false
            }
        } catch (_: java.net.PortUnreachableException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun showScanResults(host: String, ports: List<Int>) {
        val items = ports.map { "$host:$it" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_scan_title)
            .setItems(items) { _, which ->
                portInput.setText(ports[which].toString())
                saveFields()
                setStatus("Porta ${ports[which]} selecionada. Toque em Iniciar.")
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    /** Porta para conectar: número direto ou o início da faixa. */
    private fun parsePortOrFirst(text: String): Int = SrtPorts.parsePortOrFirst(text)

    override fun onResume() {
        super.onResume()
        updateBatteryButton()
    }

    // ---------- Câmera / streamer ----------

    private fun onPermissionsGranted() {
        if (streamer != null) return
        val q = selectedQuality()
        openCamera(q.size, q.fps)
    }

    private fun cameraCandidates(size: Size, fps: Int): List<Pair<Size, Int>> {
        val list = mutableListOf(size to fps)
        // Recuos automáticos se o aparelho não suportar a qualidade pedida.
        if (size.width >= 1920 && fps > 30) list += (Size(1920, 1080) to 30)
        if (size.width >= 1920) list += (Size(1280, 720) to 30)
        if (size.width == 1280 && fps > 30) list += (Size(1280, 720) to 30)
        return list.distinct()
    }

    private fun buildVideoConfig(size: Size, fps: Int, bitrate: Int): VideoConfig {
        return VideoConfig(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            startBitrate = bitrate,
            resolution = size,
            fps = fps,
            gopDurationInS = 2f, // GOP 2s: menos I-frames, menos pico de CPU/rede
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        )
    }

    private fun buildAudioConfig(): AudioConfig {
        // Mono 96k: metade dos dados do estéreo, suficiente para voz e
        // mais leve para codificar por 2h.
        return AudioConfig(
            mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
            startBitrate = 96_000,
            sampleRate = 44_100,
            channelConfig = AudioFormat.CHANNEL_IN_MONO
        )
    }

    private fun openCamera(size: Size, fps: Int) {
        lifecycleScope.launch {
            val bitrateMbps = bitrateInput.text.toString().toIntOrNull() ?: 8
            var lastError: Throwable? = null
            for ((s, f) in cameraCandidates(size, fps)) {
                val streamer = SingleStreamer(applicationContext)
                try {
                    streamer.setVideoConfig(buildVideoConfig(s, f, bitrateMbps * 1_000_000))
                    streamer.setAudioConfig(buildAudioConfig())
                    streamer.setVideoSource(
                        CameraSourceFactory(applicationContext.cameraManager.defaultCameraId)
                    )
                    streamer.setAudioSource(MicrophoneSourceFactory())
                    preview.setVideoSourceProvider(streamer)
                    this@MainActivity.streamer = streamer
                    videoSize = s
                    videoFps = f
                    observeStreamer(streamer)
                    val mode = "${s.width}x${s.height}@$f"
                    setStatus("Pronto ($mode). Preencha o IP do PC e toque em Iniciar.")
                    return@launch
                } catch (t: Throwable) {
                    lastError = t
                    try {
                        streamer.release()
                    } catch (_: Throwable) {
                    }
                }
            }
            setStatus("Falha ao abrir câmera: ${lastError?.message}")
        }
    }

    private fun reopenCamera(size: Size, fps: Int) {
        val old = streamer ?: return
        lifecycleScope.launch {
            setStatus("Ajustando qualidade…")
            try {
                old.stopStream()
            } catch (_: Throwable) {
            }
            try {
                old.release()
            } catch (_: Throwable) {
            }
            streamer = null
            collectJob?.cancel()
            openCamera(size, fps)
        }
    }

    private fun observeStreamer(s: SingleStreamer) {
        collectJob?.cancel()
        collectJob = lifecycleScope.launch {
            launch {
                s.isStreamingFlow.collect { streaming ->
                    runOnUiThread {
                        val wasStreaming = isStreaming
                        isStreaming = streaming
                        if (streaming) {
                            connecting = false
                            reconnectAttempt = 0
                            reconnectJob?.cancel()
                            setInputsEnabled(false)
                        }
                        updateStreamButtons()
                        if (!streaming && wasStreaming && !userStopped) {
                            scheduleReconnect("Conexão perdida")
                        }
                    }
                }
            }
            launch {
                s.throwableFlow.collect { t ->
                    runOnUiThread {
                        if (isStreaming) setStatus("Erro: ${t?.message ?: t}")
                    }
                }
            }
        }
    }

    // ---------- Start / stop ----------

    /** Campos travados no ar: nada ali vale com stream ativo. */
    private fun setInputsEnabled(enabled: Boolean) {
        hostLayout.isEnabled = enabled
        portLayout.isEnabled = enabled
        latencyLayout.isEnabled = enabled
        bitrateLayout.isEnabled = enabled
        qualityLayout.isEnabled = enabled
        audioCheck.isEnabled = enabled
        scanButton.isEnabled = enabled
    }

    /** Aplica o estado resolvido aos botões Iniciar/Parar. */
    private fun applyButtonState(state: StreamButtons.State) {
        startButton.isEnabled = state.startEnabled
        stopButton.isEnabled = state.stopEnabled
        stopButton.text = getString(
            if (state.stopText == StreamButtons.CANCEL) R.string.action_cancel
            else R.string.action_stop
        )
    }

    /**
     * Botões conforme o estado: transmitindo mostra Parar; conectando
     * ou aguardando retry mostra Cancelar habilitado, para dar para
     * interromper o loop de reconexão.
     */
    private fun updateStreamButtons() {
        applyButtonState(
            StreamButtons.resolve(
                isStreaming, connecting, reconnectJob?.isActive == true
            )
        )
        updateStatusChip()
    }

    /** Chip de estado: vermelho AO VIVO transmitindo, neutro parado. */
    private fun updateStatusChip() {
        val live = isStreaming
        statusChip.text = getString(
            if (live) R.string.chip_live else R.string.chip_idle
        )
        val bgAttr = if (live) MaterialR.attr.colorErrorContainer
        else MaterialR.attr.colorSecondaryContainer
        val fgAttr = if (live) MaterialR.attr.colorOnErrorContainer
        else MaterialR.attr.colorOnSecondaryContainer
        statusChip.chipBackgroundColor = ColorStateList.valueOf(
            MaterialColors.getColor(this, bgAttr, 0)
        )
        statusChip.setTextColor(MaterialColors.getColor(this, fgAttr, 0))
    }

    private fun startStream() {
        if (connecting || isStreaming) return
        val s = streamer ?: run {
            setStatus("Câmera ainda iniciando, aguarde")
            return
        }
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            hostLayout.error = getString(R.string.error_host_required)
            setStatus(getString(R.string.error_host_required))
            return
        }
        connecting = true
        updateStreamButtons() // Iniciar off, Cancelar on
        setInputsEnabled(false)
        saveFields()
        val port = parsePortOrFirst(portInput.text.toString())
        val latency = latencyInput.text.toString().toIntOrNull() ?: 120
        val bitrateMbps = bitrateInput.text.toString().toIntOrNull() ?: 8

        userStopped = false
        reconnectJob?.cancel()
        reconnectAttempt = 0
        userMaxBitrate = bitrateMbps * 1_000_000
        thermalCeiling = 0
        val descriptor =
            SrtMediaDescriptor("srt://$host:$port?latency=$latency&connect_timeout=15000")
        lastDescriptor = descriptor
        connectNow(s, descriptor, effectiveCeiling())
    }

    private fun connectNow(s: SingleStreamer, descriptor: SrtMediaDescriptor, maxBitrate: Int) {
        val seq = ++statusSeq
        connecting = true
        updateStreamButtons()
        connectJob?.cancel()
        connectJob = lifecycleScope.launch {
            try {
                setStatus("Conectando…")
                // Aplica bitrate antes de conectar (encoder parado aceita).
                if (!isStreaming) {
                    try {
                        s.setVideoConfig(buildVideoConfig(videoSize, videoFps, maxBitrate))
                    } catch (_: Throwable) {
                        // Mantém config atual se a troca falhar.
                    }
                }
                if (audioCheck.isChecked) {
                    try {
                        s.setAudioSource(MicrophoneSourceFactory())
                    } catch (_: Throwable) {
                    }
                }
                applyRegulator(s, maxBitrate)
                // Segura CPU + Wi-Fi antes de transmitir. Bitrate baixo
                // usa Wi-Fi econômico (menos bateria); alto usa high-perf.
                StreamKeepAliveService.start(this@MainActivity, maxBitrate > 4_000_000)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Trava a orientação atual: girar no meio da live
                // mudaria o enquadramento do stream.
                requestedOrientation =
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                s.startStream(descriptor)
                if (userStopped) {
                    // Cancelado no meio do handshake: garante parado.
                    try {
                        withTimeoutOrNull(4000) { s.stopStream() }
                    } catch (_: Throwable) {
                    }
                    return@launch
                }
                connecting = false
                // O flow confirma em seguida; já mostra Parar sem piscar idle.
                applyButtonState(
                    StreamButtons.resolve(
                        isStreaming = true, connecting = false,
                        retryPending = false
                    )
                )
                streamStartRealtime = SystemClock.elapsedRealtime()
                startStats()
                setStatusIfLatest(seq, "Transmitindo…")
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                connecting = false
                runOnUiThread {
                    if (!userStopped) {
                        scheduleReconnect("Falha ao iniciar: ${t.message}")
                    } else {
                        setStatusIfLatest(seq, "Conexão cancelada")
                        updateStreamButtons()
                        setInputsEnabled(true)
                    }
                }
            }
        }
    }

    private fun applyRegulator(s: SingleStreamer, maxBitrate: Int) {
        try {
            s.removeBitrateRegulatorController()
        } catch (_: Throwable) {
        }
        s.addBitrateRegulatorController(
            DefaultSrtBitrateRegulatorController.Factory(
                DefaultSrtBitrateRegulator.Factory(),
                BitrateRegulatorConfig(
                    videoBitrateRange = android.util.Range(800_000, maxBitrate),
                    audioBitrateRange = android.util.Range(96000, 96000)
                )
            )
        )
    }

    private fun stopStream() {
        val s = streamer ?: return
        val wasStreaming = isStreaming
        userStopped = true
        connecting = false
        connectJob?.cancel()
        reconnectJob?.cancel()
        reconnectAttempt = 0
        stopStats()
        val seq = ++statusSeq
        android.util.Log.d("CamSRT", "stop requested")
        lifecycleScope.launch {
            try {
                val done = withTimeoutOrNull(5000) { s.stopStream() }
                android.util.Log.d("CamSRT", "stop finished done=$done")
            } catch (t: Throwable) {
                runOnUiThread { setStatusIfLatest(seq, "Falha ao parar: ${t.message}") }
            } finally {
                StreamKeepAliveService.stop(this@MainActivity)
                runOnUiThread {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    setStatusIfLatest(
                        seq, if (wasStreaming) "Parado" else "Conexão cancelada"
                    )
                    statsText.text = ""
                    fullscreenStats.text = ""
                    applyButtonState(
                        StreamButtons.resolve(
                            isStreaming = false, connecting = false,
                            retryPending = false
                        )
                    )
                    setInputsEnabled(true)
                }
            }
        }
    }

    // ---------- Reconexão automática ----------

    private fun scheduleReconnect(reason: String) {
        if (userStopped) return
        val s = streamer ?: return
        val descriptor = lastDescriptor ?: return
        reconnectJob?.cancel()
        reconnectAttempt++
        if (reconnectAttempt > 240) {
            // ~2h de tentativas com teto de 30s: desiste e avisa.
            connecting = false
            setStatus("Sem conexão após muitas tentativas. Toque em Iniciar.")
            updateStreamButtons()
            setInputsEnabled(true)
            StreamKeepAliveService.stop(this)
            return
        }
        val delayS = when {
            reconnectAttempt <= 2 -> 1L
            reconnectAttempt <= 5 -> 2L
            reconnectAttempt <= 10 -> 5L
            reconnectAttempt <= 20 -> 10L
            reconnectAttempt <= 40 -> 15L
            else -> 30L
        }
        setStatus("$reason. Reconectando em ${delayS}s (tentativa $reconnectAttempt)…")
        reconnectJob = lifecycleScope.launch {
            delay(delayS * 1000)
            if (!isActive || userStopped || isStreaming) return@launch
            // Garante encoder parado antes de reconectar.
            try {
                withTimeoutOrNull(4000) { s.stopStream() }
            } catch (_: Throwable) {
            }
            connectNow(s, descriptor, effectiveCeiling())
        }
        updateStreamButtons() // garante Cancelar durante a espera
    }

    // ---------- Guarda térmica ----------

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener { status ->
                runOnUiThread { onThermalStatus(status) }
            }
        } catch (_: Throwable) {
        }
    }

    /** Teto real aplicado: o menor entre pedido, térmico e economia. */
    private fun effectiveCeiling(): Int {
        var ceiling = userMaxBitrate
        if (thermalCeiling > 0) ceiling = minOf(ceiling, thermalCeiling)
        if (economyMode) ceiling = minOf(ceiling, 4_000_000)
        return ceiling
    }

    /** Aplica o teto ao encoder em execução, sem reiniciar o stream. */
    private fun applyCeiling(s: SingleStreamer, statusMsg: String?) {
        val ceiling = effectiveCeiling()
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                s.videoEncoder?.bitrate = ceiling
                applyRegulator(s, ceiling)
            } catch (_: Throwable) {
            }
            if (statusMsg != null) runOnUiThread { setStatus(statusMsg) }
        }
    }

    private fun onThermalStatus(status: Int) {
        if (!isStreaming) return
        val s = streamer ?: return
        val target = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE -> {
                // Quente de verdade: teto baixo + preview off + tela escura.
                if (fullscreen) toggleFullscreen()
                if (previewOn) togglePreview()
                if (!dimmed) toggleDim()
                (userMaxBitrate * 0.35).toInt().coerceAtLeast(1_000_000)
            }
            status == PowerManager.THERMAL_STATUS_MODERATE -> {
                (userMaxBitrate * 0.6).toInt().coerceAtLeast(2_000_000)
            }
            else -> 0 // frio: sem teto
        }
        if (target == thermalCeiling) return
        thermalCeiling = target
        if (target > 0) {
            applyCeiling(
                s,
                "Aparelho esquentando: bitrate reduzido para " +
                    "${effectiveCeiling() / 1_000_000} Mbps para segurar a live."
            )
        } else {
            applyCeiling(s, "Temperatura normal: bitrate restaurado.")
        }
    }

    private fun thermalLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "temp?n/a"
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val st = pm.currentThermalStatus
            if (st >= PowerManager.THERMAL_STATUS_CRITICAL) {
                "CRÍTICO"
            } else if (st >= PowerManager.THERMAL_STATUS_SEVERE) {
                "quente"
            } else if (st >= PowerManager.THERMAL_STATUS_MODERATE) {
                "morno"
            } else {
                "frio"
            }
        } catch (_: Throwable) {
            "?"
        }
    }

    // ---------- Economia: preview + brilho + fullscreen ----------

    private fun togglePreview() {
        previewOn = !previewOn
        preview.visibility =
            if (previewOn) android.view.View.VISIBLE else android.view.View.GONE
        previewSwitch.isChecked = previewOn
        saveFields()
    }

    private fun toggleDim() {
        dimmed = !dimmed
        val lp = window.attributes
        lp.screenBrightness = if (dimmed) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        dimSwitch.isChecked = dimmed
        saveFields()
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        val cardLp = previewCard.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val containerLp = previewContainer.layoutParams
        if (fullscreen) {
            if (!previewOn) togglePreview() // full é para monitorar: garante imagem
            savedCardHeight = cardLp.height
            savedContainerHeight = containerLp.height
            cardLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            cardLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            cardLp.setMargins(0, 0, 0, 0)
            previewCard.layoutParams = cardLp
            previewCard.radius = 0f
            containerLp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            previewContainer.layoutParams = containerLp
            topAppBar.visibility = android.view.View.GONE
            controlsScroll.visibility = android.view.View.GONE
            fullscreenStats.visibility = android.view.View.VISIBLE
            fullscreenButton.setIconResource(R.drawable.ic_fullscreen_exit_24)
            fullscreenButton.contentDescription =
                getString(R.string.desc_fullscreen_exit)
            hideSystemBars()
        } else {
            val marginH = resources.getDimensionPixelSize(R.dimen.spacing_medium)
            val marginTop = resources.getDimensionPixelSize(R.dimen.spacing_small)
            cardLp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            cardLp.height = savedCardHeight
            cardLp.setMargins(marginH, marginTop, marginH, 0)
            previewCard.layoutParams = cardLp
            previewCard.radius = resources.getDimension(R.dimen.card_corner)
            containerLp.height = savedContainerHeight
            previewContainer.layoutParams = containerLp
            topAppBar.visibility = android.view.View.VISIBLE
            controlsScroll.visibility = android.view.View.VISIBLE
            fullscreenStats.visibility = android.view.View.GONE
            fullscreenButton.setIconResource(R.drawable.ic_fullscreen_24)
            fullscreenButton.contentDescription =
                getString(R.string.desc_fullscreen_enter)
            showSystemBars()
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        } else {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    /**
     * Modo economia: o maior corte possível de bateria sem parar a live.
     * Liga 720p30 + teto 4 Mbps + preview off + tela escura. Desligar
     * restaura preview, brilho e teto (a qualidade volta na mão).
     */
    private fun toggleEconomy() {
        economyMode = !economyMode
        economySwitch.isChecked = economyMode
        saveFields()
        if (economyMode) {
            if (!isStreaming) {
                val idx = qualities.indexOfFirst {
                    it.size == Size(1280, 720) && it.fps == 30
                }
                if (idx >= 0) {
                    qualityPosition = idx
                    qualityInput.setText(qualities[idx].label, false)
                }
            }
            if (fullscreen) toggleFullscreen()
            if (previewOn) togglePreview()
            if (!dimmed) toggleDim()
            streamer?.let {
                applyCeiling(
                    it,
                    "Modo economia: teto ${effectiveCeiling() / 1_000_000} Mbps, " +
                        "preview off, tela escura."
                )
            } ?: setStatus("Modo economia ligado.")
        } else {
            if (!previewOn) togglePreview()
            if (dimmed) toggleDim()
            streamer?.let { applyCeiling(it, "Modo economia desligado.") }
                ?: setStatus("Modo economia desligado.")
        }
    }

    // ---------- Estatísticas ----------

    private fun startStats() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            var ticks = 0
            var memMb = -1
            while (isActive) {
                val elapsed = SystemClock.elapsedRealtime() - streamStartRealtime
                val h = elapsed / 3_600_000
                val m = (elapsed % 3_600_000) / 60_000
                val sec = (elapsed % 60_000) / 1000
                // Memória só a cada 5 ticks (getProcessMemoryInfo tem custo).
                if (ticks % 5 == 0) memMb = appMemoryMb()
                val mem = if (memMb >= 0) " • RAM $memMb MB" else ""
                val line = String.format(
                    "%02d:%02d:%02d • %dx%d@%d • teto %d Mbps • %s%s",
                    h, m, sec, videoSize.width, videoSize.height, videoFps,
                    effectiveCeiling() / 1_000_000, thermalLabel(), mem
                )
                statsText.text = line
                if (fullscreen) fullscreenStats.text = line
                ticks++
                // Economia atualiza de 5 em 5s: menos wake da CPU.
                delay(if (economyMode) 5000 else 1000)
            }
        }
    }

    private fun stopStats() {
        statsJob?.cancel()
        statsJob = null
    }

    private fun appMemoryMb(): Int {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val infos = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            if (infos.isNotEmpty()) infos[0].totalPss / 1024 else -1
        } catch (_: Throwable) {
            -1
        }
    }

    // ---------- Câmera extra ----------

    private fun switchCamera() {
        val s = streamer ?: return
        if (cameraIds.isEmpty()) return
        cameraIndex = (cameraIndex + 1) % cameraIds.size
        val seq = ++statusSeq
        lifecycleScope.launch {
            try {
                s.setVideoSource(CameraSourceFactory(cameraIds[cameraIndex]))
                setStatusIfLatest(seq, if (isStreaming) "Transmitindo…" else "Câmera alternada")
            } catch (t: Throwable) {
                runOnUiThread { setStatusIfLatest(seq, "Falha ao trocar câmera: ${t.message}") }
            }
        }
    }

    private fun collectCameraIds() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraIds = cm.cameraIdList.toList()
            val def = applicationContext.cameraManager.defaultCameraId
            cameraIndex = cameraIds.indexOf(def).coerceAtLeast(0)
        } catch (_: Exception) {
            cameraIds = emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceIp(): String {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            Formatter.formatIpAddress(wm.connectionInfo.ipAddress)
        } catch (_: Exception) {
            "desconhecido"
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }

    private fun setStatusIfLatest(seq: Long, msg: String) {
        if (seq == statusSeq) setStatus(msg)
    }

    // ---------- Memória ----------

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Sistema pedindo RAM de volta no meio da live: baixa o teto um
        // degrau em vez de deixar o processo morrer.
        if (isStreaming && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val s = streamer ?: return
            thermalCeiling = (effectiveCeiling() * 0.7).toInt().coerceAtLeast(1_000_000)
            applyCeiling(s, null)
        }
    }

    override fun onDestroy() {
        userStopped = true
        connectJob?.cancel()
        reconnectJob?.cancel()
        statsJob?.cancel()
        collectJob?.cancel()
        scanJob?.cancel()
        saveFields()
        try {
            StreamKeepAliveService.stop(this)
        } catch (_: Throwable) {
        }
        // Libera câmera, encoder e endpoint fora do main thread. Usa
        // escopo próprio porque o lifecycleScope já está destruído aqui.
        val s = streamer
        streamer = null
        if (s != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    s.stopStream()
                } catch (_: Throwable) {
                }
                try {
                    s.release()
                } catch (_: Throwable) {
                }
            }
        }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        // Rede de segurança: se a live estava armada e caiu enquanto o
        // app estava fora da frente, retoma sozinho ao voltar.
        if (!userStopped && lastDescriptor != null && !isStreaming && streamer != null) {
            scheduleReconnect("Retomando transmissão")
        }
    }

    private fun registerBackHandler() {
        // BACK com live no ar minimiza em vez de destruir: a live
        // continua no serviço em 1º plano. Sem isso, um toque
        // acidental no meio de 2h de transmissão derruba tudo.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (fullscreen) {
                        toggleFullscreen()
                        return
                    }
                    if (isStreaming) {
                        Snackbar.make(
                            rootView,
                            R.string.snackbar_background_stream,
                            Snackbar.LENGTH_LONG
                        ).show()
                        moveTaskToBack(true)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }
}
