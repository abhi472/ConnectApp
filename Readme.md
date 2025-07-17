# Connectivity App Testing Documentation

This document outlines the steps to install and test the various connectivity features (Bluetooth Classic, BLE, Wi-Fi Direct) of your Android application.

## 1. Prerequisites

Before you begin testing, ensure you have the following:

* **Two Android Devices:** To test peer-to-peer connections (Bluetooth Classic, BLE, Wi-Fi Direct), you will need at least two physical Android devices. Emulators do not fully support these advanced connectivity features.
* **Android Studio:** For building and installing the application.
* **USB Cables:** To connect devices to your development machine.
* **Developer Options Enabled:** On both devices, enable Developer Options and USB Debugging.
    * Go to `Settings > About phone`.
    * Tap "Build number" seven times.
    * Go back to `Settings > System > Developer options` (or similar path depending on Android version).
    * Enable "USB debugging".

## 2. Installation

1.  **Build the Project:**
    * Open your project in Android Studio.
    * Go to `Build > Make Project` or click the "hammer" icon.
2.  **Install on Devices:**
    * Connect your first Android device to your computer via USB.
    * In Android Studio, select your device from the target device dropdown in the toolbar.
    * Click the "Run" (green play) button.
    * Repeat the process for your second Android device.

## 3. Initial App Launch & Permissions

Upon the first launch on each device, the app will request several runtime permissions. **It is crucial to grant all of them** for the connectivity features to work correctly.

