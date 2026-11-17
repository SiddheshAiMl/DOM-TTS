package com.itantra.core.ble

import java.util.UUID

/**
 * BLE GATT UUIDs and Protocol Constants for iTantra ESP32 Node Bridge.
 * Exactly matches docs/ESP32_BLE_PROTOCOL.md.
 */
object BleConstants {
    // Primary Service UUID
    val SERVICE_UUID: UUID = UUID.fromString("00007300-6954-616E-7472-614E6F646531")

    // Characteristics
    val CHAR_DEVICE_INFO_UUID: UUID = UUID.fromString("00007301-6954-616E-7472-614E6F646531")
    val CHAR_TX_DATA_UUID: UUID     = UUID.fromString("00007302-6954-616E-7472-614E6F646531")
    val CHAR_RX_DATA_UUID: UUID     = UUID.fromString("00007303-6954-616E-7472-614E6F646531")
    val CHAR_CONTROL_UUID: UUID     = UUID.fromString("00007304-6954-616E-7472-614E6F646531")
    val CHAR_STATUS_UUID: UUID      = UUID.fromString("00007305-6954-616E-7472-614E6F646531")
    val CHAR_STATISTICS_UUID: UUID  = UUID.fromString("00007306-6954-616E-7472-614E6F646531")

    // Client Characteristic Configuration Descriptor (CCCD) for Notifications
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Default ATT MTU requested
    const val DESIRED_ATT_MTU = 512
    const val DEFAULT_ATT_MTU = 23

    // BLE Chunk Header Flags
    const val CHUNK_FLAG_START: Byte = 0x80.toByte()
    const val CHUNK_FLAG_END: Byte   = 0x40.toByte()

    // Bounded reconnect backoff constants
    const val INITIAL_RECONNECT_DELAY_MS = 1000L
    const val MAX_RECONNECT_DELAY_MS = 16000L
    const val RECONNECT_MULTIPLIER = 2.0
}
