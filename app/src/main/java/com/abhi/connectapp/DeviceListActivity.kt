package com.abhi.connectapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.abhi.connectapp.adapter.DeviceAdapter
import com.abhi.connectapp.connectivity.BLEManager
import com.abhi.connectapp.connectivity.BluetoothClassicManager
import com.abhi.connectapp.connectivity.ConnectionState
import com.abhi.connectapp.connectivity.WiFiDirectBroadcastReceiver // Import the new Wi-Fi Direct Broadcast Receiver
import com.abhi.connectapp.connectivity.WifiDirectManager // Import the new Wi-Fi Direct Manager
import com.abhi.connectapp.databinding.ActivityDeviceListBinding
import com.abhi.connectapp.model.Device
import com.abhi.connectapp.utils.Constants


class DeviceListActivity : AppCompatActivity() {

    private val TAG = "DeviceListActivity" // Added TAG for consistent logging

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var deviceAdapter: DeviceAdapter
    private var connectionType: String? = null

    // Bluetooth Classic specific
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Wi-Fi Direct specific
    private var wifiManager: WifiManager? = null
    // No longer need to declare wifiP2pManager and wifiP2pChannel here,
    // as we access them via WifiDirectManager.wifiP2pManager and WifiDirectManager.channel
    private lateinit var wifiDirectReceiver: WiFiDirectBroadcastReceiver
    private val wifiDirectIntentFilter = IntentFilter()


