# iTantra — Comprehensive Test & Evaluation Plan
**Document ID:** TEST-PLAN-ITANTRA-001  
**Project:** SIH26173  
**Standards:** WPC G.S.R. 347(E) Regulatory Rules, NIST SP 800-38D, IEEE Software Testing  

---

## 1. Test Levels & Strategy

The iTantra testing methodology is structured into five progressive testing tiers:
1. **Tier 1: Unit Tests (Host JVM):**
   - Binary packet encoding/decoding, edge cases, byte order.
   - Cryptographic primitives (AES-256-GCM, HKDF, AAD validation, nonce monotonic sequence, replay rejection).
   - Stop-and-Wait ARQ state machine, timeout backoff, deduplication.
2. **Tier 2: Instrumented Tests (Android Runtime):**
   - Room database DAOs, migrations, and transactional updates.
   - Android Keystore integration and cryptographic key storage.
3. **Tier 3: Hardware-in-the-Loop BLE & Transport Integration:**
   - Real ESP32 GATT connection, MTU negotiation, fragmentation/reassembly.
   - Bounded reconnection backoff upon simulated physical link loss.
4. **Tier 4: RF Compliance & LoRa Performance:**
   - 433 MHz ERP calculation, duty-cycle guard enforcement.
   - Packet delivery ratio (PDR), RSSI/SNR vs range benchmarks.
5. **Tier 5: Speech & Latency Instrumentation:**
   - WER, CER, RTF, endpointing latency, and end-to-end speech-to-speech delay.

---

## 2. Test Vectors & Unit Test Specifications

### 2.1 Packet Protocol Test Vectors
The protocol suite must validate against predefined byte vectors:
- **Test Vector 01 — Standard Text Packet:**
  - Header: Version `0x01`, Type `0x01` (TEXT), Flags `0x02` (Needs ACK), Sender `0x00000001`, Session `0x12345678`, Seq `0x00000001`, Frag `0x00/0x01`.
  - Nonce: 12 bytes.
  - Ciphertext: 16 bytes payload + 16 bytes tag.
  - Assertion: Serialized byte array matches exact expected hex string.
- **Test Vector 02 — Malformed Frame Rejection:**
  - Truncated packet (< 47 bytes) throws `MalformedPacketException`.
  - Unknown protocol version (`0xFF`) throws `UnsupportedVersionException`.
  - Mismatched ciphertext length field throws `PayloadLengthMismatchException`.

### 2.2 Security & Cryptographic Tests
- **Test Case SEC-01: AAD Tampering Detection:**
  - Mutate a single bit in the 31-byte header after encryption.
  - Assert that decryption fails with `AEADBadTagException` and the frame is rejected.
- **Test Case SEC-02: Nonce Uniqueness:**
  - Generate 100,000 sequential nonces across concurrent threads.
  - Assert that no duplicate nonce exists in the generated set.
- **Test Case SEC-03: Directional Key Separation:**
  - Encrypt packet with `Key_A_to_B`. Attempt decryption with `Key_B_to_A`.
  - Assert decryption failure.
- **Test Case SEC-04: Replay Window Rejection:**
  - Feed sequence numbers: 1, 2, 3, 2 (replayed), 4, 1 (stale).
  - Assert packets 2 (duplicate) and 1 (stale) are flagged as replayed and dropped.

---

## 3. Evaluation Metrics & Benchmark Definitions

In accordance with SIH26173 requirements, all metrics must be explicitly measured on physical devices. **No metric values may be fabricated.**

| Metric | Measurement Methodology | Unit | Target / Pass Criteria |
|---|---|---|---|
| **STT WER (Word Error Rate)** | `(Insertions + Deletions + Substitutions) / Reference Words` on standardized 50-sentence test set | % | < 15% (Clean), < 25% (Noisy) |
| **STT CER (Character Error Rate)**| Character-level Levenshtein distance (critical for Indic scripts) | % | < 8% (Clean), < 15% (Noisy) |
| **TTS Intelligibility** | Mean Opinion Score (MOS) rated by >= 5 native speakers (1 to 5 scale) | Score | MOS >= 3.8 |
| **STT Endpoint Latency** | Timestamp: user stopped speaking to final transcript rendered | ms | Median < 400 ms |
| **Radio Frame Delivery Latency**| Timestamp: BLE TxData write to BLE RxData notification on peer | ms | Median < 850 ms (LoRa SF7/BW125) |
| **Text-to-Audio Latency** | Timestamp: decrypted text available to first PCM buffer played | ms | Median < 350 ms |
| **End-to-End Latency** | Timestamp: user finished speaking to audio emitted on peer phone | ms | Median < 2.0 s |
| **TTS Real-Time Factor (RTF)** | `Synthesis Processing Duration / Synthesized Audio Duration` | Ratio | RTF < 0.4 on target device |
| **App & Model Disk Footprint** | APK size + on-disk model pack size per language | MB | APK < 35 MB, Language pack < 130 MB |
| **RAM Footprint** | Android Profiler private dirty memory during active inference | MB | Peak < 300 MB, Idle < 85 MB |
| **Packet Delivery Ratio (PDR)** | `Successfully Received Packets / Transmitted Packets` over 200 trials | % | > 92% at 500m LOS |

---

## 4. Indian WPC G.S.R. 347(E) Regulatory Compliance Test

India's Ministry of Communications notification G.S.R. 347(E) governs license-exempt usage in the **433.05 MHz – 434.79 MHz** band.

### Test Verification Procedure:
1. **Frequency Compliance:**
   - Spectrum analyzer verification that all transmission carrier frequencies are constrained strictly within 433.05 MHz to 434.79 MHz.
2. **Effective Radiated Power (ERP) Guard:**
   - In the 10 mW ERP category (Category 1), radiated power must not exceed 10 mW (+10 dBm ERP).
   - Given module output $P_{tx}$, antenna gain $G$, and line loss $L$:
     $$\text{ERP (dBm)} = P_{tx} (\text{dBm}) + G (\text{dBi}) - L (\text{dB}) - 2.15$$
   - Software limit in firmware clamps maximum power setting to prevent violation.
3. **Duty Cycle Guard Test:**
   - Under the 10% duty cycle rule, total transmission airtime must not exceed 360 seconds in any continuous 3600-second (1-hour) window.
   - Firmware test: attempt continuous transmission. Verify firmware enters transmission lockout when cumulative 1-hour airtime hits 360 seconds.

---

## 5. Anonymized CSV Log Export Specification

The Diagnostics screen allows exporting diagnostic records for research evaluation.
**Strict Privacy Rule: Plaintext message text is NEVER exported.**

### CSV Header Format:
```csv
timestamp_utc,event_type,session_id,seq_num,packet_type,payload_bytes,ble_rssi,lora_rssi,lora_snr,tx_retries,airtime_ms,delivery_status,stt_latency_ms,tts_latency_ms,e2e_latency_ms
2026-11-15T15:30:01Z,TX_DONE,305419896,1,TEXT,28,-62,-92,+5,0,185,DELIVERED,312,240,1420
2026-11-15T15:30:15Z,RX_DONE,305419896,2,ALERT,19,-58,-88,+7,1,210,DELIVERED,280,195,1150
```
