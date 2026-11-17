# iTantra — System Architecture Specification
**Document ID:** ARCH-ITANTRA-001  
**Project:** SIH26173 — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links  
**Hardware Baseline:** 2 × ESP32 DevKit + 2 × SX1278 (433 MHz LoRa) + 2 × Android Phones  

---

## 1. Executive Overview

iTantra is an offline-first, multilingual, speech-over-text communication system designed for low-bitrate, long-range tactical and emergency communications. The architecture is built around a fundamental physical reality: **a 433 MHz LoRa link using license-exempt narrowband channels cannot transmit raw or compressed streaming voice audio.**

To solve this, iTantra decouples the communication layer into:
1. **Edge AI on Android Phones:** Complete on-device speech-to-text (STT), voice activity detection (VAD), pause-based sentence segmentation, optional translation, text-to-speech (TTS), and end-to-end authenticated cryptographic encryption (AES-256-GCM).
2. **Transparent Bridge on ESP32 Nodes:** The ESP32 DevKit acts strictly as an opaque BLE-to-LoRa and LoRa-to-BLE packet bridge. The ESP32 never processes speech models, has no access to encryption keys, and cannot inspect plaintext.
3. **Half-Duplex LoRa Radio Link (433 MHz):** Compact, encrypted binary frames are exchanged over an SX1278 transceiver using Stop-and-Wait Automatic Repeat reQuest (ARQ) with collision avoidance.

```
+----------------------------------------------------------------------------------------------------+
|                                    END-TO-END SYSTEM TOPOLOGY                                     |
+----------------------------------------------------------------------------------------------------+

  [ Android Phone A ]                                                             [ Android Phone B ]
  +------------------+                                                           +------------------+
  | - Mic Audio      |                                                           | - Speaker Audio  |
  | - VAD & STT      |                                                           | - Offline TTS    |
  | - UI & Room DB   |                                                           | - UI & Room DB   |
  | - AES-256-GCM    |                                                           | - AES-256-GCM    |
  | - BLE Central    |                                                           | - BLE Central    |
  +--------+---------+                                                           +--------+---------+
           | (BLE GATT Client)                                                            | (BLE GATT Client)
           v                                                                              v
  +--------+---------+                                                           +--------+---------+
  |  ESP32 Node A    |                                                           |  ESP32 Node B    |
  | (BLE Peripheral) |                                                           | (BLE Peripheral) |
  | - SPI Master     |                                                           | - SPI Master     |
  | - Packet Queue   |                                                           | - Packet Queue   |
  +--------+---------+                                                           +--------+---------+
           | (SPI Bus)                                                                    | (SPI Bus)
           v                                                                              v
  +--------+---------+                                                           +--------+---------+
  |  SX1278 LoRa A   | <~~~~~~~~~~~ 433 MHz Half-Duplex LoRa ~~~~~~~~~~~> |  SX1278 LoRa B   |
  |  (Transceiver)   |                (P2P Binary Frames)                        |  (Transceiver)   |
  +------------------+                                                           +------------------+
```

---

## 2. Layered Architecture

The Android application follows Clean Architecture with strict separation of concerns across presentation, domain, core, transport, and speech engine modules.

```
+-----------------------------------------------------------------------+
| Presentation Layer (Jetpack Compose, MVVM)                           |
| - OnboardingScreen     - HomeDashboardScreen   - ChatScreen           |
| - PushToTalkScreen     - HandsFreeScreen       - LanguageManagerScreen|
| - EmergencyAlertScreen - DiagnosticsScreen     - AppNavigation        |
+-----------------------------------^-----------------------------------+
                                    | StateFlow / UI Events
+-----------------------------------v-----------------------------------+
| Domain Layer (Use Cases & Repositories)                               |
| - MessageRepository    - NodeRepository        - DiagnosticsRepository|
| - SpeechSessionManager - EmergencyManager      - PairingManager       |
+-----------------------------------^-----------------------------------+
                                    | Interfaces
+-----------------------------------v-----------------------------------+
| Core & Hardware Abstraction Layer                                     |
| +-------------------------------------------------------------------+ |
| | core/protocol   : Binary Packet Serialization, ARQ, Frame Envelopes | |
| | core/crypto     : AES-256-GCM, HKDF-SHA-256, Monotonic Nonces       | |
| | core/transport  : RadioTransport API, DemoTransport, BleTransport    | |
| | core/database   : Room (Messages, Pending Queues), DataStore Prefs  | |
| +-------------------------------------------------------------------+ |
+-----------------------------------^-----------------------------------+
                                    | Native Driver / JNI / Models
+-----------------------------------v-----------------------------------+
| Speech Engines (Local On-Device Only)                                 |
| - SpeechRecognizerEngine (AI4Bharat IndicConformer via sherpa-onnx)  |
| - TextToSpeechEngine    (AI4Bharat Indic-TTS via sherpa-onnx)        |
| - VoiceActivityDetector (Silero VAD ONNX)                            |
+-----------------------------------------------------------------------+
```

---

## 3. End-to-End Data Flow

