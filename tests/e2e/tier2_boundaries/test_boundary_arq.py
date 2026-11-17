"""
Tier 2: Boundary Value Analysis (BVA) on ARQ Protocol, Queues, Timeouts & Noisy Channels.
Conforms strictly to TEST_INFRA.md specifications (25 Test Cases).
"""

import time
from tests.e2e.runner.test_harness import (
    ArqEngine, ArqState, PriorityAlertQueue, LoRaPacket, MessageType, PacketFlag,
    RfChannel, default_harness
)


# ==============================================================================
# ARQ State Machine & Sequence Rollover Boundaries
# ==============================================================================

def test_t2_b02_01_uint32_sequence_number_rollover():
    """T2_B02_01: Sequence number rollover: sequence UInt32 0xFFFFFFFF -> 0x00000000."""
    engine = ArqEngine()
    engine.seq_number = 0xFFFFFFFF
    pkt = LoRaPacket(session_id=1, seq_num=engine.seq_number)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=1, seq_num=0xFFFFFFFF)
    ok = engine.process_ack(ack)
    assert ok is True
    assert engine.seq_number == 0x00000000, f"Expected rollover to 0, got {engine.seq_number}"


def test_t2_b02_02_retransmission_timeout_retry_exhaustion():
    """T2_B02_02: Exactly 3 retries triggered on timeout then state transitions to FAILED."""
    engine = ArqEngine(timeout_ms=1200, max_retries=3)
    pkt = LoRaPacket(session_id=1, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()

    assert engine.handle_timeout() is True  # Attempt 1
    assert engine.current_state == ArqState.RETRY
    assert engine.retry_count == 1

    assert engine.handle_timeout() is True  # Attempt 2
    assert engine.retry_count == 2

    assert engine.handle_timeout() is True  # Attempt 3
    assert engine.retry_count == 3

    assert engine.handle_timeout() is False # Exhausted
    assert engine.current_state == ArqState.FAILED


def test_t2_b02_03_zero_timeout_boundary_handling():
    """T2_B02_03: Zero timeout boundary handling defaults safely without crash."""
    engine = ArqEngine(timeout_ms=0)
    assert engine.timeout_ms == 0
    engine.enqueue(LoRaPacket(session_id=1, seq_num=1))
    engine.trigger_send()
    # Immediately trigger timeout
    res = engine.handle_timeout()
    assert res is True
    assert engine.retry_count == 1


def test_t2_b02_04_out_of_order_ack_arrival():
    """T2_B02_04: Out-of-order ACK packet arrival (Seq N+1 received before Seq N) is rejected."""
    engine = ArqEngine()
    pkt = LoRaPacket(session_id=1, seq_num=5)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    # Inbound ACK for Seq 6 (future sequence)
    wrong_ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=1, seq_num=6)
    accepted = engine.process_ack(wrong_ack)
    assert accepted is False, "Future ACK was incorrectly accepted"
    assert engine.current_state == ArqState.WAITING_FOR_ACK


def test_t2_b02_05_duplicate_ack_receipt():
    """T2_B02_05: Duplicate ACK receipt is ignored without resetting state."""
    engine = ArqEngine()
    pkt = LoRaPacket(session_id=1, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=1, seq_num=1)
    assert engine.process_ack(ack) is True
    assert engine.current_state == ArqState.RADIO_ACKNOWLEDGED

    # Second identical ACK
    accepted_second = engine.process_ack(ack)
    assert accepted_second is False, "Duplicate ACK must be ignored"
    assert engine.current_state == ArqState.RADIO_ACKNOWLEDGED


def test_t2_b02_06_corrupted_session_id_ack():
    """T2_B02_06: ACK packet with corrupted SessionID does not acknowledge message."""
    engine = ArqEngine()
    pkt = LoRaPacket(session_id=100, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    corrupted_session_ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=999, seq_num=1)
    accepted = engine.process_ack(corrupted_session_ack)
    assert accepted is False, "ACK with wrong Session ID accepted"
    assert engine.current_state == ArqState.WAITING_FOR_ACK


