package com.abhi.connectapp.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
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
    private var bluetoothManager: android.bluetooth.BluetoothManager? = null // Added BluetoothManager
    private val bluetoothLeScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }
    private val bluetoothLeAdvertiser by lazy { bluetoothAdapter?.bluetoothLeAdvertiser }

    // BLE Client (Scanner & Gatt Client) related
    private var scanning = false
    private val scanResults = mutableMapOf<String, BluetoothDevice>()
    private var bluetoothGattClient: BluetoothGatt? = null // Renamed to clarify client role
    private var clientWriteCharacteristic: BluetoothGattCharacteristic? = null // Renamed
    private var clientNotifyCharacteristic: BluetoothGattCharacteristic? = null // Renamed

    // BLE Server (Gatt Server & Advertiser) related
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var serverWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var serverNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>() // Devices that enabled notifications
    private var advertising = false

    // LiveData for BLE Scan Results
    private val _bleScanResults = MutableLiveData<List<BluetoothDevice>>()
    val bleScanResults: LiveData<List<BluetoothDevice>> = _bleScanResults

    // LiveData for BLE Client Connection State (when this device connects to another server)
    private val _bleClientConnectionState = MutableLiveData<Pair<ConnectionState, String?>>()
    val bleClientConnectionState: LiveData<Pair<ConnectionState, String?>> = _bleClientConnectionState

    // LiveData for BLE Server Connection State (when other devices connect to this server)
    private val _bleServerConnectionState = MutableLiveData<Pair<ConnectionState, String?>>()
    val bleServerConnectionState: LiveData<Pair<ConnectionState, String?>> = _bleServerConnectionState

    // LiveData for received BLE Data (from client or server)
    private val _bleReceivedData = MutableLiveData<String>()
    val bleReceivedData: LiveData<String> = _bleReceivedData

    // Callback for BLE Scan (Client role)
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = if (hasBluetoothConnectPermission()) device.name else "N/A"
            Log.d(TAG, "BLE Device found: ${deviceName} (${device.address})")
            if (device.address != null && !scanResults.containsKey(device.address)) {
                scanResults[device.address] = device
                _bleScanResults.postValue(scanResults.values.toList())
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            for (result in results) {
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE Scan Failed: $errorCode")
            scanning = false
            _bleScanResults.postValue(emptyList())
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "BLE scan failed: $errorCode"))
        }
    }

    // Callback for BLE GATT Client operations (when this device connects to another server)
    private val bleGattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceName = if (hasBluetoothConnectPermission()) gatt.device.name else "N/A"
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT client: $deviceName ($deviceAddress)")
                    _bleClientConnectionState.postValue(Pair(ConnectionState.CONNECTED, deviceName))
                    if (hasBluetoothConnectPermission()) {
                        gatt.discoverServices()
                    } else {
                        Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for discoverServices.")
                        _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for client service discovery."))
                        gatt.close()
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT client: $deviceName ($deviceAddress)")
                    _bleClientConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, deviceName))
                    gatt.close()
                    bluetoothGattClient = null
                    clientWriteCharacteristic = null
                    clientNotifyCharacteristic = null
                }
            } else {
                Log.w(TAG, "Client Connection state change error: $status for $deviceName ($deviceAddress)")
                _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Client connection error: $deviceName (Status: $status)"))
                gatt.close()
                bluetoothGattClient = null
                clientWriteCharacteristic = null
                clientNotifyCharacteristic = null
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    clientWriteCharacteristic = service.getCharacteristic(CHARACTERISTIC_WRITE_UUID)
                    clientNotifyCharacteristic = service.getCharacteristic(CHARACTERISTIC_NOTIFY_UUID)

                    if (clientWriteCharacteristic != null) {
                        Log.d(TAG, "Client Write characteristic found: ${clientWriteCharacteristic!!.uuid}")
                    } else {
                        Log.e(TAG, "Client Write characteristic not found for service ${SERVICE_UUID}")
                    }

                    if (clientNotifyCharacteristic != null) {
                        Log.d(TAG, "Client Notify characteristic found: ${clientNotifyCharacteristic!!.uuid}")
                        if (hasBluetoothConnectPermission()) {
                            gatt.setCharacteristicNotification(clientNotifyCharacteristic, true)
                            val descriptor = clientNotifyCharacteristic?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                            descriptor?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        } else {
                            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for client notifications.")
                        }
                    } else {
                        Log.e(TAG, "Client Notify characteristic not found for service ${SERVICE_UUID}")
                    }
                } else {
                    Log.e(TAG, "Client Service not found: ${SERVICE_UUID}")
                    _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Client Service not found on device."))
                }
            } else {
                Log.w(TAG, "Client onServicesDiscovered received: $status")
                _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Client Service discovery failed: $status"))
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Client Characteristic ${characteristic.uuid} written successfully.")
            } else {
                Log.w(TAG, "Client Characteristic write failed: ${status} for ${characteristic.uuid}")
            }
        }

        @Suppress("DEPRECATION") // For older Android versions compatibility
        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value
                val message = String(data, Charsets.UTF_8)
                Log.d(TAG, "Client Characteristic ${characteristic.uuid} read: $message")
                _bleReceivedData.postValue(message)
            } else {
                Log.w(TAG, "Client Characteristic read failed: ${status} for ${characteristic.uuid}")
            }
        }

        @Suppress("DEPRECATION") // For older Android versions compatibility
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_NOTIFY_UUID) {
                val data = characteristic.value
                val message = String(data, Charsets.UTF_8)
                Log.d(TAG, "Client Characteristic ${characteristic.uuid} notified: $message")
                _bleReceivedData.postValue(message)
            }
        }
    }

    // Callback for BLE Advertising (Server role)
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            advertising = true
            Log.d(TAG, "BLE Advertising started successfully.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.CONNECTING, "Advertising..."))
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            advertising = false
            val errorMessage = "BLE Advertising failed: $errorCode"
            Log.e(TAG, errorMessage)
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, errorMessage))
        }
    }

    // Callback for BLE GATT Server operations (when other devices connect to this server)
    private val bleGattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val deviceName = if (hasBluetoothConnectPermission()) device.name else "N/A"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Server: Device connected: $deviceName (${device.address})")
                    _bleServerConnectionState.postValue(Pair(ConnectionState.CONNECTED, deviceName))
                    // Add device to list of registered devices if it's not already there
                    registeredDevices.add(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Server: Device disconnected: $deviceName (${device.address})")
                    _bleServerConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, deviceName))
                    registeredDevices.remove(device)
                }
            } else {
                Log.w(TAG, "Server: Connection state change error: $status for $deviceName (${device.address})")
                _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Server connection error: $deviceName (Status: $status)"))
                registeredDevices.remove(device)
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            if (characteristic.uuid == CHARACTERISTIC_WRITE_UUID) {
                val receivedMessage = value?.let { String(it, Charsets.UTF_8) } ?: ""
                Log.d(TAG, "Server: Received write request from ${device.name}: $receivedMessage")
                _bleReceivedData.postValue(receivedMessage)

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                Log.w(TAG, "Server: Unknown characteristic write request: ${characteristic.uuid}")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Server: Client ${device.name} enabled notifications.")
                    // Add device to list of devices that want notifications
                    registeredDevices.add(device)
                } else if (value != null && value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d(TAG, "Server: Client ${device.name} disabled notifications.")
                    // Remove device from list of devices that want notifications
                    registeredDevices.remove(device)
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            } else {
                Log.w(TAG, "Server: Unknown descriptor write request: ${descriptor.uuid}")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            super.onNotificationSent(device, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Server: Notification sent successfully to ${device.name}.")
            } else {
                Log.w(TAG, "Server: Failed to send notification to ${device.name}, status: $status")
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
            bluetoothManager = appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager // Initialize BluetoothManager
            Log.d(TAG, "BLEManager initialized.")
        }
    }

    // --- Permission Check Helpers ---
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

    fun hasBluetoothAdvertisePermission(): Boolean {
        return appContext?.let {
            // BLUETOOTH_ADVERTISE is only for Android 12 (API 31) and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Not required on older versions
            }
        } ?: false
    }

    private fun hasFineLocationPermission(): Boolean {
        return appContext?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    // --- BLE Client (Scanner & Gatt Client) Methods ---

    /**
     * Starts BLE scanning for devices.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothScanPermission()
    fun startScan() {
        if (bluetoothAdapter == null || bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothAdapter or Scanner not initialized.")
            _bleScanResults.postValue(emptyList())
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Bluetooth is off. Enable Bluetooth to scan."))
            return
        }

        // Check for necessary permissions
        if (!hasBluetoothScanPermission() || !hasFineLocationPermission()) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN or ACCESS_FINE_LOCATION permission for BLE scan.")
            _bleScanResults.postValue(emptyList())
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Missing scan permissions."))
            return
        }

        if (!scanning) {
            scanResults.clear()
            _bleScanResults.postValue(emptyList())

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID)) // Filter by our custom service UUID
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            Log.d(TAG, "Starting BLE scan...")
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
            scanning = true
            _bleClientConnectionState.postValue(Pair(ConnectionState.CONNECTING, "Scanning for BLE devices..."))
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
            if (bluetoothLeScanner != null) {
                bluetoothLeScanner?.stopScan(bleScanCallback)
                scanning = false
                Log.d(TAG, "BLE scan stopped.")
                _bleClientConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Scan finished."))
            } else {
                Log.e(TAG, "Cannot stop scan: Scanner null or BLUETOOTH_SCAN permission missing.")
            }
        }
    }

    /**
     * Connects to a selected BLE device (GATT server).
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun connect(device: BluetoothDevice) {
        if (!hasBluetoothConnectPermission()) {
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "BLUETOOTH_CONNECT permission not granted for connection."))
            return
        }
        if (bluetoothAdapter == null || appContext == null) {
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "BLE Manager not initialized."))
            return
        }

        stopScan() // Stop scanning when connecting

        Log.d(TAG, "Attempting to connect to BLE device: ${device.name} (${device.address})")
        _bleClientConnectionState.postValue(Pair(ConnectionState.CONNECTING, device.name ?: device.address))

        bluetoothGattClient = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, bleGattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(appContext, false, bleGattClientCallback)
        }
    }

    /**
     * Disconnects from the currently connected GATT server (client role).
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun disconnectClient() {
        if (bluetoothGattClient != null) {
            Log.d(TAG, "Disconnecting from GATT server (client role).")
            bluetoothGattClient?.disconnect()
        } else {
            Log.w(TAG, "Cannot disconnect client: BluetoothGattClient is null or permission missing.")
            _bleClientConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Already disconnected or permission missing."))
        }
    }

    /**
     * Writes data to a BLE characteristic on the connected GATT server (client role).
     * @param data The string data to write.
     * @return True if write was initiated, false otherwise.
     */
    @SuppressLint("MissingPermission") // Permission checked by hasBluetoothConnectPermission()
    fun writeCharacteristic(data: String): Boolean {
        if (bluetoothGattClient == null || clientWriteCharacteristic == null) {
            Log.e(TAG, "BluetoothGattClient or client write characteristic not ready.")
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Cannot send data: Not connected or characteristic missing."))
            return false
        }

        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for writing characteristic.")
            _bleClientConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for data transfer."))
            return false
        }

        val bytes = data.toByteArray(Charsets.UTF_8)
        clientWriteCharacteristic?.value = bytes

        return bluetoothGattClient?.writeCharacteristic(clientWriteCharacteristic) ?: false
    }

    // --- BLE Server (Gatt Server & Advertiser) Methods ---

    /**
     * Initializes the GATT Server and adds the custom service.
     * Call this once when the app starts or when you want to enable server capabilities.
     */
    @SuppressLint("MissingPermission") // Permissions checked internally
    fun startGattServer() {
        if (bluetoothAdapter == null || appContext == null || bluetoothManager == null) { // Added bluetoothManager check
            Log.e(TAG, "BluetoothAdapter, AppContext, or BluetoothManager not initialized for GATT Server.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Server init failed: Manager not initialized."))
            return
        }

        if (!bluetoothAdapter!!.isEnabled) {
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Bluetooth is off. Enable Bluetooth to start server."))
            return
        }

        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for GATT Server.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Server init failed: BLUETOOTH_CONNECT permission missing."))
            return
        }

        if (bluetoothGattServer != null) {
            Log.d(TAG, "GATT Server already running.")
            return
        }

        bluetoothGattServer = bluetoothManager?.openGattServer(appContext, bleGattServerCallback) // Corrected call
        bluetoothGattServer?.let { gattServer ->
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            // Add Write Characteristic
            serverWriteCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_WRITE_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(serverWriteCharacteristic)

            // Add Notify Characteristic
            serverNotifyCharacteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            ).apply {
                addDescriptor(
                    BluetoothGattDescriptor(
                        CLIENT_CHARACTERISTIC_CONFIG_UUID,
                        BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
                    )
                )
            }
            service.addCharacteristic(serverNotifyCharacteristic)

            gattServer.addService(service)
            Log.d(TAG, "GATT Server initialized and service added.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "GATT Server Ready.")) // Ready to advertise/accept
        } ?: run {
            Log.e(TAG, "Failed to open GATT Server.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Failed to open GATT Server."))
        }
    }

    /**
     * Starts advertising the BLE GATT Service.
     * Requires BLUETOOTH_ADVERTISE permission (Android 12+).
     */
    @SuppressLint("MissingPermission") // Permissions checked internally
    fun startAdvertising() {
        if (bluetoothLeAdvertiser == null) {
            Log.e(TAG, "BluetoothLEAdvertiser not initialized.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Advertising failed: Advertiser not ready."))
            return
        }
        if (!hasBluetoothAdvertisePermission()) {
            Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Advertising failed: BLUETOOTH_ADVERTISE permission missing."))
            return
        }
        if (advertising) {
            Log.d(TAG, "Already advertising.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    /**
     * Stops BLE GATT Service advertising.
     */
    @SuppressLint("MissingPermission") // Permissions checked internally
    fun stopAdvertising() {
        if (bluetoothLeAdvertiser != null && advertising) {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            advertising = false
            Log.d(TAG, "BLE Advertising stopped.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.DISCONNECTED, "Advertising stopped."))
        } else {
            Log.w(TAG, "Cannot stop advertising: Advertiser null or not advertising.")
        }
    }

    /**
     * Sends a notification to all subscribed BLE clients (Server role).
     * @param data The string data to send.
     * @return True if notification was initiated for at least one device, false otherwise.
     */
    @SuppressLint("MissingPermission") // Permissions checked by hasBluetoothConnectPermission()
    fun sendNotification(data: String): Boolean { // Added Boolean return type
        if (serverNotifyCharacteristic == null) {
            Log.e(TAG, "Server notify characteristic not ready.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Cannot send notification: Characteristic missing."))
            return false
        }
        if (!hasBluetoothConnectPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted for sending notification.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "Permission missing for notification."))
            return false
        }

        serverNotifyCharacteristic?.value = data.toByteArray(Charsets.UTF_8)

        var notificationInitiated = false
        // Iterate through all registered devices and send notification
        if (bluetoothGattServer != null) {
            for (device in registeredDevices) {
                Log.d(TAG, "Server: Sending notification to ${device.name ?: device.address}")
                // notifyCharacteristicChanged returns true if the notification was successfully queued
                if (bluetoothGattServer?.notifyCharacteristicChanged(device, serverNotifyCharacteristic, false) == true) {
                    notificationInitiated = true
                }
            }
        } else {
            Log.e(TAG, "GATT Server is null, cannot send notification.")
            _bleServerConnectionState.postValue(Pair(ConnectionState.FAILED, "GATT Server not active, cannot send notification."))
            return false
        }
        return notificationInitiated
    }

    /**
     * Cleans up all BLE resources (Client and Server).
     */
    @SuppressLint("MissingPermission") // Permissions checked internally
    fun close() {
        // Stop Client operations
        stopScan()
        disconnectClient()
        bluetoothGattClient?.close()
        bluetoothGattClient = null
        clientWriteCharacteristic = null
        clientNotifyCharacteristic = null

        // Stop Server operations
        stopAdvertising()
        bluetoothGattServer?.close()
        bluetoothGattServer = null
        serverWriteCharacteristic = null
        serverNotifyCharacteristic = null
        registeredDevices.clear()

        appContext = null
        bluetoothAdapter = null
        bluetoothManager = null // Clear BluetoothManager reference
        Log.d(TAG, "BLEManager closed.")
    }
}
