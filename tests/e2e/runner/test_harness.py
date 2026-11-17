"""
High-Fidelity Hardware (COM6/COM7) and Simulated Radio/Protocol Test Harness
for iTantra (SIH26173) E2E Test Suite.
Conforms strictly to TEST_INFRA.md and PROJECT.md specifications.
"""

import os
import sys
import time
import struct
import hmac
import hashlib
import binascii
import collections
from enum import Enum, auto
from typing import Optional, Tuple, List, Dict, Any

# Cryptography support
try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives import hashes
    HAS_CRYPTOGRAPHY = True
except ImportError:
    HAS_CRYPTOGRAPHY = False

# Serial hardware support
try:
    import serial
    import serial.tools.list_ports
    HAS_SERIAL = True
except ImportError:
    HAS_SERIAL = False


# ==============================================================================
# Protocol Constants & Enums
# ==============================================================================

class MessageType(Enum):
    TEXT = 0x01
    ALERT = 0x02
    ACK = 0x03
    PING = 0x04
    PONG = 0x05
    FRAGMENT = 0x06
    STATUS = 0x07

class PacketFlag:
    FLAG_URGENT = 0x01       # Bit 0
    FLAG_NEEDS_ACK = 0x02     # Bit 1
    FLAG_COMPRESSED = 0x04    # Bit 2

class ArqState(Enum):
    QUEUED = auto()
    SENDING = auto()
    SENT_TO_NODE = auto()
    WAITING_FOR_ACK = auto()
    RADIO_ACKNOWLEDGED = auto()
    DELIVERED = auto()
    RETRY = auto()
    FAILED = auto()

class ModelStatus(Enum):
    NOT_INSTALLED = auto()
    DOWNLOADING = auto()
    VERIFYING = auto()
    READY = auto()
    CORRUPTED = auto()

HEADER_SIZE = 31
TAG_SIZE = 16
MIN_PACKET_SIZE = HEADER_SIZE + TAG_SIZE  # 47 bytes
MAX_PACKET_SIZE = 255
MAX_CIPHERTEXT_SIZE = MAX_PACKET_SIZE - MIN_PACKET_SIZE  # 208 bytes

WPC_CARRIER_FREQ_HZ = 433175000  # 433.175 MHz
WPC_BANDWIDTH_HZ = 125000       # 125 kHz
WPC_MAX_DUTY_CYCLE_SEC = 360    # 10% duty cycle / 360 seconds per hour
WPC_WINDOW_SEC = 3600
WPC_MAX_TX_POWER_DBM = 10       # 10 mW ERP cap

BLE_CHUNK_HEADER_SIZE = 2
CHUNK_FLAG_START = 0x80
CHUNK_FLAG_END = 0x40
CHUNK_SEQ_MASK = 0x3F


# ==============================================================================
# CRC16 & Framing Utilities
# ==============================================================================

def crc16_ccitt(data: bytes, poly: int = 0x1021, init: int = 0xFFFF) -> int:
    """Computes CRC-16-CCITT (standard SX1278 hardware CRC)."""
    crc = init
    for byte in data:
        crc ^= (byte << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ poly) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


def cobs_encode(data: bytes) -> bytes:
    """Consistent Overhead Byte Stuffing (COBS) encoding with 0x00 delimiter."""
    dest = bytearray()
    code_idx = 0
    code = 1
    dest.append(0)  # Placeholder for first code

    for byte in data:
        if byte == 0:
            dest[code_idx] = code
            code = 1
            code_idx = len(dest)
            dest.append(0)
        else:
            dest.append(byte)
            code += 1
            if code == 0xFF:
                dest[code_idx] = code
                code = 1
                code_idx = len(dest)
                dest.append(0)

    dest[code_idx] = code
    dest.append(0x00)  # Delimiter
    return bytes(dest)


