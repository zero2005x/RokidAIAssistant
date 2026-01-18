package com.example.rokidaiassistant.activities.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.rokidaiassistant.data.Constants
import com.example.rokidaiassistant.sdk.CxrApi
import com.example.rokidaiassistant.sdk.BluetoothStatusCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bluetooth connection page UI state
 */
data class BluetoothUiState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val devices: List<BluetoothDevice> = emptyList(),
    val error: String? = null,
    val connectedDeviceName: String? = null
)

/**
 * Bluetooth Connection ViewModel
 * 
 * Responsible for:
 * 1. BLE scanning for Rokid glasses
 * 2. Calling CxrApi to establish connection
 * 3. Managing connection state
 */
@SuppressLint("MissingPermission")
class BluetoothInitViewModel : ViewModel() {
    
    companion object {
        private const val TAG = "BluetoothInitVM"
    }
    
    private val _uiState = MutableStateFlow(BluetoothUiState())
    val uiState: StateFlow<BluetoothUiState> = _uiState.asStateFlow()
    
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    
    // BLE scan callback
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name != null && !discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                _uiState.value = _uiState.value.copy(devices = discoveredDevices.toList())
                Log.d(TAG, "Device found: ${device.name} - ${device.address}")
            }
        }
        
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result ->
                onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            val errorMsg = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already in progress"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Application registration failed"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature not supported"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Scan failed ($errorCode)"
            }
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                error = errorMsg
            )
        }
    }
    
    // Rokid Bluetooth status callback
    private val bluetoothStatusCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(uuid: String?, mac: String?, glassType: Int) {
            Log.d(TAG, "Connection info: uuid=$uuid, mac=$mac, type=$glassType")
        }
        
        override fun onConnected() {
            Log.d(TAG, "Bluetooth connected successfully!")
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    isConnected = true,
                    error = null
                )
            }
        }
        
        override fun onDisconnected() {
            Log.d(TAG, "Bluetooth disconnected")
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    isConnected = false,
                    connectedDeviceName = null
                )
            }
        }
        
        override fun onFailed(code: Int, msg: String?) {
            Log.e(TAG, "Connection failed: code=$code, msg=$msg")
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    isConnecting = false,
                    error = "Connection failed: ${msg ?: "Unknown error"} ($code)"
                )
            }
        }
    }
    
    /**
     * Start BLE scanning
     */
    fun startScan(context: Context) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (bluetoothAdapter == null) {
            _uiState.value = _uiState.value.copy(error = "This device does not support Bluetooth")
            return
        }
        
        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            _uiState.value = _uiState.value.copy(error = "Unable to get BLE scanner")
            return
        }
        
        // Clear previous device list
        discoveredDevices.clear()
        _uiState.value = _uiState.value.copy(
            isScanning = true,
            devices = emptyList(),
            error = null
        )
        
        // Set scan filter - only scan for Rokid devices
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
                .build()
        )
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        
        try {
            scanner.startScan(filters, settings, scanCallback)
            Log.d(TAG, "Started BLE scan, Service UUID: ${Constants.SERVICE_UUID}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scan", e)
            _uiState.value = _uiState.value.copy(
                isScanning = false,
                error = "Failed to start scan: ${e.message}"
            )
        }
    }
    
    /**
     * Stop BLE scanning
     */
    fun stopScan(context: Context) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop scan", e)
        }
        _uiState.value = _uiState.value.copy(isScanning = false)
        Log.d(TAG, "Stopped BLE scan")
    }
    
    /**
     * Connect to specified device
     */
    fun connectToDevice(context: Context, device: BluetoothDevice) {
        // Stop scanning first
        stopScan(context)
        
        _uiState.value = _uiState.value.copy(
            isConnecting = true,
            connectedDeviceName = device.name,
            error = null
        )
        
        try {
            Log.d(TAG, "Starting connection: ${device.name} (${device.address})")
            
            // Step 1: Initialize Bluetooth connection
            CxrApi.getInstance().initBluetooth(context, device, bluetoothStatusCallback)
            
            // Step 2: Read SN authentication file
            val snBytes = try {
                context.resources.openRawResource(Constants.getSNResource()).use { 
                    it.readBytes() 
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read SN authentication file", e)
                throw Exception("Failed to read SN authentication file. Please ensure the file is placed in res/raw/")
            }
            
            Log.d(TAG, "SN authentication file size: ${snBytes.size} bytes")
            
            // Step 3: Perform final connection (with authentication)
            val uuid = device.uuids?.firstOrNull()?.uuid?.toString() ?: ""
            CxrApi.getInstance().connectBluetooth(
                context,
                uuid,
                device.address,
                bluetoothStatusCallback,
                snBytes,
                Constants.CLIENT_SECRET
            )
            
            Log.d(TAG, "Connection request initiated, waiting for callback...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred during connection", e)
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                error = "Connection failed: ${e.message}"
            )
        }
    }
    
    /**
     * Disconnect from device
     */
    fun disconnect() {
        try {
            CxrApi.getInstance().deinitBluetooth()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
        _uiState.value = BluetoothUiState()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up resources when ViewModel is destroyed
        try {
            CxrApi.getInstance().deinitBluetooth()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
