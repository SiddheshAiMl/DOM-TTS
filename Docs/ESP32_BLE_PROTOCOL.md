# iTantra — ESP32 BLE GATT & Hardware Protocol Specification
**Document ID:** SPEC-ESP32-BLE-001  
**Target Hardware:** ESP32-WROOM-32 DevKit + SX1278 (Ra-02 433 MHz LoRa)  
**Role:** Transparent BLE-to-LoRa and LoRa-to-BLE Packet Bridge  

---

## 1. Hardware Pinout Baseline

The ESP32 DevKit communicates with the SX1278 LoRa module via SPI. Both nodes must use this exact pinout:

| SX1278 Pin | ESP32 GPIO | Description |
|---|---|---|
| **VCC** | **3.3 V** | Regulated 3.3V rail (SX1278 draws up to 120 mA peak during TX; verify regulator) |
| **GND** | **GND** | Common ground connection |
| **SCK** | **GPIO 18** | SPI Clock |
| **MISO** | **GPIO 19** | SPI Master In / Slave Out |
| **MOSI** | **GPIO 23** | SPI Master Out / Slave In |
| **NSS / CS** | **GPIO 5** | SPI Chip Select (Active Low) |
| **RESET** | **GPIO 14** | Hardware Reset (Active Low) |
| **DIO0** | **GPIO 26** | RX Done / TX Done Interrupt Line |

> [!CAUTION]
> Never connect SX1278 VCC to a 5V rail (the SX1278 is a 3.3V CMOS device; 5V will destroy the transceiver). Always attach a 433 MHz matched antenna to the module before transmitting. Transmitting into an open load can destroy the RF power amplifier.

---

## 2. BLE GATT Service Definition

The ESP32 firmware advertises the Primary Service:
- **Service Name:** `iTantra Link Service`
- **Service UUID:** `00007300-6954-616E-7472-614E6F646531`

### Characteristic Table

| Characteristic | UUID | Properties | Format / Size | Description |
|---|---|---|---|---|
| **DeviceInfo** | `00007301-6954-616E-7472-614E6F646531` | `READ` | UTF-8 String | Firmware version, protocol version, Node ID, hardware rev |
| **TxData** | `00007302-6954-616E-7472-614E6F646531` | `WRITE`, `WRITE_NO_RESP` | Binary Envelope (<= MTU - 3) | Phone submits an outbound radio frame |
| **RxData** | `00007303-6954-616E-7472-614E6F646531` | `NOTIFY` | Binary Envelope (<= MTU - 3) | Node notifies Phone of an inbound radio frame |
| **Control** | `00007304-6954-616E-7472-614E6F646531` | `WRITE` | Binary Command (Variable) | Channel config, TX power, radio reset, link ping |
| **Status** | `00007305-6954-616E-7472-614E6F646531` | `READ`, `NOTIFY` | 8-byte Binary Record | Radio state, queue depth, last error code |
| **Statistics** | `00007306-6954-616E-7472-614E6F646531` | `NOTIFY` | 16-byte Binary Record | TX frames, RX frames, retries, dropped, last RSSI, SNR |

---

## 3. BLE Framing & MTU Chunking

Standard BLE 4.2 has a default ATT MTU of 23 bytes (20 bytes of payload). Android devices typically negotiate ATT MTUs between 185 and 512 bytes.

Because a LoRa application frame can reach 255 bytes, the BLE transport layer implements **application-level chunking**:

```
+-------------------------------------------------------------+
|                     BLE ENVELOPE HEADER                     |
+-------------------------------------------------------------+
| Chunk Header (2 bytes):                                     |
| - Byte 0: Chunk Control Flags (Bit 7: START, Bit 6: END)   |
|           Bits 0-5: Sequence Index (0..63)                  |
| - Byte 1: Total Frame Length (Bytes 0..255)                 |
+-------------------------------------------------------------+
| Payload Chunk (1 to MTU-5 bytes):                           |
| - Raw frame bytes                                           |
+-------------------------------------------------------------+
```

### Chunking Rules:
1. If the entire frame fits in `(negotiated_MTU - 5)` bytes, it is sent in a single chunk with flags `START | END` (`0xC0`).
2. If the frame exceeds `(negotiated_MTU - 5)` bytes:
   - Chunk 0 has flag `START` (`0x80`), Sequence 0.
   - Middle chunks have flag `0x00`, Sequence incremented.
   - Final chunk has flag `END` (`0x40`), Sequence incremented.
