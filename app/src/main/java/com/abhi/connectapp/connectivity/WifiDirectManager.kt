package com.abhi.connectapp.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Singleton object for managing Wi-Fi Direct connections.
 * It encapsulates the WifiP2pManager and provides methods for initialization,
 * peer discovery, and connecting to devices.
 *
 * It uses LiveData to communicate connection state, discovered peers,
 * connection info, and received data to observing UI components.
 */
object WifiDirectManager {

    private const val TAG = "WifiDirectManager"

    private var appContext: Context? = null // Store application context for permissions
    // Make these internal so DeviceListActivity can access them for BroadcastReceiver setup
    internal var wifiP2pManager: WifiP2pManager? = null
    internal var channel: WifiP2pManager.Channel? = null

    // LiveData for Wi-Fi Direct P2P state (enabled/disabled)
    private val _wifiDirectState = MutableLiveData<Boolean>()
    val wifiDirectState: LiveData<Boolean> = _wifiDirectState

    // LiveData for discovered Wi-Fi Direct peers
    private val _peers = MutableLiveData<List<WifiP2pDevice>>()
    val peers: LiveData<List<WifiP2pDevice>> = _peers

    // LiveData for Wi-Fi Direct connection state and messages
    private val _connectionState = MutableLiveData<Pair<ConnectionState, String?>>()
    val connectionState: LiveData<Pair<ConnectionState, String?>> = _connectionState

    // LiveData for Wi-Fi Direct connection info (Group Owner IP, etc.)
    private val _connectionInfo = MutableLiveData<WifiP2pInfo>()
    val connectionInfo: LiveData<WifiP2pInfo> = _connectionInfo

    // LiveData for received data (for socket communication later)
    private val _receivedData = MutableLiveData<String>()
    val receivedData: LiveData<String> = _receivedData

    // Placeholder for socket communication threads (will be added later)
    // private var serverThread: ServerSocketThread? = null
    // private var clientThread: ClientSocketThread? = null


    /**
     * Initializes the WifiDirectManager with the application context.
     * This must be called once, ideally from your Application class or MainActivity's onCreate.
     * @param context Application context to perform permission checks and get system service.
     */
    fun init(context: Context) {
        if (appContext == null) { // Initialize only once
            appContext = context.applicationContext // Use application context to prevent memory leaks
            wifiP2pManager = appContext?.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            // Create a channel to communicate with the Wi-Fi Direct framework
            // Looper.getMainLooper() ensures callbacks are on the main thread
            channel = wifiP2pManager?.initialize(appContext, Looper.getMainLooper(), object : WifiP2pManager.ChannelListener {
                override fun onChannelDisconnected() {
                    // Called when the Wi-Fi Direct channel is disconnected.
                    // This can happen if Wi-Fi is turned off or if the app process crashes.
                    Log.d(TAG, "Wi-Fi Direct Channel disconnected. Notifying UI.")
                    // Use postValue via the public LiveData property or a setter if _connectionState was private
                    _connectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Wi-Fi Direct channel disconnected."))
                    // Optionally, re-initialize the manager here or notify the UI to do so.
                    // init(context) // Re-initialize might be too aggressive, let UI decide
                }
            })

            if (wifiP2pManager == null || channel == null) {
                _connectionState.postValue(Pair(ConnectionState.FAILED, "Failed to initialize Wi-Fi Direct Manager."))
                Log.e(TAG, "Wi-Fi Direct Manager or Channel is null after initialization.")
            } else {
                Log.d(TAG, "Wi-Fi Direct Manager initialized successfully.")
            }
        }
    }