def cobs_decode(frame: bytes) -> bytes:
    """Consistent Overhead Byte Stuffing (COBS) decoding."""
    if not frame:
        raise ValueError("Empty COBS frame")
    if frame[-1] == 0x00:
        frame = frame[:-1]
    if not frame:
        return b""

    dest = bytearray()
    idx = 0
    length = len(frame)

    while idx < length:
        code = frame[idx]
        if code == 0:
            raise ValueError("Zero byte encountered in COBS payload")
        idx += 1
        for _ in range(code - 1):
            if idx >= length:
                raise ValueError("Premature end of COBS frame")
            dest.append(frame[idx])
            idx += 1
        if code < 0xFF and idx < length:
            dest.append(0x00)

    return bytes(dest)


# ==============================================================================
# Binary Packet Protocol
# ==============================================================================

class LoRaPacket:
    """Represents a 31-byte Big-Endian authenticated header packet with ciphertext and tag."""

    def __init__(self,
                 version: int = 0x01,
                 msg_type: int = MessageType.TEXT.value,
                 flags: int = PacketFlag.FLAG_NEEDS_ACK,
                 sender_id: int = 1,
                 session_id: int = 1,
                 seq_num: int = 1,
                 frag_index: int = 0,
                 frag_count: int = 1,
                 nonce: bytes = b"\x00" * 12,
                 ciphertext: bytes = b"",
                 auth_tag: bytes = b"\x00" * 16):
        self.version = version
        self.msg_type = msg_type if isinstance(msg_type, int) else msg_type.value
        self.flags = flags
        self.sender_id = sender_id
        self.session_id = session_id
        self.seq_num = seq_num
        self.frag_index = frag_index
        self.frag_count = frag_count
        self.nonce = nonce if len(nonce) == 12 else nonce.ljust(12, b"\x00")[:12]
        self.ciphertext = ciphertext
        self.ciphertext_len = len(ciphertext)
        self.auth_tag = auth_tag if len(auth_tag) == 16 else auth_tag.ljust(16, b"\x00")[:16]

    @property
    def is_urgent(self) -> bool:
        return bool(self.flags & PacketFlag.FLAG_URGENT)

    @property
    def needs_ack(self) -> bool:
        return bool(self.flags & PacketFlag.FLAG_NEEDS_ACK)

    @property
    def is_compressed(self) -> bool:
        return bool(self.flags & PacketFlag.FLAG_COMPRESSED)

    def pack_header(self) -> bytes:
        """Serializes the 31-byte Big-Endian header (used as AES-GCM AAD)."""
        return struct.pack(
            ">BBBIIIBB12sH",
            self.version,
            self.msg_type,
            self.flags,
            self.sender_id,
            self.session_id,
            self.seq_num,
            self.frag_index,
            self.frag_count,
            self.nonce,
            len(self.ciphertext)
        )

    def pack_frame(self) -> bytes:
        """Serializes the complete over-the-air binary packet (Header + Ciphertext + Tag)."""
        header = self.pack_header()
        frame = header + self.ciphertext + self.auth_tag
        if len(frame) > MAX_PACKET_SIZE:
            raise ValueError(f"Packet size {len(frame)} exceeds MAX_LORA_FRAME_LEN (255)")
        return frame

    @classmethod
    def unpack_frame(cls, raw: bytes) -> "LoRaPacket":
        """Deserializes a complete over-the-air binary packet."""
        if len(raw) < MIN_PACKET_SIZE:
            raise ValueError(f"Packet length {len(raw)} is less than minimum {MIN_PACKET_SIZE}")
        if len(raw) > MAX_PACKET_SIZE:
            raise ValueError(f"Packet length {len(raw)} exceeds maximum {MAX_PACKET_SIZE}")

        header_bytes = raw[:HEADER_SIZE]
        version, msg_type, flags, sender_id, session_id, seq_num, frag_index, frag_count, nonce, ct_len = \
            struct.unpack(">BBBIIIBB12sH", header_bytes)

        expected_total = HEADER_SIZE + ct_len + TAG_SIZE
        if len(raw) != expected_total:
            raise ValueError(f"Frame length {len(raw)} mismatch with header ciphertext length {ct_len} (expected {expected_total})")

        ciphertext = raw[HEADER_SIZE:HEADER_SIZE + ct_len]
        auth_tag = raw[HEADER_SIZE + ct_len:HEADER_SIZE + ct_len + TAG_SIZE]

        return cls(
            version=version,
            msg_type=msg_type,
            flags=flags,
            sender_id=sender_id,
            session_id=session_id,
            seq_num=seq_num,
            frag_index=frag_index,
            frag_count=frag_count,
            nonce=nonce,
            ciphertext=ciphertext,
            auth_tag=auth_tag
        )


