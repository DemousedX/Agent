package com.demoused.phoneagent

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.demoused.phoneagent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AgentService.mediaProjection = projectionManager.getMediaProjection(
                result.resultCode, result.data!!
            )
            AgentService.mediaProjection?.registerCallback(object :
                android.media.projection.MediaProjection.Callback() {
                override fun onStop() { AgentService.mediaProjection = null }
            }, null)
        }
        startAgentService()
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startProjectionRequest() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("agent_prefs", MODE_PRIVATE)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        // Завантажуємо збережені значення
        binding.etBotToken.setText(prefs.getString("bot_token", ""))
        binding.etGroqKey.setText(prefs.getString("groq_key", ""))
        val savedUserId = prefs.getLong("allowed_user_id", 0L)
        if (savedUserId != 0L) binding.etUserId.setText(savedUserId.toString())

        binding.btnStart.setOnClickListener {
            val token = binding.etBotToken.text.toString().trim()
            val groqKey = binding.etGroqKey.text.toString().trim()
            val userId = binding.etUserId.text.toString().trim().toLongOrNull() ?: 0L

            if (token.isBlank()) { toast("Введи Bot Token"); return@setOnClickListener }
            if (groqKey.isBlank()) { toast("Введи Groq API Key"); return@setOnClickListener }

            // Зберігаємо перед запуском
            prefs.edit()
                .putString("bot_token", token)
                .putString("groq_key", groqKey)
                .putLong("allowed_user_id", userId)
                .apply()

            requestPermissionsAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            toast("Агент зупинено")
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startProjectionRequest()
    }

    private fun startProjectionRequest() {
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startAgentService() {
        ContextCompat.startForegroundService(this, Intent(this, AgentService::class.java))
        toast("🚀 Агент запущено")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
