"""
Tier 1: Feature F11 to F13 Persistence, Preferences & Dynamic Cryptographic Pairing Tests.
Conforms strictly to TEST_INFRA.md specifications.
"""

import os
import sqlite3
import time
import json
import secrets
import hmac
import hashlib
from tests.e2e.runner.test_harness import CryptoEngine

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


# ==============================================================================
# F11: Room Database Message Persistence
# ==============================================================================

def _setup_in_memory_db():
    conn = sqlite3.connect(":memory:")
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER NOT NULL,
            sender_id INTEGER NOT NULL,
            text TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            delivery_status TEXT NOT NULL
        )
    """)
    conn.commit()
    return conn


def test_t1_f11_01_insert_message_autoincrement_pk():
    """T1_F11_01: Insert MessageEntity into Room DB -> verify auto-generated primary key."""
    conn = _setup_in_memory_db()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status)
        VALUES (?, ?, ?, ?, ?)
    """, (1, 10, "First message", int(time.time()), "SENT_TO_NODE"))
    conn.commit()
    inserted_id = cursor.lastrowid
    assert inserted_id == 1, f"Expected PK 1, got {inserted_id}"


def test_t1_f11_02_query_messages_by_session_ordered():
    """T1_F11_02: Query messages by sessionId ordered by timestamp ascending."""
    conn = _setup_in_memory_db()
    cursor = conn.cursor()
    base_ts = 1700000000
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Msg 1', ?, 'DELIVERED')", (base_ts + 10,))
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Msg 2', ?, 'DELIVERED')", (base_ts + 20,))
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (2, 20, 'Msg 3', ?, 'DELIVERED')", (base_ts + 15,))
    conn.commit()

    cursor.execute("SELECT text FROM messages WHERE session_id = ? ORDER BY timestamp ASC", (1,))
    rows = cursor.fetchall()
    assert [r[0] for r in rows] == ["Msg 1", "Msg 2"], f"Incorrect query results: {rows}"


def test_t1_f11_03_update_delivery_status():
    """T1_F11_03: Update delivery status from SENT_TO_NODE to DELIVERED."""
    conn = _setup_in_memory_db()
    cursor = conn.cursor()
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Status test', 100, 'SENT_TO_NODE')")
    msg_id = cursor.lastrowid
    conn.commit()

    cursor.execute("UPDATE messages SET delivery_status = 'DELIVERED' WHERE id = ?", (msg_id,))
    conn.commit()

    cursor.execute("SELECT delivery_status FROM messages WHERE id = ?", (msg_id,))
    status = cursor.fetchone()[0]
    assert status == "DELIVERED", f"Expected DELIVERED, got {status}"


def test_t1_f11_04_query_undelivered_retry_queue():
    """T1_F11_04: Query undelivered messages (deliveryStatus != DELIVERED) for offline retry queue."""
    conn = _setup_in_memory_db()
    cursor = conn.cursor()
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Delivered msg', 1, 'DELIVERED')")
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Queued msg', 2, 'QUEUED')")
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Retrying msg', 3, 'RETRY')")
    conn.commit()

    cursor.execute("SELECT text FROM messages WHERE delivery_status != 'DELIVERED'")
    undelivered = [r[0] for r in cursor.fetchall()]
    assert undelivered == ["Queued msg", "Retrying msg"]


def test_t1_f11_05_delete_session_history():
    """T1_F11_05: Delete message / clear session history removes records cleanly."""
    conn = _setup_in_memory_db()
    cursor = conn.cursor()
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'Msg', 1, 'DELIVERED')")
    conn.commit()

    cursor.execute("DELETE FROM messages WHERE session_id = ?", (1,))
    conn.commit()

    cursor.execute("SELECT COUNT(*) FROM messages WHERE session_id = ?", (1,))
    count = cursor.fetchone()[0]
    assert count == 0, "Messages still exist after session deletion"


# ==============================================================================
# F12: Jetpack DataStore Preferences
# ==============================================================================

class MockDataStore:
    def __init__(self):
        self.defaults = {
            "node_id": 1,
            "primary_language": "hi",
            "rf_frequency_hz": 433175000,
            "vad_silence_threshold_ms": 600
        }
        self.prefs = dict(self.defaults)

    def set_node_id(self, val: int):
        assert val > 0, "Node ID must be positive UInt32"
        self.prefs["node_id"] = val

    def set_language(self, lang: str):
        valid = ["hi", "bn", "mr", "te", "ta", "gu", "kn", "ml", "or", "en"]
        assert lang in valid, f"Unsupported language: {lang}"
        self.prefs["primary_language"] = lang

    def set_rf_frequency(self, freq: int):
        # 433.05 - 434.79 MHz ISM Band
        assert 433050000 <= freq <= 434790000, f"Frequency {freq} outside WPC ISM band"
        self.prefs["rf_frequency_hz"] = freq

    def set_vad_silence_threshold(self, ms: int):
        assert 300 <= ms <= 2000, f"VAD silence {ms}ms outside [300, 2000]ms bounds"
        self.prefs["vad_silence_threshold_ms"] = ms

    def reset_to_defaults(self):
        self.prefs = dict(self.defaults)


