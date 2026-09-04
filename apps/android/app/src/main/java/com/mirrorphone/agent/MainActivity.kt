package com.mirrorphone.agent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.mirrorphone.agent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), ServerDiscovery.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var discovery: ServerDiscovery
    private var servers: List<Server> = emptyList()
    private lateinit var listAdapter: ServerAdapter

    private var pendingServer: Server? = null
    private var manualExpanded = false

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            val host = data.getStringExtra(QrScanActivity.EXTRA_HOST)
                ?: return@registerForActivityResult
            val port = data.getIntExtra(QrScanActivity.EXTRA_PORT, 7878)
            val pin = data.getStringExtra(QrScanActivity.EXTRA_PIN)
            val name = data.getStringExtra(QrScanActivity.EXTRA_NAME) ?: host
            pendingServer = Server(name = name, host = host, port = port, pin = pin)
            binding.inputIp.setText(host)
            binding.inputPort.setText(port.toString())
            binding.inputPin.setText(pin ?: "")
            setStatus("${getString(R.string.status_qr_ok)} — $name", active = false)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val server = pendingServer ?: buildServerFromInputs() ?: run {
                setStatus(getString(R.string.status_missing), active = false)
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
            setStatus("${getString(R.string.status_streaming)} → ${server.name}", active = true)
            binding.btnStart.isEnabled = false
            binding.btnStop.isEnabled = true
        } else {
            setStatus(getString(R.string.status_denied), active = false)
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

        listAdapter = ServerAdapter(this)
        binding.serverList.adapter = listAdapter

        binding.serverList.setOnItemClickListener { _, _, position, _ ->
            val server = servers.getOrNull(position) ?: return@setOnItemClickListener
            pendingServer = server
            binding.inputIp.setText(server.host)
            binding.inputPort.setText(server.port.toString())
            binding.inputPin.setText(server.pin ?: "")
            setStatus("${getString(R.string.status_selected)}: ${server.name}", active = false)
        }

        binding.btnScanQr.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        binding.btnStart.setOnClickListener {
            if (pendingServer == null) pendingServer = buildServerFromInputs()
            if (pendingServer == null) {
                setStatus(getString(R.string.status_missing), active = false)
                return@setOnClickListener
            }
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.btnStop.setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
            setStatus(getString(R.string.status_idle), active = false)
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
            pendingServer = null
        }

        binding.manualHeader.setOnClickListener { toggleManual() }
    }

    override fun onStart() {
        super.onStart()
        discovery.start(this)
        binding.discoveryProgress.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        discovery.stop()
    }

    override fun onServersChanged(list: List<Server>) {
        runOnUiThread {
            servers = list
            listAdapter.setServers(list)
            binding.emptyServers.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.serverList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            binding.discoveryProgress.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun toggleManual() {
        manualExpanded = !manualExpanded
        binding.manualBody.visibility = if (manualExpanded) View.VISIBLE else View.GONE
        binding.manualChevron.rotation = if (manualExpanded) 180f else 0f
    }

    private fun setStatus(text: String, active: Boolean) {
        binding.status.text = text
        binding.statusDot.setBackgroundResource(
            if (active) R.drawable.dot_active else R.drawable.dot_idle
        )
    }

    private fun buildServerFromInputs(): Server? {
        val host = binding.inputIp.text.toString().trim()
        val port = binding.inputPort.text.toString().trim().toIntOrNull() ?: 7878
        val pin = binding.inputPin.text.toString().trim().uppercase()
        if (host.isEmpty()) return null
        return Server(name = host, host = host, port = port, pin = pin.ifEmpty { null })
    }

    /** Adapter simple para pintar cada servidor con su ícono, nombre y host:port. */
    private class ServerAdapter(ctx: Context) : BaseAdapter() {
        private val inflater = LayoutInflater.from(ctx)
        private val items = mutableListOf<Server>()

        fun setServers(list: List<Server>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_server, parent, false)
            val server = items[position]
            view.findViewById<TextView>(R.id.serverName).text = server.name
            view.findViewById<TextView>(R.id.serverAddress).text = "${server.host}:${server.port}"
            return view
        }
    }
}
