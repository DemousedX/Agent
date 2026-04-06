package com.demoused.phoneagent

import android.Manifest
import android.content.Intent
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
                override fun onStop() {
                    AgentService.mediaProjection = null
                }
            }, null)
            toast("✅ Дозвіл на скріншот отримано")
        } else {
            toast("⚠️ Скріншоти недоступні без дозволу")
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

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.etServerUrl.setText("ws://192.168.1.100:8765/ws")

        binding.btnStart.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isBlank()) {
                toast("Введіть URL сервера")
                return@setOnClickListener
            }
            requestPermissionsAndStart()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            toast("Агент зупинено")
        }
    }

    private fun requestPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startProjectionRequest()
    }

    private fun startProjectionRequest() {
        toast("Дозвольте захоплення екрану для скріншотів")
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startAgentService() {
        val url = binding.etServerUrl.text.toString().trim()
        val intent = Intent(this, AgentService::class.java).apply {
            putExtra(AgentService.EXTRA_WS_URL, url)
        }
        ContextCompat.startForegroundService(this, intent)
        toast("🚀 Агент запущено")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
