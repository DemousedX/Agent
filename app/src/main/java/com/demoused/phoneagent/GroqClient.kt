package com.demoused.phoneagent

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
        You are a phone control agent running on an Android device.
        Parse the user's command and return a JSON action object.

        Available actions:
        - screenshot: Take a screenshot. No params.
        - read_file: Read file. Params: path (string)
        - write_file: Overwrite file. Params: path (string), content (string)
        - create_file: Create new file. Params: path (string), content (string, optional)
        - list_dir: List directory. Params: path (string, default "/sdcard")
        - delete_file: Delete file. Params: path (string)

        ${SafetyGuard.safetySystemPrompt()}

        Respond ONLY with valid JSON. No markdown, no explanation.
        Examples:
        {"action":"screenshot"}
        {"action":"read_file","path":"/sdcard/notes.txt"}
        {"action":"write_file","path":"/sdcard/notes.txt","content":"hello"}
        {"action":"list_dir","path":"/sdcard/Download"}
    """.trimIndent()

    fun parseCommand(userMessage: String): JSONObject {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
        val payload = JSONObject().apply {
            put("model", "llama-3.3-70b-versatile")
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 256)
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty Groq response")
        val json = JSONObject(responseBody)

        if (!response.isSuccessful) {
            val errMsg = json.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
            throw Exception("Groq error: $errMsg")
        }

        val content = json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()

        return JSONObject(content)
    }
}
