package com.abhi.connectapp.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.UUID

// Common UUIDs for example (replace with your specific service/characteristic UUIDs)
val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB") // Example Service UUID
val CHARACTERISTIC_WRITE_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB") // Example Write Char UUID
val CHARACTERISTIC_NOTIFY_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB") // Example Notify Char UUID
val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB") // Standard CCCD UUID

object BLEManager {
    private const val TAG = "BLEManager"

    private var appContext: Context? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val bluetoothLeScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var scanning = false
    private val scanResults = mutableMapOf<String, BluetoothDevice>()

    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    // LiveData for BLE Scan Results
    private val _bleScanResults = MutableLiveData<List<BluetoothDevice>>()
    val bleScanResults: LiveData<List<BluetoothDevice>> = _bleScanResults

    // LiveData for BLE Connection State
    private val _bleConnectionState = MutableLiveData<Pair<ConnectionState, String?>>()
    val bleConnectionState: LiveData<Pair<ConnectionState, String?>> = _bleConnectionState

    // LiveData for received BLE Data (assuming we'll use a notify characteristic)
    private val _bleReceivedData = MutableLiveData<String>()
    val bleReceivedData: LiveData<String> = _bleReceivedData

    // Callback for BLE Scan
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            // Check BLUETOOTH_CONNECT permission before accessing device.name
            val deviceName = if (hasBluetoothConnectPermission()) device.name else "N/A"
            Log.d(TAG, "BLE Device found: ${deviceName} (${device.address})")
            if (device.address != null && !scanResults.containsKey(device.address)) {
                scanResults[device.address] = device
                _bleScanResults.postValue(scanResults.values.toList())
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            // Iterate through batch results and process each one
            for (result in results) {
                // When processing batch results, you typically just pass the ScanResult itself
                // to your processing logic. The 'callbackType' from the original onScanResult
                // is not relevant here as these are batched.
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) // Use a valid callback type from ScanSettings
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            scanning = false
            _bleScanResults.postValue(emptyList()) // Clear results on failure
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "BLE scan failed: $errorCode"))
        }
    }

    // Callback for BLE GATT operations
    private val bleGattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Check BLUETOOTH_CONNECT permission before accessing gatt.device.name
            val deviceName = if (hasBluetoothConnectPermission()) gatt.device.name else "N/A"
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT client: $deviceName ($deviceAddress)")
                    _bleConnectionState.postValue(Pair(ConnectionState.CONNECTED, deviceName))
                    // Discover services after successful connection
                    if (hasBluetoothConnectPermission()) {
                        gatt.discoverServices()
                    } else {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for discoverServices.")
                        _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for service discovery."))
                        // Close GATT if permissions are missing to prevent hanging
                        gatt.close() // Close even if permission check fails, to clean up
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT client: $deviceName ($deviceAddress)")
                    _bleConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, deviceName))
                    gatt.close() // Close even if permission check fails, to clean up
                    bluetoothGatt = null
                    writeCharacteristic = null
                    notifyCharacteristic = null
                }
            } else {
                Log.w(TAG, "Connection state change error: $status for $deviceName ($deviceAddress)")
                _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Connection error: $deviceName (Status: $status)"))
                gatt.close() // Close even if permission check fails, to clean up
                bluetoothGatt = null
                writeCharacteristic = null
                notifyCharacteristic = null
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    writeCharacteristic = service.getCharacteristic(CHARACTERISTIC_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID)

                    if (writeCharacteristic != null) {
                        Log.d(TAG, "Write characteristic found: ${writeCharacteristic!!.uuid}")
                    } else {
                        Log.e(TAG, "Write characteristic not found for service ${SERVICE_UUID}")
                    }

                    if (notifyCharacteristic != null) {
                        Log.d(TAG, "Notify characteristic found: ${notifyCharacteristic!!.uuid}")
                        // Enable notifications for this characteristic
                        if (hasBluetoothConnectPermission()) {
                            gatt.setCharacteristicNotification(notifyCharacteristic, true)
                            val descriptor = notifyCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                            descriptor?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        } else {
                            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for notifications.")
                            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for notifications."))
                        }
                    } else {
                        Log.e(TAG, "Notify characteristic not found for service ${SERVICE_UUID}")
                    }
                } else {
                    Log.e(TAG, "Service not found: ${SERVICE_UUID}")
                    _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Service not found on device."))
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
                _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Service discovery failed: $status"))
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Characteristic ${characteristic.uuid} written successfully.")
                // Optionally update UI that data was sent
            } else {
                Log.w(TAG, "Characteristic write failed: ${status} for ${characteristic.uuid}")
                // Inform UI about write failure
            }
        }

        @Suppress("DEPRECATION") // For older Android versions compatibility
        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                val message = String(data, Charsets.UTF_8)
                Log.d(TAG, "Characteristic ${characteristic.uuid} read: $message")
                _bleReceivedData.postValue(message) // Post to LiveData
            } else {
                Log.w(TAG, "Characteristic read failed: ${status} for ${characteristic.uuid}")
            }
        }

        @Suppress("DEPRECATION") // For older Android versions compatibility
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_NOTIFY_UUID) { // Corrected reference
                val data = characteristic.value
                val message = String(data, Charsets.UTF_8)
                Log.d(TAG, "Characteristic ${characteristic.uuid} notified: $message")
                _bleReceivedData.postValue(message) // Post to LiveData
            }
        }
    }

    /**
     * Initializes the BLEManager with the application context and BluetoothAdapter.
     */
    fun init(context: Context, adapter: BluetoothAdapter) {
        if (appContext == null) {
            appContext = context.applicationContext
            bluetoothAdapter = adapter
            Log.d(TAG, "BLEManager initialized.")
        }
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    private fun hasFineLocationPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    /**
     * Starts BLE scanning for devices.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothScanPermission()
    fun startScan() {
        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothAdapter or Scanner not initialized.")
            _bleScanResults.postValue(emptyList())
            return
        }

        // Check for necessary permissions
        if (!hasBluetoothScanPermission() || !hasFineLocationPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN or ACCESS_FINE_LOCATION permission for BLE scan.")
            _bleScanResults.postValue(emptyList()) // Clear previous results
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Missing scan permissions."))
            return
        }

        if (!scanning) {
            scanResults.clear()
            _bleScanResults.postValue(emptyList()) // Clear existing list for new scan

            val scanFilter = ScanFilter.Builder()
                //.setServiceUuid(ParcelUuid(SERVICE_UUID)) // Optional: Filter by service UUID
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            Log.d(TAG, "Starting BLE scan...")
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
            scanning = true
            _bleConnectionState.postValue(Pair(ConnectionState.CONNECTING, "Scanning for BLE devices...")) // Use CONNECTING for scanning status
            // Stop scan after a delay (e.g., 10 seconds)
            Handler(Looper.getMainLooper()).postDelayed({
                stopScan()
            }, 10000) // Scan for 10 seconds
        }
    }

    /**
     * Stops BLE scanning.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothScanPermission()
    fun stopScan() {
        if (scanning) {
            if (bluetoothLeScanner != null) { // Permission checked by hasBluetoothScanPermission()
                bluetoothLeScanner?.stopScan(bleScanCallback)
                scanning = false
                Log.d(TAG, "BLE scan stopped.")
                _bleConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Scan finished.")) // Use DISCONNECTED after scan
            } else {
                Log.e(TAG, "Cannot stop scan: Scanner null or BLUETOOTH_SCAN permission missing.")
            }
        }
    }

    /**
     * Connects to a selected BLE device (GATT server).
     * @param device The BluetoothDevice to connect to.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "BLUETOOTH_CONNECT permission not granted for connection."))
            return
        }
        if (bluetoothAdapter == null || appContext == null) {
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "BLE Manager not initialized."))
            return
        }

        stopScan() // Stop scanning when connecting

        Log.d(TAG, "Attempting to connect to BLE device: ${device.name} (${device.address})")
        _bleConnectionState.postValue(Pair(ConnectionState.CONNECTING, device.name ?: device.address))

        // Connect to GATT server
        // autoConnect=false means direct connection, faster for known devices but consumes more power
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, bleGattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, bleGattCallback)
        }
    }

    /**
     * Disconnects from the currently connected GATT server.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun disconnect() {
        if (bluetoothGatt != null) { // Permission checked by hasBluetoothConnectPermission()
            Log.d(TAG, "Disconnecting from GATT server.")
            bluetoothGatt?.disconnect()
            // State change will be handled by onConnectionStateChange callback
        } else {
            Log.w(TAG, "Cannot disconnect: BluetoothGatt is null or BLUETOOTH_CONNECT permission missing.")
            _bleConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Already disconnected or permission missing."))
        }
    }

    /**
     * Writes data to a BLE characteristic.
     * @param data The string data to write.
     * @return True if write was initiated, false otherwise.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun writeCharacteristic(data: String): Boolean {
        if (bluetoothGatt == null || writeCharacteristic == null) {
            Log.e(TAG, "BluetoothGatt or write characteristic not ready.")
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Cannot send data: Not connected or characteristic missing."))
            return false
        }

        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for writing characteristic.")
            _bleConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for data transfer."))
            return false
        }

        val bytes = data.toByteArray(Charsets.UTF_8)
        writeCharacteristic?.value = bytes

        return bluetoothGatt?.writeCharacteristic(writeCharacteristic) ?: false
    }

    /**
     * Reads data from a BLE characteristic.
     * Note: This is usually for static reads. For continuous data, use notifications.
     * @return True if read was initiated, false otherwise.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        if (bluetoothGatt == null || characteristic == null) {
            Log.e(TAG, "BluetoothGatt or characteristic not ready.")
            return false
        }
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for reading characteristic.")
            return false
        }
        return bluetoothGatt?.readCharacteristic(characteristic) ?: false
    }

    /**
     * Cleans up BLE resources.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun close() {
        stopScan()
        disconnect() // Disconnects if connected
        if (bluetoothGatt != null) { // Permission checked by hasBluetoothConnectPermission()
            bluetoothGatt?.close()
        } else {
            Log.w(TAG, "BLUETOOTH_CONNECT permission missing, cannot explicitly close BluetoothGatt.")
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        appContext = null
        bluetoothAdapter = null
        Log.d(TAG, "BLEManager closed.")
    }
}
