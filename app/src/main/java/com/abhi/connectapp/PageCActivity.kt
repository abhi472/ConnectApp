package com.abhi.connectapp


import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.abhi.connectapp.connectivity.BluetoothClassicManager
import com.abhi.connectapp.databinding.ActivityPageCBinding

class PageCActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPageCBinding
    private var connectedDeviceName: String? = null
    private var connectedDeviceAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPageCBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve connected device info from intent
        connectedDeviceName = intent.getStringExtra("device_name")
        connectedDeviceAddress = intent.getStringExtra("device_address") // Though not directly used, useful for debugging

        binding.tvConnectedDeviceInfo.text = "Connected to: ${connectedDeviceName ?: "Unknown Device"}"
        binding.tvMessagesDisplay.movementMethod = ScrollingMovementMethod() // Enable scrolling for messages

        setupSendMessageButton()
        observeBluetoothManager()

        // Optional: Start listening for connections if this device is meant to be the server/receiver initially
        // This part needs careful thought based on your app's flow.
        // If this activity is always launched after a client connection, you don't need to listen here.
        // If this activity could also be reached by a device waiting for connection, you'd start server here.
        // For now, assuming client has connected to a server on another device.
    }

    private fun setupSendMessageButton() {
        binding.btnSendMessage.setOnClickListener {
            val message = binding.etMessageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                BluetoothClassicManager.writeData(message)
                appendMessage("[You]: $message\n")
                binding.etMessageInput.text.clear() // Clear input field
            } else {
                Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeBluetoothManager() {
        // Observe incoming data
        BluetoothClassicManager.receivedData.observe(this) { data ->
            appendMessage("[Remote]: $data\n")
        }

        // Observe connection state changes
        BluetoothClassicManager.connectionState.observe(this) { (state, message) ->
            when (state) {
                ConnectionState.DISCONNECTED -> {
                    Toast.makeText(this, "Disconnected: ${message ?: ""}", Toast.LENGTH_LONG).show()
                    // Optionally disable UI elements or navigate back
                    finish() // Go back to device list or main screen
                }
                ConnectionState.FAILED -> {
                    Toast.makeText(this, "Connection Error: ${message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                    // Optionally disable UI elements or navigate back
                    finish()
                }
                // CONNECTING and CONNECTED states are primarily handled by DeviceListActivity
                else -> { /* Do nothing for other states here */ }
            }
        }
    }

    private fun appendMessage(message: String) {
        // Append message to TextView and scroll to bottom
        binding.tvMessagesDisplay.append(message)
        binding.svMessages.post {
            binding.svMessages.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // No explicit unregistering for LiveData is needed as it's lifecycle-aware,
        // but ensure manager is properly stopped if this activity is the "end" of the communication flow.
        // For a multi-activity app, you might want to keep the connection alive if navigating away.
        // For a full app, consider where to call BluetoothClassicManager.stop() to clean up.
        // For now, if we finish() this activity, the connection persists until explicitly stopped.
        // If we want to ensure disconnect on leaving PageC, you'd add:
        // BluetoothClassicManager.stop()
    }
}