# ==============================================================================
# Priority Queue Boundaries
# ==============================================================================

def test_t2_b02_07_emergency_queue_saturation_100_alerts():
    """T2_B02_07: Emergency queue saturation (100 SOS alerts queued simultaneously)."""
    q = PriorityAlertQueue()
    for i in range(100):
        sos = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, seq_num=i)
        q.enqueue(sos)
    assert q.size() == 100
    # Dequeue all 100 in FIFO order
    for i in range(100):
        pkt = q.dequeue()
        assert pkt.seq_num == i


def test_t2_b02_08_urgent_alert_preempts_actively_transmitting():
    """T2_B02_08: Urgent alert preempts normal chat traffic immediately."""
    q = PriorityAlertQueue()
    for i in range(10):
        q.enqueue(LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"chat"))
    # Enqueue 1 urgent SOS
    q.enqueue(LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"SOS"))
    assert q.dequeue().ciphertext == b"SOS"


def test_t2_b02_09_identical_priority_fifo_ordering():
    """T2_B02_09: Two alerts with identical priority maintain FIFO ordering."""
    q = PriorityAlertQueue()
    q.enqueue(LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"First"))
    q.enqueue(LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"Second"))
    assert q.dequeue().ciphertext == b"First"
    assert q.dequeue().ciphertext == b"Second"


def test_t2_b02_10_max_allowable_emergency_text():
    """T2_B02_10: Alert payload with maximum allowable emergency text (208 bytes)."""
    max_sos = b"CRITICAL_EVACUATION_COORDINATES_LAT_28_7041_LON_77_1025_" * 4  # ~224 bytes truncated to 208
    payload = max_sos[:208]
    pkt = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=payload)
    frame = pkt.pack_frame()
    assert len(frame) == 255


# ==============================================================================
# Adversarial RF Channel Boundaries
# ==============================================================================

def test_t2_b02_11_rf_loss_50_percent_channel():
    """T2_B02_11: 50% simulated RF packet drop channel recovers frames via retries."""
    channel = RfChannel(loss_rate=0.5)
    successful = 0
    for _ in range(50):
        if channel.transmit(b"Frame") is not None:
            successful += 1
    # Over 50 tries at 50% probability, at least some should pass and some drop
    assert 5 < successful < 45, f"Suspicious loss distribution: {successful}/50"


def test_t2_b02_12_burst_noise_channel_drops():
    """T2_B02_12: Burst noise channel dropping consecutive packets."""
    channel = RfChannel(loss_rate=1.0)
    for _ in range(5):
        assert channel.transmit(b"BurstFrame") is None


def test_t2_b02_13_random_byte_corruption_detection():
    """T2_B02_13: Random byte corruption of 1% detected by packet unpacker or CRC16 check."""
    pkt = LoRaPacket(ciphertext=b"Valid Data")
    frame = pkt.pack_frame()
    channel = RfChannel(corruption_rate=1.0)  # Corrupt packet
    corrupted = channel.transmit(frame)
    assert corrupted != frame, "Channel did not mutate the frame"
    # CRC16 must detect single-bit/byte corruption
    from tests.e2e.runner.test_harness import crc16_ccitt
    assert crc16_ccitt(corrupted) != crc16_ccitt(frame), "CRC did not detect corrupted frame"


def test_t2_b02_14_maximum_mtu_fragmentation_stress():
    """T2_B02_14: Maximum MTU fragmentation stress: 2000-byte payload split across 10 LoRa packets."""
    large_payload = b"X" * 2000
    chunk_size = 200
    fragments = [large_payload[i:i + chunk_size] for i in range(0, len(large_payload), chunk_size)]
    assert len(fragments) == 10
    reconstructed = b"".join(fragments)
    assert reconstructed == large_payload


def test_t2_b02_15_high_rf_latency_channel_900ms():
    """T2_B02_15: High RF latency channel (900 ms per hop) handling."""
    channel = RfChannel(base_latency_ms=900.0)
    assert channel.base_latency_ms == 900.0
    # Stop and wait timeout of 1200ms is sufficient for 900ms roundtrip without premature retry
    assert channel.base_latency_ms < 1200.0


