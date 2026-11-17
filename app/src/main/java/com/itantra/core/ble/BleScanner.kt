package com.itantra.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredBleNode(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

class BleScanner(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _discoveredNodes = MutableStateFlow<List<DiscoveredBleNode>>(emptyList())
    val discoveredNodes: StateFlow<List<DiscoveredBleNode>> = _discoveredNodes.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = device.name ?: result.scanRecord?.deviceName ?: "iTantra Node"
            val rssi = result.rssi

            // Only capture nodes matching iTantra naming or service UUID
            val isITantra = name.contains("iTantra", ignoreCase = true) ||
                    result.scanRecord?.serviceUuids?.any { it.uuid == BleConstants.SERVICE_UUID } == true

            if (isITantra) {
                val node = DiscoveredBleNode(
                    device = device,
                    name = name,
                    address = device.address,
                    rssi = rssi
                )
                _discoveredNodes.value = (_discoveredNodes.value.filter { it.address != node.address } + node)
                    .sortedByDescending { it.rssi }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
            _errorMessage.value = "BLE scan failed with error code: $errorCode"
        }
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions()) {
            _errorMessage.value = "Bluetooth permissions not granted"
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _errorMessage.value = "Bluetooth is turned off"
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _errorMessage.value = "BLE hardware scanner unavailable"
            return
        }

        _discoveredNodes.value = emptyList()
        _errorMessage.value = null
        _isScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (hasPermissions() && _isScanning.value) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: Exception) {}
        }
        _isScanning.value = false
    }
}