### 3.1 Transmission Flow (Sender Phone A -> Receiver Phone B)
1. **Audio Capture:** AudioRecord captures 16 kHz 16-bit mono PCM audio in transient circular buffers.
2. **VAD & Segmentation:** Silero VAD monitors speech probability chunk-by-chunk (30 ms). When silence exceeds the endpointing threshold (e.g., 600 ms), the utterance is finalized.
3. **Speech-to-Text:** The captured PCM buffer is fed to the local `SpeechRecognizerEngine` (sherpa-onnx / IndicConformer).
4. **Transient Audio Cleanup:** Raw PCM audio buffers are zeroed out immediately after transcription, ensuring audio never persists on disk unless the user explicitly enables local voice-note debugging.
5. **Transcript Verification:** The user can preview and edit the text (in PTT mode) or the text is forwarded automatically (in auto-send / hands-free mode).
6. **Authenticated Encryption:**
   - The message payload is encoded in UTF-8.
   - The transport header (version, type, flags, sender ID, session ID, sequence number) is prepared.
   - The 96-bit nonce is constructed from session state and monotonic counter.
   - The header is authenticated as Additional Authenticated Data (AAD).
   - AES-256-GCM produces the ciphertext and 16-byte authentication tag.
7. **Local Persistence:** The message is saved in Room with status `QUEUED`.
8. **BLE GATT Submission:** The Android BLE Central writes the binary frame to the ESP32 `TxData` characteristic. If the frame exceeds negotiated ATT MTU, the app performs transparent fragmentation. Status transitions to `SENT_TO_NODE`.
9. **LoRa Radio Transmission:** ESP32 Node A transmits the opaque frame over 433 MHz LoRa.
10. **Radio ACK & Stop-and-Wait:** Node B receives the frame, verifies frame CRC, and returns a LoRa ACK frame. Node A notifies Phone A via `Statistics`/`Status`, updating status to `RADIO_ACKNOWLEDGED`.
11. **Decryption on Receiver Phone B:**
    - Node B sends received frame to Phone B via BLE `RxData` notification.
    - Phone B extracts header, verifies sender ID, session ID, and validates against replay window.
    - AES-256-GCM decrypts ciphertext and authenticates AAD. If authentication fails, the frame is dropped and an alert logged.
    - Application-level ACK is transmitted back to Phone A, promoting status to `DELIVERED`.
12. **Display & Synthesis:**
    - Phone B displays the chat bubble immediately with timestamp and verified sender badge.
    - The message is pushed to the local TTS queue.
    - `TextToSpeechEngine` synthesizes audio in the target language and plays it through the device speaker.

---

## 4. Concurrency & Threading Model

| Component | Coroutine Dispatcher | Responsibility |
|---|---|---|
| Audio Capture & VAD | `Dispatchers.IO` (Dedicated Thread) | Real-time PCM reading from AudioRecord, zero frame drops |
| STT Inference | `Dispatchers.Default` | CPU-bound ONNX model execution |
| TTS Synthesis | `Dispatchers.Default` | CPU-bound acoustic model and vocoder inference |
| Cryptography & Protocol | `Dispatchers.Default` | AES-256-GCM encryption/decryption, serialization |
| BLE GATT Operations | `Dispatchers.IO` (Single Sequential Queue)| Serialized BluetoothGatt callback processing |
| Database & Preferences | `Dispatchers.IO` | Room database operations and DataStore writes |
| UI & ViewModels | `Dispatchers.Main` | Compose state rendering and user interactions |

---

## 5. Storage Architecture

### Room Database (`AppDatabase`)
- **`MessageEntity` Table:**
  - `id`: UUID (String, Primary Key)
  - `senderId`: Long
  - `recipientId`: Long
  - `sessionId`: Long
  - `sequenceNumber`: Long
  - `timestamp`: Long (Epoch Milliseconds)
  - `plaintext`: String
  - `sourceLanguage`: String (e.g., `hi`, `en`, `gu`)
  - `targetLanguage`: String
  - `deliveryStatus`: String (`QUEUED`, `SENDING`, `SENT_TO_NODE`, `RADIO_ACKNOWLEDGED`, `DELIVERED`, `FAILED`)
  - `isUrgent`: Boolean
  - `audioDurationMs`: Long
  - `retryCount`: Int

### DataStore Preferences (`app_preferences.proto` / `Preferences`)
- User identification (Local Node ID, Node Alias)
- Active input language and output voice language
- Communication mode (Typed Chat, PTT, Hands-Free)
- VAD silence endpointing threshold (300 ms to 1500 ms)
- RF regulatory profile (Frequency, Bandwidth, Spreading Factor, TX Power guard)
- UI theme settings (System, Light, Dark)

---

## 6. Offline Speech Pipeline & RAM Management

To ensure reliable operation on low-end Android hardware (3 GB - 4 GB RAM):
1. **Zero Cloud Dependencies:** All runtimes (sherpa-onnx, ONNX Runtime Mobile) operate 100% offline. Network permissions are not requested for model inference.
2. **Lazy Loading:** Speech models for a given language are only loaded into memory when that language is active.
3. **Quantized Models:** Int8 quantized ONNX models (`.int8.onnx`) are utilized to keep total RAM footprint below 250 MB during active inference.
4. **Emergency Priority Queue:** When an `ALERT` packet arrives, normal TTS playback is paused, and the emergency alert is announced immediately.
