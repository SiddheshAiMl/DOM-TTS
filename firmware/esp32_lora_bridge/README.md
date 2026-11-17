# iTantra — ESP32 to SX1278 LoRa & BLE Transparent Packet Bridge Firmware
**Hardware Target:** 2 × ESP32 DevKit + 2 × SX1278 (Ra-02 433 MHz)  
**Project:** SIH26173  

---

## 1. Hardware Pinout Baseline

Connect each ESP32 DevKit to an SX1278 433 MHz LoRa module (e.g. Ai-Thinker Ra-02):

| SX1278 Pin | ESP32 GPIO | Description | Notes |
|---|---|---|---|
| **VCC** | **3.3 V** | Power Rail | Must be regulated 3.3V (never connect to 5V) |
| **GND** | **GND** | Ground | Common ground |
| **SCK** | **GPIO 18** | SPI Clock | Hardware SPI bus |
| **MISO** | **GPIO 19** | SPI MISO | Hardware SPI bus |
| **MOSI** | **GPIO 23** | SPI MOSI | Hardware SPI bus |
| **NSS / CS** | **GPIO 5** | SPI Chip Select | Active Low |
| **RESET** | **GPIO 14** | Module Reset | Active Low |
| **DIO0** | **GPIO 26** | RX/TX Done IRQ | Interrupt pin |

> [!CAUTION]
> Always attach a 433 MHz antenna before powering on and transmitting. Operating the radio without an antenna can damage the power amplifier.

---

## 2. Flashing Instructions

### Option A: Using PlatformIO (Recommended)
1. Install [PlatformIO IDE](https://platformio.org/) in VS Code.
2. Open directory: `firmware/esp32_lora_bridge`.
3. Connect your ESP32 board via USB.
4. Click **Build** (checkmark icon) and **Upload** (arrow icon).
5. Open Serial Monitor at **115200 baud**.

### Option B: Using Arduino IDE
1. Install [Arduino IDE 2.x](https://www.arduino.cc/en/software).
2. Install the ESP32 Board package via Board Manager:
   - URL: `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. Install the **LoRa** library by Sandeep Mistry via Library Manager (`Tools -> Manage Libraries...`).
4. Open `firmware/esp32_lora_bridge/esp32_lora_bridge.ino`.
5. Select board: **ESP32 Dev Module**.
6. Select port and click **Upload**.

---

## 3. Serial Monitor Output on Successful Boot

```
==================================================
   iTantra ESP32 LoRa-BLE Bridge (SIH26173)
==================================================
[RADIO] Initializing SX1278 at 433.175 MHz ... SUCCESS!
[BLE] Initializing as Peripheral 'iTantra-Node-3A4F' ...
[BLE] GATT Server active & advertising started.
[SYSTEM] System online. Ready for transparent packet bridging.
```

---

## 4. Verification & Testing Procedure

1. **BLE Advertising Verification:**
   - Launch the iTantra Android app.
   - Go to Home Dashboard -> "Scan Nearby Nodes".
   - Verify `iTantra-Node-XXXX` appears in the list with RSSI value.
   - Tap Connect and confirm GATT discovery.
2. **Two-Node LoRa Communication Test:**
   - Power Node A and Node B.
   - From Phone A, type a message in Chat or use Push-to-Talk.
   - Observe Node A Serial Monitor: `[LORA TX] Sent 47 bytes in 65 ms`.
   - Observe Node B Serial Monitor: `[LORA RX] Received 47 bytes (RSSI: -65 dBm, SNR: 8 dB)`.
   - Phone B receives the packet over BLE, decrypts it, and displays the chat bubble.