    /**
     * Checks if the necessary Wi-Fi Direct permissions are granted.
     * @return True if permissions are granted, false otherwise.
     */
    private fun hasWifiDirectPermissions(): Boolean {
        return appContext?.let {
            val fineLocationGranted = ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val wifiStateGranted = ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
            val changeWifiStateGranted = ContextCompat.checkSelfPermission(it, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED

            // For Android 13 (API 33) and above, NEARBY_WIFI_DEVICES is required for peer discovery
            val nearbyWifiDevicesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(it, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not required on older versions
            }
            fineLocationGranted && wifiStateGranted && changeWifiStateGranted && nearbyWifiDevicesGranted
        } ?: run {
            Log.e(TAG, "Application context is null. Cannot check Wi-Fi Direct permissions.")
            false
        }
    }

    /**
     * Starts discovering nearby Wi-Fi Direct peers.
     * Requires ACCESS_FINE_LOCATION and NEARBY_WIFI_DEVICES (API 33+) permissions.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun discoverPeers() {
        if (!hasWifiDirectPermissions()) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct permissions not granted. Cannot start discovery."))
            Log.w(TAG, "Permissions not granted for discovery.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct Manager not initialized."))
            return
        }

        _peers.postValue(emptyList()) // Clear previous peers
        _connectionState.postValue(Pair(ConnectionState.CONNECTING, "Starting Wi-Fi Direct discovery..."))

        channel?.let { ch ->
            wifiP2pManager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery initiated successfully.")
                    // Peers will be updated via WIFI_P2P_PEERS_CHANGED_ACTION broadcast
                }

                override fun onFailure(reasonCode: Int) {
                    val errorMessage = "Discovery failed: ${getReasonString(reasonCode)}"
                    Log.e(TAG, errorMessage)
                    _connectionState.postValue(Pair(ConnectionState.FAILED, errorMessage))
                }
            })
        }
    }

    /**
     * Connects to a specific Wi-Fi Direct device.
     * @param device The WifiP2pDevice to connect to.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun connectToDevice(device: WifiP2pDevice) {
        if (!hasWifiDirectPermissions()) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct permissions not granted. Cannot connect."))
            Log.w(TAG, "Permissions not granted for connection.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct Manager not initialized."))
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Set groupOwnerIntent to influence GO negotiation (0-15, 15 is highest intent)
            // For a glasses app, it might prefer to be GO, for a phone, it might prefer to be client.
            // This can be adjusted based on your application's architecture.
            // groupOwnerIntent = 15 // Example: this device prefers to be GO
        }

        _connectionState.postValue(Pair(ConnectionState.CONNECTING, "Connecting to ${device.deviceName ?: device.deviceAddress}..."))

        channel?.let { ch ->
            wifiP2pManager?.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Connection attempt initiated successfully to ${device.deviceName}.")
                    // Connection info will be updated via WIFI_P2P_CONNECTION_CHANGED_ACTION broadcast
                }

                override fun onFailure(reasonCode: Int) {
                    val errorMessage = "Connection failed: ${getReasonString(reasonCode)}"
                    Log.e(TAG, errorMessage)
                    _connectionState.postValue(Pair(ConnectionState.FAILED, errorMessage))
                }
            })
        }
    }

    /**
     * Disconnects from the current Wi-Fi Direct group.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun disconnect() {
        if (!hasWifiDirectPermissions()) {
            _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct permissions not granted. Cannot disconnect."))
            Log.w(TAG, "Permissions not granted for disconnection.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            _connectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Wi-Fi Direct Manager not initialized."))
            return
        }

        channel?.let { ch ->
            wifiP2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Disconnected successfully.")
                    _connectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Disconnected from Wi-Fi Direct group."))
                    _peers.postValue(emptyList()) // Clear peers on disconnect
                    _connectionInfo.postValue(WifiP2pInfo()) // Clear connection info
                    // Stop any active socket threads here if they exist
                }

                override fun onFailure(reasonCode: Int) {
                    val errorMessage = "Disconnection failed: ${getReasonString(reasonCode)}"
                    Log.e(TAG, errorMessage)
                    _connectionState.postValue(Pair(ConnectionState.FAILED, errorMessage))
                }
            })
        }
    }

    /**
     * Requests the current list of peers.
     * The result will be delivered via the WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION intent.
     * This method is typically called by the BroadcastReceiver.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun requestPeers() {
        if (!hasWifiDirectPermissions()) {
            Log.w(TAG, "Permissions not granted for requesting peers.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            Log.e(TAG, "Wi-Fi Direct Manager not initialized for requesting peers.")
            return
        }

        channel?.let { ch ->
            wifiP2pManager?.requestPeers(ch) { p2pDeviceList ->
                // This callback is invoked when peers are available
                Log.d(TAG, "Peers requested. Found ${p2pDeviceList.deviceList.size} devices.")
                _peers.postValue(p2pDeviceList.deviceList.toList())
            }
        }
    }

    /**
     * Requests the current connection information.
     * The result will be delivered via the WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION intent.
     * This method is typically called by the BroadcastReceiver.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun requestConnectionInfo() {
        if (!hasWifiDirectPermissions()) {
            Log.w(TAG, "Permissions not granted for requesting connection info.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            Log.e(TAG, "Wi-Fi Direct Manager not initialized for requesting connection info.")
            return
        }

        channel?.let { ch ->
            wifiP2pManager?.requestConnectionInfo(ch) { info ->
                // This callback is invoked when connection info is available
                if (info != null) {
                    Log.d(TAG, "Connection info available: isGroupOwner=${info.isGroupOwner}, groupOwnerAddress=${info.groupOwnerAddress}")
                    _connectionInfo.postValue(info)
                    _connectionState.postValue(Pair(ConnectionState.CONNECTED, info.groupOwnerAddress.hostAddress))
                    // At this point, you can start your socket communication threads
                    // based on whether this device is the Group Owner or a client.
                } else {
                    Log.d(TAG, "No connection info available.")
                    _connectionState.postValue(Pair(ConnectionState.FAILED, "No connection info available."))
                }
            }
        }
    }

    /**
     * Requests the device's own Wi-Fi Direct information.
     * This method is typically called by the BroadcastReceiver.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasWifiDirectPermissions()
    fun requestDeviceInfo() {
        if (!hasWifiDirectPermissions()) {
            Log.w(TAG, "Permissions not granted for requesting device info.")
            return
        }
        if (wifiP2pManager == null || channel == null) {
            Log.e(TAG, "Wi-Fi Direct Manager not initialized for requesting device info.")
            return
        }

        channel?.let { ch ->
            wifiP2pManager?.requestDeviceInfo(ch) { device ->
                if (device != null) {
                    Log.d(TAG, "Device info available: name=${device.deviceName}, status=${device.status}")
                    // You can use this to update your device's own status in UI
                } else {
                    Log.d(TAG, "No device info available.")
                }
            }
        }
    }

    /**
     * Helper function to convert Wi-Fi Direct reason codes to human-readable strings.
     */
    private fun getReasonString(reasonCode: Int): String {
        return when (reasonCode) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P Unsupported"
            WifiP2pManager.ERROR -> "Generic Error"
            WifiP2pManager.BUSY -> "Busy"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "No Service Requests"
            else -> "Unknown Reason ($reasonCode)"
        }
    }

    /**
     * Cleans up Wi-Fi Direct resources.
     * Call this when your app no longer needs Wi-Fi Direct (e.g., in onDestroy).
     */
    fun close() {
        // Stop any active socket threads
        // serverThread?.cancel()
        // clientThread?.cancel()
        disconnect() // Disconnects from any group
        channel = null
        wifiP2pManager = null
        appContext = null
        Log.d(TAG, "WifiDirectManager closed.")
    }

    // New public methods to allow BroadcastReceiver to update LiveData
    fun updateWifiDirectState(isEnabled: Boolean) {
        _wifiDirectState.postValue(isEnabled)
    }

    fun updateConnectionDisconnected() {
        _connectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Disconnected from P2P group."))
    }

    // Placeholder for sending data (will be implemented with sockets)
    fun sendData(data: String) {
        // This method will be implemented once socket communication is set up.
        // It will write data to the appropriate output stream (client or server socket).
        Log.d(TAG, "Attempting to send data: $data (Wi-Fi Direct socket not yet implemented)")
        _connectionState.postValue(Pair(ConnectionState.FAILED, "Wi-Fi Direct data transfer not yet implemented."))
    }
}
