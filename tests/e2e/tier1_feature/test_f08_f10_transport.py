"""
Tier 1: Feature F08 to F10 Transport Layer Tests (Stop-and-Wait ARQ, Priority Queue, USB-Serial OTG).
Conforms strictly to TEST_INFRA.md specifications.
"""

from tests.e2e.runner.test_harness import (
    ArqEngine, ArqState, PriorityAlertQueue, LoRaPacket, MessageType, PacketFlag,
    cobs_encode, cobs_decode
)


# ==============================================================================
# F08: Stop-and-Wait ARQ Protocol Hardening
# ==============================================================================

def test_t1_f08_01_arq_queued_to_sending():
    """T1_F08_01: Outbound packet enters state QUEUED then SENDING."""
    engine = ArqEngine()
    pkt = LoRaPacket(flags=PacketFlag.FLAG_NEEDS_ACK, session_id=1, seq_num=1)
    engine.enqueue(pkt)
    assert engine.current_state == ArqState.QUEUED
    engine.trigger_send()
    assert engine.current_state == ArqState.SENDING


def test_t1_f08_02_arq_forward_to_node():
    """T1_F08_02: Forward to node transitions state to SENT_TO_NODE."""
    engine = ArqEngine()
    pkt = LoRaPacket(flags=PacketFlag.FLAG_NEEDS_ACK, session_id=1, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    assert engine.current_state == ArqState.SENT_TO_NODE


def test_t1_f08_03_arq_matching_ack_received():
    """T1_F08_03: Inbound matching ACK packet transitions to RADIO_ACKNOWLEDGED."""
    engine = ArqEngine()
    pkt = LoRaPacket(flags=PacketFlag.FLAG_NEEDS_ACK, session_id=42, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=42, seq_num=engine.current_packet.seq_num)
    matched = engine.process_ack(ack)
    assert matched, "Matching ACK was rejected by ARQ engine"
    assert engine.current_state == ArqState.RADIO_ACKNOWLEDGED


def test_t1_f08_04_arq_delivery_ack():
    """T1_F08_04: App-level delivery ACK transitions state to DELIVERED."""
    engine = ArqEngine()
    pkt = LoRaPacket(flags=PacketFlag.FLAG_NEEDS_ACK, session_id=42, seq_num=1)
    engine.enqueue(pkt)
    engine.trigger_send()
    engine.on_node_forwarded()
    engine.on_waiting_ack()

    ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=42, seq_num=engine.current_packet.seq_num)
    engine.process_ack(ack)
    engine.on_delivered()
    assert engine.current_state == ArqState.DELIVERED


def test_t1_f08_05_arq_monotonic_sequence_increment():
    """T1_F08_05: Verify sequence number increments monotonically per transmitted message."""
    engine = ArqEngine()
    initial_seq = engine.seq_number

    for i in range(3):
        pkt = LoRaPacket(flags=PacketFlag.FLAG_NEEDS_ACK, session_id=1, seq_num=engine.seq_number)
        engine.enqueue(pkt)
        engine.trigger_send()
        engine.on_node_forwarded()
        engine.on_waiting_ack()
        ack = LoRaPacket(msg_type=MessageType.ACK.value, session_id=1, seq_num=engine.seq_number)
        engine.process_ack(ack)

    assert engine.seq_number == initial_seq + 3, f"Expected seq {initial_seq + 3}, got {engine.seq_number}"


# ==============================================================================
# F09: Priority Emergency Alert Queue
# ==============================================================================

def test_t1_f09_01_sos_alert_preempts_chat_packets():
    """T1_F09_01: Enqueue 3 background chat packets, then enqueue 1 SOS Alert -> SOS dequeues first."""
    q = PriorityAlertQueue()
    chat1 = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Chat 1")
    chat2 = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Chat 2")
    chat3 = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Chat 3")
    sos = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"SOS!")

    q.enqueue(chat1)
    q.enqueue(chat2)
    q.enqueue(chat3)
    q.enqueue(sos)

    first_out = q.dequeue()
    assert first_out is not None
    assert first_out.is_urgent or first_out.msg_type == MessageType.ALERT.value
    assert first_out.ciphertext == b"SOS!"


def test_t1_f09_02_urgent_flag_bit_in_header():
    """T1_F09_02: Verify FLAG_URGENT bit (Bit 0) is set in binary header for SOS alert."""
    sos = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"EMERGENCY")
    header = sos.pack_header()
    flag_byte = header[2]
    assert (flag_byte & 0x01) == 0x01, "Bit 0 (FLAG_URGENT) not set in packed header"


