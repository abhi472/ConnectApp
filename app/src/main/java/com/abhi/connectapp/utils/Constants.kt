package com.abhi.connectapp.utils

import java.util.UUID

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
    const val REQUEST_CODE_ENABLE_WIFI = 1007 // New: For enabling Wi-Fi if needed

    // Connection Types
    const val CONNECTION_TYPE_BLUETOOTH_CLASSIC = "bluetooth_classic"
    const val CONNECTION_TYPE_BLE = "ble"
    const val CONNECTION_TYPE_WIFI_DIRECT = "wifi_direct" // New: Wi-Fi Direct connection type

    // Bluetooth Classic UUID (IMPORTANT: Generate a unique one for your app for production)
    // You can use an online UUID generator (e.g., uuidgenerator.net)
    // For testing with generic SPP apps, you might use 00001101-0000-1000-8000-00805F9B34FB
    val BLUETOOTH_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    // Example of a custom UUID: UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    // val MY_APP_SECURE_UUID: UUID = UUID.fromString("e0cbf06c-cd8b-46ce-bb8f-8d99c4392a54") // Replace with YOUR unique UUID!
}
