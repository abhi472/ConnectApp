package com.abhi.connectapp;



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
import com.abhi.connectapp.connectivity.BluetoothClassicManager
import com.abhi.connectapp.connectivity.ConnectionCallback
import com.abhi.connectapp.databinding.ActivityDeviceListBinding
import com.abhi.connectapp.model.Device
import com.abhi.connectapp.utils.Constants

class DeviceListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceListBinding
    private lateinit var deviceAdapter: DeviceAdapter
    private var connectionType: String? = null

    // Bluetooth Classic specific
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var bluetoothClassicManager: BluetoothClassicManager

    // BroadcastReceiver for Bluetooth Classic discovery
    private val bluetoothDiscoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Requires BLUETOOTH_SCAN permission (handled by check in startScanningBluetoothClassic)
                    // and BLUETOOTH_CONNECT permission to get device.name/address
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    device?.let {
                        // Accessing device.name/address here is generally safe because ACTION_FOUND implies
                        // the device object is valid and the system has done initial permission checks.
                        // However, the `BluetoothClassicManager` itself will re-check BLUETOOTH_CONNECT for internal calls.
                        val deviceName = it.name ?: "N/A"
                        val deviceAddress = it.address
                        Log.d("DeviceListActivity", "Found device: $deviceName ($deviceAddress)")
                        deviceAdapter.addDevice(Device(deviceName, deviceAddress, Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC))
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    stopScanning()
                    Toast.makeText(context, "Bluetooth discovery finished.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Activity Result Launchers
    private val requestBluetoothEnableLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "Bluetooth is enabled.", Toast.LENGTH_SHORT).show()
            // If permissions are already granted, start scan
            if (checkBluetoothClassicPermissions()) {
                startScanningBluetoothClassic()
            }
        } else {
            Toast.makeText(this, "Bluetooth not enabled. Cannot scan.", Toast.LENGTH_LONG).show()
            finish() // Or keep user on page with a warning
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
        setupBluetooth() // This will now pass 'this' (Context)
        setupScanButton()

        // Based on connection type, start initial process or show prompt
        when (connectionType) {
            Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                requestBluetoothClassicPermissionsAndScan()
            }
            Constants.CONNECTION_TYPE_BLE -> {
                // Implement BLE setup and scanning later
                binding.tvStatusMessage.text = "BLE scanning not yet implemented."
                binding.tvStatusMessage.visibility = View.VISIBLE
            }
            Constants.CONNECTION_TYPE_WIFI -> {
                // Implement Wi-Fi Direct setup and scanning later
                binding.tvStatusMessage.text = "Wi-Fi Direct not yet implemented."
                binding.tvStatusMessage.visibility = View.VISIBLE
            }
            else -> {
                Toast.makeText(this, "Invalid connection type.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter { device ->
            // Handle device selection for connection
            Toast.makeText(this, "Selected: ${device.name}", Toast.LENGTH_SHORT).show()
            when (connectionType) {
                Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> connectToBluetoothClassicDevice(device.address)
                // Add BLE and Wi-Fi connection initiation here later
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
                // Permissions are granted, proceed based on connection type
                when (connectionType) {
                    Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> {
                        if (bluetoothAdapter?.isEnabled == true) {
                            startScanningBluetoothClassic()
                        } else {
                            promptEnableBluetooth()
                        }
                    }
                    // Add logic for BLE and WiFi permissions here (e.g., requestLocationPermissionForBLE, requestWifiDirectPermissions)
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
                finish() // Cannot proceed without permissions
            }
            .setCancelable(false)
            .show()
    }

    private fun setupScanButton() {
        binding.btnScanDevices.setOnClickListener {
            // Clear existing devices and re-scan based on the current connection type
            deviceAdapter.clearDevices()
            when (connectionType) {
                Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC -> requestBluetoothClassicPermissionsAndScan()
                // Add BLE and Wi-Fi direct scan initiation here
            }
        }
    }

    // --- Bluetooth Classic Specific Functions ---
    private fun setupBluetooth() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Device doesn't support Bluetooth.", Toast.LENGTH_LONG).show()
            finish()
        }
        // MODIFICATION: Pass 'this' (the Context) to the BluetoothClassicManager constructor
        bluetoothClassicManager = BluetoothClassicManager(this, bluetoothAdapter!!, object :
            ConnectionCallback {
            override fun onConnectionAttempt(deviceName: String) {
                runOnUiThread {
                    Toast.makeText(this@DeviceListActivity, "Attempting to connect to $deviceName...", Toast.LENGTH_SHORT).show()
                    binding.tvStatusMessage.text = "Connecting to $deviceName..."
                    binding.tvStatusMessage.visibility = View.VISIBLE
                    binding.progressBarScanning.visibility = View.VISIBLE
                }
            }

            @SuppressLint("MissingPermission")
            override fun onConnectionSuccess(device: BluetoothDevice) {
                runOnUiThread {
                    Toast.makeText(this@DeviceListActivity, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
                    binding.tvStatusMessage.text = "Connected to ${device.name}"
                    binding.progressBarScanning.visibility = View.GONE
                    // Navigate to Page C (data exchange page)
                    val intent = Intent(this@DeviceListActivity, PageCActivity::class.java).apply {
                        // Pass device info. Note: device.name might still require BLUETOOTH_CONNECT if not already checked.
                        // However, since we're already connected, it should be fine.
                        putExtra("device_name", device.name)
                        putExtra("device_address", device.address)
                        putExtra("connection_type", Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC)
                        // IMPORTANT: For `PageCActivity` to send/receive data, it needs a reference to the `ConnectedThread`
                        // or a way to interact with `bluetoothClassicManager`. This typically involves:
                        // 1. Making `bluetoothClassicManager` a singleton (e.g., using object or dependency injection).
                        // 2. Using a shared ViewModel/repository pattern.
                        // We will address this in the `PageCActivity` implementation.
                    }
                    startActivity(intent)
                    // Consider finishing this activity or keeping it in backstack based on UX
                    finish() // Finish DeviceListActivity as connection is established.
                }
            }

            override  fun onConnectionFailed(deviceName: String, error: String) {
                runOnUiThread {
                    Toast.makeText(this@DeviceListActivity, "Failed to connect to $deviceName: $error", Toast.LENGTH_LONG).show()
                    binding.tvStatusMessage.text = "Connection failed to $deviceName: $error"
                    binding.progressBarScanning.visibility = View.GONE
                    // Re-enable scan button or suggest retry
                }
            }

            override fun onDataReceived(data: String) {
                // This callback is primarily for PageCActivity. Logging here for debug.
                Log.d("DeviceListActivity", "Data received (should be handled in PageC): $data")
            }
        })
    }

    // Checks if the necessary Bluetooth Classic permissions are granted
    private fun checkBluetoothClassicPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE) // Needed if this device will act as a server
        }
        // Location is needed for scanning on Android 6.0+
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    // Requests Bluetooth Classic specific permissions or proceeds if already granted
    private fun requestBluetoothClassicPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All permissions are already granted
            if (bluetoothAdapter?.isEnabled == true) {
                startScanningBluetoothClassic()
            } else {
                promptEnableBluetooth()
            }
        }
    }

    // Prompts the user to enable Bluetooth if it's off
    private fun promptEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        requestBluetoothEnableLauncher.launch(enableBtIntent)
    }

    // Starts the Bluetooth Classic device discovery process
    private fun startScanningBluetoothClassic() {
        if (bluetoothAdapter?.isDiscovering == true) {
            // If already discovering, cancel it before starting a new one
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.cancelDiscovery()
            } else {
                Log.e("DeviceListActivity", "BLUETOOTH_SCAN permission not granted for cancelDiscovery.")
                Toast.makeText(this, "Permission missing to stop previous scan.", Toast.LENGTH_SHORT).show()
                return
            }
        }

        deviceAdapter.clearDevices()
        binding.tvStatusMessage.text = "Scanning for Bluetooth devices..."
        binding.tvStatusMessage.visibility = View.VISIBLE
        binding.progressBarScanning.visibility = View.VISIBLE

        // Register for broadcasts when a device is found or discovery finishes
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Context.registerReceiver() requires the appropriate permissions.
        // ACTION_FOUND implicitly requires BLUETOOTH_SCAN.
        registerReceiver(bluetoothDiscoveryReceiver, filter)

        // Start discovery (this is asynchronous)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.startDiscovery()
            Toast.makeText(this, "Bluetooth discovery started.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth scan permission not granted. Cannot start discovery.", Toast.LENGTH_SHORT).show()
            stopScanning()
        }
    }

    // Stops the Bluetooth Classic discovery process
    private fun stopScanning() {
        if (bluetoothAdapter?.isDiscovering == true) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                bluetoothAdapter?.cancelDiscovery()
            } else {
                Log.e("DeviceListActivity", "BLUETOOTH_SCAN permission not granted to cancel discovery.")
            }
        }
        binding.progressBarScanning.visibility = View.GONE
        binding.tvStatusMessage.visibility = View.GONE // Or set to "Scan finished"
    }

    // Initiates connection to a selected Bluetooth Classic device
    private fun connectToBluetoothClassicDevice(address: String) {
        // Ensure BLUETOOTH_CONNECT permission is held before calling getRemoteDevice.
        // Although getRemoteDevice itself doesn't require it, subsequent socket operations do.
        // We rely on the initial permission check when starting the activity.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "BLUETOOTH_CONNECT permission not granted to initiate connection.", Toast.LENGTH_SHORT).show()
            return
        }

        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device != null) {
            stopScanning() // Stop discovery before attempting connection
            bluetoothClassicManager.connect(device)
        } else {
            Toast.makeText(this, "Device not found for connection.", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothDiscoveryReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered, or already unregistered. Safe to ignore.
            Log.w("DeviceListActivity", "Bluetooth discovery receiver not registered or already unregistered: ${e.message}")
        }
        bluetoothAdapter?.cancelDiscovery() // Ensure discovery is stopped
        bluetoothClassicManager.stop() // Clean up any ongoing connections/listeners
    }

    // Helper extension function for string capitalization
    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
}