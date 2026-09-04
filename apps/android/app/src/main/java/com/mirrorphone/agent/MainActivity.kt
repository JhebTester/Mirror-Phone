package com.mirrorphone.agent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.mirrorphone.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val host = binding.inputIp.text.toString().trim()
            val port = binding.inputPort.text.toString().trim().toIntOrNull() ?: 7878
            val svc = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(CaptureService.EXTRA_HOST, host)
                putExtra(CaptureService.EXTRA_PORT, port)
            }
            startForegroundService(svc)
            binding.status.text = "Espejando a $host:$port"
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
        } else {
            binding.status.text = "Permiso denegado"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                42
            )
        }

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnStart.setOnClickListener {
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
        binding.btnStop.setOnClickListener {
            val svc = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            }
            startService(svc)
            binding.status.text = "Detenido"
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
        }
    }
}
