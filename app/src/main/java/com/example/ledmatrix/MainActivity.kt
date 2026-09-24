package com.example.ledmatrix

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
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
    }

    private lateinit var matrixView: MatrixView
    private lateinit var statusText: TextView
    private lateinit var connectivityManager: ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var connected = false

    private var wifiNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var httpClient: OkHttpClient? = null
    private var socket: WebSocket? = null

    // Поколения нужны, чтобы игнорировать события устаревших соединений.
    private var networkGeneration = 0L
    private var socketGeneration = 0L

    private val reconnectTask = Runnable {
        if (started && wifiNetwork != null) {
            connectSocket()
        }
    }

    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (!started || !connected) {
                return
            }

            sendCurrentState()

            if (started && connected) {
                handler.postDelayed(this, HEARTBEAT_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        matrixView = findViewById(R.id.matrixView)
        statusText = findViewById(R.id.statusText)

        connectivityManager =
            getSystemService(ConnectivityManager::class.java)

        matrixView.isEnabled = false

        matrixView.onCellChanged = {
            sendCurrentState()
        }

        findViewById<Button>(R.id.reconnectButton).setOnClickListener {
            if (wifiNetwork != null) {
                connectSocket()
            } else {
                showStatus(
                    "Нет доступной Wi-Fi-сети. " +
                        "Подключитесь к LEDS в настройках телефона."
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        started = true
        startWifiRequest()
    }

    override fun onStop() {
        started = false
        networkGeneration++

        // Сначала отправляем отпускание всех ячеек, затем закрываем сокет.
        dropSocket(graceful = true)

        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Callback уже мог быть снят системой.
            }
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

        /*
         * Не требуем INTERNET или VALIDATED:
         * точка доступа Wemos работает без интернета.
         */
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                handler.post {
                    if (!started || generation != networkGeneration) {
                        return@post
                    }

                    if (wifiNetwork != network) {
                        wifiNetwork = network
                        connectSocket()
                    }
                }
            }

            override fun onLost(network: Network) {
                handler.post {
                    if (!started || generation != networkGeneration) {
                        return@post
                    }

                    if (wifiNetwork == network) {
                        wifiNetwork = null
                        dropSocket()

                        showStatus(
                            "Wi-Fi потерян. Подключитесь к сети LEDS."
                        )
                    }
                }
            }

            override fun onUnavailable() {
                handler.post {
                    if (!started || generation != networkGeneration) {
                        return@post
                    }

                    wifiNetwork = null
                    dropSocket()

                    showStatus(
                        "Wi-Fi недоступен. Проверьте подключение к LEDS."
                    )
                }
            }
        }

        networkCallback = callback

        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (exception: RuntimeException) {
            networkCallback = null

            showStatus(
                "Не удалось запросить Wi-Fi: " +
                    (exception.message ?: exception.javaClass.simpleName)
            )
        }
    }

    private fun connectSocket() {
        if (!started) {
            return
        }

        val network = wifiNetwork ?: return

        // При ручном переподключении отпускаем старое нажатие.
        dropSocket(graceful = true)

        showStatus("Подключение к Wemos: 192.168.4.1…")

        val generation = socketGeneration

        /*
         * SocketFactory именно выбранной Wi-Fi-сети.
         * Мобильная сеть не используется для подключения к Wemos.
         */
        val client = OkHttpClient.Builder()
            .socketFactory(network.socketFactory)
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        httpClient = client

        val request = Request.Builder()
            .url(SOCKET_URL)
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(
                webSocket: WebSocket,
                response: Response
            ) {
                handler.post {
                    if (!started || generation != socketGeneration) {
                        webSocket.cancel()
                        return@post
                    }

                    connected = true
                    matrixView.isEnabled = true

                    showStatus("Подключено к Wemos. Матрица готова.")

                    // Новое соединение всегда начинается без нажатий.
                    sendCurrentState()

                    handler.removeCallbacks(heartbeatTask)

                    if (connected) {
                        handler.postDelayed(
                            heartbeatTask,
                            HEARTBEAT_MS
                        )
                    }
                }
            }

            override fun onClosing(
                webSocket: WebSocket,
                code: Int,
                reason: String
            ) {
                webSocket.close(code, reason)

                handler.post {
                    connectionFailed(
                        generation,
                        "Контроллер закрыл соединение."
                    )
                }
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String
            ) {
                handler.post {
                    connectionFailed(
                        generation,
                        "Соединение закрыто."
                    )
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                throwable: Throwable,
                response: Response?
            ) {
                handler.post {
                    connectionFailed(
                        generation,
                        "Нет связи с Wemos. Проверьте сеть LEDS."
                    )
                }
            }
        }

        socket = client.newWebSocket(request, listener)
    }

    private fun connectionFailed(
        generation: Long,
        message: String
    ) {
        if (!started || generation != socketGeneration) {
            return
        }

        dropSocket()

        if (wifiNetwork != null) {
            showStatus("$message Повторное подключение…")

            handler.postDelayed(
                reconnectTask,
                RECONNECT_MS
            )
        } else {
            showStatus("Подключитесь к Wi-Fi LEDS.")
        }
    }

    private fun makeStatePacket(cell: Int): ByteString {
        val bytes = ByteArray(9)
        bytes[0] = 'S'.code.toByte()

        if (cell in 0 until 64) {
            val row = cell / 8
            val col = cell % 8

            bytes[1 + row] = (1 shl col).toByte()
        }

        return bytes.toByteString()
    }

    private fun sendCurrentState() {
        if (!started || !connected) {
            return
        }

        val currentSocket = socket ?: return
        val generation = socketGeneration

        val accepted = currentSocket.send(
            makeStatePacket(matrixView.activeCell)
        )

        if (!accepted) {
            connectionFailed(
                generation,
                "Не удалось отправить состояние."
            )
        }
    }

    private fun dropSocket(graceful: Boolean = false) {
        // Все callbacks старого сокета после этого игнорируются.
        socketGeneration++

        connected = false

        handler.removeCallbacks(heartbeatTask)
        handler.removeCallbacks(reconnectTask)

        val oldSocket = socket
        socket = null

        // Слушатель может вызваться, но connected уже false.
        matrixView.clearTouch()
        matrixView.isEnabled = false

        if (oldSocket != null) {
            if (graceful) {
                val sent = oldSocket.send(makeStatePacket(-1))
                val closing = oldSocket.close(1000, "Leaving")

                if (!sent || !closing) {
                    oldSocket.cancel()
                }
            } else {
                oldSocket.cancel()
            }
        }

        httpClient?.connectionPool?.evictAll()
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
    }
}
