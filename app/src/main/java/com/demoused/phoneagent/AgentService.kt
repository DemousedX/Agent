package com.demoused.phoneagent

import android.app.*
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class AgentService : Service() {

    companion object {
        const val TAG = "AgentService"
        const val CHANNEL_ID = "agent_channel"
        const val NOTIF_ID = 1
        var mediaProjection: MediaProjection? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var bot: TelegramBot
    private lateinit var groq: GroqClient
    private lateinit var executor: ActionExecutor
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Запуск..."))

        val botToken = prefs.getString("bot_token", "") ?: ""
        val groqKey = prefs.getString("groq_key", "") ?: ""

        if (botToken.isBlank() || groqKey.isBlank()) {
            updateNotification("❌ Не заповнені токени")
            stopSelf()
            return START_NOT_STICKY
        }

        bot = TelegramBot(botToken)
        groq = GroqClient(groqKey)
        executor = ActionExecutor(this)

        scope.launch { pollLoop() }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        updateNotification("✅ Бот активний, чекаю команд...")
        Log.i(TAG, "Polling started")
    
        coroutineScope {
            while (isActive) {
                try {
                    val updates = withContext(Dispatchers.IO) { bot.getUpdates() }
                    for (update in updates) {
                        launch { handleUpdate(update) }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                    delay(5_000)
                }
           }
        }
    }

    private suspend fun handleUpdate(update: TgUpdate) {
        val allowedId = prefs.getLong("allowed_user_id", 0L)
        if (allowedId != 0L && update.userId != allowedId) {
            withContext(Dispatchers.IO) { bot.sendMessage(update.chatId, "⛔ Доступ заборонено") }
            return
        }

        val text = update.text.trim()

        if (text == "/start") {
            withContext(Dispatchers.IO) {
                bot.sendMessage(update.chatId,
                    "📱 <b>Phone Agent активний!</b>\n\n" +
                    "Просто напиши що зробити:\n" +
                    "• <i>зроби скріншот</i>\n" +
                    "• <i>покажи що в /sdcard/Download</i>\n" +
                    "• <i>прочитай /sdcard/notes.txt</i>\n" +
                    "• <i>створи файл /sdcard/test.txt</i>")
            }
            return
        }

        val thinkingId = withContext(Dispatchers.IO) {
            bot.sendMessage(update.chatId, "🤔 Аналізую...", update.messageId)
        }

        val command = try {
            withContext(Dispatchers.IO) { groq.parseCommand(text) }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) {
                bot.editMessage(update.chatId, thinkingId, "❌ Groq помилка: ${e.message}")
            }
            return
        }

        val action = command.optString("action", "")
        val path = command.optString("path", "").ifBlank { null }

        val safety = SafetyGuard.check(action, path)
        if (!safety.allowed) {
            withContext(Dispatchers.IO) {
                bot.editMessage(update.chatId, thinkingId, "🛡 <b>Заблоковано:</b> ${safety.reason}")
            }
            return
        }

        withContext(Dispatchers.IO) {
            bot.editMessage(update.chatId, thinkingId, "⚙️ <code>${command}</code>")
        }

        val result = try {
            withContext(Dispatchers.IO) { executor.execute(command, mediaProjection) }
        } catch (e: Exception) {
            withContext(Dispatchers.IO) {
                bot.editMessage(update.chatId, thinkingId, "❌ ${e.message}")
            }
            return
        }

        val success = result.optBoolean("success", false)
        if (!success) {
            withContext(Dispatchers.IO) {
                bot.editMessage(update.chatId, thinkingId, "❌ <code>${result.optString("error")}</code>")
            }
            return
        }

        withContext(Dispatchers.IO) {
            when (action) {
                "screenshot" -> {
                    val bytes = Base64.getDecoder().decode(result.optString("data", ""))
                    bot.editMessage(update.chatId, thinkingId, "📸 Надсилаю...")
                    bot.sendPhoto(update.chatId, bytes, "📸 Скріншот")
                    bot.editMessage(update.chatId, thinkingId, "✅ Скріншот надіслано")
                }
                "read_file" -> {
                    val content = result.optString("data", "").take(3800)
                    bot.editMessage(update.chatId, thinkingId,
                        "📄 <b>$path</b>\n\n<code>${escapeHtml(content)}</code>")
                }
                "list_dir" -> {
                    val entries = result.optJSONArray("data") ?: JSONArray()
                    val lines = buildString {
                        for (i in 0 until entries.length()) {
                            val e = entries.getJSONObject(i)
                            val icon = if (e.optBoolean("is_dir")) "📁" else "📄"
                            appendLine("$icon ${e.optString("name")}")
                        }
                    }.trimEnd()
                    bot.editMessage(update.chatId, thinkingId,
                        "📂 <b>${path ?: "/sdcard"}</b>\n\n${lines.ifBlank { "Порожньо" }}")
                }
                "delete_file" -> bot.editMessage(update.chatId, thinkingId, "🗑 Видалено: <code>$path</code>")
                "write_file"  -> bot.editMessage(update.chatId, thinkingId, "✏️ Записано: <code>$path</code>")
                "create_file" -> bot.editMessage(update.chatId, thinkingId, "📝 Створено: <code>$path</code>")
                else -> bot.editMessage(update.chatId, thinkingId, "✅ Виконано")
            }
        }
    }

    private fun escapeHtml(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun buildNotification(status: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Agent")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .build()

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Phone Agent", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        scope.cancel()
        mediaProjection?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
