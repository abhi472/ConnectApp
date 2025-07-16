package com.abhi.connectapp.utils

object Constants {
    // Intent Extras
    const val EXTRA_CONNECTION_TYPE = "connection_type"

    // Request Codes
    const val REQUEST_CODE_BLUETOOTH_PERMISSIONS = 1001
    const val REQUEST_CODE_LOCATION_PERMISSION = 1002
    const val REQUEST_CODE_WIFI_PERMISSIONS = 1003
    const val REQUEST_CODE_STORAGE_PERMISSIONS = 1004
    const val REQUEST_CODE_ENABLE_BLUETOOTH = 1005
    const val REQUEST_CODE_PICK_IMAGE = 1006

    // Connection Types
    const val CONNECTION_TYPE_BLUETOOTH_CLASSIC = "bluetooth_classic"
    const val CONNECTION_TYPE_BLE = "ble"
    const val CONNECTION_TYPE_WIFI = "wifi"

    // Bluetooth Classic UUID (you should generate a unique one for your app)
    val BLUETOOTH_SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard SPP UUID, or generate your own
    // You should generate a unique UUID for your app to avoid conflicts.
    // Example: val MY_APP_UUID: java.util.UUID = java.util.UUID.fromString("YOUR-UNIQUE-UUID-HERE")
}