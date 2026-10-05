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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as MaterialR
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

private const val SCAN_CONCURRENCY = 64
private const val LIVENESS_PORT = 59999
private const val LIVENESS_TIMEOUT_MS = 350
private const val SWEEP_PROBE_TIMEOUT_MS = 300
private const val HOST_PROBE_TIMEOUT_MS = 350
private const val CONFIRM_TIMEOUT_MS = 600
private const val CONFIRM_ATTEMPTS = 2
private const val MAX_SNIFF_TARGETS = 600

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
 * - Procura de SRT por porta/faixa, sniff da rede com IP vazio
 *   (IPv4 + IPv6, portas 9990-9999), estado visual persistido,
 *   inputs travados no ar e guarda contra toque duplo no Iniciar.
 */
class MainActivity : ComponentActivity() {

    private data class Quality(
        val label: String,
        val desc: String?,
        val size: Size,
        val fps: Int,
        val defaultBitrateMbps: Int
    ) {
        override fun toString(): String = label
    }

    private val qualities = listOf(
        Quality("1080p30 Estável", "Recomendado para 2h", Size(1920, 1080), 30, 8),
        Quality("720p30 Leve", "Rede fraca, menos calor", Size(1280, 720), 30, 4),
        Quality("720p60 Médio", "60 fps, calor médio", Size(1280, 720), 60, 6),
        Quality("1080p60 Alto", "Esquenta mais", Size(1920, 1080), 60, 12)
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
    private lateinit var zoomSlider: Slider
    private lateinit var zoomLabel: TextView
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
    private var zoomJob: Job? = null

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
    private data class StreamTarget(val host: String, val port: Int, val latency: Int)

    private var lastTarget: StreamTarget? = null
    private var activeRelay: SrtRelay? = null
    private var relayedNow = false

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
        zoomSlider = findViewById(R.id.zoomSlider)
        zoomLabel = findViewById(R.id.zoomLabel)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        switchButton = findViewById(R.id.switchButton)
        previewSwitch = findViewById(R.id.previewSwitch)
        dimSwitch = findViewById(R.id.dimSwitch)
        batteryButton = findViewById(R.id.batteryButton)
        scanButton = findViewById(R.id.scanButton)

        restoreFields()
        setupQualityDropdown()
        setupZoom()

        localIpText.text =
            "IP deste celular: ${deviceIp()} (IP do PC vazio varre a rede)"
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
        // Linha do menu em 2 linhas (título + detalhe pequeno); o campo
        // mostra só o título curto para não quebrar o layout.
        val adapter = object : ArrayAdapter<Quality>(
            this, R.layout.list_item_dropdown_selected, qualities
        ) {
            override fun getView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup
            ): android.view.View {
                val v = convertView ?: layoutInflater.inflate(
                    R.layout.list_item_dropdown_selected, parent, false
                )
                (v as TextView).text = getItem(position)?.label
                return v
            }

            override fun getDropDownView(
                position: Int,
                convertView: android.view.View?,
                parent: android.view.ViewGroup
            ): android.view.View {
                val v = convertView ?: layoutInflater.inflate(
                    R.layout.list_item_dropdown, parent, false
                )
                val q = getItem(position)
                v.findViewById<TextView>(R.id.dropdownTitle).text = q?.label
                val desc = v.findViewById<TextView>(R.id.dropdownDesc)
                if (q?.desc.isNullOrEmpty()) {
                    desc.visibility = android.view.View.GONE
                } else {
                    desc.visibility = android.view.View.VISIBLE
                    desc.text = q?.desc
                }
                return v
            }
        }
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

    private data class SniffTarget(val label: String, val addr: InetAddress)

    /**
     * Com IP digitado, sonda só aquele host (todos os endereços IPv4 e
     * IPv6 que o nome resolver). Com o campo vazio, varre a rede local:
     * o /24 de cada IPv4 próprio mais os candidatos IPv6 (gateway, DNS
     * e vizinhos), nas portas 9990-9999. SRT roda sobre UDP, que não
     * tem handshake de "porta aberta": a sonda envia 1 byte e observa.
     * Porta que responde com erro ICMP (PortUnreachable) está fechada;
     * as demais são candidatas e passam por confirmação de handshake
     * SRT, que lista só quem responde como SRT de verdade.
     */
    private fun scanSrtPorts() {
        if (scanJob?.isActive == true) return
        val host = hostInput.text.toString().trim()
        if (host.isEmpty()) {
            scanNetwork()
        } else {
            scanHost(host)
        }
    }

