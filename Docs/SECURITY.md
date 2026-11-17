# iTantra — End-to-End Cryptographic & Security Specification
**Document ID:** SPEC-SECURITY-001  
**Target:** Android Phone A ⇄ Android Phone B End-to-End Security  
**Guarantees:** Confidentiality, Authenticity, Integrity, Nonce Uniqueness, and Anti-Replay  

---

## 1. Threat Model & Security Perimeter

In the iTantra communication path:
`Phone A ⇄ (BLE) ⇄ Node A ⇄ (433 MHz LoRa) ⇄ Node B ⇄ (BLE) ⇄ Phone B`

1. **Untrusted Intermediaries:** Both ESP32 nodes and the wireless 433 MHz radio medium are considered untrusted.
2. **Adversary Capabilities:**
   - Eavesdrop on all RF packets transmitted over the air.
   - Inject forged, corrupted, or replayed packets.
   - Intercept, modify, or compromise the ESP32 node hardware.
   - Re-transmit old messages (replay attacks).
3. **Security Objective:** Provide confidentiality, message authenticity, and integrity exclusively between the two Android applications. The ESP32 nodes forward opaque ciphertext and have no access to plaintext or decryption keys.

---

## 2. Cryptographic Algorithms & Primitives

All cryptographic primitives are standard NIST-approved or industry-standard algorithms executed via standard Android platform APIs (`javax.crypto`, `java.security`):

| Primitive | Specification | Standard / Reference |
|---|---|---|
| **Symmetric Cipher** | AES-256-GCM | NIST SP 800-38D |
| **Authentication Tag** | 128-bit (16 bytes) | NIST SP 800-38D |
| **Nonce Size** | 96-bit (12 bytes) | NIST SP 800-38D Recommendation |
| **Key Derivation Function** | HKDF-SHA-256 | RFC 5869 |
| **Pairing Secret** | 256-bit high-entropy CSPRNG | `SecureRandom` |
| **Key Storage** | Android Keystore System | Hardware-backed TEE / StrongBox |

---

## 3. Key Hierarchy & Derivation

To prevent cross-talk and reflection attacks, directional traffic uses distinct session keys.

```
                     +---------------------------------+
                     |   Master Pairing Secret (K_m)   |
                     |   (256 bits, QR Code Exchanged) |
                     +----------------+----------------+
                                      |
                                      v
                     +---------------------------------+
                     |         HKDF-Extract            |
                     |       (Salt = Session ID)       |
                     +----------------+----------------+
                                      |
                                      v
                     +---------------------------------+
                     |    Pseudorandom Key (PRK)       |
                     +----------------+----------------+
                                      |
                      +---------------+---------------+
                      | HKDF-Expand                   | HKDF-Expand
                      | Info = "iTantra-A2B-Key"      | Info = "iTantra-B2A-Key"
                      v                               v
             +-----------------+             +-----------------+
             |    Key A -> B   |             |    Key B -> A   |
             |   (256-bit AES) |             |   (256-bit AES) |
             +-----------------+             +-----------------+
```

### Derivation Steps:
1. `PRK = HKDF-Extract(salt = sessionId, IKM = masterPairingSecret)`
2. `Key_A_to_B = HKDF-Expand(PRK, info = "iTantra-v1-A2B", length = 32 bytes)`
3. `Key_B_to_A = HKDF-Expand(PRK, info = "iTantra-v1-B2A", length = 32 bytes)`

Phone A uses `Key_A_to_B` to encrypt outbound traffic and `Key_B_to_A` to decrypt inbound traffic. Phone B uses the exact inverse.

---

## 4. Nonce Construction & Uniqueness Guarantee

> [!CRITICAL]
> In AES-GCM, reusing a nonce with the same key catastrophically destroys confidentiality and allows message forgery.

To guarantee that nonces are globally unique and strictly monotonic:
- **Nonce Length:** 12 bytes (96 bits).
- **Structure:**
  - `Bytes 0..3` (4 bytes): Local Sender ID (UInt32, Big-Endian)
  - `Bytes 4..7` (4 bytes): Session ID (UInt32, Big-Endian)
  - `Bytes 8..11` (4 bytes): Monotonic Sequence Counter (UInt32, Big-Endian)

```
+-------------------+-------------------+-------------------+
| Sender ID (4B)    | Session ID (4B)   | Counter (4B)      |
+-------------------+-------------------+-------------------+
<----------------------------- 12 Bytes -------------------->
```

### Invariants:
1. The sequence counter increments by 1 for every outbound packet.
2. The sequence counter is persisted atomically in SQLite / DataStore.
3. If sequence counter reaches `0xFFFFFFFF` (4,294,967,295), or if persistent counter state is uncertain after an unexpected crash, the session is invalidated, requiring fresh session negotiation.

---

## 5. Additional Authenticated Data (AAD)

The transport header contains routing and sequencing metadata that must be visible to the protocol parser. To prevent an attacker from modifying the header over the air (e.g., tampering with sequence numbers, flags, or sender IDs), the entire header is bound as **Additional Authenticated Data (AAD)** in AES-GCM.

The AAD buffer consists of the first 31 bytes of the binary packet:
- Protocol Version (1 byte)
- Message Type (1 byte)
- Flags (1 byte)
- Sender ID (4 bytes)
- Session ID (4 bytes)
- Sequence Number (4 bytes)
- Fragment Index & Count (2 bytes)
- Nonce (12 bytes)
- Ciphertext Length (2 bytes)

If any header field is modified during radio transit, AES-GCM decryption throws an `AEADBadTagException` and the frame is immediately dropped.

---

## 6. Anti-Replay Mechanism

To prevent an eavesdropper from capturing legitimate packets and transmitting them later:
1. The receiver maintains a **Sliding Replay Window** of width 64 packets.
2. For any incoming packet with sequence number $S$:
   - If $S > S_{max}$: The window shifts right, and $S$ is marked received.
   - If $S_{max} - 64 < S \le S_{max}$: The packet is checked against the 64-bit window bitmask. If already received, it is discarded as a duplicate. If not yet received, it is marked.
   - If $S \le S_{max} - 64$: The packet is older than the window horizon and is discarded immediately.
3. Duplicate frames that pass authentication are logged for radio diagnostic metrics (duplicate counter) and dropped before reaching the chat database or speech synthesizer.

---

## 7. Pairing & Key Exchange (MVP)

1. **QR Code Pairing Exchange:**
   - Phone A generates a 256-bit random master secret $K_m$ using `SecureRandom`.
   - Phone A displays a QR code containing:
     - Version (1 byte)
     - Node A ID (4 bytes)
     - Session ID (4 bytes)
     - Master Secret $K_m$ (32 bytes, Base64)
     - Human-verifiable 6-digit confirmation code: `Truncate(HMAC-SHA256(Km, "pairing-code"), 6 digits)`.
2. **Visual Verification:**
   - Phone B scans the QR code, extracts $K_m$, and displays the matching 6-digit confirmation code.
   - Users visually confirm that both phone screens display the identical 6-digit code.
3. **Session Key Derivation:**
   - Both devices immediately derive directional keys via HKDF and zero out the ephemeral pairing UI.

---

## 8. Android Keystore Integration

1. Master pairing secrets and active session keys are protected using the **Android Keystore System**.
2. Keys are generated with flags:
   - `KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT`
   - `KeyProperties.BLOCK_MODE_GCM`
   - `KeyProperties.ENCRYPTION_PADDING_NONE`
   - Hardware-backed isolation (`InsideSecureHardware` where supported).
3. If hardware Keystore is not present on older test devices, encrypted SharedPreferences backed by master keys is used as fallback.
