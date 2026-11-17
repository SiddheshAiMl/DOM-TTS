# iTantra (SIH26173)
> **Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links**  
> Offline-First Speech-over-Text Communication over BLE ⇄ ESP32 ⇄ SX1278 (433 MHz LoRa)

---

## 1. System Topology

```
+-------------------+       BLE       +---------------+      433 MHz LoRa      +---------------+       BLE       +-------------------+
|  Android Phone A  | <-------------> |  Node A ESP32 | <~~~~~~~~~~~~~~~~~~~~> |  Node B ESP32 | <-------------> |  Android Phone B  |
| - Mic Capture     |  (GATT Client)  | - BLE Server  |    (Half-Duplex P2P)   | - BLE Server  |  (GATT Client)  | - Speaker Audio   |
| - VAD & STT       |                 | - SPI Master  |                        | - SPI Master  |                 | - Offline TTS     |
| - AES-256-GCM     |                 | - SX1278 LoRa |                        | - SX1278 LoRa |                 | - AES-256-GCM     |
+-------------------+                 +---------------+                        +---------------+                 +-------------------+
```

### Core Engineering Principles
1. **Never Stream Raw Voice Audio Over LoRa:** The bandwidth of 433 MHz license-exempt LoRa cannot carry real-time audio. The sender phone converts speech to compact text locally, encrypts it, transmits compact ciphertext, and the receiver phone reconstructs speech via local TTS.
2. **ESP32 is Only a Bridge:** ESP32 nodes forward opaque ciphertext frames. Speech models never run on the ESP32.
3. **100% Offline-First:** Zero cloud speech or translation APIs. All AI models execute locally on-device.
4. **End-to-End Cryptography:** Authenticated encryption (AES-256-GCM) with Additional Authenticated Data (AAD) and monotonic nonces prevents eavesdropping, tampering, and replay.

---

## 2. Target Languages (10 Indic Languages + English)

The system supports the full SIH26173 language matrix:
1. **Hindi** (`hi`)
2. **Gujarati** (`gu`)
3. **Marathi** (`mr`)
4. **Kannada** (`kn`)
5. **Malayalam** (`ml`)
6. **Tamil** (`ta`)
7. **Telugu** (`te`)
8. **Odia** (`or`)
9. **Bengali** (`bn`)
10. **English** (`en`)

---

## 3. Hardware Requirements & Wiring Baseline

### Bill of Materials:
- 2 × ESP32 DevKit (ESP-WROOM-32)
- 2 × SX1278 433 MHz LoRa transceiver module (e.g., Ai-Thinker Ra-02)
- 2 × 433 MHz matched antennas (spring coil or SMA whip)
- 2 × Android smartphones running Android 8.0+ (API 26+)

### ESP32 to SX1278 Pinout:
| SX1278 Pin | ESP32 GPIO | Description |
|---|---|---|
| **VCC** | **3.3 V** | Stable 3.3V power supply rail |
| **GND** | **GND** | Common ground |
| **SCK** | **GPIO 18** | SPI Clock |
| **MISO** | **GPIO 19** | SPI Master In / Slave Out |
| **MOSI** | **GPIO 23** | SPI Master Out / Slave In |
| **NSS / CS** | **GPIO 5** | SPI Chip Select |
| **RESET** | **GPIO 14** | Transceiver Reset |
| **DIO0** | **GPIO 26** | RX / TX Interrupt Line |

---

## 4. Operational Modes

1. **Typed Chat:** Encrypted keyboard messaging with delivery state tracking.
2. **Push-to-Talk (PTT):** Press-and-hold recording, VAD endpointing, offline STT, preview, and transmission.
3. **Hands-free / Phone Mode:** Continuous VAD phrase segmentation with turn-taking arbitration (speech-over-text, not raw cellular voice).
4. **Emergency Alert Mode:** Prioritized SOS/Medical/Evacuation presets with guaranteed radio priority queue and audible announcements.

---

## 5. Quick Start: Building & Testing

### Prerequisites
- JDK 17 or JDK 21 (e.g. bundled in Android Studio)
- Android SDK Platform 35
- Gradle 8.9+

### Build Debug APK:
```bash
./gradlew assembleDebug
```

### Run Unit Tests:
```bash
./gradlew test
```

### Transport-Demo Mode vs Physical Hardware:
The application includes a **Transport-Demo Mode** enabled by default before physical ESP32 nodes are paired.
- In Demo Mode, the app simulates BLE transmission, realistic radio latency (250 ms), stop-and-wait ACK roundtrip (350 ms), and local TTS synthesis.
- A prominent yellow banner indicates **DEMO TRANSPORT ACTIVE** so that simulated delivery is never mistaken for real LoRa RF transmission.
- Once connected to a real ESP32 node via BLE, the app seamlessly switches to real hardware transport.

---

## 6. Offline Model Pack Setup

Model packs are loaded locally without internet connectivity.
See [docs/MODEL_LICENSING.md](docs/MODEL_LICENSING.md) for expected directory structure, checksums, and license provenance.

To import language packs:
1. Connect phone via USB or use device file manager.
2. Place verified model packs in:
   `Android/data/com.itantra/files/models/<lang_code>/`
3. Launch app and open **Language & Model Manager** to verify model readiness and SHA256 integrity.

---

## 7. Documentation Index

- [System Architecture](docs/ARCHITECTURE.md)
- [ESP32 BLE GATT Protocol](docs/ESP32_BLE_PROTOCOL.md)
- [Security & Cryptography](docs/SECURITY.md)
- [Model Licensing & Provenance](docs/MODEL_LICENSING.md)
- [Test & Evaluation Plan](docs/TEST_PLAN.md)
