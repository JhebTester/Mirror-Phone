package com.mirrorphone.agent

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.mirrorphone.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ServerDiscovery.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var discovery: ServerDiscovery
    private var servers: List<Server> = emptyList()
    private lateinit var listAdapter: ArrayAdapter<String>

    // Info del servidor a conectar (se llena por QR / lista / manual)
    private var pendingServer: Server? = null

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val host = data.getStringExtra(QrScanActivity.EXTRA_HOST) ?: return@registerForActivityResult
            val port = data.getIntExtra(QrScanActivity.EXTRA_PORT, 7878)
            val pin = data.getStringExtra(QrScanActivity.EXTRA_PIN)
            val name = data.getStringExtra(QrScanActivity.EXTRA_NAME) ?: host
            pendingServer = Server(name = name, host = host, port = port, pin = pin)
            binding.inputIp.setText(host)
            binding.inputPort.setText(port.toString())
            binding.inputPin.setText(pin ?: "")
            binding.status.text = "QR OK → $name. Pulsa Iniciar."
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val server = pendingServer ?: buildServerFromInputs() ?: run {
                binding.status.text = "Faltan datos"
                return@registerForActivityResult
            }
            val svc = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(CaptureService.EXTRA_HOST, server.host)
                putExtra(CaptureService.EXTRA_PORT, server.port)
                putExtra(CaptureService.EXTRA_PIN, server.pin ?: "")
            }
            startForegroundService(svc)
            binding.status.text = "Espejando a ${server.name}"
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
        discovery = ServerDiscovery(this)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        binding.serverList.adapter = listAdapter

        binding.serverList.setOnItemClickListener { _, _, position, _ ->
            val server = servers.getOrNull(position) ?: return@setOnItemClickListener
            pendingServer = server
            binding.inputIp.setText(server.host)
            binding.inputPort.setText(server.port.toString())
            binding.inputPin.setText(server.pin ?: "")
            binding.status.text = "Seleccionado: ${server.name}"
        }

        binding.btnScanQr.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        binding.btnStart.setOnClickListener {
            if (pendingServer == null) pendingServer = buildServerFromInputs()
            if (pendingServer == null) {
                binding.status.text = "Faltan IP o PIN"
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.btnStop.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
            binding.status.text = "Detenido"
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
            pendingServer = null
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start(this)
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
    }

    override fun onServersChanged(list: List<Server>) {
        runOnUiThread {
            servers = list
            listAdapter.clear()
            listAdapter.addAll(list.map { it.toString() })
            binding.emptyServers.visibility =
                if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun buildServerFromInputs(): Server? {
        val host = binding.inputIp.text.toString().trim()
        val port = binding.inputPort.text.toString().trim().toIntOrNull() ?: 7878
        val pin = binding.inputPin.text.toString().trim().uppercase()
        if (host.isEmpty()) return null
        return Server(name = host, host = host, port = port, pin = pin.ifEmpty { null })
    }
}
