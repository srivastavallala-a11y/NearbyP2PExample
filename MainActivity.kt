package com.example.nearbyp2pexample

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.*

class MainActivity : AppCompatActivity() {

    private val SERVICE_ID = "com.example.nearbyp2pexample.SERVICE"
    private lateinit var connectionsClient: ConnectionsClient

    private lateinit var logView: TextView
    private lateinit var btnAdvertise: Button
    private lateinit var btnDiscover: Button
    private lateinit var btnSend: Button
    private lateinit var btnPickFile: Button
    private lateinit var btnSendFile: Button
    private lateinit var inputText: EditText

    private var connectedEndpointId: String? = null
    private var pickedFileUri: Uri? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled inline */ }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedFileUri = uri
            log("File selected: $uri")
        } else {
            log("File pick cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionsClient = Nearby.getConnectionsClient(this)

        logView = findViewById(R.id.logView)
        btnAdvertise = findViewById(R.id.btnAdvertise)
        btnDiscover = findViewById(R.id.btnDiscover)
        btnSend = findViewById(R.id.btnSend)
        btnPickFile = findViewById(R.id.btnPickFile)
        btnSendFile = findViewById(R.id.btnSendFile)
        inputText = findViewById(R.id.inputText)

        btnAdvertise.setOnClickListener { checkAndStartAdvertise() }
        btnDiscover.setOnClickListener { checkAndStartDiscover() }
        btnSend.setOnClickListener { sendMessageToConnected() }
        btnPickFile.setOnClickListener { pickFile() }
        btnSendFile.setOnClickListener { sendPickedFile() }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    private fun checkAndStartAdvertise() {
        requestNeededPermissions()
        startAdvertising()
    }

    private fun checkAndStartDiscover() {
        requestNeededPermissions()
        startDiscovery()
    }

    private fun startAdvertising() {
        log("Starting advertising...")
        val adOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        connectionsClient.startAdvertising("User-${Random().nextInt(9999)}", SERVICE_ID, connectionLifecycleCallback, adOptions)
            .addOnSuccessListener { log("Advertising started — waiting for connection...") }
            .addOnFailureListener { log("Advertise failed: ${it.message}") }
    }

    private fun startDiscovery() {
        log("Starting discovery...")
        val discOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
            .addOnSuccessListener { log("Discovery started") }
            .addOnFailureListener { log("Discovery failed: ${it.message}") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            log("Found: ${info.endpointName}")
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Connect to ${info.endpointName}?")
                    .setPositiveButton("Yes") { _, _ ->
                        connectionsClient.requestConnection("Me", endpointId, connectionLifecycleCallback)
                            .addOnSuccessListener { log("Connection request sent") }
                            .addOnFailureListener { log("Request failed: ${it.message}") }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }

        override fun onEndpointLost(endpointId: String) {
            log("Lost endpoint: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            log("Connection initiated from ${connectionInfo.endpointName}")
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Accept connection from ${connectionInfo.endpointName}?")
                    .setPositiveButton("Accept") { _, _ ->
                        connectionsClient.acceptConnection(endpointId, payloadCallback)
                        log("Accepted connection.")
                    }
                    .setNegativeButton("Reject") { _, _ ->
                        connectionsClient.rejectConnection(endpointId)
                        log("Rejected connection.")
                    }
                    .show()
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    log("Connected: $endpointId")
                    connectedEndpointId = endpointId
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> log("Connection rejected.")
                ConnectionsStatusCodes.STATUS_ERROR -> log("Connection error.")
                else -> log("Connection status: ${result.status.statusCode}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            log("Disconnected: $endpointId")
            if (connectedEndpointId == endpointId) connectedEndpointId = null
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val text = String(payload.asBytes()!!, StandardCharsets.UTF_8)
                log("Msg from $endpointId: $text")
            } else if (payload.type == Payload.Type.FILE) {
                log("Received file payload. Saving to cache...")
                val receivedFile = payload.asFile()
                log("File payload received (handle saving via content resolver).")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> log("Transfer in progress: ${update.bytesTransferred}/${update.totalBytes}")
                PayloadTransferUpdate.Status.SUCCESS -> log("Transfer success")
                PayloadTransferUpdate.Status.FAILURE -> log("Transfer failed")
                else -> {}
            }
        }
    }

    private fun sendMessageToConnected() {
        val endpoint = connectedEndpointId
        val txt = inputText.text.toString()
        if (endpoint == null) { log("No connected device"); return }
        if (txt.isBlank()) { log("Type message first"); return }
        val payload = Payload.fromBytes(txt.toByteArray(StandardCharsets.UTF_8))
        connectionsClient.sendPayload(endpoint, payload)
            .addOnSuccessListener { log("Message sent") }
            .addOnFailureListener { log("Send failed: ${it.message}") }
    }

    private fun pickFile() { pickFileLauncher.launch("*/*") }

    private fun sendPickedFile() {
        val endpoint = connectedEndpointId
        val uri = pickedFileUri
        if (endpoint == null) { log("No connected endpoint") ; return }
        if (uri == null) { log("Pick a file first") ; return }
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")!!
            val payload = Payload.fromFile(pfd)
            connectionsClient.sendPayload(endpoint, payload)
                .addOnSuccessListener { log("File transfer started") }
                .addOnFailureListener { log("File send failed: ${it.message}") }
        } catch (e: Exception) {
            log("File send exception: ${e.message}")
        }
    }

    private fun log(msg: String) { runOnUiThread { logView.append("\n$msg") } }

    override fun onStop() {
        super.onStop()
        try {
            connectionsClient.stopAllEndpoints()
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
        } catch (ignored: Exception) {}
    }
}