* **Bluetooth Permissions:** (e.g., `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`)
* **Location Permissions:** (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` - required for Bluetooth scanning and Wi-Fi Direct discovery).
* **Wi-Fi Permissions:** (`ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` - for Wi-Fi Direct).
* **Storage Permissions:** (`READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` - for future image gallery features).

If you accidentally deny any permissions, the app will prompt you with a dialog to go to settings. You'll need to manually grant them from: `Settings > Apps & notifications > [Your App Name] > Permissions`.

## 4. Testing Each Connection Type

### 4.1. Bluetooth Classic

**Goal:** Test peer-to-peer connection and basic message exchange.

**Steps (on both devices):**

1.  **Enable Bluetooth:**
    * Ensure Bluetooth is turned **ON** on both devices. If not, the app will prompt you to enable it.
2.  **Select "Bluetooth Classic":**
    * On the `MainActivity` screen, select "Bluetooth Classic".
3.  **Start Discovery:**
    * Both devices will navigate to `DeviceListActivity`.
    * Tap the "Scan for Devices" button on **both** devices.
    * **Important:** For Bluetooth Classic discovery, one device might need to be "discoverable." You can usually make a device discoverable from its Bluetooth settings.
4.  **Pairing (if necessary):**
    * If the devices haven't been paired before, you might get a system pairing request. Accept it on both devices.
5.  **Connect:**
    * Once devices appear in the "Discovered Peers" list on each other's screens, tap on the name of the *other* device to initiate a connection.
    * One device will act as the client, the other as the server (handled automatically by `BluetoothClassicManager`).
6.  **Verify Connection:**
    * Upon successful connection, both devices should display a "Connected to [Device Name]" toast message and navigate to `PageCActivity`.
7.  **Test Message Exchange:**
    * In `PageCActivity`, type a message in the input field and tap "Send".
    * Verify that the message appears on the other device's message display and is prefixed with "\[Remote\]:".
    * Send a message back from the second device and verify it appears on the first.
8.  **Disconnect:**
    * You can disconnect by closing `PageCActivity` (using the back button or `finish()`, as `onDestroy` calls `BluetoothClassicManager.stop()`).

### 4.2. BLE (Bluetooth Low Energy)

**Goal:** Test BLE scanning, connection, and basic message exchange via GATT characteristics.

**Steps (on both devices):**

1.  **Enable Bluetooth:**
    * Ensure Bluetooth is turned **ON** on both devices.
2.  **Select "BLE (Bluetooth Low Energy)":**
    * On the `MainActivity` screen, select "BLE (Bluetooth Low Energy)".
3.  **Start Scan:**
    * Both devices will navigate to `DeviceListActivity`.
    * Tap the "Scan for Devices" button on **both** devices.
    * BLE scanning is passive, so devices don't need to be "discoverable" in the classic sense, but they need to be advertising a compatible service (which is not implemented in this app, but for testing, any BLE device might show up).
4.  **Connect:**
    * Once BLE devices appear in the "Discovered Peers" list, tap on the name of the *other* device to initiate a connection.
    * **Note:** For a successful BLE GATT connection, the *other* device needs to be running a GATT server with the `SERVICE_UUID`, `CHARACTERISTIC_WRITE_UUID`, and `CHARACTERISTIC_NOTIFY_UUID` defined in `BLEManager.kt`. This app currently only implements the *client* side of BLE. To fully test, you'd need a separate app acting as a BLE GATT server or a physical BLE peripheral advertising those UUIDs.
    * For the purpose of this app's current client-only BLE implementation, you might see "Connection failed" if no compatible GATT server is found.
5.  **Verify Connection (if successful with a GATT server):**
    * If a compatible GATT server is connected, both devices should display a "Connected to \[Device Name\]" toast message and navigate to `PageCActivity`.
6.  **Test Message Exchange (if connected):**
    * In `PageCActivity`, type a message and tap "Send".
    * Verify that the message is sent via `BLEManager.writeCharacteristic()`. (Requires the GATT server to receive and potentially echo back for full test).
7.  **Disconnect:**
    * You can disconnect by closing `PageCActivity`.

### 4.3. Wi-Fi Direct

**Goal:** Test Wi-Fi Direct peer discovery and group formation, ensuring both apps recognize the connection.

**Steps (on both devices):**

1.  **Enable Wi-Fi:**
    * Ensure Wi-Fi is turned **ON** on both devices. You do **NOT** need to be connected to a Wi-Fi router; just Wi-Fi enabled. The app will prompt you to enable it if it's off.
2.  **Select "Wi-Fi Direct":**
    * On the `MainActivity` screen, select "Wi-Fi Direct".
3.  **Start Discovery:**
    * Both devices will navigate to `DeviceListActivity`.
    * Tap the "Scan for Devices" button on **both** devices.
    * The app will start discovering Wi-Fi Direct peers.
4.  **Connect:**
    * Once devices appear in the "Discovered Peers" list, tap on the name of the *other* device to initiate a connection.
    * The receiving device will get a system-level pop-up asking to accept or decline the Wi-Fi Direct connection. **You must accept this prompt.**
5.  **Verify Connection:**
    * Upon successful group formation, both devices should display a "Wi-Fi Direct Group Formed! GO: \[IP Address\]" toast message and navigate to `PageCActivity`.
    * The "Connected to:" info in `PageCActivity` will show the Group Owner's IP address. This confirms that both apps are aware of the established Wi-Fi Direct group.
6.  **Test Message Exchange (Placeholder):**
    * Currently, the `sendData` method in `WifiDirectManager` is a placeholder. It will log the attempt but won't actually transfer data over sockets yet. This is the next phase of implementation.
7.  **Disconnect:**
    * You can disconnect by closing `PageCActivity`.

## 5. Troubleshooting Tips

* **Permissions:** Always double-check that all required permissions are granted. Denied permissions are the most common cause of connectivity issues.
* **Bluetooth/Wi-Fi On:** Ensure the respective radio (Bluetooth or Wi-Fi) is enabled on both devices.
* **Device Visibility:** For Bluetooth Classic, ensure one device is discoverable.
* **System Prompts:** Be vigilant for system-level pop-ups (pairing requests, Wi-Fi Direct connection acceptance).
* **Logs (Logcat):** Use Android Studio's Logcat to monitor `D/BluetoothClassicManager`, `D/BLEManager`, `D/WifiDirectManager`, and `D/WiFiDirectReceiver` tags for detailed error messages and connection flow information.
* **Clear App Data:** If you encounter persistent issues, try clearing the app's data on both devices (`Settings > Apps & notifications > [Your App Name] > Storage & cache > Clear storage / Clear cache`).
* **Restart Devices:** Sometimes, a device restart can resolve underlying Bluetooth or Wi-Fi stack issues.
* **UUIDs:** For Bluetooth Classic and BLE, ensure the UUIDs used in `Constants.kt` and `BLEManager.kt` match between the two devices if you're testing with a custom service.

This documentation should provide a solid guide for thoroughly testing your application's connectivity features.