    private fun scanHost(host: String) {
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
                val bare = SrtPorts.bareHost(host)
                val addrs = try {
                    InetAddress.getAllByName(bare).toList()
                } catch (_: Throwable) {
                    emptyList()
                }
                if (addrs.isEmpty()) {
                    runOnUiThread {
                        if (!isStreaming && !connecting) scanButton.isEnabled = true
                        setStatusIfLatest(seq, "Não resolveu $host. Confira o IP ou nome.")
                    }
                    return@launch
                }
                val sem = Semaphore(SCAN_CONCURRENCY)
                val candidates = ports.map { port ->
                    async {
                        sem.withPermit {
                            port.takeIf { p ->
                                addrs.any {
                                    udpProbeAddress(withScope(it), p, HOST_PROBE_TIMEOUT_MS)
                                }
                            }
                        }
                    }
                }.awaitAll().filterNotNull().sorted()
                if (candidates.isEmpty()) {
                    runOnUiThread {
                        if (!isStreaming && !connecting) scanButton.isEnabled = true
                        setStatusIfLatest(
                            seq,
                            "Nenhum SRT achado em $host. Confira o IP e a " +
                                "porta listener no OBS."
                        )
                    }
                    return@launch
                }
                runOnUiThread { setStatusIfLatest(seq, "Confirmando SRT…") }
                val confirmed = candidates.map { port ->
                    async {
                        sem.withPermit {
                            port.takeIf { p ->
                                addrs.any { confirmSrt(withScope(it), p) }
                            }
                        }
                    }
                }.awaitAll().filterNotNull().sorted()
                runOnUiThread {
                    if (!isStreaming && !connecting) scanButton.isEnabled = true
                    if (confirmed.isNotEmpty()) {
                        setStatusIfLatest(
                            seq,
                            "SRT confirmado: ${confirmed.joinToString()} " +
                                "(toque para usar)."
                        )
                        if (seq == statusSeq) {
                            showScanResults(confirmed.map { bare to it })
                        }
                    } else {
                        setStatusIfLatest(
                            seq,
                            "Sem resposta SRT; candidatos UDP: " +
                                "${candidates.joinToString()} (toque para usar)."
                        )
                        if (seq == statusSeq) {
                            showScanResults(candidates.map { bare to it })
                        }
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

    /**
     * Sniff da rede local. Primeiro testa quem está vivo com 1 sonda
     * UDP numa porta fechada (host vivo devolve ICMP PortUnreachable;
     * morto some, senão todo endereço morto viraria falso positivo).
     * Depois sonda as portas SRT só nos vivos, em IPv4 e IPv6.
     */
    private fun scanNetwork() {
        val ports = SrtPorts.parseNetworkScanPorts(portInput.text.toString().trim())
        if (ports.isEmpty()) {
            setStatus("Nada para procurar")
            return
        }
        val seq = ++statusSeq
        setStatus("Varrendo a rede (${ports.size} portas)…")
        scanButton.isEnabled = false
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val targets = collectSniffTargets()
                if (targets.isEmpty()) {
                    runOnUiThread {
                        if (!isStreaming && !connecting) scanButton.isEnabled = true
                        setStatusIfLatest(
                            seq, "Sem rede local para varrer. Confira o Wi-Fi."
                        )
                    }
                    return@launch
                }
                runOnUiThread {
                    setStatusIfLatest(seq, "Varrendo ${targets.size} endereços…")
                }
                val sem = Semaphore(SCAN_CONCURRENCY)
                val alive = targets.map { t ->
                    async {
                        sem.withPermit { t.takeIf { udpLiveness(it.addr) } }
                    }
                }.awaitAll().filterNotNull()
                if (alive.isEmpty()) {
                    runOnUiThread {
                        if (!isStreaming && !connecting) scanButton.isEnabled = true
                        setStatusIfLatest(
                            seq, "Nenhum aparelho respondeu na rede Wi-Fi."
                        )
                    }
                    return@launch
                }
                val candidates = alive.flatMap { t -> ports.map { p -> t to p } }.map { (t, p) ->
                    async {
                        sem.withPermit {
                            (t to p).takeIf { udpProbeAddress(t.addr, p, SWEEP_PROBE_TIMEOUT_MS) }
                        }
                    }
                }.awaitAll().filterNotNull()
                    .sortedWith(compareBy({ it.first.label }, { it.second }))
                if (candidates.isEmpty()) {
                    runOnUiThread {
                        if (!isStreaming && !connecting) scanButton.isEnabled = true
                        setStatusIfLatest(
                            seq,
                            "Nenhum SRT achado na rede. Confira o listener no OBS."
                        )
                    }
                    return@launch
                }
                runOnUiThread { setStatusIfLatest(seq, "Confirmando SRT…") }
                val confirmed = candidates.map { (t, p) ->
                    async {
                        sem.withPermit {
                            (t to p).takeIf { confirmSrt(t.addr, p) }
                        }
                    }
                }.awaitAll().filterNotNull()
                runOnUiThread {
                    if (!isStreaming && !connecting) scanButton.isEnabled = true
                    if (confirmed.isNotEmpty()) {
                        setStatusIfLatest(
                            seq, "Achados ${confirmed.size} SRT (toque para usar)."
                        )
                        if (seq == statusSeq) {
                            showScanResults(confirmed.map { it.first.label to it.second })
                        }
                    } else {
                        setStatusIfLatest(
                            seq,
                            "Sem resposta SRT; ${candidates.size} candidatos " +
                                "UDP (toque para usar)."
                        )
                        if (seq == statusSeq) {
                            showScanResults(candidates.map { it.first.label to it.second })
                        }
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

    private fun socketFor(addr: InetAddress): DatagramSocket =
        if (addr is Inet6Address) {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("::"), 0))
        } else {
            DatagramSocket()
        }

    /** Sonda UDP: timeout ou dado = candidata; ICMP fechada = não. */
    private fun udpProbeAddress(addr: InetAddress, port: Int, timeoutMs: Int): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = socketFor(addr)
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(addr, port))
            socket.send(DatagramPacket(ByteArray(1), 1))
            try {
                val buf = ByteArray(64)
                socket.receive(DatagramPacket(buf, buf.size))
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

    /**
     * Confirma SRT de verdade: envia pedido de handshake e exige
     * resposta ecoando ISN e socket id com cookie não zero. Elimina
     * as duplicadas de porta filtrada, que passam na sonda UDP.
     */
    private fun confirmSrt(addr: InetAddress, port: Int): Boolean {
        val isn = Random.nextInt(0, Int.MAX_VALUE)
        val sid = Random.nextInt(1, Int.MAX_VALUE)
        val probe = SrtHandshake.buildProbe(
            isn, sid, (SystemClock.elapsedRealtime() and 0xFFFFFFFFL).toInt()
        )
        var socket: DatagramSocket? = null
        return try {
            socket = socketFor(addr)
            socket.soTimeout = CONFIRM_TIMEOUT_MS
            socket.connect(InetSocketAddress(addr, port))
            repeat(CONFIRM_ATTEMPTS) {
                try {
                    socket.send(DatagramPacket(probe, probe.size))
                    val buf = ByteArray(512)
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    if (SrtHandshake.isHandshakeReply(buf, pkt.length, isn, sid)) return true
                } catch (_: java.net.SocketTimeoutException) {
                    // sem resposta: tenta de novo
                } catch (_: java.net.PortUnreachableException) {
                    return false
                }
            }
            false
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

    /** Host vivo devolve ICMP numa porta fechada; morto não responde. */
    private fun udpLiveness(addr: InetAddress): Boolean {
        var socket: DatagramSocket? = null
        return try {
            socket = socketFor(addr)
            socket.soTimeout = LIVENESS_TIMEOUT_MS
            socket.connect(InetSocketAddress(addr, LIVENESS_PORT))
            socket.send(DatagramPacket(ByteArray(1), 1))
            try {
                val buf = ByteArray(64)
                socket.receive(DatagramPacket(buf, buf.size))
                true
            } catch (_: java.net.SocketTimeoutException) {
                false
            } catch (_: java.net.PortUnreachableException) {
                true
            }
        } catch (_: java.net.PortUnreachableException) {
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                socket?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Link-local (fe80::/10) sem escopo não roteia: amarra o endereço
     * à interface Wi-Fi para a sonda sair pela rede certa.
     */
    private fun withScope(addr: InetAddress): InetAddress {
        if (addr !is Inet6Address) return addr
        if (!addr.isLinkLocalAddress || addr.scopeId != 0) return addr
        return try {
            val nif = outboundInterface() ?: linkLocalInterface()
            if (nif != null) Inet6Address.getByAddress(null, addr.address, nif) else addr
        } catch (_: Throwable) {
            addr
        }
    }

    /** Interface da rede ativa (o Wi-Fi, não os dados móveis). */
    private fun outboundInterface(): NetworkInterface? {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val name = try {
                cm.getLinkProperties(cm.activeNetwork)?.interfaceName
            } catch (_: Throwable) {
                null
            }
            if (name != null) {
                try {
                    NetworkInterface.getByName(name)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Reserva: interface com link-local, preferindo Wi-Fi e cabo. */
    private fun linkLocalInterface(): NetworkInterface? {
        return try {
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { n ->
                    try {
                        n.isUp && !n.isLoopback
                    } catch (_: Throwable) {
                        false
                    }
                }
            fun score(n: NetworkInterface): Int {
                val name = try {
                    n.name
                } catch (_: Throwable) {
                    ""
                }
                return SrtPorts.interfaceNameScore(name)
            }
            ifs.filter { n ->
                try {
                    n.inetAddresses.toList().any {
                        it is Inet6Address && it.isLinkLocalAddress
                    }
                } catch (_: Throwable) {
                    false
                }
            }.minByOrNull(::score) ?: ifs.minByOrNull(::score)
        } catch (_: Throwable) {
            null
        }
    }

    private fun linkPropertiesList(): List<LinkProperties> {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val out = mutableListOf<LinkProperties>()
            try {
                cm.getLinkProperties(cm.activeNetwork)?.let { out += it }
            } catch (_: Throwable) {
            }
            try {
                for (n in cm.allNetworks) {
                    try {
                        cm.getLinkProperties(n)?.let { out += it }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Vizinhos IPv4 com ARP resolvido (o Mac aparece aqui). */
    private fun readArpTable(): List<String> {
        return try {
            java.io.File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 6) return@mapNotNull null
                if (cols[5] == "lo" || cols[2] == "0x0") return@mapNotNull null
                cols[0].takeIf { it.contains('.') }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Vizinhos IPv4/IPv6 do cache do sistema (best-effort). */
    private fun readIpNeigh(): List<String> {
        return try {
            val proc = try {
                ProcessBuilder("ip", "neigh", "show")
                    .redirectErrorStream(true).start()
            } catch (_: Throwable) {
                ProcessBuilder("/system/bin/ip", "neigh", "show")
                    .redirectErrorStream(true).start()
            }
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                try {
                    proc.destroy()
                } catch (_: Throwable) {
                }
                return emptyList()
            }
            proc.inputStream.bufferedReader().readLines().mapNotNull { line ->
                if (line.contains("FAILED")) return@mapNotNull null
                val ip = line.trim().split(Regex("\\s+")).firstOrNull()
                    ?: return@mapNotNull null
                ip.takeIf { it.contains('.') || it.contains(':') }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Alvos do sniff: o /24 de cada IPv4 local privado (é onde o
     * compartilhamento do Mac coloca o celular) mais gateway, DNS e
     * vizinhos, que é onde o IPv6 do Mac aparece (varrer IPv6 na força
     * bruta é inviável: o espaço é grande demais).
     */
    private fun collectSniffTargets(): List<SniffTarget> {
        val targets = LinkedHashMap<String, SniffTarget>()
        val ownKeys = mutableSetOf<String>()
        fun keyOf(label: String) = label.substringBefore('%').lowercase()
        fun addAddr(a0: InetAddress) {
            var addr = a0
            if (addr.isLoopbackAddress || addr.isMulticastAddress ||
                addr.isAnyLocalAddress
            ) {
                return
            }
            addr = withScope(addr)
            val label = try {
                addr.hostAddress ?: return
            } catch (_: Throwable) {
                return
            }
            val key = keyOf(label)
            if (!targets.containsKey(key)) targets[key] = SniffTarget(label, addr)
        }
        fun add(s: String) {
            try {
                addAddr(InetAddress.getByName(SrtPorts.bareHost(s)))
            } catch (_: Throwable) {
            }
        }
        val ownV4 = mutableListOf<String>()
        try {
            for (lp in linkPropertiesList()) {
                for (la in lp.linkAddresses) {
                    val a = la.address ?: continue
                    if (a.isLoopbackAddress) continue
                    try {
                        a.hostAddress?.let { ownKeys += keyOf(it) }
                    } catch (_: Throwable) {
                    }
                    if (a is Inet4Address) {
                        try {
                            a.hostAddress?.let { ownV4 += it }
                        } catch (_: Throwable) {
                        }
                    }
                }
                for (r in lp.routes) {
                    try {
                        r.gateway?.let { addAddr(it) }
                    } catch (_: Throwable) {
                    }
                }
                for (d in lp.dnsServers) {
                    try {
                        addAddr(d)
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }
        if (ownV4.isEmpty()) {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                for (nif in ifs) {
                    try {
                        if (!nif.isUp || nif.isLoopback) continue
                    } catch (_: Throwable) {
                        continue
                    }
                    for (addr in nif.inetAddresses.toList()) {
                        if (addr.isLoopbackAddress) continue
                        try {
                            addr.hostAddress?.let { ownKeys += keyOf(it) }
                        } catch (_: Throwable) {
                        }
                        if (addr is Inet4Address) {
                            try {
                                addr.hostAddress?.let { ownV4 += it }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        for (v4 in ownV4.distinct()) {
            if (SrtPorts.isSweepableIpv4(v4)) {
                for (h in SrtPorts.ipv4SweepHosts(v4)) add(h)
            }
        }
        for (n in (readArpTable() + readIpNeigh()).distinct()) add(n)
        return targets.values.filter { keyOf(it.label) !in ownKeys }.take(MAX_SNIFF_TARGETS)
    }

    private fun showScanResults(results: List<Pair<String, Int>>) {
        val items = results.map { (h, p) -> "${SrtPorts.formatSrtHost(h)}:$p" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_scan_title)
            .setItems(items) { _, which ->
                val (h, p) = results[which]
                hostInput.setText(h)
                portInput.setText(p.toString())
                saveFields()
                setStatus("Selecionado ${items[which]}. Toque em Iniciar.")
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
                    refreshZoomRange()
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
                        if (isStreaming) setStatus("Erro: ${SrtErrors.describe(t?.message)}")
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
        val target = StreamTarget(host, port, latency)
        lastTarget = target
        connectNow(s, target, effectiveCeiling())
    }

    private fun connectNow(s: SingleStreamer, target: StreamTarget, maxBitrate: Int) {
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
                stopRelay()
                // Resolve e sobe relay (se preciso) fora da main thread.
                val (descriptor, relayed) = withContext(Dispatchers.IO) {
                    buildRoutedDescriptor(target)
                }
                relayedNow = relayed
                setStatusIfLatest(
                    seq, if (relayed) "Conectando via IPv6…" else "Conectando…"
                )
                // Via relay, mostra pacotes enviados/recebidos enquanto
                // conecta: ↑ sobe e ↓ parado = a resposta não volta
                // (listener, firewall ou endereço errado).
                val monitor = if (relayed) launch {
                    while (isActive) {
                        delay(2000)
                        val counters = relayCounters()
                        if (counters.isNotEmpty()) {
                            runOnUiThread {
                                setStatusIfLatest(seq, "Conectando via IPv6… $counters")
                            }
                        }
                    }
                } else null
                try {
                    s.startStream(descriptor)
                } finally {
                    monitor?.cancel()
                }
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
                setStatusIfLatest(seq, streamingStatus())
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) {
                    stopRelay()
                    throw t
                }
                connecting = false
                stopRelay()
                runOnUiThread {
                    if (!userStopped) {
                        scheduleReconnect("Falha ao iniciar: ${SrtErrors.describe(t.message)}")
                    } else {
                        setStatusIfLatest(seq, "Conexão cancelada")
                        updateStreamButtons()
                        setInputsEnabled(true)
                    }
                }
            }
        }
    }

    /**
     * Descritor efetivo da tentativa: direto quando há IPv4; via relay
     * local (127.0.0.1) quando o alvo é só IPv6, que a biblioteca SRT
     * não disca. Roda fora da main thread (resolve DNS e binda porta).
     */
    private fun buildRoutedDescriptor(t: StreamTarget): Pair<SrtMediaDescriptor, Boolean> {
        return when (val route = SrtPorts.routeFor(t.host)) {
            is SrtPorts.StreamRoute.Direct -> Pair(
                SrtMediaDescriptor(
                    "srt://${SrtPorts.formatSrtHost(route.hostForUrl)}:${t.port}" +
                        "?latency=${t.latency}&connect_timeout=15000"
                ),
                false
            )

            is SrtPorts.StreamRoute.Relay -> {
                val relay = SrtRelay(InetSocketAddress(withScope(route.addr), t.port))
                val localPort = relay.start()
                activeRelay = relay
                Pair(
                    SrtMediaDescriptor(
                        "srt://127.0.0.1:$localPort?latency=${t.latency}&connect_timeout=15000"
                    ),
                    true
                )
            }
        }
    }

    private fun stopRelay() {
        try {
            activeRelay?.stop()
        } catch (_: Throwable) {
        }
        activeRelay = null
        relayedNow = false
    }

    private fun streamingStatus(): String =
        if (relayedNow) "Transmitindo via IPv6…" else "Transmitindo…"

    /** Contadores do relay para diagnóstico: "(↑12 ↓0)". Vazio sem relay. */
    private fun relayCounters(): String {
        val r = activeRelay ?: return ""
        return "(↑${r.forwardedToTarget} ↓${r.forwardedToClient})"
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
                stopRelay()
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
        val target = lastTarget ?: return
        reconnectJob?.cancel()
        reconnectAttempt++
        if (reconnectAttempt > 240) {
            // ~2h de tentativas com teto de 30s: desiste e avisa.
            connecting = false
            stopRelay()
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
        val counters = relayCounters()
        val suffix = if (counters.isEmpty()) "" else " $counters"
        setStatus("$reason. Reconectando em ${delayS}s (tentativa $reconnectAttempt)…$suffix")
        reconnectJob = lifecycleScope.launch {
            delay(delayS * 1000)
            if (!isActive || userStopped || isStreaming) return@launch
            // Garante encoder parado antes de reconectar.
            try {
                withTimeoutOrNull(4000) { s.stopStream() }
            } catch (_: Throwable) {
            }
            connectNow(s, target, effectiveCeiling())
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
                val relay = relayCounters()
                    .takeIf { relayedNow && it.isNotEmpty() }?.let { " • relay $it" } ?: ""
                val line = String.format(
                    "%02d:%02d:%02d • %dx%d@%d • teto %d Mbps • %s%s%s",
                    h, m, sec, videoSize.width, videoSize.height, videoFps,
                    effectiveCeiling() / 1_000_000, thermalLabel(), mem, relay
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

    /**
     * Zoom para ajuste do enquadramento: o slider aplica o zoom da
     * câmera (vale ao vivo, com ou sem stream) e a pinça no preview
     * continua funcionando, com o slider acompanhando. O último valor
     * é salvo e reaplicado ao abrir ou trocar de câmera.
     */
    private fun setupZoom() {
        // Contínuo de propósito: o máximo varia por câmera (ex. 12.93) e
        // o Slider derruba o app se o passo não dividir a faixa exata.
        zoomSlider.stepSize = 0f
        zoomSlider.isEnabled = false
        zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) applyZoom(value)
        }
        preview.listener = object : PreviewView.Listener {
            override fun onZoomRationOnPinchChanged(zoomRatio: Float) {
                syncZoomUi(zoomRatio)
                prefs.edit().putFloat("zoom", zoomRatio).apply()
            }
        }
    }

    private fun formatZoom(ratio: Float): String =
        String.format("%.1fx", ratio)

    /** Reflete o zoom atual no slider e no rótulo, sem reaplicar. */
    private fun syncZoomUi(ratio: Float) {
        try {
            zoomSlider.value =
                ratio.coerceIn(zoomSlider.valueFrom, zoomSlider.valueTo)
        } catch (_: Throwable) {
        }
        zoomLabel.text = formatZoom(ratio)
    }

    /** Aplica o zoom pedido no slider (cancela o anterior). */
    private fun applyZoom(ratio: Float) {
        zoomLabel.text = formatZoom(ratio)
        zoomJob?.cancel()
        zoomJob = lifecycleScope.launch {
            try {
                val source = streamer?.videoInput?.sourceFlow?.value
                    as? ICameraSource ?: return@launch
                source.settings.zoom.setZoomRatio(ratio)
                prefs.edit().putFloat("zoom", ratio).apply()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Lê a faixa de zoom da câmera atual, ajusta o slider e reaplica
     * o último zoom salvo. Chamar ao abrir e ao trocar de câmera.
     */
    private fun refreshZoomRange() {
        lifecycleScope.launch {
            val source = streamer?.videoInput?.sourceFlow?.value
                as? ICameraSource ?: return@launch
            val range = try {
                source.settings.zoom.availableRatioRange
            } catch (_: Throwable) {
                return@launch
            }
            val saved = try {
                prefs.getFloat("zoom", 1f).coerceIn(range.lower, range.upper)
            } catch (_: Throwable) {
                1f
            }
            try {
                source.settings.zoom.setZoomRatio(saved)
            } catch (_: Throwable) {
            }
            // O Slider exige mínimo < máximo: sem zoom na câmera, usa
            // faixa fictícia com o controle desligado.
            val hasZoom = range.upper > range.lower
            val from = if (hasZoom) range.lower else 1f
            val to = if (hasZoom) range.upper else 1.1f
            try {
                // Alarga antes de mexer, para nenhum passo intermediário
                // sair da faixa e lançar IllegalArgumentException.
                zoomSlider.valueFrom = minOf(zoomSlider.valueFrom, from)
                zoomSlider.valueTo = maxOf(zoomSlider.valueTo, to)
                zoomSlider.value = if (hasZoom) saved else from
                zoomSlider.valueFrom = from
                zoomSlider.valueTo = to
                zoomSlider.isEnabled = hasZoom
            } catch (_: Throwable) {
                zoomSlider.isEnabled = false
            }
            zoomLabel.text = formatZoom(saved)
        }
    }

    private fun switchCamera() {
        val s = streamer ?: return
        if (cameraIds.isEmpty()) return
        cameraIndex = (cameraIndex + 1) % cameraIds.size
        val seq = ++statusSeq
        lifecycleScope.launch {
            try {
                s.setVideoSource(CameraSourceFactory(cameraIds[cameraIndex]))
                refreshZoomRange()
                setStatusIfLatest(seq, if (isStreaming) streamingStatus() else "Câmera alternada")
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

    /** IPs do aparelho (IPv4 + IPv6), sem precisar de permissão extra. */
    private fun deviceIp(): String {
        return try {
            var v4: String? = null
            var v6: String? = null
            var v6Link: String? = null
            val ifs = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (nif in ifs) {
                try {
                    if (!nif.isUp || nif.isLoopback) continue
                } catch (_: Throwable) {
                    continue
                }
                for (addr in nif.inetAddresses.toList()) {
                    if (addr.isLoopbackAddress || addr.isMulticastAddress) continue
                    when {
                        addr is Inet4Address && v4 == null -> v4 = addr.hostAddress
                        addr is Inet6Address && !addr.isLinkLocalAddress && v6 == null ->
                            v6 = addr.hostAddress?.substringBefore('%')

                        addr is Inet6Address && addr.isLinkLocalAddress && v6Link == null ->
                            v6Link = addr.hostAddress?.substringBefore('%')
                    }
                }
            }
            listOfNotNull(v4, v6 ?: v6Link).joinToString(" • ").ifEmpty { "desconhecido" }
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
        stopRelay()
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
        if (!userStopped && lastTarget != null && !isStreaming && streamer != null) {
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
