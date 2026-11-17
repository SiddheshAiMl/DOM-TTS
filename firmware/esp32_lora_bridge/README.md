# iTantra — ESP32 to SX1278 LoRa & BLE Transparent Packet Bridge Firmware

**Hardware Target:** 2 × ESP32-D0WD-V3 DevKit (4MB Flash) + 2 × SX1278 (Ra-02 433 MHz)  
**Project:** SIH26173 — iTantra  
**Regulatory Compliance:** Indian WPC G.S.R. 347(E) (433.05 - 434.79 MHz, 10 mW ERP / +10 dBm, 10% duty cycle / 360 s/hr max airtime)

---

## 1. Hardware Pinout Baseline

Connect each ESP32 DevKit to an SX1278 433 MHz LoRa module (Ai-Thinker Ra-02):

| SX1278 Pin | ESP32 GPIO | Description | Notes |
|---|---|---|---|
| **VCC** | **3.3 V** | Power Rail | Must be regulated 3.3V (peak 120 mA, never 5V) |
| **GND** | **GND** | Ground | Common ground |
| **SCK** | **GPIO 18** | SPI Clock | Hardware VSPI bus |
| **MISO** | **GPIO 19** | SPI MISO | Hardware VSPI bus |
| **MOSI** | **GPIO 23** | SPI MOSI | Hardware VSPI bus |
| **NSS / CS** | **GPIO 5** | SPI Chip Select | Active Low |
| **RESET** | **GPIO 14** | Module Reset | Active Low |
| **DIO0** | **GPIO 26** | RX/TX Done IRQ | Interrupt pin |

> [!CAUTION]
> Always attach a 433 MHz antenna before powering on and transmitting. Operating the radio without an antenna can damage the power amplifier.

---

## 2. Identified Hardware Nodes

| Node | Serial COM Port | Chip Type | Flash Size | Physical MAC | Advertised BLE Local Name |
|---|---|---|---|---|---|
| **Node A** | `COM6` | ESP32-D0WD-V3 (rev v3.1) | 4MB | `00:70:07:83:cd:14` | `iTantra-Node-CD14` |
| **Node B** | `COM7` | ESP32-D0WD-V3 (rev v3.1) | 4MB | `00:70:07:84:dd:00` | `iTantra-Node-DD00` |

---

## 3. Firmware Architecture

- **FreeRTOS Dual-Core Design:**
  - **Core 0:** BLE GATT Server (`iTantra Link Service` UUID `00007300-6954-616E-7472-614E6F646531`) and Serial UART bridge.
  - **Core 1:** Radio task controlling SX1278 via VSPI Master at 433.175 MHz.
- **Cross-Core Queues:**
  - `qBleToRadio`: Inbound packets from BLE/Serial to be transmitted over LoRa (depth: 20 packets, max 255 bytes each).
  - `qRadioToBle`: Outbound packets received over LoRa to be forwarded to BLE client / Serial (depth: 20 packets, max 255 bytes each).
- **LoRa RF Parameters:**
  - Frequency: 433.175 MHz
  - Bandwidth: 125 kHz
  - Spreading Factor: 7 (SF7)
  - Coding Rate: 4/5 (`LORA_CODING_RATE = 5`)
  - Sync Word: `0x73`
  - Tx Power: +10 dBm (10 mW ERP compliant)
- **Serial CLI (115200 Baud):**
  - `PING\r\n` -> Replies `[PONG] iTantra Node Online`
  - `STATS\r\n` -> Replies `[STATS] TX: <n> | RX: <n> | Retries: <n> | RSSI: <n> dBm | SNR: <n> dB`
  - `TX:<data>\r\n` -> Enqueues `<data>` into `qBleToRadio` for immediate over-the-air LoRa broadcast.

---

## 4. Build & Compilation Instructions

### Using Arduino CLI (Installed Host Toolchain)

```powershell
# Compile sketch with huge_app partition scheme (3MB APP / 1MB SPIFFS)
& "C:\Program Files\Arduino CLI\arduino-cli.exe" compile `
    --fqbn esp32:esp32:esp32:PartitionScheme=huge_app `
    --build-path "c:\Users\Admin\Documents\GitHub\DOM-TTS\firmware\esp32_lora_bridge\build" `
    "c:\Users\Admin\Documents\GitHub\DOM-TTS\firmware\esp32_lora_bridge"
```

**Build Output:**
- Sketch storage: 1,107,543 bytes (35% of 3,145,728 bytes)
- RAM usage: 42,316 bytes (12% of 327,680 bytes)
- Target binary: `build/esp32_lora_bridge.ino.bin`

### Using PlatformIO

```ini
; platformio.ini targets:
[env:nodeA]  ; Target COM6
[env:nodeB]  ; Target COM7
```

```powershell
pio run -e nodeA -t upload
pio run -e nodeB -t upload
```

---

## 5. Automated Flashing & Testing Scripts

The firmware folder includes turnkey PowerShell automation scripts:

1. **Flash Both Nodes:**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\firmware\esp32_lora_bridge\flash_nodes.ps1
   ```

2. **Verify Hardware PING/PONG and Bidirectional LoRa Link:**
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\firmware\esp32_lora_bridge\test_lora_link.ps1
   ```

---

## 6. Serial Boot Log Example

```
==================================================
   iTantra ESP32 LoRa-BLE Bridge (SIH26173)
==================================================
[RADIO] Initializing SX1278 at 433.175 MHz ... SUCCESS!
[BLE] Initializing as Peripheral 'iTantra-Node-CD14' ...
[BLE] GATT Server active & advertising started.
[SYSTEM] System online. Ready for transparent packet bridging.
```
