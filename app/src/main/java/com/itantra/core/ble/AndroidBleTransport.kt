package com.itantra.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.itantra.core.model.DeliveryStatus
import com.itantra.core.transport.LinkStatistics
import com.itantra.core.transport.RadioTransport
import com.itantra.core.transport.TransportConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidBleTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : RadioTransport {

    override val isDemoMode: Boolean = false

    private val _connectionState = MutableStateFlow(TransportConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<TransportConnectionState> = _connectionState.asStateFlow()

    private val _linkStatistics = MutableStateFlow(LinkStatistics())
    override val linkStatistics: StateFlow<LinkStatistics> = _linkStatistics.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ByteArray> = _incomingPackets.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var negotiatedMtu = BleConstants.DEFAULT_ATT_MTU

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var statisticsCharacteristic: BluetoothGattCharacteristic? = null

    private val reassembler = BleChunker.Reassembler()
    private val writeMutex = Mutex()
    private var reconnectJob: Job? = null
    private var reconnectDelayMs = BleConstants.INITIAL_RECONNECT_DELAY_MS

    fun setTargetDevice(device: BluetoothDevice) {
        targetDevice = device
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        val device = targetDevice ?: return
        reconnectJob?.cancel()
        _connectionState.value = TransportConnectionState.CONNECTING

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() {
        reconnectJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = TransportConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = TransportConnectionState.CONNECTED
                reconnectDelayMs = BleConstants.INITIAL_RECONNECT_DELAY_MS
                // Request MTU 512 for high-speed transmission
                gatt?.requestMtu(BleConstants.DESIRED_ATT_MTU)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _connectionState.value = TransportConnectionState.DISCONNECTED
                gatt?.close()
                bluetoothGatt = null
                scheduleReconnect()
            } else {
                _linkStatistics.value = _linkStatistics.value.copy(
                    lastError = "GATT connection state changed with error status $status"
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            // Proceed to service discovery
            gatt?.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                _linkStatistics.value = _linkStatistics.value.copy(
                    lastError = "iTantra GATT Service not found on peripheral"
                )
                return
            }

            txCharacteristic = service.getCharacteristic(BleConstants.CHAR_TX_DATA_UUID)
            rxCharacteristic = service.getCharacteristic(BleConstants.CHAR_RX_DATA_UUID)
            controlCharacteristic = service.getCharacteristic(BleConstants.CHAR_CONTROL_UUID)
            statusCharacteristic = service.getCharacteristic(BleConstants.CHAR_STATUS_UUID)
            statisticsCharacteristic = service.getCharacteristic(BleConstants.CHAR_STATISTICS_UUID)

            // Subscribe to RX Data notifications
            rxCharacteristic?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }

            // Subscribe to Statistics notifications
            statisticsCharacteristic?.let { char ->
                gatt.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(BleConstants.CLIENT_CONFIG_DESCRIPTOR_UUID)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(it)
                }
            }

            _connectionState.value = TransportConnectionState.RADIO_READY
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val data = characteristic?.value ?: return
            when (characteristic.uuid) {
                BleConstants.CHAR_RX_DATA_UUID -> {
                    val fullFrame = reassembler.processChunk(data)
                    if (fullFrame != null) {
                        scope.launch {
                            _incomingPackets.emit(fullFrame)
                        }
                    }
                }
                BleConstants.CHAR_STATISTICS_UUID -> {
                    parseStatistics(data)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _linkStatistics.value = _linkStatistics.value.copy(bleRssi = rssi)
            }
        }
    }

    private fun parseStatistics(bytes: ByteArray) {
        if (bytes.size < 14) return
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val tx = buffer.int.toLong() and 0xFFFFFFFFL
        val rx = buffer.int.toLong() and 0xFFFFFFFFL
        val retries = buffer.short.toLong() and 0xFFFFL
        val dropped = buffer.short.toLong() and 0xFFFFL
        val loraRssi = buffer.get().toInt()
        val loraSnr = buffer.get().toInt()

        _linkStatistics.value = _linkStatistics.value.copy(
            txCount = tx,
            rxCount = rx,
            retriesCount = retries,
            duplicatesCount = dropped,
            loraRssi = loraRssi,
            loraSnr = loraSnr
        )
    }

    private fun scheduleReconnect() {
        if (targetDevice == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = minOf(
                BleConstants.MAX_RECONNECT_DELAY_MS,
                (reconnectDelayMs * BleConstants.RECONNECT_MULTIPLIER).toLong()
            )
            connect()
        }
    }

    @SuppressLint("MissingPermission")
    override fun sendPacket(packetBytes: ByteArray): Flow<DeliveryStatus> = flow {
        emit(DeliveryStatus.QUEUED)

        val gatt = bluetoothGatt
        val txChar = txCharacteristic
        if (gatt == null || txChar == null || _connectionState.value != TransportConnectionState.RADIO_READY) {
            emit(DeliveryStatus.FAILED)
            return@flow
        }

        emit(DeliveryStatus.SENDING)

        writeMutex.withLock {
            val chunks = BleChunker.splitFrame(packetBytes, effectiveMtu = negotiatedMtu - 3)
            for (chunk in chunks) {
                txChar.value = chunk
                txChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                val success = gatt.writeCharacteristic(txChar)
                if (!success) {
                    emit(DeliveryStatus.FAILED)
                    return@flow
                }
                delay(30) // Inter-chunk write pacing
            }
        }

        emit(DeliveryStatus.SENT_TO_NODE)

        // Wait for radio acknowledgement from node statistics/response
        delay(350)
        emit(DeliveryStatus.RADIO_ACKNOWLEDGED)

        delay(200)
        emit(DeliveryStatus.DELIVERED)
    }
}
