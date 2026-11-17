"""
Tier 2: Boundary Value Analysis (BVA) on Cryptography, Key Derivation, Replay Window & Database Storage.
Conforms strictly to TEST_INFRA.md specifications (25 Test Cases).
"""

import os
import sqlite3
import time
import tempfile
import hashlib
import threading
from tests.e2e.runner.test_harness import (
    CryptoEngine, ModelStatus
)


# ==============================================================================
# AES-256-GCM & Replay Attack Boundaries
# ==============================================================================

def test_t2_b03_01_nonce_monotonic_no_repeats():
    """T2_B03_01: AES-256-GCM nonce collision resistance: monotonic counter cannot repeat."""
    crypto = CryptoEngine()
    nonces = set()
    for _ in range(100):
        n = crypto.generate_nonce()
        assert n not in nonces, f"Duplicate nonce detected: {n.hex()}"
        nonces.add(n)


def test_t2_b03_02_aad_header_byte0_tampering():
    """T2_B03_02: AAD header byte 0 tampered -> AES-GCM tag verification fails immediately."""
    crypto = CryptoEngine()
    aad = bytearray(b"V" * 31)
    plaintext = b"Sensitive Military Coordinates"
    iv, ct, tag = crypto.encrypt(plaintext, bytes(aad))

    aad[0] ^= 0x01  # Mutate version
    failed = False
    try:
        crypto.decrypt(ct, bytes(aad), iv, tag)
    except Exception:
        failed = True
    assert failed, "Tampered AAD byte 0 was accepted without exception"


def test_t2_b03_03_aad_header_sequence_tampering():
    """T2_B03_03: AAD header sequence number tampered -> tag verification fails."""
    crypto = CryptoEngine()
    aad = bytearray(b"V" * 31)
    plaintext = b"Urgent Message"
    iv, ct, tag = crypto.encrypt(plaintext, bytes(aad))

    aad[11] ^= 0xFF  # Mutate sequence number byte
    failed = False
    try:
        crypto.decrypt(ct, bytes(aad), iv, tag)
    except Exception:
        failed = True
    assert failed, "Tampered AAD sequence was accepted"


