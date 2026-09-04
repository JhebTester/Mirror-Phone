package com.mirrorphone.agent

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

/**
 * Descubre servidores Mirror Phone en la LAN mediante mDNS (_mirrorphone._tcp.).
 */
class ServerDiscovery(private val context: Context) {

    interface Listener {
        fun onServersChanged(servers: List<Server>)
    }

    private val nsd: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private val servers = mutableMapOf<String, Server>()
    private var listener: Listener? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String?) {
            Log.i(TAG, "discovery started $serviceType")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.i(TAG, "found ${service.serviceName}")
            resolve(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            servers.remove(service.serviceName)
            emit()
        }

        override fun onDiscoveryStopped(serviceType: String?) {}
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.e(TAG, "start failed $errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
    }

    fun start(listener: Listener) {
        this.listener = listener
        servers.clear()
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "discovery start error", e)
        }
    }

    fun stop() {
        try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        listener = null
    }

    private fun resolve(service: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(
                service,
                { it.run() },
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                    override fun onServiceUpdated(info: NsdServiceInfo) = accept(info)
                    override fun onServiceLost() {
                        servers.remove(service.serviceName)
                        emit()
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                }
            )
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                    Log.w(TAG, "resolve failed $errorCode")
                }
                override fun onServiceResolved(info: NsdServiceInfo) = accept(info)
            })
        }
    }

    private fun accept(info: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= 34)
            info.hostAddresses.firstOrNull()?.hostAddress
        else
            @Suppress("DEPRECATION") info.host?.hostAddress
        host ?: return
        val port = info.port
        val txt = info.attributes ?: emptyMap()
        val name = txt["name"]?.let { String(it) } ?: info.serviceName
        val pin = txt["pin"]?.let { String(it) }
        val v = txt["v"]?.let { String(it) } ?: "2"
        servers[info.serviceName] = Server(name = name, host = host, port = port, pin = pin, version = v)
        emit()
    }

    private fun emit() {
        listener?.onServersChanged(servers.values.sortedBy { it.name })
    }

    companion object {
        const val TAG = "ServerDiscovery"
        const val SERVICE_TYPE = "_mirrorphone._tcp."
    }
}
