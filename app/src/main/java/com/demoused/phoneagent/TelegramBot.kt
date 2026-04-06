package com.demoused.phoneagent

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TgUpdate(
    val updateId: Long,
    val chatId: Long,
    val userId: Long,
    val text: String,
    val messageId: Long,
)

class TelegramBot(private val token: String) {

    private val base = "https://api.telegram.org/bot$token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var offset = 0L

    fun getUpdates(): List<TgUpdate> {
        val url = "$base/getUpdates?timeout=30&offset=$offset&allowed_updates=[\"message\"]"
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        if (!json.optBoolean("ok")) return emptyList()

        val results = json.getJSONArray("result")
        val updates = mutableListOf<TgUpdate>()

        for (i in 0 until results.length()) {
            val upd = results.getJSONObject(i)
            val updId = upd.getLong("update_id")
            offset = updId + 1

            val msg = upd.optJSONObject("message") ?: continue
            val text = msg.optString("text", "")
            if (text.isBlank()) continue
            val from = msg.optJSONObject("from") ?: continue

            updates.add(TgUpdate(
                updateId = updId,
                chatId = msg.getJSONObject("chat").getLong("id"),
                userId = from.getLong("id"),
                text = text,
                messageId = msg.getLong("message_id"),
            ))
        }
        return updates
    }

    fun sendMessage(chatId: Long, text: String, replyToMessageId: Long? = null): Long {
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "HTML")
            replyToMessageId?.let { put("reply_to_message_id", it) }
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val response = client.newCall(
            Request.Builder().url("$base/sendMessage").post(body).build()
        ).execute()
        val json = JSONObject(response.body?.string() ?: "{}")
        return json.optJSONObject("result")?.optLong("message_id") ?: 0L
    }

    fun editMessage(chatId: Long, messageId: Long, text: String) {
        val payload = JSONObject().apply {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text)
            put("parse_mode", "HTML")
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder().url("$base/editMessageText").post(body).build()
        ).execute()
    }

    fun sendPhoto(chatId: Long, photoBytes: ByteArray, caption: String) {
        val reqBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("caption", caption)
            .addFormDataPart(
                "photo", "screenshot.png",
                photoBytes.toRequestBody("image/png".toMediaType())
            )
            .build()
        client.newCall(
            Request.Builder().url("$base/sendPhoto").post(reqBody).build()
        ).execute()
    }
}
