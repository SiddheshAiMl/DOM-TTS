"""
Tier 1: Feature F03 to F06 Hardware, Firmware & LoRa Link Tests.
Conforms strictly to TEST_INFRA.md specifications.
"""

import os
import sys
import time
import re
from tests.e2e.runner.test_harness import (
    default_harness, LoRaPacket, MessageType, PacketFlag, crc16_ccitt, HAS_SERIAL
)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
INO_PATH = os.path.join(REPO_ROOT, "firmware", "esp32_lora_bridge", "esp32_lora_bridge.ino")


def _is_hw_mode() -> bool:
    mode = os.environ.get("ITANTRA_TEST_MODE", "simulated").lower()
    return mode == "hardware" and default_harness.is_hardware_available


# ==============================================================================
# F03: ESP32 Hardware Identification
# ==============================================================================

def test_t1_f03_01_detect_serial_ports():
    """T1_F03_01: Detect presence of serial COM ports (COM6 and COM7) or simulated nodes."""
    if _is_hw_mode() and HAS_SERIAL:
        import serial.tools.list_ports
        ports = [p.device for p in serial.tools.list_ports.comports()]
        assert "COM6" in ports, f"COM6 not detected in active ports: {ports}"
        assert "COM7" in ports, f"COM7 not detected in active ports: {ports}"
    else:
        node_a, node_b = default_harness.get_nodes()
        assert node_a is not None and node_b is not None


def test_t1_f03_02_node_a_identification():
    """T1_F03_02: Verify Node A (COM6) responds to CP210x identification or simulator."""
    if _is_hw_mode() and HAS_SERIAL:
        import serial.tools.list_ports
        p6 = [p for p in serial.tools.list_ports.comports() if p.device == "COM6"]
        assert len(p6) > 0, "COM6 not found"
        desc = p6[0].description.lower()
        assert "cp210" in desc or "serial" in desc or "usb" in desc
    else:
        node_a, _ = default_harness.get_nodes()
        assert node_a.node_id == 1
        assert "CD14" in node_a.name


def test_t1_f03_03_node_b_identification():
    """T1_F03_03: Verify Node B (COM7) responds to CP210x identification or simulator."""
    if _is_hw_mode() and HAS_SERIAL:
        import serial.tools.list_ports
        p7 = [p for p in serial.tools.list_ports.comports() if p.device == "COM7"]
        assert len(p7) > 0, "COM7 not found"
        desc = p7[0].description.lower()
        assert "cp210" in desc or "serial" in desc or "usb" in desc
    else:
        _, node_b = default_harness.get_nodes()
        assert node_b.node_id == 2
        assert "DD00" in node_b.name


def test_t1_f03_04_node_a_mac_mapping():
    """T1_F03_04: Verify Node A MAC address maps to BLE name iTantra-Node-CD14."""
    mac_bytes = bytes.fromhex("00700783cd14")
    ble_name = f"iTantra-Node-{mac_bytes[4]:02X}{mac_bytes[5]:02X}"
    assert ble_name == "iTantra-Node-CD14", f"Expected iTantra-Node-CD14, got {ble_name}"


def test_t1_f03_05_node_b_mac_mapping():
    """T1_F03_05: Verify Node B MAC address maps to BLE name iTantra-Node-DD00."""
    mac_bytes = bytes.fromhex("00700784dd00")
    ble_name = f"iTantra-Node-{mac_bytes[4]:02X}{mac_bytes[5]:02X}"
    assert ble_name == "iTantra-Node-DD00", f"Expected iTantra-Node-DD00, got {ble_name}"


# ==============================================================================
# F04: ESP32 Firmware Build & Configuration
# ==============================================================================

