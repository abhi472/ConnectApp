package com.abhi.connectapp.connectivity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * A BroadcastReceiver that listens for Wi-Fi Direct system events.
 * It uses the WifiDirectManager to request updated information and
 * then the WifiDirectManager's LiveData to notify observers.
 *
 * @param manager The WifiP2pManager instance.
 * @param channel The WifiP2pManager.Channel instance.
 */
class WiFiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) : BroadcastReceiver() {

    private val TAG = "WiFiDirectReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Indicates whether Wi-Fi Direct is enabled or disabled.
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isWifiP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d(TAG, "Wi-Fi P2P state changed: ${if (isWifiP2pEnabled) "Enabled" else "Disabled"}")
                // Call a public method on WifiDirectManager to update its LiveData
                WifiDirectManager.updateWifiDirectState(isWifiP2pEnabled)
            }
            // Indicates that the available peer list has changed.
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Wi-Fi P2P peers changed. Requesting peers...")
                // Request the updated list of peers via the manager
                WifiDirectManager.requestPeers()
            }
            // Indicates that Wi-Fi Direct connection details have changed.
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "Wi-Fi P2P connection changed.")
                val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                }

                if (networkInfo?.isConnected == true) {
                    // We are connected to a P2P group. Request connection info to find Group Owner IP.
                    Log.d(TAG, "Connected to a P2P group. Requesting connection info...")
                    WifiDirectManager.requestConnectionInfo()
                } else {
                    // Disconnected from a P2P group
                    Log.d(TAG, "Disconnected from P2P group.")
                    // Call a public method on WifiDirectManager to update its LiveData
                    WifiDirectManager.updateConnectionDisconnected()
                }
            }
            // Indicates that this device's details (e.g., name, status) have changed.
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                if (device != null) {
                    Log.d(TAG, "This device changed: ${device.deviceName}, status: ${device.status}")
                    WifiDirectManager.requestDeviceInfo() // Update own device info via manager
                } else {
                    Log.e(TAG, "Device info is null in WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.")
                }
            }
        }
    }
}