    // BroadcastReceiver for Bluetooth Classic discovery
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        val deviceName = if (checkBluetoothConnectPermission()) it.name else "Unknown Device"
                        val deviceAddress = it.address
                        Log.d(TAG, "Found Classic device: $deviceName ($deviceAddress)")
                        deviceAdapter.addDevice(Device(deviceName, deviceAddress, Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC))
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    stopScanning()
                    Toast.makeText(context, "Bluetooth Classic discovery finished.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Activity Result Launchers
    private val requestBluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth is enabled.", Toast.LENGTH_SHORT).show()
            when (connectionType) {
                Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                    if (checkBluetoothClassicPermissions()) {
                        startScanningBluetoothClassic()
                    }
                }
                Constants.CONNECTION_TYPE_BLE -> {
                    if (checkBLEPermissions()) {
                        startScanningBLE()
                    }
                }
            }
        } else {
            Toast.makeText(this, "Bluetooth not enabled. Cannot scan.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val requestWifiEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Wi-Fi is enabled.", Toast.LENGTH_SHORT).show()
            // After Wi-Fi is enabled, proceed with Wi-Fi Direct scan
            if (checkWifiDirectPermissions()) {
                startScanningWifiDirect()
            }
        } else {
            Toast.makeText(this, "Wi-Fi not enabled. Cannot use Wi-Fi Direct.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectionType = intent.getStringExtra(Constants.EXTRA_CONNECTION_TYPE)
        binding.tvConnectionTypeTitle.text = "Discovering ${connectionType?.replace("_", " ")?.capitalizeWords()} Devices"

        setupRecyclerView()
        setupPermissionLauncher()
        setupBluetoothAdapters() // Initialize Bluetooth Classic and BLE
        setupWifiDirect() // Initialize Wi-Fi Direct components
        setupScanButton()
        observeConnectionState() // Observe LiveData for connection state changes for all managers
        observeBleScanResults() // Observe LiveData for BLE scan results
        observeWifiDirectPeers() // New: Observe LiveData for Wi-Fi Direct peers
        observeWifiDirectState() // New: Observe Wi-Fi Direct enabled/disabled state
        observeWifiDirectConnectionInfo() // New: Observe Wi-Fi Direct connection info

        when (connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                requestBluetoothClassicPermissionsAndScan()
            }
            Constants.CONNECTION_TYPE_BLE -> {
                requestBLEPermissionsAndScan()
            }
            Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                requestWifiDirectPermissionsAndScan()
            }
            else -> {
                Toast.makeText(this, "Invalid connection type.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Register receivers
        when (connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                registerReceiver(bluetoothDiscoveryReceiver, filter)
            }
            Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                // Ensure WifiP2pManager and Channel are initialized before registering receiver
                // Access manager and channel directly from the singleton object
                val manager = WifiDirectManager.wifiP2pManager
                val channel = WifiDirectManager.channel

                if (manager != null && channel != null) {
                    wifiDirectReceiver = WiFiDirectBroadcastReceiver(manager, channel)
                    registerReceiver(wifiDirectReceiver, wifiDirectIntentFilter)
                } else {
                    Log.e(TAG, "WifiP2pManager or Channel is null during onResume for Wi-Fi Direct.")
                    Toast.makeText(this, "Wi-Fi Direct not ready. Restart app.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister receivers
        when (connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                try {
                    unregisterReceiver(bluetoothDiscoveryReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Bluetooth discovery receiver not registered or already unregistered: ${e.message}")
                }
            }
            Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                try {
                    unregisterReceiver(wifiDirectReceiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Wi-Fi Direct receiver not registered or already unregistered: ${e.message}")
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        // Ensure classic discovery is stopped with permission check
        if (checkBluetoothScanPermission()) {
            bluetoothAdapter?.cancelDiscovery()
        }
        BLEManager.stopScan() // Ensure BLE scan is stopped
        BLEManager.close() // Close BLE resources
        BluetoothClassicManager.stop() // Stop Classic BT resources
        WifiDirectManager.close() // Close Wi-Fi Direct resources
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            Toast.makeText(this, "Selected: ${device.name} (${device.address})", Toast.LENGTH_SHORT).show()
            when (device.type) { // Use device.type to determine connection logic
                Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                    val remoteDevice = if (checkBluetoothConnectPermission()) bluetoothAdapter?.getRemoteDevice(device.address) else null
                    if (remoteDevice != null) {
                        BluetoothClassicManager.connect(remoteDevice)
                    } else {
                        Toast.makeText(this, "Could not get remote device for Classic or permission missing.", Toast.LENGTH_SHORT).show()
                    }
                }
                Constants.CONNECTION_TYPE_BLE -> {
                    val remoteDevice = if (checkBluetoothConnectPermission()) bluetoothAdapter?.getRemoteDevice(device.address) else null
                    if (remoteDevice != null) {
                        BLEManager.connect(remoteDevice)
                    } else {
                        Toast.makeText(this, "Could not get remote device for BLE or permission missing.", Toast.LENGTH_SHORT).show()
                    }
                }
                Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                    val wifiP2pDevice = WifiDirectManager.peers.value?.find { it.deviceAddress == device.address }
                    if (wifiP2pDevice != null) {
                        WifiDirectManager.connectToDevice(wifiP2pDevice)
                    } else {
                        Toast.makeText(this, "Wi-Fi Direct device not found in current scan results.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val grantedPermissions = permissions.filter { it.value }.map { it.key }
            val deniedPermissions = permissions.filter { !it.value }.map { it.key }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Permissions denied: ${deniedPermissions.joinToString()}", Toast.LENGTH_LONG).show()
                showPermissionDeniedDialog(deniedPermissions)
            } else {
                Toast.makeText(this, "All required permissions granted!", Toast.LENGTH_SHORT).show()
                // Based on connection type, proceed with scan
                when (connectionType) {
                    Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                        if (bluetoothAdapter?.isEnabled == true) {
                            startScanningBluetoothClassic()
                        } else {
                            promptEnableBluetooth()
                        }
                    }
                    Constants.CONNECTION_TYPE_BLE -> {
                        if (bluetoothAdapter?.isEnabled == true) {
                            startScanningBLE()
                        } else {
                            promptEnableBluetooth()
                        }
                    }
                    Constants.CONNECTION_TYPE_WIFI_DIRECT -> {
                        // For Wi-Fi Direct, check if Wi-Fi itself is enabled
                        if (wifiManager?.isWifiEnabled == true) {
                            startScanningWifiDirect()
                        } else {
                            promptEnableWifi()
                        }
                    }
                }
            }
        }
    }

    private fun showPermissionDeniedDialog(deniedPermissions: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("The app needs the following permissions to function: ${deniedPermissions.joinToString { it.split(".").last() }}. Please grant them in settings.")
            .setPositiveButton("Go to Settings") { dialog, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = android.net.Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupScanButton() {
        binding.btnScanDevices.setOnClickListener {
            deviceAdapter.clearDevices()
            when (connectionType) {
                Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> requestBluetoothClassicPermissionsAndScan()
                Constants.CONNECTION_TYPE_BLE -> requestBLEPermissionsAndScan()
                Constants.CONNECTION_TYPE_WIFI_DIRECT -> requestWifiDirectPermissionsAndScan()
            }
        }
    }

    // --- Common Bluetooth Initialization ---
    private fun setupBluetoothAdapters() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth.", Toast.LENGTH_LONG).show()
            // Don't finish if other connection types are supported
        }
        // Initialize both Bluetooth managers
        bluetoothAdapter?.let { adapter ->
            BluetoothClassicManager.init(applicationContext, adapter)
            BLEManager.init(applicationContext, adapter)
        }
    }

    // --- Wi-Fi Direct Initialization ---
    private fun setupWifiDirect() {
        // Get WifiManager for checking Wi-Fi state
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        // Initialize WifiDirectManager (already done in MainActivity, but safe to call again)
        WifiDirectManager.init(applicationContext)

        // Prepare IntentFilter for Wi-Fi Direct BroadcastReceiver
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        wifiDirectIntentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)

        // The receiver will be instantiated and registered in onResume
    }

    // --- Observe Connection States for all Managers ---
    private fun observeConnectionState() {
        BluetoothClassicManager.connectionState.observe(this) { (state, message) ->
            if (connectionType == Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC) {
                updateUIForConnectionState(state, message)
            }
        }
        BLEManager.bleConnectionState.observe(this) { (state, message) ->
            if (connectionType == Constants.CONNECTION_TYPE_BLE) {
                updateUIForConnectionState(state, message)
            }
        }
        WifiDirectManager.connectionState.observe(this) { (state, message) ->
            if (connectionType == Constants.CONNECTION_TYPE_WIFI_DIRECT) {
                updateUIForConnectionState(state, message)
            }
        }
    }

    private fun updateUIForConnectionState(state: ConnectionState, message: String?) {
        when (state) {
            ConnectionState.CONNECTING -> {
                Toast.makeText(this, "Attempting to connect to $message...", Toast.LENGTH_SHORT).show()
                binding.tvStatusMessage.text = "Connecting to $message..."
                binding.tvStatusMessage.visibility = View.VISIBLE
                binding.progressBarScanning.visibility = View.VISIBLE
            }
            ConnectionState.CONNECTED -> {
                Toast.makeText(this, "Connected to $message", Toast.LENGTH_SHORT).show()
                binding.tvStatusMessage.text = "Connected to $message"
                binding.progressBarScanning.visibility = View.GONE

                // For Wi-Fi Direct, explicitly request connection info to get GO IP
                if (connectionType == Constants.CONNECTION_TYPE_WIFI_DIRECT) {
                    // This callback will trigger onConnectionInfoAvailable, which then navigates
                    WifiDirectManager.requestConnectionInfo()
                } else {
                    // Navigate for Bluetooth Classic and BLE
                    navigateToPageC(message)
                }
            }
            ConnectionState.FAILED -> {
                Toast.makeText(this, "Connection failed: ${message ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                binding.tvStatusMessage.text = "Connection failed: ${message ?: "Unknown error"}"
                binding.progressBarScanning.visibility = View.GONE
            }
            ConnectionState.DISCONNECTED -> {
                Toast.makeText(this, "Disconnected: ${message ?: ""}", Toast.LENGTH_SHORT).show()
                binding.tvStatusMessage.text = "Disconnected."
                binding.progressBarScanning.visibility = View.GONE
                // Clear devices on disconnect for all types
                deviceAdapter.clearDevices()
            }
        }
    }

    // New: Handle navigation to PageC once connection info is available for Wi-Fi Direct
    private fun navigateToPageC(deviceName: String?) {
        val intent = Intent(this@DeviceListActivity, PageCActivity::class.java).apply {
            putExtra("device_name", deviceName)
            putExtra("connection_type", connectionType)
        }
        startActivity(intent)
        finish()
    }


    // --- Observe BLE Scan Results ---
    @SuppressLint("MissingPermission")
    private fun observeBleScanResults() {
        BLEManager.bleScanResults.observe(this) { bleDevices ->
            if (connectionType == Constants.CONNECTION_TYPE_BLE) {
                deviceAdapter.clearDevices()
                bleDevices.forEach {
                    deviceAdapter.addDevice(Device(it.name ?: "Unknown BLE Device", it.address, Constants.CONNECTION_TYPE_BLE))
                }
            }
        }
    }

    // --- New: Observe Wi-Fi Direct Peers ---
    private fun observeWifiDirectPeers() {
        WifiDirectManager.peers.observe(this) { wifiP2pDevices ->
            if (connectionType == Constants.CONNECTION_TYPE_WIFI_DIRECT) {
                deviceAdapter.clearDevices()
                wifiP2pDevices.forEach {
                    deviceAdapter.addDevice(Device(it.deviceName ?: "Unknown Wi-Fi Direct Device", it.deviceAddress, Constants.CONNECTION_TYPE_WIFI_DIRECT))
                }
                if (wifiP2pDevices.isEmpty()) {
                    binding.tvStatusMessage.text = "No Wi-Fi Direct peers found. Keep scanning..."
                } else {
                    binding.tvStatusMessage.text = "Found ${wifiP2pDevices.size} Wi-Fi Direct peers."
                }
            }
        }
    }

    // New: Observe Wi-Fi Direct State (Enabled/Disabled)
    private fun observeWifiDirectState() {
        WifiDirectManager.wifiDirectState.observe(this) { isEnabled ->
            if (connectionType == Constants.CONNECTION_TYPE_WIFI_DIRECT) {
                if (isEnabled) {
                    Toast.makeText(this, "Wi-Fi Direct is ON.", Toast.LENGTH_SHORT).show()
                    binding.tvStatusMessage.text = "Wi-Fi Direct is ON. Ready to scan."
                } else {
                    Toast.makeText(this, "Wi-Fi Direct is OFF. Please enable Wi-Fi.", Toast.LENGTH_LONG).show()
                    binding.tvStatusMessage.text = "Wi-Fi Direct is OFF."
                    deviceAdapter.clearDevices()
                }
            }
        }
    }

    // New: Observe Wi-Fi Direct Connection Info (crucial for socket setup)
    private fun observeWifiDirectConnectionInfo() {
        WifiDirectManager.connectionInfo.observe(this) { info ->
            if (connectionType == Constants.CONNECTION_TYPE_WIFI_DIRECT) {
                if (info.groupFormed) {
                    val connectedToName = if (info.isGroupOwner) "Clients" else info.groupOwnerAddress.hostAddress
                    Toast.makeText(this, "Wi-Fi Direct Group Formed! GO: ${info.groupOwnerAddress.hostAddress}", Toast.LENGTH_LONG).show()
                    binding.tvStatusMessage.text = "Connected via Wi-Fi Direct to ${connectedToName}"
                    binding.progressBarScanning.visibility = View.GONE

                    // THIS IS WHERE THE "CONNECTION IN BOTH APPS" LOGIC IS FULFILLED
                    // Both devices will receive this callback.
                    // Now, you can proceed to set up your TCP/IP sockets for data transfer.
                    // For now, we navigate to PageC, passing the GO's address as the "device name"
                    navigateToPageC(info.groupOwnerAddress.hostAddress)
                } else {
                    // This state might occur if group formation fails after connection attempt
                    Toast.makeText(this, "Wi-Fi Direct group not formed.", Toast.LENGTH_LONG).show()
                    binding.tvStatusMessage.text = "Wi-Fi Direct group not formed."
                    binding.progressBarScanning.visibility = View.GONE
                }
            }
        }
    }


    // --- Bluetooth Classic Permissions & Scan ---
    private fun checkBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkBluetoothClassicPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothClassicPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkBluetoothScanPermission()) permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!checkBluetoothConnectPermission()) permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (bluetoothAdapter?.isEnabled == true) {
                startScanningBluetoothClassic()
            } else {
                promptEnableBluetooth()
            }
        }
    }

    @SuppressLint("MissingPermission") // Permissions are checked before calling this
    private fun startScanningBluetoothClassic() {
        // Stop other scans
        BLEManager.stopScan()
        WifiDirectManager.disconnect() // Stop any active Wi-Fi Direct connection/discovery

        if (bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }

        deviceAdapter.clearDevices()
        binding.tvStatusMessage.text = "Scanning for Bluetooth devices..."
        binding.tvStatusMessage.visibility = View.VISIBLE
        binding.progressBarScanning.visibility = View.VISIBLE

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothDiscoveryReceiver, filter)

        bluetoothAdapter?.startDiscovery()
        Toast.makeText(this, "Bluetooth discovery started.", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission") // Permissions are checked before calling this
    private fun stopScanning() { // This method stops Classic BT discovery
        if (checkBluetoothScanPermission() && bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        } else {
            Log.e(TAG, "Cannot stop Classic BT scan: Permission missing or not discovering.")
        }
        binding.progressBarScanning.visibility = View.GONE
        binding.tvStatusMessage.visibility = View.GONE
    }

    private fun promptEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        requestBluetoothEnableLauncher.launch(enableBtIntent)
    }


    // --- BLE Permissions & Scan ---
    private fun checkBLEPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBLEPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkBluetoothScanPermission()) permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            if (!checkBluetoothConnectPermission()) permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            if (bluetoothAdapter?.isEnabled == true) {
                startScanningBLE()
            } else {
                promptEnableBluetooth()
            }
        }
    }

    private fun startScanningBLE() {
        // Stop other scans
        stopScanning() // Calls stopScanning for Classic BT
        WifiDirectManager.disconnect() // Stop any active Wi-Fi Direct connection/discovery

        deviceAdapter.clearDevices()
        binding.tvStatusMessage.text = "Scanning for BLE devices..."
        binding.tvStatusMessage.visibility = View.VISIBLE
        binding.progressBarScanning.visibility = View.VISIBLE

        BLEManager.startScan()
    }

    // --- New: Wi-Fi Direct Permissions & Scan ---
    private fun checkWifiDirectPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_WIFI_STATE)
        permissions.add(Manifest.permission.CHANGE_WIFI_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestWifiDirectPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.ACCESS_WIFI_STATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.CHANGE_WIFI_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Check if Wi-Fi is enabled
            if (wifiManager?.isWifiEnabled == true) {
                startScanningWifiDirect()
            } else {
                promptEnableWifi()
            }
        }
    }

    private fun startScanningWifiDirect() {
        // Stop other scans
        stopScanning() // Stop Classic BT discovery
        BLEManager.stopScan() // Stop BLE scan

        deviceAdapter.clearDevices()
        binding.tvStatusMessage.text = "Scanning for Wi-Fi Direct peers..."
        binding.tvStatusMessage.visibility = View.VISIBLE
        binding.progressBarScanning.visibility = View.VISIBLE

        WifiDirectManager.discoverPeers()
    }

    private fun promptEnableWifi() {
        val enableWifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
        requestWifiEnableLauncher.launch(enableWifiIntent)
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
}