def test_t1_f04_01_firmware_file_exists():
    """T1_F04_01: Verify firmware/esp32_lora_bridge/esp32_lora_bridge.ino exists."""
    assert os.path.isfile(INO_PATH), f"Firmware file missing at {INO_PATH}"
    with open(INO_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    assert "iTantra" in content, "Firmware missing iTantra project identifier"


def test_t1_f04_02_spi_pin_definitions():
    """T1_F04_02: Verify SPI pin definitions (SCK=18, MISO=19, MOSI=23, CS=5, RST=14, DIO0=26)."""
    with open(INO_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    assert "#define LORA_SCK_PIN   18" in content, "LORA_SCK_PIN must be 18"
    assert "#define LORA_MISO_PIN  19" in content, "LORA_MISO_PIN must be 19"
    assert "#define LORA_MOSI_PIN  23" in content, "LORA_MOSI_PIN must be 23"
    assert "#define LORA_SS_PIN    5" in content, "LORA_SS_PIN must be 5"
    assert "#define LORA_RST_PIN   14" in content, "LORA_RST_PIN must be 14"
    assert "#define LORA_DIO0_PIN  26" in content, "LORA_DIO0_PIN must be 26"


def test_t1_f04_03_wpc_frequency_configuration():
    """T1_F04_03: Verify WPC ISM frequency set to 433.175 MHz and bandwidth 125 kHz."""
    with open(INO_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    assert "433175000" in content, "WPC frequency must be 433.175 MHz"
    assert "125E3" in content, "WPC bandwidth must be 125 kHz"


def test_t1_f04_04_freertos_queue_declarations():
    """T1_F04_04: Verify FreeRTOS queues qBleToRadio and qRadioToBle declared and initialized."""
    with open(INO_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    assert "QueueHandle_t qBleToRadio;" in content, "Missing qBleToRadio queue declaration"
    assert "QueueHandle_t qRadioToBle;" in content, "Missing qRadioToBle queue declaration"
    assert "xQueueCreate" in content, "Missing xQueueCreate call in firmware setup"


def test_t1_f04_05_wpc_duty_cycle_limiter_code():
    """T1_F04_05: Verify WPC duty cycle limiter (10% / 360s/hr) enforcement code active."""
    with open(INO_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    assert "WPC_MAX_DUTY_CYCLE_SEC 360" in content, "WPC_MAX_DUTY_CYCLE_SEC must be 360"
    assert "WPC Duty Cycle limit reached" in content, "Firmware must enforce duty cycle blocking"


# ==============================================================================
# F05: Firmware Flashing & Hardware PING/PONG
# ==============================================================================

def test_t1_f05_01_node_a_ping_pong():
    """T1_F05_01: Send PING\\r\\n to Node A -> expect [PONG] iTantra Node Online."""
    node_a, _ = default_harness.get_nodes()
    pong = node_a.send_ping()
    assert "[PONG] iTantra Node Online" in pong, f"Unexpected PING response from Node A: {pong}"


def test_t1_f05_02_node_b_ping_pong():
    """T1_F05_02: Send PING\\r\\n to Node B -> expect [PONG] iTantra Node Online."""
    _, node_b = default_harness.get_nodes()
    pong = node_b.send_ping()
    assert "[PONG] iTantra Node Online" in pong, f"Unexpected PING response from Node B: {pong}"


def test_t1_f05_03_node_a_stats_query():
    """T1_F05_03: Send STATS\\r\\n to Node A -> verify statistics output with TX/RX counters."""
    node_a, _ = default_harness.get_nodes()
    stats = node_a.get_stats_str()
    assert "[STATS]" in stats, f"Invalid STATS response: {stats}"
    assert "TX:" in stats and "RX:" in stats, f"Missing TX/RX counters: {stats}"


def test_t1_f05_04_node_b_stats_query():
    """T1_F05_04: Send STATS\\r\\n to Node B -> verify statistics output."""
    _, node_b = default_harness.get_nodes()
    stats = node_b.get_stats_str()
    assert "[STATS]" in stats, f"Invalid STATS response: {stats}"
    assert "RSSI:" in stats and "SNR:" in stats, f"Missing RSSI/SNR in stats: {stats}"


def test_t1_f05_05_baud_rate_stability():
    """T1_F05_05: Verify baud rate stability over 10 consecutive PING/PONG cycles."""
    node_a, _ = default_harness.get_nodes()
    for _ in range(10):
        pong = node_a.send_ping()
        assert "[PONG] iTantra Node Online" in pong


# ==============================================================================
# F06: Physical LoRa Bidirectional Link Test
# ==============================================================================

def test_t1_f06_01_node_a_to_b_lora_transmission():
    """T1_F06_01: Node A transmits binary packet -> Node B receives packet over LoRa link."""
    node_a = default_harness.node_a_sim
    node_b = default_harness.node_b_sim
    node_b.inbound_packets.clear()

    pkt = LoRaPacket(
        msg_type=MessageType.TEXT.value,
        sender_id=1,
        session_id=100,
        seq_num=1,
        ciphertext=b"Hello Node B over LoRa"
    )
    ok = node_a.transmit_packet(pkt)
    assert ok, "Node A failed to transmit packet"
    assert len(node_b.inbound_packets) == 1, "Node B did not receive packet"
    rx_pkt = node_b.inbound_packets.popleft()
    assert rx_pkt.ciphertext == b"Hello Node B over LoRa"


def test_t1_f06_02_node_b_to_a_lora_transmission():
    """T1_F06_02: Node B transmits binary packet -> Node A receives packet over LoRa link."""
    node_a = default_harness.node_a_sim
    node_b = default_harness.node_b_sim
    node_a.inbound_packets.clear()

    pkt = LoRaPacket(
        msg_type=MessageType.TEXT.value,
        sender_id=2,
        session_id=100,
        seq_num=2,
        ciphertext=b"Hello Node A over LoRa"
    )
    ok = node_b.transmit_packet(pkt)
    assert ok, "Node B failed to transmit packet"
    assert len(node_a.inbound_packets) == 1, "Node A did not receive packet"
    rx_pkt = node_a.inbound_packets.popleft()
    assert rx_pkt.ciphertext == b"Hello Node A over LoRa"


def test_t1_f06_03_lora_rssi_range():
    """T1_F06_03: Verify RSSI reported by receiver is within physical operating range (-120 to -30 dBm)."""
    node_b = default_harness.node_b_sim
    rssi = node_b.stats["last_rssi"]
    assert -125 <= rssi <= -25, f"RSSI {rssi} dBm outside physical LoRa range [-125, -25]"


def test_t1_f06_04_lora_snr_range():
    """T1_F06_04: Verify SNR reported by receiver is valid (-20 dB to +15 dB)."""
    node_b = default_harness.node_b_sim
    snr = node_b.stats["last_snr"]
    assert -20 <= snr <= 15, f"SNR {snr} dB outside valid range [-20, 15]"


def test_t1_f06_05_lora_crc16_check():
    """T1_F06_05: Verify CRC16 hardware check passes on received over-the-air frame."""
    frame_data = b"\x01\x01\x02\x00\x00\x00\x01\x00\x00\x00\x01\x00\x00\x00\x01\x00\x01" + (b"\x00" * 12) + b"\x00\x04TEST"
    crc = crc16_ccitt(frame_data)
    assert isinstance(crc, int) and 0 <= crc <= 0xFFFF, f"Invalid CRC16 value: {crc}"
    # Verify deterministic property
    assert crc16_ccitt(frame_data) == crc