# ==============================================================================
# BLE Chunking & Reassembly
# ==============================================================================

def ble_chunk_packet(packet_bytes: bytes, mtu: int = 23) -> List[bytes]:
    """Splits a full LoRa packet into BLE chunks according to the BLE Chunking Envelope."""
    max_payload = max(mtu - 5, 18)
    if max_payload > 240:
        max_payload = 240

    total_len = len(packet_bytes)
    chunks = []
    offset = 0
    seq = 0

    while offset < total_len:
        chunk_len = min(total_len - offset, max_payload)
        is_start = (offset == 0)
        is_end = (offset + chunk_len >= total_len)

        flags = (seq & CHUNK_SEQ_MASK)
        if is_start:
            flags |= CHUNK_FLAG_START
        if is_end:
            flags |= CHUNK_FLAG_END

        chunk = bytearray()
        chunk.append(flags)
        chunk.append(total_len & 0xFF)
        chunk.extend(packet_bytes[offset:offset + chunk_len])
        chunks.append(bytes(chunk))

        offset += chunk_len
        seq = (seq + 1) & CHUNK_SEQ_MASK

    return chunks


class BleReassembler:
    """ESP32 firmware-equivalent BLE chunk reassembler."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.buffer = bytearray()
        self.expected_length = 0
        self.last_seq = -1
        self.is_complete = False

    def push_chunk(self, chunk: bytes) -> Optional[bytes]:
        """Processes an incoming BLE chunk and returns full packet if reassembly complete."""
        if len(chunk) < BLE_CHUNK_HEADER_SIZE:
            return None

        flags = chunk[0]
        total_len = chunk[1]
        is_start = bool(flags & CHUNK_FLAG_START)
        is_end = bool(flags & CHUNK_FLAG_END)
        seq = flags & CHUNK_SEQ_MASK
        payload = chunk[BLE_CHUNK_HEADER_SIZE:]

        if is_start:
            self.buffer = bytearray(payload)
            self.expected_length = total_len
            self.last_seq = seq
        else:
            if self.expected_length == 0:
                return None
            expected_seq = (self.last_seq + 1) & CHUNK_SEQ_MASK
            if seq != expected_seq:
                # Out-of-order chunk -> drop and reset
                self.reset()
                return None
            self.last_seq = seq
            self.buffer.extend(payload)

        if is_end and len(self.buffer) == self.expected_length:
            result = bytes(self.buffer)
            self.reset()
            return result
        return None


# ==============================================================================
# Cryptographic Operations (AES-256-GCM + HKDF + Nonce Replay Window)
# ==============================================================================

class CryptoEngine:
    """AES-256-GCM authenticated encryption/decryption with HKDF subkeys and replay protection."""

    def __init__(self, key: Optional[bytes] = None):
        self.key = key or os.urandom(32)
        self.nonce_counter = 0
        self.replay_window_bitmap = 0  # 64-bit sliding window bitmap
        self.highest_seq = 0

    @classmethod
    def derive_keys(cls, master_secret: bytes, salt: bytes = b"iTantra-Salt-v1") -> Tuple[bytes, bytes]:
        """Derives 32-byte encryption and 32-byte auth sub-keys using HKDF-SHA256."""
        if HAS_CRYPTOGRAPHY:
            hkdf = HKDF(
                algorithm=hashes.SHA256(),
                length=64,
                salt=salt,
                info=b"iTantra-Subkeys"
            )
            derived = hkdf.derive(master_secret)
        else:
            # Standard RFC 5869 fallback implementation
            prk = hmac.new(salt, master_secret, hashlib.sha256).digest()
            t1 = hmac.new(prk, b"iTantra-Subkeys" + b"\x01", hashlib.sha256).digest()
            t2 = hmac.new(prk, t1 + b"iTantra-Subkeys" + b"\x02", hashlib.sha256).digest()
            derived = t1 + t2
        return derived[:32], derived[32:64]

    def generate_nonce(self) -> bytes:
        """Generates a monotonic 12-byte nonce (4-byte timestamp + 8-byte counter)."""
        self.nonce_counter += 1
        ts = int(time.time()) & 0xFFFFFFFF
        return struct.pack(">IQ", ts, self.nonce_counter)

    def encrypt(self, plaintext: bytes, aad_header: bytes, nonce: Optional[bytes] = None) -> Tuple[bytes, bytes, bytes]:
        """Encrypts plaintext with AES-256-GCM, returning (nonce, ciphertext, auth_tag)."""
        iv = nonce or self.generate_nonce()
        if HAS_CRYPTOGRAPHY:
            aesgcm = AESGCM(self.key)
            ct_and_tag = aesgcm.encrypt(iv, plaintext, aad_header)
            ciphertext = ct_and_tag[:-16]
            tag = ct_and_tag[-16:]
            return iv, ciphertext, tag
        else:
            # Fallback HMAC-based authenticated envelope if cryptography lib missing
            ciphertext = bytearray(b ^ self.key[i % len(self.key)] for i, b in enumerate(plaintext))
            tag = hmac.new(self.key, aad_header + iv + ciphertext, hashlib.sha256).digest()[:16]
            return iv, bytes(ciphertext), tag

    def decrypt(self, ciphertext: bytes, aad_header: bytes, nonce: bytes, tag: bytes) -> bytes:
        """Decrypts AES-256-GCM and verifies 16-byte auth tag against AAD header."""
        if HAS_CRYPTOGRAPHY:
            aesgcm = AESGCM(self.key)
            ct_and_tag = ciphertext + tag
            return aesgcm.decrypt(nonce, ct_and_tag, aad_header)
        else:
            expected_tag = hmac.new(self.key, aad_header + nonce + ciphertext, hashlib.sha256).digest()[:16]
            if not hmac.compare_digest(expected_tag, tag):
                raise ValueError("Authentication tag mismatch (tampering detected)")
            return bytes(b ^ self.key[i % len(self.key)] for i, b in enumerate(ciphertext))

    def check_replay(self, seq_num: int) -> bool:
        """64-bit sliding window replay attack protection. Returns True if sequence is valid (not replayed)."""
        if seq_num > self.highest_seq:
            diff = seq_num - self.highest_seq
            if diff < 64:
                self.replay_window_bitmap = (self.replay_window_bitmap << diff) | 1
            else:
                self.replay_window_bitmap = 1
            self.highest_seq = seq_num
            return True
        else:
            diff = self.highest_seq - seq_num
            if diff >= 64:
                # Outside 64-bit window -> reject
                return False
            bit_mask = 1 << diff
            if self.replay_window_bitmap & bit_mask:
                # Already received in window -> replay!
                return False
            self.replay_window_bitmap |= bit_mask
            return True


# ==============================================================================
# Multilingual Semantic Compression Engine
# ==============================================================================

class SemanticCompressionEngine:
    """Compact tokenization & compression engine for 10 Indic languages (<60 bytes payload)."""

    INDIC_LANGUAGES = ["hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or", "en"]

    DICTIONARY_PRESETS = {
        "hi": ["नमस्ते", "तुरंत", "बेस", "कैंप", "पर", "रिपोर्ट", "करो", "मदद", "चाहिए", "सुरक्षित", "पानी", "दवा"],
        "bn": ["নমস্কার", "আমরা", "নিরাপদ", "স্থানে", "আছি", "সাহায্য", "জরুরী", "জল", "ওষুধ", "বন্যা", "আশ্রয়"],
        "mr": ["नमस्कार", "आम्ही", "सुरक्षित", "आहोत", "तात्काळ", "मदत", "हवी", "डॉक्टर", "पाणी", "अन्न"],
        "te": ["నమస్కారం", "రవాణా", "సిద్ధంగా", "ఉంది", "సహాయం", "కావాలి", "నీరు", "ఆహారం", "రక్షణ"],
        "ta": ["வணக்கம்", "உதவி", "தேவைப்படுகிறது", "பாதுகாப்பான", "இடத்தில்", "இருக்கிறோம்", "மருந்து", "உணவு"],
        "gu": ["નમસ્તે", "અમને", "મદદની", "જરૂર", "છે", "સુરક્ષિત", "પાણી", "ખોરાક", "ડોક્ટર"],
        "kn": ["ನಮಸ್ಕಾರ", "ತುರ್ತು", "ಸಹಾಯ", "ಬೇಕಾಗಿದೆ", "ಸುರಕ್ಷಿತ", "ಸ್ಥಳ", "ಔಷಧ", "ಆಹಾರ"],
        "ml": ["നമസ്കാരം", "സഹായം", "ആവശ്യമാണ്", "സുരക്ഷിതമാണ്", "വെള്ളം", "മരുന്ന്", "രക്ഷാപ്രവർത്തനം"],
        "or": ["ନମସ୍କାର", "ଆମେ", "ସୁରକ୍ଷିତ", "ଅଛୁ", "ସାହାଯ୍ୟ", "ଦରକାର", "ଡାକ୍ତର", "ଜଳ", "ଖାଦ୍ୟ"],
        "en": ["hello", "immediate", "base", "camp", "report", "help", "needed", "safe", "water", "medical", "evacuate"]
    }

    @classmethod
    def compress(cls, text: str, lang: str = "hi") -> bytes:
        """Compresses Indic utterance into tokenized binary byte array."""
        if not text:
            return b""
        tokens = text.strip().split()
        compressed = bytearray()
        compressed.append(cls.INDIC_LANGUAGES.index(lang) if lang in cls.INDIC_LANGUAGES else 0)

        vocab = cls.DICTIONARY_PRESETS.get(lang, cls.DICTIONARY_PRESETS["en"])
        for token in tokens:
            if token in vocab:
                idx = vocab.index(token)
                compressed.append(0x80 | (idx & 0x7F))  # Token index mark
            else:
                raw_bytes = token.encode("utf-8")
                compressed.append(len(raw_bytes))
                compressed.extend(raw_bytes)

        return bytes(compressed)

    @classmethod
    def decompress(cls, payload: bytes) -> Tuple[str, str]:
        """Decompresses tokenized binary byte array back to original text and language code."""
        if not payload:
            return "", "en"
        lang_idx = payload[0]
        lang = cls.INDIC_LANGUAGES[lang_idx] if lang_idx < len(cls.INDIC_LANGUAGES) else "en"
        vocab = cls.DICTIONARY_PRESETS.get(lang, cls.DICTIONARY_PRESETS["en"])

        tokens = []
        i = 1
        while i < len(payload):
            byte = payload[i]
            if byte & 0x80:
                idx = byte & 0x7F
                tokens.append(vocab[idx] if idx < len(vocab) else f"<unk_{idx}>")
                i += 1
            else:
                token_len = byte
                i += 1
                if i + token_len > len(payload):
                    raise ValueError("Truncated compressed token stream")
                token_bytes = payload[i:i + token_len]
                tokens.append(token_bytes.decode("utf-8", errors="replace"))
                i += token_len

        return " ".join(tokens), lang


# ==============================================================================
# Stop-and-Wait ARQ Protocol Engine
# ==============================================================================

class ArqEngine:
    """Reliable Stop-and-Wait ARQ Protocol State Machine with ACK verification & retries."""

    def __init__(self, timeout_ms: int = 1200, max_retries: int = 3):
        self.timeout_ms = timeout_ms
        self.max_retries = max_retries
        self.current_state = ArqState.QUEUED
        self.current_packet: Optional[LoRaPacket] = None
        self.retry_count = 0
        self.last_sent_time = 0
        self.seq_number = 1

    def enqueue(self, packet: LoRaPacket):
        self.current_packet = packet
        self.current_packet.seq_num = self.seq_number
        self.current_state = ArqState.QUEUED
        self.retry_count = 0

    def trigger_send(self):
        if self.current_state in (ArqState.QUEUED, ArqState.RETRY):
            self.current_state = ArqState.SENDING
            self.last_sent_time = time.time() * 1000

    def on_node_forwarded(self):
        if self.current_state == ArqState.SENDING:
            self.current_state = ArqState.SENT_TO_NODE

    def on_waiting_ack(self):
        if self.current_state == ArqState.SENT_TO_NODE:
            self.current_state = ArqState.WAITING_FOR_ACK

    def process_ack(self, ack_packet: LoRaPacket) -> bool:
        """Verifies if incoming packet is matching ACK (Type 0x03, matching Session & SeqNum)."""
        if self.current_state not in (ArqState.SENDING, ArqState.SENT_TO_NODE, ArqState.WAITING_FOR_ACK):
            return False
        if ack_packet.msg_type != MessageType.ACK.value:
            return False
        if not self.current_packet:
            return False
        if ack_packet.session_id != self.current_packet.session_id:
            return False
        if ack_packet.seq_num != self.current_packet.seq_num:
            return False

        self.current_state = ArqState.RADIO_ACKNOWLEDGED
        self.seq_number = (self.seq_number + 1) & 0xFFFFFFFF
        return True

    def on_delivered(self):
        if self.current_state == ArqState.RADIO_ACKNOWLEDGED:
            self.current_state = ArqState.DELIVERED

    def handle_timeout(self) -> bool:
        """Handles timeout event. Returns True if retry initiated, False if max retries exhausted."""
        if self.retry_count < self.max_retries:
            self.retry_count += 1
            self.current_state = ArqState.RETRY
            return True
        else:
            self.current_state = ArqState.FAILED
            return False


# ==============================================================================
# Priority Emergency Alert Queue
# ==============================================================================

class PriorityAlertQueue:
    """Priority queue ensuring urgent/SOS packets preempt normal traffic."""

    def __init__(self):
        self.urgent_queue = collections.deque()
        self.normal_queue = collections.deque()

    def enqueue(self, packet: LoRaPacket):
        if packet.is_urgent or packet.msg_type == MessageType.ALERT.value:
            self.urgent_queue.append(packet)
        else:
            self.normal_queue.append(packet)

    def dequeue(self) -> Optional[LoRaPacket]:
        if self.urgent_queue:
            return self.urgent_queue.popleft()
        if self.normal_queue:
            return self.normal_queue.popleft()
        return None

    def size(self) -> int:
        return len(self.urgent_queue) + len(self.normal_queue)


# ==============================================================================
# WPC Duty Cycle Limiter
# ==============================================================================

class WpcDutyCycleLimiter:
    """Enforces Indian WPC G.S.R. 347(E) regulatory airtime limit (10% duty cycle / 360s/hr)."""

    def __init__(self, max_airtime_sec: int = WPC_MAX_DUTY_CYCLE_SEC, window_sec: int = WPC_WINDOW_SEC):
        self.max_airtime_sec = max_airtime_sec
        self.window_sec = window_sec
        self.transmissions = collections.deque()  # (timestamp, duration_sec)

    def _prune(self, now: float):
        cutoff = now - self.window_sec
        while self.transmissions and self.transmissions[0][0] < cutoff:
            self.transmissions.popleft()

    def get_total_airtime(self, now: Optional[float] = None) -> float:
        current_time = now if now is not None else time.time()
        self._prune(current_time)
        return sum(duration for _, duration in self.transmissions)

    def can_transmit(self, packet_airtime_sec: float, now: Optional[float] = None) -> bool:
        current_time = now if now is not None else time.time()
        return (self.get_total_airtime(current_time) + packet_airtime_sec) <= self.max_airtime_sec

    def record_transmission(self, packet_airtime_sec: float, now: Optional[float] = None) -> bool:
        current_time = now if now is not None else time.time()
        if not self.can_transmit(packet_airtime_sec, current_time):
            return False
        self.transmissions.append((current_time, packet_airtime_sec))
        return True


# ==============================================================================
# Simulated RF Channel & Transceiver Loopback
# ==============================================================================

class RfChannel:
    """Simulated RF medium with propagation loss, noise, latency, and packet corruption."""

    def __init__(self, loss_rate: float = 0.0, corruption_rate: float = 0.0, base_latency_ms: float = 50.0):
        self.loss_rate = loss_rate
        self.corruption_rate = corruption_rate
        self.base_latency_ms = base_latency_ms
        self.distance_meters = 100.0

    def compute_signal_metrics(self) -> Tuple[int, int]:
        """Calculates physical RSSI (dBm) and SNR (dB) using log-distance path loss model."""
        if self.distance_meters <= 0.5:
            return -25, 15  # Saturation limit
        # Baseline: -30 dBm at 1m, path loss exponent = 2.5
        path_loss = 25.0 * (1.0 if self.distance_meters <= 1.0 else (self.distance_meters / 10.0))
        rssi = int(max(-125, min(-25, -30 - path_loss + 25)))
        snr = int(max(-20, min(15, 12 - (path_loss / 8.0))))
        return rssi, snr

    def transmit(self, frame: bytes) -> Optional[bytes]:
        """Simulates physical over-the-air transmission."""
        import random
        if self.loss_rate > 0 and random.random() < self.loss_rate:
            return None  # Dropped packet

        if self.corruption_rate > 0 and random.random() < self.corruption_rate:
            # Corrupt one random byte
            mutated = bytearray(frame)
            idx = random.randint(0, len(mutated) - 1)
            mutated[idx] ^= 0xFF
            return bytes(mutated)

        return frame


class SimulatedTransceiverNode:
    """High-fidelity software transceiver node mimicking ESP32 LoRa Bridge."""

    def __init__(self, node_id: int, name: str, mac_addr: str):
        self.node_id = node_id
        self.name = name
        self.mac_addr = mac_addr
        self.ble_connected = False
        self.stats = {
            "tx_count": 0,
            "rx_count": 0,
            "retries_count": 0,
            "dropped_count": 0,
            "last_rssi": -120,
            "last_snr": 0,
            "total_airtime_ms": 0
        }
        self.inbound_packets = collections.deque()
        self.wpc_limiter = WpcDutyCycleLimiter()
        self.peer_node: Optional["SimulatedTransceiverNode"] = None
        self.rf_channel = RfChannel()

    def connect_peer(self, peer: "SimulatedTransceiverNode"):
        self.peer_node = peer

    def send_ping(self) -> str:
        return "[PONG] iTantra Node Online"

    def get_stats_str(self) -> str:
        return (f"[STATS] TX: {self.stats['tx_count']} | RX: {self.stats['rx_count']} | "
                f"Retries: {self.stats['retries_count']} | RSSI: {self.stats['last_rssi']} dBm | "
                f"SNR: {self.stats['last_snr']} dB")

    def transmit_packet(self, packet: LoRaPacket) -> bool:
        """Transmits packet over simulated LoRa link to peer node."""
        frame = packet.pack_frame()
        airtime_sec = (len(frame) * 8) / (125000 / 128)  # Approximate LoRa airtime model

        if not self.wpc_limiter.record_transmission(airtime_sec):
            self.stats["dropped_count"] += 1
            return False

        self.stats["tx_count"] += 1
        self.stats["total_airtime_ms"] += int(airtime_sec * 1000)

        if self.peer_node:
            rx_frame = self.rf_channel.transmit(frame)
            if rx_frame is not None:
                rssi, snr = self.rf_channel.compute_signal_metrics()
                self.peer_node.receive_frame(rx_frame, rssi, snr)

        return True

    def receive_frame(self, frame: bytes, rssi: int, snr: int):
        self.stats["rx_count"] += 1
        self.stats["last_rssi"] = rssi
        self.stats["last_snr"] = snr
        try:
            pkt = LoRaPacket.unpack_frame(frame)
            self.inbound_packets.append(pkt)
        except Exception:
            # Corrupted frame dropped
            self.stats["dropped_count"] += 1


# ==============================================================================
# Hardware Transceiver Interface (COM6 / COM7)
# ==============================================================================

class HardwareTransceiverNode:
    """Hardware serial interface to physical ESP32 on COM6 / COM7."""

    def __init__(self, port: str, baudrate: int = 115200):
        self.port = port
        self.baudrate = baudrate
        self.serial_conn = None

    def is_available(self) -> bool:
        if not HAS_SERIAL:
            return False
        ports = [p.device for p in serial.tools.list_ports.comports()]
        return self.port in ports

    def connect(self) -> bool:
        if not self.is_available():
            return False
        try:
            self.serial_conn = serial.Serial()
            self.serial_conn.port = self.port
            self.serial_conn.baudrate = self.baudrate
            self.serial_conn.dtr = False
            self.serial_conn.rts = False
            self.serial_conn.timeout = 2.0
            self.serial_conn.open()
            time.sleep(0.5)
            self.serial_conn.reset_input_buffer()
            return True
        except Exception:
            self.serial_conn = None
            return False

    def close(self):
        if self.serial_conn and self.serial_conn.is_open:
            self.serial_conn.close()
            self.serial_conn = None

    def send_ping(self) -> str:
        if not self.serial_conn or not self.serial_conn.is_open:
            raise ConnectionError(f"{self.port} not open")
        self.serial_conn.reset_input_buffer()
        self.serial_conn.write(b"PING\r\n")
        time.sleep(0.3)
        return self.serial_conn.read(200).decode("utf-8", errors="replace").strip()

    def get_stats_str(self) -> str:
        if not self.serial_conn or not self.serial_conn.is_open:
            raise ConnectionError(f"{self.port} not open")
        self.serial_conn.reset_input_buffer()
        self.serial_conn.write(b"STATS\r\n")
        time.sleep(0.3)
        return self.serial_conn.read(300).decode("utf-8", errors="replace").strip()

    def transmit_payload(self, payload: str):
        if not self.serial_conn or not self.serial_conn.is_open:
            raise ConnectionError(f"{self.port} not open")
        cmd = f"TX:{payload}\n".encode("utf-8")
        self.serial_conn.write(cmd)


# ==============================================================================
# Unified Test Harness Factory
# ==============================================================================

class TestHarness:
    """Unified test harness managing Node A and Node B across hardware and simulated modes."""

    def __init__(self, mode: str = "simulated"):
        self.mode = mode.lower()
        self.node_a_sim = SimulatedTransceiverNode(1, "iTantra-Node-CD14", "00:70:07:83:cd:14")
        self.node_b_sim = SimulatedTransceiverNode(2, "iTantra-Node-DD00", "00:70:07:84:dd:00")
        self.node_a_sim.connect_peer(self.node_b_sim)
        self.node_b_sim.connect_peer(self.node_a_sim)

        self.node_a_hw = HardwareTransceiverNode("COM6")
        self.node_b_hw = HardwareTransceiverNode("COM7")

    @property
    def is_hardware_available(self) -> bool:
        return self.node_a_hw.is_available() and self.node_b_hw.is_available()

    def get_nodes(self) -> Tuple[Any, Any]:
        if self.mode == "hardware" and self.is_hardware_available:
            return self.node_a_hw, self.node_b_hw
        return self.node_a_sim, self.node_b_sim


# Singleton instance for tests
default_harness = TestHarness(mode="simulated")