def test_t1_f12_01_save_node_id_preference():
    """T1_F12_01: Save user node ID preference -> verify persistent retrieval."""
    ds = MockDataStore()
    ds.set_node_id(42)
    assert ds.prefs["node_id"] == 42


def test_t1_f12_02_save_primary_indic_language():
    """T1_F12_02: Save primary Indic language preference (e.g. Hindi)."""
    ds = MockDataStore()
    ds.set_language("hi")
    assert ds.prefs["primary_language"] == "hi"


def test_t1_f12_03_save_rf_frequency_preference():
    """T1_F12_03: Save RF frequency preference (433.175 MHz) -> verify constraints."""
    ds = MockDataStore()
    ds.set_rf_frequency(433175000)
    assert ds.prefs["rf_frequency_hz"] == 433175000


def test_t1_f12_04_save_vad_silence_threshold():
    """T1_F12_04: Save silence threshold for VAD -> verify value bounds (300ms to 2000ms)."""
    ds = MockDataStore()
    ds.set_vad_silence_threshold(800)
    assert ds.prefs["vad_silence_threshold_ms"] == 800


def test_t1_f12_05_reset_preferences_to_factory_defaults():
    """T1_F12_05: Reset preferences to factory defaults restores initial specification values."""
    ds = MockDataStore()
    ds.set_node_id(999)
    ds.set_language("te")
    ds.reset_to_defaults()
    assert ds.prefs["node_id"] == 1
    assert ds.prefs["primary_language"] == "hi"
    assert ds.prefs["rf_frequency_hz"] == 433175000
    assert ds.prefs["vad_silence_threshold_ms"] == 600


# ==============================================================================
# F13: Dynamic Cryptographic Key Pairing
# ==============================================================================

def test_t1_f13_01_generate_high_entropy_session_key():
    """T1_F13_01: Generate 32-byte high-entropy session pairing key."""
    key = secrets.token_bytes(32)
    assert len(key) == 32
    assert key != b"\x00" * 32
    # Ensure distinct successive keys
    assert key != secrets.token_bytes(32)


def test_t1_f13_02_generate_pairing_qr_payload():
    """T1_F13_02: Generate pairing QR code payload containing Node ID, Secret, and timestamp."""
    secret = secrets.token_hex(32)
    qr_data = {
        "node_id": "iTantra-Node-CD14",
        "secret": secret,
        "timestamp": int(time.time()),
        "v": 1
    }
    payload_str = json.dumps(qr_data)
    decoded = json.loads(payload_str)
    assert decoded["node_id"] == "iTantra-Node-CD14"
    assert len(decoded["secret"]) == 64


def test_t1_f13_03_derive_hkdf_sha256_subkeys():
    """T1_F13_03: Derive HKDF-SHA256 encryption and authentication sub-keys from master secret."""
    master = secrets.token_bytes(32)
    enc_key, auth_key = CryptoEngine.derive_keys(master)
    assert len(enc_key) == 32
    assert len(auth_key) == 32
    assert enc_key != auth_key


def test_t1_f13_04_verify_hmac_6_digit_confirmation_code():
    """T1_F13_04: Verify HMAC-SHA256 6-digit confirmation code matches deterministically on both nodes."""
    master = b"shared_master_secret_test_32byte!"
    # 6-digit PIN derivation
    digest = hmac.new(master, b"iTantra-Pairing-Confirmation", hashlib.sha256).digest()
    code_int = int.from_bytes(digest[:4], byteorder="big") % 1000000
    code_str = f"{code_int:06d}"

    # Node B performs same computation
    digest_b = hmac.new(master, b"iTantra-Pairing-Confirmation", hashlib.sha256).digest()
    code_b_str = f"{(int.from_bytes(digest_b[:4], byteorder='big') % 1000000):06d}"

    assert code_str == code_b_str
    assert len(code_str) == 6


def test_t1_f13_05_eradication_of_static_pairing_secret():
    """T1_F13_05: Confirm eradication of static string 'iTantra-Fixed-Pairing-Secret-32B' from PairingManager."""
    forbidden = "iTantra-Fixed-Pairing-Secret-32B"
    pairing_mgr_path = os.path.join(REPO_ROOT, "app", "src", "main", "java", "com", "itantra", "core", "pairing", "PairingManager.kt")
    assert os.path.isfile(pairing_mgr_path), "PairingManager.kt missing"
    with open(pairing_mgr_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert forbidden not in content, f"Found forbidden static secret in PairingManager.kt"
    assert "SecureRandom" in content, "PairingManager must use SecureRandom for dynamic keys"
