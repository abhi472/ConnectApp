package com.abhi.connectapp.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.abhi.connectapp.utils.Constants
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

// Define connection states
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/**
 * Singleton object for managing Bluetooth Classic connections.
 * It handles client-side connections, server-side listening, and data transfer.
 * Uses LiveData to communicate connection state and received data to observing UI components.
 */
object BluetoothClassicManager { // Changed to object for singleton pattern

    private const val TAG = "BluetoothClassicManager"
    private val MY_UUID: UUID = Constants.BLUETOOTH_SPP_UUID // Use a unique UUID for your app
    private const val MY_APP_NAME = "MyConnectivityApp" // Name for your Bluetooth service

    private var appContext: Context? = null // Store application context for permissions
    private var bluetoothAdapter: BluetoothAdapter? = null

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var acceptThread: AcceptThread? = null // For the server side

    // LiveData for connection state and received messages
    private val _connectionState = MutableLiveData<Pair<ConnectionState, String?>>()
    val connectionState: LiveData<Pair<ConnectionState, String?>> = _connectionState

    private val _receivedData = MutableLiveData<String>()
    val receivedData: LiveData<String> = _receivedData

    /**
     * Initializes the BluetoothClassicManager with the application context and BluetoothAdapter.
     * This must be called once, ideally from your Application class or MainActivity's onCreate.
     * @param context Application context to perform permission checks.
     * @param adapter The BluetoothAdapter instance.
     */
    fun init(context: Context, adapter: BluetoothAdapter) {
        if (appContext == null || bluetoothAdapter == null) { // Initialize only once
            appContext = context.applicationContext // Use application context to prevent memory leaks
            bluetoothAdapter = adapter
            Log.d(TAG, "BluetoothClassicManager initialized.")
        }
    }

