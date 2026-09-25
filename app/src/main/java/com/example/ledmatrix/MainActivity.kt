package com.example.ledmatrix

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    companion object {
        private const val SOCKET_URL = "ws://192.168.4.1:81/"
        private const val HEARTBEAT_MS = 400L
        private const val RECONNECT_MS = 1500L
        private const val SEND_INTERVAL_MS = 10L
    }

    private lateinit var matrixView: MatrixView
    private lateinit var animationGrid: AnimationGridView
    private lateinit var statusText: TextView
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var drawingLayout: View
    private lateinit var animationLayout: View

    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var connected = false

    private var wifiNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var httpClient: OkHttpClient? = null
    private var socket: WebSocket? = null

    private var networkGeneration = 0L
    private var socketGeneration = 0L

    private val pendingBits = ByteArray(8)
    private val lastSentBits = ByteArray(8)
    private var forceSend = false

    private val frames = mutableListOf<Frame>()
    private var currentFrameIndex = -1
    private var isPlaying = false

    private var playSequence = listOf<Int>()
    private var playPos = 0

    // Жёсткий таймлайн для воспроизведения.
    // postAtTime срабатывает точно в заданное время,
    // компенсируя задержки выполнения кода и Wi-Fi.
    private var nextPlayTime = 0L

    private val reconnectTask = Runnable {
        if (started && wifiNetwork != null) {
            connectSocket()
        }
    }

    private val sendTask = object : Runnable {
        override fun run() {
            if (!started || !connected) return
            if (isPlaying) return

            val currentSocket = socket ?: return

            val bits = ByteArray(8)
            for (i in 0 until 8) bits[i] = pendingBits[i]

            val cell = matrixView.activeCell
            if (cell in 0 until 64) {
                val row = cell / 8
                val col = cell % 8
                bits[row] = (bits[row].toInt() or (1 shl col)).toByte()
            }

            for (i in 0 until 8) pendingBits[i] = 0

            var changed = forceSend
            if (!changed) {
                for (i in 0 until 8) {
                    if (bits[i] != lastSentBits[i]) { changed = true; break }
                }
            }

            if (changed) {
                for (i in 0 until 8) lastSentBits[i] = bits[i]
                forceSend = false

                val packet = ByteArray(9)
                packet[0] = 'S'.code.toByte()
                for (i in 0 until 8) packet[1 + i] = bits[i]

                val accepted = currentSocket.send(packet.toByteString())
                if (!accepted) {
                    connectionFailed(socketGeneration, "Не удалось отправить состояние.")
                    return
                }
            }

            if (started && connected && !isPlaying) {
                handler.postDelayed(this, SEND_INTERVAL_MS)
            }
        }
    }

    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (!started || !connected) return
            forceSend = true
            if (started && connected) {
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    private val playTask = object : Runnable {
        override fun run() {
            if (!started || !connected || !isPlaying || playSequence.isEmpty()) {
                stopPlayback()
                return
            }

            // Инициализируем таймлайн при первом запуске
            if (nextPlayTime == 0L) {
                nextPlayTime = SystemClock.uptimeMillis()
            }

            val frameIdx = playSequence[playPos]
            sendAnimationFrame(frameIdx)

            showStatus("Воспроизведение: кадр ${frameIdx + 1}/${frames.size} (${playPos + 1}/${playSequence.size})")

            playPos++

            val durationInput = findViewById<EditText>(R.id.durationInput)
            val duration = durationInput.text.toString().toIntOrNull() ?: 200

            if (playPos >= playSequence.size) {
                playPos = 0
                val delayInput = findViewById<EditText>(R.id.delayInput)
                val delay = delayInput.text.toString().toIntOrNull() ?: 1000
                nextPlayTime += delay.toLong()
            } else {
                nextPlayTime += duration.toLong()
            }

            // Планируем строго по таймлайну, а не "через N мс".
            // Если текущий кадр отправился с задержкой,
            // следующий уйдёт раньше, выравнивая ритм.
            handler.postAtTime(this, nextPlayTime)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        matrixView = findViewById(R.id.matrixView)
        animationGrid = findViewById(R.id.animationGridView)
        statusText = findViewById(R.id.statusText)
        drawingLayout = findViewById(R.id.drawingLayout)
        animationLayout = findViewById(R.id.animationLayout)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        matrixView.isEnabled = false

        matrixView.onCellChanged = {
            val cell = matrixView.activeCell
            if (cell in 0 until 64) {
                val row = cell / 8
                val col = cell % 8
                pendingBits[row] = (pendingBits[row].toInt() or (1 shl col)).toByte()
            }
            forceSend = true
        }

        findViewById<Button>(R.id.tabDrawing).setOnClickListener {
            drawingLayout.visibility = View.VISIBLE
            animationLayout.visibility = View.GONE
            stopPlayback()

            sendClearCommand()

            for (i in 0 until 8) {
                pendingBits[i] = 0
                lastSentBits[i] = 0
            }
            forceSend = true
            matrixView.clearTouch()

            if (connected && started) {
                handler.removeCallbacks(sendTask)
                handler.postDelayed(sendTask, SEND_INTERVAL_MS)
            }

            showStatus("Режим рисования.")
        }

        findViewById<Button>(R.id.tabAnimation).setOnClickListener {
            drawingLayout.visibility = View.GONE
            animationLayout.visibility = View.VISIBLE
            stopPlayback()

            handler.removeCallbacks(sendTask)

            sendClearCommand()

            if (frames.isEmpty()) {
                frames.add(Frame())
                currentFrameIndex = 0
            }
            showStatus("Режим анимации. Нарисуйте кадр.")
        }

        findViewById<Button>(R.id.reconnectButton).setOnClickListener {
            if (wifiNetwork != null) {
                connectSocket()
            } else {
                showStatus("Нет доступной Wi-Fi-сети. Подключитесь к LEDS.")
            }
        }

        findViewById<Button>(R.id.colorButton).setOnClickListener {
            showColorPicker()
        }

        findViewById<Button>(R.id.eraserButton).setOnClickListener {
            animationGrid.setErasing(true)
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            animationGrid.clearGrid()
        }

        findViewById<Button>(R.id.saveFrameButton).setOnClickListener {
            if (currentFrameIndex in frames.indices) {
                frames[currentFrameIndex] = animationGrid.getCurrentFrame()
            } else {
                frames.add(animationGrid.getCurrentFrame())
            }

            frames.add(Frame())
            currentFrameIndex = frames.size - 1
            animationGrid.clearGrid()

            val nonEmpty = frames.count { it.colors.any { it != 0 } }
            showStatus("Кадр сохранён. Всего: ${frames.size} (непустых: $nonEmpty). Редактируется кадр ${currentFrameIndex + 1}.")
        }

        findViewById<Button>(R.id.framesButton).setOnClickListener {
            showFramesDialog()
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (isPlaying) {
                stopPlayback()
                sendClearCommand()
            } else {
                startPlayback()
            }
        }
    }

    private fun showColorPicker() {
        val colors = intArrayOf(
            Color.RED, Color.parseColor("#FF6600"), Color.parseColor("#FFCC00"),
            Color.GREEN, Color.parseColor("#00CCFF"), Color.BLUE,
            Color.parseColor("#6633FF"), Color.MAGENTA, Color.WHITE,
            Color.parseColor("#FF6699"), Color.parseColor("#99FF66"), Color.parseColor("#66FFFF")
        )
        val names = arrayOf(
            "Красный", "Оранжевый", "Жёлтый",
            "Зелёный", "Голубой", "Синий",
            "Фиолетовый", "Пурпурный", "Белый",
            "Розовый", "Салатовый", "Бирюзовый"
        )

        AlertDialog.Builder(this)
            .setTitle("Выберите цвет")
            .setItems(names) { _: DialogInterface, which: Int ->
                animationGrid.setPaintColor(colors[which])
            }
            .show()
    }

    private fun showFramesDialog() {
        if (frames.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Кадры (0)")
                .setMessage("Список пуст. Нарисуйте кадр и нажмите «Сохранить».")
                .setPositiveButton("Новый кадр") { _, _ ->
                    frames.add(Frame())
                    currentFrameIndex = 0
                    animationGrid.clearGrid()
                    showStatus("Создан новый кадр 1")
                }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }

        val items = frames.indices.map { "Кадр ${it + 1}" }.toTypedArray()
        var selected = if (currentFrameIndex in frames.indices) currentFrameIndex else 0

        AlertDialog.Builder(this)
            .setTitle("Кадры (${frames.size})")
            .setSingleChoiceItems(items, selected) { _, which -> selected = which }
            .setPositiveButton("Закрыть") { _, _ ->
                if (selected in frames.indices) {
                    currentFrameIndex = selected
                    animationGrid.loadFrame(frames[selected])
                }
            }
            .setNeutralButton("Новый") { _, _ ->
                frames.add(Frame())
                currentFrameIndex = frames.size - 1
                animationGrid.clearGrid()
                showStatus("Создан новый кадр ${currentFrameIndex + 1}")
            }
            .setNegativeButton("Удалить") { _, _ ->
                if (selected in frames.indices) {
                    frames.removeAt(selected)
                    if (frames.isEmpty()) {
                        currentFrameIndex = -1
                        animationGrid.clearGrid()
                        showStatus("Все кадры удалены")
                    } else {
                        currentFrameIndex = (selected - 1).coerceAtLeast(0)
                        if (currentFrameIndex in frames.indices) {
                            animationGrid.loadFrame(frames[currentFrameIndex])
                        }
                        showStatus("Кадр удалён. Осталось: ${frames.size}")
                    }
                }
            }
            .show()
    }

    private fun startPlayback() {
        playSequence = frames.indices
            .filter { frames[it].colors.any { c -> c != 0 } }
            .toList()

        if (playSequence.isEmpty()) {
            showStatus("Нет непустых кадров для анимации")
            return
        }

        isPlaying = true
        playPos = 0
        nextPlayTime = 0L

        handler.removeCallbacks(playTask)
        handler.removeCallbacks(sendTask)
        handler.post(playTask)

        findViewById<Button>(R.id.startButton).text = "Стоп"
    }

    private fun stopPlayback() {
        isPlaying = false
        handler.removeCallbacks(playTask)
        nextPlayTime = 0L
        findViewById<Button>(R.id.startButton)?.text = "Старт"
    }

    private fun sendAnimationFrame(index: Int) {
        if (!started || !connected) return
        val currentSocket = socket ?: return
        if (index !in frames.indices) return

        val frame = frames[index]
        val packet = ByteArray(193)
        packet[0] = 'A'.code.toByte()

        for (i in 0 until 64) {
            val color = frame.colors[i]
            packet[1 + i * 3] = Color.red(color).toByte()
            packet[1 + i * 3 + 1] = Color.green(color).toByte()
            packet[1 + i * 3 + 2] = Color.blue(color).toByte()
        }

        if (!currentSocket.send(packet.toByteString())) {
            // Очередь отправки OkHttp переполнена — Wi-Fi не успевает.
            // Сбрасываем таймлайн, чтобы следующий кадр ушёл сразу
            // и мы не накапливали задержку.
            nextPlayTime = 0L
        }
    }

    private fun sendClearCommand() {
        if (!started || !connected) return
        val currentSocket = socket ?: return
        val packet = ByteArray(1)
        packet[0] = 'C'.code.toByte()
        currentSocket.send(packet.toByteString())
    }

    override fun onStart() {
        super.onStart()
        started = true
        startWifiRequest()
    }

    override fun onStop() {
        started = false
        networkGeneration++
        stopPlayback()
        dropSocket(graceful = true)

        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {}
        }

        networkCallback = null
        wifiNetwork = null
        super.onStop()
    }

    private fun showStatus(text: String) {
        statusText.text = text
    }

    private fun startWifiRequest() {
        val generation = ++networkGeneration
        showStatus("Ожидание Wi-Fi. Подключитесь к сети LEDS.")

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post {
                    if (!started || generation != networkGeneration) return@post
                    if (wifiNetwork != network) {
                        wifiNetwork = network
                        connectSocket()
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (!started || generation != networkGeneration) return@post
                    if (wifiNetwork == network) {
                        wifiNetwork = null
                        dropSocket()
                        showStatus("Wi-Fi потерян. Подключитесь к сети LEDS.")
                    }
                }
            }

            override fun onUnavailable() {
                handler.post {
                    if (!started || generation != networkGeneration) return@post
                    wifiNetwork = null
                    dropSocket()
                    showStatus("Wi-Fi недоступен. Проверьте подключение к LEDS.")
                }
            }
        }

        networkCallback = callback

        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (exception: RuntimeException) {
            networkCallback = null
            showStatus("Не удалось запросить Wi-Fi: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private fun connectSocket() {
        if (!started) return
        val network = wifiNetwork ?: return

        dropSocket(graceful = true)
        showStatus("Подключение к Wemos: 192.168.4.1…")

        val generation = socketGeneration

        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        httpClient = client

        val request = Request.Builder().url(SOCKET_URL).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handler.post {
                    if (!started || generation != socketGeneration) {
                        webSocket.cancel()
                        return@post
                    }

                    connected = true
                    matrixView.isEnabled = true
                    showStatus("Подключено к Wemos. Матрица готова.")

                    for (i in 0 until 8) {
                        pendingBits[i] = 0
                        lastSentBits[i] = 0
                    }
                    forceSend = true

                    handler.removeCallbacks(heartbeatTask)
                    handler.removeCallbacks(sendTask)

                    if (connected) {
                        handler.postDelayed(sendTask, SEND_INTERVAL_MS)
                        handler.postDelayed(heartbeatTask, HEARTBEAT_MS)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                handler.post { connectionFailed(generation, "Контроллер закрыл соединение.") }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { connectionFailed(generation, "Соединение закрыто.") }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                handler.post { connectionFailed(generation, "Нет связи с Wemos. Проверьте сеть LEDS.") }
            }
        }

        socket = client.newWebSocket(request, listener)
    }

    private fun connectionFailed(generation: Long, message: String) {
        if (!started || generation != socketGeneration) return

        dropSocket()

        if (wifiNetwork != null) {
            showStatus("$message Повторное подключение…")
            handler.postDelayed(reconnectTask, RECONNECT_MS)
        } else {
            showStatus("Подключитесь к Wi-Fi LEDS.")
        }
    }

    private fun dropSocket(graceful: Boolean = false) {
        socketGeneration++
        connected = false

        handler.removeCallbacks(heartbeatTask)
        handler.removeCallbacks(sendTask)
        handler.removeCallbacks(reconnectTask)

        val oldSocket = socket
        socket = null

        matrixView.clearTouch()
        matrixView.isEnabled = false

        for (i in 0 until 8) {
            pendingBits[i] = 0
            lastSentBits[i] = 0
        }
        forceSend = false

        if (oldSocket != null) {
            if (graceful) {
                val emptyPacket = ByteArray(9)
                emptyPacket[0] = 'S'.code.toByte()
                val sent = oldSocket.send(emptyPacket.toByteString())
                val closing = oldSocket.close(1000, "Leaving")
                if (!sent || !closing) oldSocket.cancel()
            } else {
                oldSocket.cancel()
            }
        }

        httpClient?.connectionPool?.evictAll()
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
    }
}
