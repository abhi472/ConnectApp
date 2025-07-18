package com.abhi.connectapp

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import android.Manifest
import androidx.activity.ComponentActivity
import com.abhi.connectapp.adapter.ConnectivityOptionAdapter
import com.abhi.connectapp.connectivity.WifiDirectManager // Import the new manager
import com.abhi.connectapp.databinding.ActivityMainBinding
import com.abhi.connectapp.model.ConnectivityOption
import com.abhi.connectapp.utils.Constants

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var requestPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Wi-Fi Direct Manager early
        // It's safe to call init multiple times, it only initializes once.
        WifiDirectManager.init(applicationContext)

        setupPermissionLauncher()
        setupRecyclerView()
    }

    private fun setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "All necessary permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied. App functionality may be limited.", Toast.LENGTH_LONG).show()
            }
        }
        requestInitialPermissions()
    }

    private fun requestInitialPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Bluetooth Permissions (for Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }

        // Location Permissions (for BLE scanning, BT Classic scanning, and Wi-Fi Direct)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Storage Permissions (for images in WiFi section - Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            // Wi-Fi Direct specific permission for Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else { // Android 10-12 (API 29-32)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // Wi-Fi State permissions (needed for Wi-Fi Direct on all versions)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_WIFI_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CHANGE_WIFI_STATE)
        }


        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }


    private fun setupRecyclerView() {
        val options = listOf(
            ConnectivityOption("Bluetooth Classic", Constants.CONNECTION_TYPE_BLUETOOTH_CLASSIC),
            ConnectivityOption("BLE (Bluetooth Low Energy)", Constants.CONNECTION_TYPE_BLE), // Removed "Not Implemented"
            ConnectivityOption("Wi-Fi Direct", Constants.CONNECTION_TYPE_WIFI_DIRECT) // Changed to Wi-Fi Direct
        )

        val adapter = ConnectivityOptionAdapter(options) { selectedOption ->
            val intent = Intent(this, DeviceListActivity::class.java).apply {
                putExtra(Constants.EXTRA_CONNECTION_TYPE, selectedOption.type)
            }
            startActivity(intent)
        }

        binding.rvConnectivityOptions.layoutManager = LinearLayoutManager(this)
        binding.rvConnectivityOptions.adapter = adapter
    }
}