    /**
     * Checks if the BLUETOOTH_CONNECT permission is granted.
     * @return true if permission is granted, false otherwise.
     */
    private fun hasBluetoothConnectPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } ?: run {
            Log.e(TAG, "Application context is null. Cannot check BLUETOOTH_CONNECT permission.")
            false
        }
    }

    /**
     * Checks if the BLUETOOTH_SCAN permission is granted.
     * @return true if permission is granted, false otherwise.
     */
    private fun hasBluetoothScanPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } ?: run {
            Log.e(TAG, "Application context is null. Cannot check BLUETOOTH_SCAN permission.")
            false
        }
    }

    /**
     * Safely retrieves the device name, checking for BLUETOOTH_CONNECT permission.
     * @param device The BluetoothDevice.
     * @return The device name or a fallback string if permission is denied.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            device.name ?: "Unknown Device"
        } else {
            "Unknown Device (Permission Denied)"
        }
    }

    /**
     * Safely retrieves the device address, checking for BLUETOOTH_CONNECT permission.
     * @param device The BluetoothDevice.
     * @return The device address or a fallback string if permission is denied.
     */
    private fun getDeviceAddress(device: BluetoothDevice): String {
        // Device address does not require BLUETOOTH_CONNECT permission
        return device.address
    }

    /**
     * Initiates a client-side connection to a remote Bluetooth device.
     * @param device The BluetoothDevice to connect to.
     */
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "BLUETOOTH_CONNECT permission not granted."))
            return
        }
        if (bluetoothAdapter == null) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "BluetoothAdapter not initialized."))
            return
        }

        // Cancel any existing threads before starting a new connection attempt
        stopAllThreads()

        _connectionState.postValue(Pair(ConnectionState.CONNECTING, getDeviceName(device)))
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    /**
     * Starts listening for incoming Bluetooth Classic connections (server side).
     */
    fun startListeningForConnections() {
        if (!hasBluetoothConnectPermission()) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "BLUETOOTH_CONNECT permission not granted for server."))
            return
        }
        if (bluetoothAdapter == null) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "BluetoothAdapter not initialized."))
            return
        }

        // Cancel any existing threads before starting to listen
        stopAllThreads()

        acceptThread = AcceptThread()
        acceptThread?.start()
        Log.d(TAG, "Bluetooth server started listening.")
        _connectionState.postValue(Pair(ConnectionState.CONNECTING, "Listening for connections..."))
    }

    /**
     * Called when a connection is successfully established (either client or server).
     * Manages the active connection thread and updates connection state.
     * @param socket The connected BluetoothSocket.
     * @param device The remote BluetoothDevice.
     */
    private fun connected(socket: BluetoothSocket, device: BluetoothDevice) {
        Log.d(TAG, "Connected to ${getDeviceName(device)} (${getDeviceAddress(device)})")

        // Cancel the threads that were used for connection/listening as we are now connected
        connectThread?.cancel()
        connectThread = null
        acceptThread?.cancel()
        acceptThread = null

        // Start the thread to manage the connection and perform data transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()

        _connectionState.postValue(Pair(ConnectionState.CONNECTED, getDeviceName(device)))
    }

    /**
     * Stops all active Bluetooth Classic threads (connection, listening, and data transfer).
     */
    fun stop() {
        Log.d(TAG, "Stopping all Bluetooth Classic threads.")
        stopAllThreads()
        _connectionState.postValue(Pair(ConnectionState.DISCONNECTED, null))
    }

    /**
     * Helper to stop all current threads.
     */
    private fun stopAllThreads() {
        connectThread?.cancel()
        connectThread = null
        connectedThread?.cancel()
        connectedThread = null
        acceptThread?.cancel()
        acceptThread = null
    }

    /**
     * Thread for handling client-side connection attempts to a remote device.
     */
    private class ConnectThread(private val device: BluetoothDevice) : Thread() { // Removed 'inner'
        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                if (!BluetoothClassicManager.hasBluetoothConnectPermission()) { // Access permission check via singleton
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for createRfcommSocketToServiceRecord.")
                    BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for socket creation."))
                    return@lazy null
                }
                device.createRfcommSocketToServiceRecord(MY_UUID)
            } catch (e: IOException) {
                Log.e(TAG, "Socket's create() method failed", e)
                null
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked before calls
        override fun run() {
            val deviceName = BluetoothClassicManager.getDeviceName(device) // Access via singleton
            Log.i(TAG, "BEGIN ConnectThread to $deviceName")

            // Always cancel discovery because it will slow down a connection
            BluetoothClassicManager.bluetoothAdapter?.let { adapter -> // Access via singleton
                BluetoothClassicManager.appContext?.let { ctx -> // Access via singleton
                    if (adapter.isDiscovering) {
                        if (BluetoothClassicManager.hasBluetoothScanPermission()) { // Check permission here
                            adapter.cancelDiscovery()
                        } else {
                            Log.e(TAG, "BLUETOOTH_SCAN permission not granted for cancelDiscovery in ConnectThread.")
                        }
                    }
                }
            }


            mmSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                try {
                    if (!BluetoothClassicManager.hasBluetoothConnectPermission()) { // Access permission check via singleton
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for socket.connect().")
                        BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for socket connection."))
                        return
                    }
                    socket.connect()
                    Log.d(TAG, "Client socket connected to $deviceName")
                } catch (e: IOException) {
                    Log.e(TAG, "Could not connect to $deviceName: ${e.message}", e)
                    // Close the socket
                    try {
                        socket.close()
                    } catch (e2: IOException) {
                        Log.e(TAG, "Could not close client socket after failed connection", e2)
                    }
                    BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, e.message ?: "Connection failed"))
                    return
                }

                // The connection attempt succeeded. Perform work in a separate thread.
                BluetoothClassicManager.connected(socket, device) // Access via singleton
            } ?: run {
                Log.e(TAG, "ConnectThread: Socket is null, connection failed.")
                BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, "Socket creation failed."))
            }
        }

        /**
         * Closes the client socket and causes the thread to finish.
         */
        fun cancel() {
            try {
                mmSocket?.close()
                Log.d(TAG, "ConnectThread socket closed.")
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket in cancel()", e)
            }
        }
    }

    /**
     * Thread for handling server-side incoming connections.
     * Listens for a connection, accepts it, and then hands it over to ConnectedThread.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    private class AcceptThread : Thread() { // Removed 'inner'
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            try {
                if (!BluetoothClassicManager.hasBluetoothConnectPermission()) { // Access permission check via singleton
                    Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for listenUsingRfcommWithServiceRecord.")
                    BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for server socket creation."))
                    return@lazy null
                }
                BluetoothClassicManager.bluetoothAdapter?.listenUsingRfcommWithServiceRecord(MY_APP_NAME, MY_UUID) // Access via singleton
            } catch (e: IOException) {
                Log.e(TAG, "Socket's listen() method failed", e)
                null
            }
        }

        @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission() before starting thread
        override fun run() {
            Log.i(TAG, "BEGIN AcceptThread")
            var socket: BluetoothSocket? = null
            // Keep listening until exception occurs or a socket is returned.
            while (true) {
                try {
                    Log.d(TAG, "AcceptThread: Waiting for incoming connections...")
                    socket = mmServerSocket?.accept() // This is a blocking call
                } catch (e: IOException) {
                    Log.e(TAG, "Socket's accept() method failed", e)
                    BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, e.message ?: "Server listening failed"))
                    break // Exit loop on exception
                }

                socket?.let {
                    val remoteDevice = it.remoteDevice
                    Log.d(TAG, "AcceptThread: Connection accepted from ${BluetoothClassicManager.getDeviceName(remoteDevice)}") // Access via singleton
                    // A connection was accepted. Perform work in a separate thread.
                    BluetoothClassicManager.connected(it, remoteDevice) // Access via singleton
                    // Close the server socket once a connection is established (for single connections)
                    try {
                        mmServerSocket?.close()
                        Log.d(TAG, "AcceptThread server socket closed after accepting connection.")
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not close server socket after accepting connection", e)
                    }
                    return // Exit the thread
                }
            }
        }

        /**
         * Closes the server socket and causes the thread to finish.
         */
        fun cancel() {
            try {
                mmServerSocket?.close()
                Log.d(TAG, "AcceptThread server socket closed by cancel().")
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the server socket in cancel()", e)
            }
        }
    }

    /**
     * Thread for handling data transfer after a connection is established.
     * Manages input and output streams.
     */
    class ConnectedThread(internal val mmSocket: BluetoothSocket) : Thread() { // Removed 'inner', mmSocket is internal
        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val buffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission() on calling methods
        override fun run() {
            Log.i(TAG, "BEGIN ConnectedThread")
            val remoteDeviceName = BluetoothClassicManager.getDeviceName(mmSocket.remoteDevice) // Access via singleton

            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    // Read from the InputStream.
                    numBytes = mmInStream.read(buffer)
                    // Get the message as a string
                    val receivedMessage = String(buffer, 0, numBytes)
                    Log.d(TAG, "Data received: $receivedMessage")
                    BluetoothClassicManager._receivedData.postValue(receivedMessage) // Post to LiveData for UI observers

                } catch (e: IOException) {
                    Log.e(TAG, "Input stream was disconnected or error occurred", e)
                    // Notify about connection loss
                    BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Connection lost: ${e.message}"))
                    try { mmSocket.close() } catch (e: IOException) { /* ignore */ }
                    break // Exit the loop
                }
            }
        }

        /**
         * Writes bytes to the output stream (sends data to the remote device).
         * @param bytes The bytes to send.
         */
        fun write(bytes: ByteArray) {
            val remoteDeviceName = BluetoothClassicManager.getDeviceName(mmSocket.remoteDevice) // Access via singleton
            try {
                mmOutStream.write(bytes)
                Log.d(TAG, "Data sent: ${String(bytes)}")
                // Optionally, add a callback for data sent successfully if needed
            } catch (e: IOException) {
                Log.e(TAG, "Error writing to output stream", e)
                BluetoothClassicManager._connectionState.postValue(Pair(ConnectionState.FAILED, "Error sending data to $remoteDeviceName: ${e.message}"))
            }
        }

        /**
         * Closes the connection socket and causes the thread to finish.
         */
        fun cancel() {
            try {
                mmSocket.close()
                Log.d(TAG, "ConnectedThread socket closed.")
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connected socket in cancel()", e)
            }
        }
    }

    /**
     * Public method to send data through the active connection.
     * @param data The string data to send.
     */
    fun writeData(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        connectedThread?.write(bytes) ?: run {
            Log.e(TAG, "No connected thread to send data. Connection not active.")
            _connectionState.postValue(Pair(ConnectionState.FAILED, "No active connection to send data."))
        }
    }

    /**
     * Returns the currently connected BluetoothDevice, if any.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission() on calling methods
    fun getConnectedDevice(): BluetoothDevice? = connectedThread?.mmSocket?.remoteDevice

    /**
     * Returns the name of the currently connected device, if any.
     */
    fun getConnectedDeviceName(): String? = connectedThread?.mmSocket?.remoteDevice?.let { getDeviceName(it) }
}