3. If any chunk is lost or fails during BLE notification, reassembly timeout expires (500 ms) and the partial buffer is discarded.

---

## 4. Status & Statistics Record Formats

### 4.1 Status Characteristic (8 Bytes, Big-Endian)
- `Byte 0`: Radio Hardware State
  - `0x00`: Uninitialized
  - `0x01`: Idle / Standby
  - `0x02`: Transmitting
  - `0x03`: Listening (RX continuous)
  - `0x04`: Error / Fault
- `Byte 1`: LoRa Channel Index (0..9)
- `Byte 2`: Queue Depth Outbound (0..32 packets)
- `Byte 3`: Queue Depth Inbound (0..32 packets)
- `Bytes 4-5`: Last Error Code (UInt16)
  - `0x0000`: No Error
  - `0x0001`: SX1278 SPI Timeout
  - `0x0002`: Radio Transmit Timeout
  - `0x0003`: Queue Full / Dropped Packet
  - `0x0004`: Invalid BLE Frame CRC
  - `0x0005`: WPC Duty Cycle Limit Reached
- `Bytes 6-7`: Reserved

### 4.2 Statistics Characteristic (16 Bytes, Big-Endian)
- `Bytes 0-3`: Total Packets Transmitted over LoRa (UInt32)
- `Bytes 4-7`: Total Packets Received over LoRa (UInt32)
- `Bytes 8-9`: LoRa Retransmit Count (UInt16)
- `Bytes 10-11`: Dropped Packets Count (UInt16)
- `Byte 12`: Last Packet RSSI (Signed Int8, dBm, e.g., -85 dBm)
- `Byte 13`: Last Packet SNR (Signed Int8, dB, e.g., +6 dB)
- `Bytes 14-15`: Duty Cycle Consumption (UInt16, seconds transmitted in last 1-hour window)

---

## 5. Control Commands (Write to `Control` Characteristic)

Commands are structured as: `[Command ID (1 Byte)] [Length (1 Byte)] [Payload (N Bytes)]`

| Command ID | Name | Payload | Action |
|---|---|---|---|
| `0x01` | `PING_NODE` | None | ESP32 responds immediately with status update |
| `0x02` | `RESET_RADIO` | None | ESP32 pulses SX1278 RESET pin and re-initializes registers |
| `0x03` | `SET_FREQUENCY` | 4 bytes (Hz, UInt32) | Configures carrier frequency (must be 433.05 - 434.79 MHz) |
| `0x04` | `SET_TX_POWER` | 1 byte (dBm, Int8) | Configures TX power (clamped by firmware to legal ERP limit) |
| `0x05` | `CLEAR_STATS` | None | Resets packet and error counters |

---

## 6. Reconnection & Watchdog Behavior

1. **BLE Advertising:**
   - On boot or disconnect, ESP32 begins advertising with name `iTantra-Node-XXXX` (where `XXXX` is the lower 2 bytes of the ESP32 MAC address).
   - Advertising interval: 100 ms for first 60 seconds, then 500 ms to conserve battery.
2. **Supervision Timeout:**
   - BLE connection supervision timeout: 4000 ms.
   - If phone moves out of BLE range, ESP32 clears unacknowledged BLE outbound buffers after 30 seconds to prevent stale data buildup.
3. **Hardware Watchdog:**
   - FreeRTOS hardware watchdog runs on both ESP32 cores with a 5000 ms timeout.
   - If the SPI driver hangs during radio TX/RX, the watchdog resets the module.

---

## 7. Verification & Bench Test Procedure

1. **SPI Bus Integrity Test:**
   - Firmware reads SX1278 register `0x42` (RegVersion). Must return `0x12`.
2. **BLE Echo Test:**
   - Send 64-byte payload from Android app to `TxData`.
   - ESP32 in bench-test mode immediately echoes bytes back via `RxData` notification.
   - Verify 0 byte corruption over 1,000 round-trips.
3. **LoRa Packet Loopback Test:**
   - Node A transmits 32-byte frame at 433 MHz.
   - Node B receives, checks CRC on DIO0 interrupt, and returns ACK frame.
   - Verify RSSI is between -30 dBm and -60 dBm at 1-meter bench spacing with antennas connected.