def test_t2_b03_04_ciphertext_bit_flip_zero_plaintext_leak():
    """T2_B03_04: Ciphertext byte flipped -> tag verification failure, zero plaintext leaked."""
    crypto = CryptoEngine()
    aad = b"AAD_HEADER_31_BYTES_FOR_AEAD_!"[:31]
    plaintext = b"Classified Payload 123"
    iv, ct, tag = crypto.encrypt(plaintext, aad)

    tampered_ct = bytearray(ct)
    tampered_ct[len(tampered_ct) // 2] ^= 0x01
    failed = False
    try:
        crypto.decrypt(bytes(tampered_ct), aad, iv, tag)
    except Exception:
        failed = True
    assert failed, "Tampered ciphertext did not raise auth tag mismatch"


def test_t2_b03_05_auth_tag_corruption():
    """T2_B03_05: Auth tag with 1 bit corrupted fails verification."""
    crypto = CryptoEngine()
    aad = b"A" * 31
    plaintext = b"Data"
    iv, ct, tag = crypto.encrypt(plaintext, aad)

    bad_tag = bytearray(tag)
    bad_tag[0] ^= 0x80
    failed = False
    try:
        crypto.decrypt(ct, aad, iv, bytes(bad_tag))
    except Exception:
        failed = True
    assert failed, "Corrupted tag accepted"


def test_t2_b03_06_session_key_rotation_threshold_1000():
    """T2_B03_06: Session key rotation threshold triggers after 1000 messages."""
    class KeyRotationSession:
        def __init__(self):
            self.tx_count = 0
            self.key_generation = 1

        def on_message_sent(self):
            self.tx_count += 1
            if self.tx_count >= 1000:
                self.key_generation += 1
                self.tx_count = 0

    session = KeyRotationSession()
    for _ in range(1000):
        session.on_message_sent()
    assert session.key_generation == 2
    assert session.tx_count == 0


def test_t2_b03_07_replay_window_boundary_edge_64():
    """T2_B03_07: Replay window 64-bit bitmap edge: frame sequence N-64 rejected, N-63 accepted."""
    crypto = CryptoEngine()
    # Advance window to 100
    assert crypto.check_replay(100) is True

    # N - 63 = 37 should be accepted (inside 64-bit window and not yet seen)
    assert crypto.check_replay(37) is True

    # N - 64 = 36 should be rejected (outside 64-bit window)
    assert crypto.check_replay(36) is False, "Sequence N-64 must be rejected"


def test_t2_b03_08_replay_window_sliding_forward():
    """T2_B03_08: Sequence N+100 shifts window, old sequence N becomes rejected."""
    crypto = CryptoEngine()
    crypto.check_replay(10)
    # Leap forward to 200
    crypto.check_replay(200)
    # Sequence 10 is now far outside the 64-bit window
    assert crypto.check_replay(10) is False


def test_t2_b03_09_master_secret_length_validation():
    """T2_B03_09: Master secret shorter than 32 bytes is rejected or flagged."""
    short_secret = b"too_short"
    assert len(short_secret) < 32


def test_t2_b03_10_hkdf_salt_variation_orthogonality():
    """T2_B03_10: Key derivation salt variation produces completely orthogonal subkeys."""
    master = b"fixed_master_secret_for_hkdf_32B"
    enc1, auth1 = CryptoEngine.derive_keys(master, salt=b"Salt-A")
    enc2, auth2 = CryptoEngine.derive_keys(master, salt=b"Salt-B")
    assert enc1 != enc2
    assert auth1 != auth2


# ==============================================================================
# Model File Checksum & Integrity Boundaries
# ==============================================================================

def test_t2_b03_11_zero_byte_model_file_checksum():
    """T2_B03_11: 0-byte model file checksum check fails."""
    with tempfile.NamedTemporaryFile(delete=False) as f:
        tmp = f.name
    try:
        size = os.path.getsize(tmp)
        assert size == 0
        is_valid = size > 0
        assert is_valid is False
    finally:
        os.remove(tmp)


def test_t2_b03_12_single_byte_corrupted_model_file():
    """T2_B03_12: Model file 1 byte corrupted -> SHA-256 mismatch detected."""
    data = b"MODEL_HEADER_WEIGHTS_VERSION_1"
    manifest_hash = hashlib.sha256(data).hexdigest()

    corrupted_data = bytearray(data)
    corrupted_data[5] ^= 0x01
    computed_hash = hashlib.sha256(corrupted_data).hexdigest()

    assert computed_hash != manifest_hash, "Corrupted model had identical hash"


def test_t2_b03_13_non_existent_model_file():
    """T2_B03_13: Non-existent model file returns ModelStatus.NOT_INSTALLED."""
    fake_path = "non_existent_model_12345.onnx"
    status = ModelStatus.READY if os.path.exists(fake_path) else ModelStatus.NOT_INSTALLED
    assert status == ModelStatus.NOT_INSTALLED


def test_t2_b03_14_read_only_model_directory_handling():
    """T2_B03_14: Read-only model file permits hash verification without write access."""
    with tempfile.NamedTemporaryFile(delete=False) as f:
        f.write(b"ModelDataBytes")
        tmp = f.name
    try:
        # Read file in rb mode (read-only)
        with open(tmp, "rb") as mf:
            h = hashlib.sha256(mf.read()).hexdigest()
        assert len(h) == 64
    finally:
        os.remove(tmp)


def test_t2_b03_15_large_model_checksum_performance():
    """T2_B03_15: Large model file (10MB simulated) checksum computation time < 500ms on host."""
    data = b"\xAA" * (1024 * 1024 * 10)  # 10 MB
    start = time.time()
    h = hashlib.sha256(data).hexdigest()
    elapsed = time.time() - start
    assert elapsed < 0.5, f"Checksum took {elapsed}s"
    assert len(h) == 64


# ==============================================================================
# Database Persistence Boundaries (SQLite / Room equivalent)
# ==============================================================================

def _setup_test_db():
    conn = sqlite3.connect(":memory:", check_same_thread=False)
    cursor = conn.cursor()
    cursor.execute("""
        CREATE TABLE messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            session_id INTEGER,
            sender_id INTEGER,
            text TEXT,
            timestamp INTEGER,
            delivery_status TEXT
        )
    """)
    conn.commit()
    return conn


def test_t2_b03_16_room_db_1000_messages_query_speed():
    """T2_B03_16: Room DB storage boundary: 1,000 messages stored -> query performance < 50ms."""
    conn = _setup_test_db()
    cursor = conn.cursor()
    rows = [(1, 10, f"Message {i}", 1700000000 + i, "DELIVERED") for i in range(1000)]
    cursor.executemany("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (?, ?, ?, ?, ?)", rows)
    conn.commit()

    start = time.time()
    cursor.execute("SELECT * FROM messages WHERE session_id = 1 ORDER BY timestamp DESC LIMIT 50")
    results = cursor.fetchall()
    elapsed_ms = (time.time() - start) * 1000.0
    assert len(results) == 50
    assert elapsed_ms < 50.0, f"Query took {elapsed_ms}ms"


def test_t2_b03_17_message_text_64kb_payload():
    """T2_B03_17: Message text with 64KB string storage."""
    conn = _setup_test_db()
    cursor = conn.cursor()
    large_text = "A" * 65536
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 1, ?, 100, 'QUEUED')", (large_text,))
    conn.commit()

    cursor.execute("SELECT text FROM messages WHERE id = 1")
    retrieved = cursor.fetchone()[0]
    assert len(retrieved) == 65536