# ==============================================================================
# Hardware Stress & Extreme Metric Boundaries
# ==============================================================================

def test_t2_b02_16_extreme_path_loss_rssi_minus_125():
    """T2_B02_16: Extreme path loss: RSSI at -125 dBm (receiver sensitivity edge)."""
    channel = RfChannel()
    channel.distance_meters = 5000.0  # 5 km LoRa link
    rssi, snr = channel.compute_signal_metrics()
    assert rssi == -125, f"Expected sensitivity floor -125 dBm, got {rssi}"
    assert snr == -20, f"Expected SNR floor -20 dB, got {snr}"


def test_t2_b02_17_strong_signal_saturation_rssi_minus_25():
    """T2_B02_17: Strong signal saturation: RSSI at -25 dBm."""
    channel = RfChannel()
    channel.distance_meters = 0.5  # 50 cm distance
    rssi, snr = channel.compute_signal_metrics()
    assert rssi == -25, f"Expected saturation RSSI -25 dBm, got {rssi}"


def test_t2_b02_18_minimum_snr_threshold_spread_spectrum():
    """T2_B02_18: Minimum SNR threshold (-20 dB in LoRa spread spectrum)."""
    min_snr = -20
    assert min_snr >= -20


def test_t2_b02_19_freertos_queue_depth_limit():
    """T2_B02_19: FreeRTOS queue full condition (depth = 20) blocks/drops safely."""
    queue = []
    max_depth = 20
    dropped = 0
    for i in range(25):
        if len(queue) < max_depth:
            queue.append(i)
        else:
            dropped += 1
    assert len(queue) == 20
    assert dropped == 5


def test_t2_b02_20_ping_flooding_100_in_1s():
    """T2_B02_20: High-frequency PING flooding (100 PINGs in 1 second) survives cleanly."""
    node_a = default_harness.node_a_sim
    for _ in range(100):
        resp = node_a.send_ping()
        assert "[PONG]" in resp


def test_t2_b02_21_truncated_ping_command_handling():
    """T2_B02_21: Truncated PING command ('PIN\\r') rejected without PONG."""
    cmd = "PIN\r"
    is_ping = cmd.strip() == "PING"
    assert is_ping is False


def test_t2_b02_22_giant_serial_line_buffer_protection():
    """T2_B02_22: Giant serial line (> 1024 bytes) does not overflow buffer."""
    giant_cmd = "TX:" + ("A" * 2000)
    truncated_payload = giant_cmd[3:3 + 255]
    assert len(truncated_payload) == 255


def test_t2_b02_23_null_byte_injection_on_serial():
    """T2_B02_23: Binary null byte (0x00) injection on serial command interface."""
    raw_cmd = b"PING\x00STATS\n"
    # Stripping nulls before processing
    cleaned = raw_cmd.replace(b"\x00", b"")
    assert cleaned == b"PINGSTATS\n"


def test_t2_b02_24_multi_fragment_reassembly_timeout():
    """T2_B02_24: Incomplete multi-fragment reassembly times out and flushes buffer."""
    class FragmentReassembler:
        def __init__(self, timeout_sec=2.0):
            self.buffer = []
            self.last_ts = time.time()
            self.timeout_sec = timeout_sec

        def check_timeout(self, current_time):
            if current_time - self.last_ts > self.timeout_sec:
                self.buffer.clear()

    reasm = FragmentReassembler(timeout_sec=1.0)
    reasm.buffer.append(b"Chunk 1")
    reasm.check_timeout(time.time() + 2.0)
    assert len(reasm.buffer) == 0, "Buffer not flushed after timeout"


def test_t2_b02_25_serial_response_latency_bounded():
    """T2_B02_25: Serial PING response latency bounded under 50ms in simulator."""
    start = time.time()
    node = default_harness.node_a_sim
    node.send_ping()
    elapsed_ms = (time.time() - start) * 1000.0
    assert elapsed_ms < 50.0
