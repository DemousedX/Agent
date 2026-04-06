package com.demoused.phoneagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AgentService : Service() {

    companion object {
        const val TAG = "AgentService"
        const val CHANNEL_ID = "agent_channel"
        const val NOTIF_ID = 1
        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_PROJECTION_CODE = "projection_code"

        var mediaProjection: MediaProjection? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wsClient: WebSocket? = null
    private lateinit var executor: ActionExecutor
    private var wsUrl: String = ""
    private var reconnectJob: Job? = null

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        executor = ActionExecutor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Підключення..."))
        wsUrl = intent?.getStringExtra(EXTRA_WS_URL) ?: return START_NOT_STICKY
        connect()
        return START_STICKY
    }

    private fun connect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive) {
                try {
                    Log.i(TAG, "Connecting to $wsUrl")
                    updateNotification("Підключення...")
                    val request = Request.Builder().url(wsUrl).build()
                    wsClient = okHttpClient.newWebSocket(request, createListener())
                    delay(Long.MAX_VALUE)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Connection error: ${e.message}")
                    updateNotification("Помилка. Повтор через 5с...")
                    delay(5_000)
                }
            }
        }
    }

    private fun createListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            updateNotification("✅ Підключено до сервера")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received: $text")
            scope.launch {
                try {
                    val command = JSONObject(text)
                    val result = executor.execute(command, mediaProjection)
                    webSocket.send(result.toString())
                } catch (e: Exception) {
                    val error = JSONObject().apply {
                        put("success", false)
                        put("error", e.message ?: "Execution error")
                    }
                    webSocket.send(error.toString())
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            updateNotification("❌ З'єднання розірвано. Повтор...")
            scope.launch {
                delay(5_000)
                connect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WS closed: $reason")
            updateNotification("Відключено. Повтор...")
            scope.launch {
                delay(3_000)
                connect()
            }
        }
    }

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Phone Agent",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Agent background service" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        wsClient?.close(1000, "Service stopped")
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