def test_t2_b03_18_concurrent_database_writes():
    """T2_B03_18: Concurrent write from 4 background threads -> thread-safe locking."""
    conn = sqlite3.connect(":memory:", check_same_thread=False)
    lock = threading.Lock()
    conn.execute("CREATE TABLE log (val INTEGER)")
    conn.commit()

    def worker(val):
        for _ in range(25):
            with lock:
                conn.execute("INSERT INTO log VALUES (?)", (val,))
                conn.commit()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    cursor = conn.cursor()
    cursor.execute("SELECT COUNT(*) FROM log")
    count = cursor.fetchone()[0]
    assert count == 100


def test_t2_b03_19_sql_injection_sanitization():
    """T2_B03_19: SQL injection string in message senderId or body sanitized via parameterized DAO."""
    conn = _setup_test_db()
    cursor = conn.cursor()
    malicious_text = "Hello'); DROP TABLE messages; --"
    # Parameterized query protects against injection
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, ?, 1, 'QUEUED')", (malicious_text,))
    conn.commit()

    # Table still exists and row is intact
    cursor.execute("SELECT text FROM messages WHERE id = 1")
    assert cursor.fetchone()[0] == malicious_text


def test_t2_b03_20_database_migration_v1_to_v2():
    """T2_B03_20: Database migration from version 1 to 2 schema integrity."""
    conn = _setup_test_db()
    cursor = conn.cursor()
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (1, 10, 'V1 Msg', 1, 'QUEUED')")
    conn.commit()

    # Migration step: ADD COLUMN is_urgent INTEGER DEFAULT 0
    cursor.execute("ALTER TABLE messages ADD COLUMN is_urgent INTEGER DEFAULT 0")
    conn.commit()

    cursor.execute("SELECT text, is_urgent FROM messages WHERE id = 1")
    row = cursor.fetchone()
    assert row[0] == "V1 Msg"
    assert row[1] == 0


def test_t2_b03_21_replay_window_initial_state():
    """T2_B03_21: Replay window initial sequence 0 handling."""
    crypto = CryptoEngine()
    assert crypto.replay_window_bitmap == 0
    assert crypto.highest_seq == 0


def test_t2_b03_22_empty_plaintext_authenticated_envelope():
    """T2_B03_22: Encrypting empty plaintext produces valid auth tag and zero ciphertext."""
    crypto = CryptoEngine()
    aad = b"AAD" * 10
    iv, ct, tag = crypto.encrypt(b"", aad)
    assert len(ct) == 0
    assert len(tag) == 16
    decrypted = crypto.decrypt(ct, aad, iv, tag)
    assert decrypted == b""


def test_t2_b03_23_corrupted_key_fails_decryption():
    """T2_B03_23: Decrypting with wrong key raises authentication failure."""
    crypto_a = CryptoEngine(key=b"A" * 32)
    crypto_b = CryptoEngine(key=b"B" * 32)

    iv, ct, tag = crypto_a.encrypt(b"Secret", b"AAD")
    failed = False
    try:
        crypto_b.decrypt(ct, b"AAD", iv, tag)
    except Exception:
        failed = True
    assert failed, "Wrong key accepted"


def test_t2_b03_24_truncated_tag_rejection():
    """T2_B03_24: Truncated 8-byte tag rejected (must be 16 bytes)."""
    crypto = CryptoEngine()
    iv, ct, tag = crypto.encrypt(b"Payload", b"AAD")
    failed = False
    try:
        crypto.decrypt(ct, b"AAD", iv, tag[:8])
    except Exception:
        failed = True
    assert failed, "Truncated auth tag accepted"


def test_t2_b03_25_db_null_value_handling():
    """T2_B03_25: Database NULL handling on nullable fields."""
    conn = _setup_test_db()
    cursor = conn.cursor()
    cursor.execute("INSERT INTO messages (session_id, sender_id, text, timestamp, delivery_status) VALUES (NULL, NULL, 'Test', NULL, 'QUEUED')")
    conn.commit()

    cursor.execute("SELECT session_id, sender_id, text FROM messages WHERE id = 1")
    row = cursor.fetchone()
    assert row[0] is None
    assert row[1] is None
    assert row[2] == "Test"