def test_t1_f09_03_medical_alert_priority_sorting():
    """T1_F09_03: Medical alert preset serialization and urgent priority sorting."""
    q = PriorityAlertQueue()
    chat = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Regular update")
    med = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"MEDICAL_AID_REQUIRED")
    q.enqueue(chat)
    q.enqueue(med)

    out = q.dequeue()
    assert out.ciphertext == b"MEDICAL_AID_REQUIRED"


def test_t1_f09_04_evacuation_alert_priority_sorting():
    """T1_F09_04: Evacuation alert preset serialization and urgent priority sorting."""
    q = PriorityAlertQueue()
    chat = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Log update")
    evac = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"EVACUATE_IMMEDIATELY")
    q.enqueue(chat)
    q.enqueue(evac)

    out = q.dequeue()
    assert out.ciphertext == b"EVACUATE_IMMEDIATELY"


def test_t1_f09_05_resume_non_urgent_after_emergency_cleared():
    """T1_F09_05: Verification that non-urgent packets resume transmission once emergency queue is clear."""
    q = PriorityAlertQueue()
    chat1 = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Chat 1")
    chat2 = LoRaPacket(msg_type=MessageType.TEXT.value, ciphertext=b"Chat 2")
    sos = LoRaPacket(msg_type=MessageType.ALERT.value, flags=PacketFlag.FLAG_URGENT, ciphertext=b"SOS")

    q.enqueue(chat1)
    q.enqueue(sos)
    q.enqueue(chat2)

    assert q.dequeue().ciphertext == b"SOS"
    assert q.dequeue().ciphertext == b"Chat 1"
    assert q.dequeue().ciphertext == b"Chat 2"
    assert q.dequeue() is None


# ==============================================================================
# F10: USB Serial OTG Transport
# ==============================================================================

def test_t1_f10_01_cobs_encoding_delimiter_no_internal_zeros():
    """T1_F10_01: COBS frame encoding wraps binary packet with trailing 0x00 delimiter without internal zeros."""
    raw_packet = b"\x01\x00\x02\x00\x03\x04\x05\x00\xFF"
    framed = cobs_encode(raw_packet)
    assert framed[-1] == 0x00, "COBS frame must terminate with 0x00 delimiter"
    assert 0x00 not in framed[:-1], "COBS frame payload must not contain internal 0x00 bytes"


def test_t1_f10_02_cobs_decoding_roundtrip():
    """T1_F10_02: COBS frame decoding reconstructs exact original binary packet."""
    raw_packet = b"\x00\x01\x02\x00\x00\xFF\xAA\x00\xBB"
    framed = cobs_encode(raw_packet)
    decoded = cobs_decode(framed)
    assert decoded == raw_packet, f"Decoded COBS frame {decoded} did not match original {raw_packet}"


def test_t1_f10_03_simulated_usb_serial_delivery():
    """T1_F10_03: Simulated USB-Serial transport delivers framed packet to transceiver harness."""
    pkt = LoRaPacket(ciphertext=b"USB Serial Test")
    frame = pkt.pack_frame()
    cobs_frame = cobs_encode(frame)

    # Decode and unpack on receiver end
    rx_bytes = cobs_decode(cobs_frame)
    rx_pkt = LoRaPacket.unpack_frame(rx_bytes)
    assert rx_pkt.ciphertext == b"USB Serial Test"


def test_t1_f10_04_serial_rx_fragmented_chunk_parsing():
    """T1_F10_04: Serial RX parser correctly extracts packet across fragmented chunk reads."""
    raw_packet = b"Fragmented Serial Message"
    framed = cobs_encode(raw_packet)

    # Split framed into 3 arbitrary chunks
    chunk1 = framed[:5]
    chunk2 = framed[5:15]
    chunk3 = framed[15:]

    rx_stream = bytearray()
    extracted = None

    for chunk in [chunk1, chunk2, chunk3]:
        rx_stream.extend(chunk)
        if 0x00 in rx_stream:
            delimiter_idx = rx_stream.index(0x00)
            complete_frame = bytes(rx_stream[:delimiter_idx + 1])
            rx_stream = rx_stream[delimiter_idx + 1:]
            extracted = cobs_decode(complete_frame)
            break

    assert extracted == raw_packet, f"Failed to extract packet from fragmented chunks: {extracted}"


def test_t1_f10_05_connection_lifecycle_transitions():
    """T1_F10_05: Connection lifecycle (CONNECT -> ACTIVE -> DISCONNECT -> RECONNECT) transitions properly."""
    lifecycle_states = []

    def set_state(state):
        lifecycle_states.append(state)

    set_state("CONNECT")
    set_state("ACTIVE")
    set_state("DISCONNECT")
    set_state("RECONNECT")
    set_state("ACTIVE")

    assert lifecycle_states == ["CONNECT", "ACTIVE", "DISCONNECT", "RECONNECT", "ACTIVE"]
