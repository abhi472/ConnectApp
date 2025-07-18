package com.abhi.connectapp


import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abhi.connectapp.connectivity.BLEManager
import com.abhi.connectapp.connectivity.BluetoothClassicManager
import com.abhi.connectapp.connectivity.ConnectionState
import com.abhi.connectapp.connectivity.WifiDirectManager // Import Wi-Fi Direct Manager
import com.abhi.connectapp.databinding.ActivityPageCBinding
import com.abhi.connectapp.utils.Constants


class PageCActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageCBinding
    private var connectedDeviceName: String? = null
    private var connectionType: String? = null
    private var bleRole: String? = null // New: To store whether this device is BLE Client or Server in the connection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageCBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectedDeviceName = intent.getStringExtra("device_name")
        connectionType = intent.getStringExtra("connection_type")
        bleRole = intent.getStringExtra("ble_role") // Get the BLE role from intent

        val connectionInfoText = "Connected to: ${connectedDeviceName ?: "Unknown Device"} via ${connectionType?.replace("_", " ")?.capitalizeWords()}" +
                if (bleRole != null) " (Role: $bleRole)" else ""
        binding.tvConnectedDeviceInfo.text = connectionInfoText
        binding.tvMessagesDisplay.movementMethod = ScrollingMovementMethod()

        setupSendMessageButton()
        observeManagers() // New method to observe both managers
    }

    private fun setupSendMessageButton() {
        binding.btnSendMessage.setOnClickListener {
            val message = binding.etMessageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                val sent = when (connectionType) {
                    Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                        BluetoothClassicManager.writeData(message)
                        true
                    }
                    Constants.CONNECTION_TYPE_BLE -> {
                        if (bleRole == "BLE Client") { // This device is the client, writing to a server
                            BLEManager.writeCharacteristic(message)
                        } else if (bleRole == "BLE Server") { // This device is the server, sending notification to a client
                            BLEManager.sendNotification(message)
                        } else {
                            false // Should not happen if role is properly set
                        }
                    }
                    Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                        // For Wi-Fi Direct, we'll implement socket communication here later.
                        // For now, it will just log and return false indicating not yet sent.
                        WifiDirectManager.sendData(message) // Placeholder call
                        true // Assume true for now, actual socket logic will determine success
                    }
                    else -> false
                }

                if (sent) {
                    appendMessage("[You]: $message\n")
                    binding.etMessageInput.text.clear()
                } else {
                    Toast.makeText(this, "Failed to send message. Check connection.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeManagers() {
        // Observe incoming data based on connection type
        when (connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                BluetoothClassicManager.receivedData.observe(this) { data ->
                    appendMessage("[Remote]: $data\n")
                }
                BluetoothClassicManager.connectionState.observe(this) { (state, message) ->
                    handleConnectionStateChange(state, message)
                }
            }
            Constants.CONNECTION_TYPE_BLE -> {
                // Observe received data from either client or server role
                BLEManager.bleReceivedData.observe(this) { data ->
                    appendMessage("[Remote]: $data\n")
                }
                // Observe connection state based on the role this device took
                if (bleRole == "BLE Client") {
                    BLEManager.bleClientConnectionState.observe(this) { (state, message) ->
                        handleConnectionStateChange(state, message)
                    }
                } else if (bleRole == "BLE Server") {
                    BLEManager.bleServerConnectionState.observe(this) { (state, message) ->
                        handleConnectionStateChange(state, message)
                    }
                }
            }
            Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                WifiDirectManager.receivedData.observe(this) { data ->
                    appendMessage("[Remote]: $data\n")
                }
                // Observe Wi-Fi Direct connection state
                WifiDirectManager.connectionState.observe(this) { (state, message) ->
                    handleConnectionStateChange(state, message)
                }
            }
        }
    }

    private fun handleConnectionStateChange(state: ConnectionState, message: String?) {
        when (state) {
            ConnectionState.DISCONNECTED -> {
                Toast.makeText(this, "Disconnected: ${message ?: ""}", Toast.LENGTH_LONG).show()
                binding.etMessageInput.isEnabled = false
                binding.btnSendMessage.isEnabled = false
                finish()
            }
            ConnectionState.FAILED -> {
                Toast.makeText(this, "Connection Error: ${message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                binding.etMessageInput.isEnabled = false
                binding.btnSendMessage.isEnabled = false
                finish()
            }
            else -> { /* Do nothing for CONNECTING and CONNECTED states here, as they initiate this activity */ }
        }
    }


    private fun appendMessage(message: String) {
        binding.tvMessagesDisplay.append(message)
        binding.svMessages.post {
            binding.svMessages.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Determine whether to stop the manager based on your app's logic.
        // If connection should persist, do not call stop() here.
        // For this example, let's assume PageC is the main communication endpoint,
        // so we'll stop the manager if the activity is destroyed.
        when(connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> BluetoothClassicManager.stop()
            Constants.CONNECTION_TYPE_BLE -> {
                // Disconnect based on the role this device took
                if (bleRole == "BLE Client") {
                    BLEManager.disconnectClient()
                } else if (bleRole == "BLE Server") {
                    // For server, we typically stop advertising and close the server when the activity is destroyed
                    BLEManager.stopAdvertising()
                    // Note: Closing the GATT server might disconnect all clients.
                    // If you want the server to persist, you'd manage it in a service.
                    // For now, we close it with the activity.
                    BLEManager.close() // Close all BLE resources
                }
            }
            Constants.CONNECTION_TYPE_WIFI_DIRECT -> WifiDirectManager.disconnect() // Disconnect Wi-Fi Direct group
        }
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
}
