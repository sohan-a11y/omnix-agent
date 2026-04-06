package com.omnix.agent.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.omnix.agent.executor.OmnixOrchestrator
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * OMNIX Mesh Network — Task 36.
 * Peer-to-peer device mesh using mDNS (NsdManager) + TCP sockets.
 * Allows routing commands from one OMNIX device to another.
 *
 * Example: "Hey OMNIX, check balance on my phone" from a tablet.
 *
 * Service type: _omnix._tcp
 */
object OmnixMesh {

    private const val TAG = "OmnixMesh"
    private const val SERVICE_TYPE = "_omnix._tcp."
    private const val MESH_PORT = 47890

    private val deviceId = UUID.randomUUID().toString().take(8)
    private val json = Json { ignoreUnknownKeys = true }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private val peers = mutableMapOf<String, PeerInfo>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Serializable
    data class PeerInfo(
        val id: String,
        val host: String,
        val port: Int,
        val deviceName: String
    )

    @Serializable
    data class MeshCommand(
        val fromDevice: String,
        val skillId: String,
        val params: Map<String, String> = emptyMap(),
        val requestId: String = UUID.randomUUID().toString()
    )

    @Serializable
    data class MeshResponse(
        val requestId: String,
        val success: Boolean,
        val result: String = ""
    )

    // ── Advertise ──────────────────────────────────────────────────────────────
    fun advertise(context: Context) {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        startTcpServer(context)

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "OMNIX-$deviceId"
            serviceType = SERVICE_TYPE
            port = MESH_PORT
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d(TAG, "Registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        discoverPeers()
    }

    // ── Discover peers ────────────────────────────────────────────────────────
    private fun discoverPeers() {
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType == SERVICE_TYPE && !info.serviceName.contains(deviceId)) {
                    nsdManager?.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val peer = PeerInfo(
                                id = info.serviceName,
                                host = host,
                                port = info.port,
                                deviceName = info.serviceName
                            )
                            peers[peer.id] = peer
                            Log.d(TAG, "Discovered peer: ${peer.deviceName} @ $host:${info.port}")
                        }
                    })
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                peers.remove(info.serviceName)
            }
        }

        nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    // ── TCP Server ─────────────────────────────────────────────────────────────
    private fun startTcpServer(context: Context) {
        scope.launch {
            try {
                serverSocket = ServerSocket(MESH_PORT)
                Log.d(TAG, "Mesh TCP server listening on port $MESH_PORT")
                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleIncomingCommand(client, context) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP server stopped: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingCommand(socket: Socket, context: Context) =
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)

                val raw = reader.readLine() ?: return@withContext
                val command = json.decodeFromString<MeshCommand>(raw)
                Log.d(TAG, "Received command from ${command.fromDevice}: ${command.skillId}")

                val success = try {
                    OmnixOrchestrator.executeSkillById(command.skillId, command.params)
                    true
                } catch (e: Exception) { false }

                writer.println(json.encodeToString(MeshResponse(command.requestId, success)))
                socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "Command handler error: ${e.message}")
            }
        }

    // ── Send command to a peer ─────────────────────────────────────────────────
    suspend fun sendToPeer(peerId: String, skillId: String, params: Map<String, String> = emptyMap()): Boolean =
        withContext(Dispatchers.IO) {
            val peer = peers[peerId] ?: return@withContext false
            try {
                val socket = Socket(peer.host, peer.port)
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                val command = MeshCommand(fromDevice = deviceId, skillId = skillId, params = params)
                writer.println(json.encodeToString(command))

                val responseRaw = reader.readLine() ?: return@withContext false
                val response = json.decodeFromString<MeshResponse>(responseRaw)
                socket.close()
                response.success
            } catch (e: Exception) {
                Log.w(TAG, "Send to peer failed: ${e.message}")
                false
            }
        }

    /** Broadcast a command to all discovered peers. */
    suspend fun broadcast(skillId: String, params: Map<String, String> = emptyMap()) {
        peers.keys.forEach { peerId ->
            scope.launch { sendToPeer(peerId, skillId, params) }
        }
    }

    fun getPeers(): List<PeerInfo> = peers.values.toList()

    fun stop() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        serverSocket?.close()
        scope.cancel()
    }
}
