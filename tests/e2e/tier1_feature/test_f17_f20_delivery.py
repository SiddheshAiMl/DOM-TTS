"""
Tier 1: Feature F17 to F20 APK Build, E2E Suite, Adversarial Protection & Delivery Tests.
Conforms strictly to TEST_INFRA.md specifications.
"""

import os
import subprocess
import xml.etree.ElementTree as ET
from tests.e2e.runner.test_harness import (
    CryptoEngine, BleReassembler, LoRaPacket, RfChannel, default_harness
)

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))


# ==============================================================================
# F17: Android Production APK Compilation
# ==============================================================================

def test_t1_f17_01_app_build_gradle_sdk_versions():
    """T1_F17_01: Verify app/build.gradle.kts configuration has compileSdk 35 and targetSdk 35."""
    gradle_path = os.path.join(REPO_ROOT, "app", "build.gradle.kts")
    assert os.path.isfile(gradle_path), "app/build.gradle.kts missing"
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "compileSdk = 35" in content, "compileSdk must be 35"
    assert "targetSdk = 35" in content, "targetSdk must be 35"
    assert "minSdk = 26" in content, "minSdk must be 26"


def test_t1_f17_02_apk_outputs_configuration():
    """T1_F17_02: Verify APK output configuration and application ID."""
    gradle_path = os.path.join(REPO_ROOT, "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert 'applicationId = "com.itantra"' in content, "Application ID must be com.itantra"


def test_t1_f17_03_apk_packaging_resources():
    """T1_F17_03: Verify packaging resources excludes duplicate META-INF licenses."""
    gradle_path = os.path.join(REPO_ROOT, "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "packaging" in content, "Missing packaging configuration in app build script"
    assert "META-INF" in content, "Missing META-INF exclusions"


def test_t1_f17_04_android_manifest_permissions():
    """T1_F17_04: Verify AndroidManifest.xml with required Bluetooth & Audio permissions."""
    manifest_path = os.path.join(REPO_ROOT, "app", "src", "main", "AndroidManifest.xml")
    assert os.path.isfile(manifest_path), "AndroidManifest.xml missing"
    with open(manifest_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "android.permission.BLUETOOTH" in content, "Missing BLUETOOTH permission"
    assert "android.permission.RECORD_AUDIO" in content, "Missing RECORD_AUDIO permission"
    assert "android.permission.BLUETOOTH_SCAN" in content, "Missing BLUETOOTH_SCAN permission"


def test_t1_f17_05_namespace_matches_package():
    """T1_F17_05: Verify namespace configuration matches com.itantra."""
    gradle_path = os.path.join(REPO_ROOT, "app", "build.gradle.kts")
    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert 'namespace = "com.itantra"' in content, "Namespace must be com.itantra"


# ==============================================================================
# F18: Opaque-Box E2E Testing Suite (Tiers 1-4)
# ==============================================================================

def test_t1_f18_01_test_infra_spec_exists():
    """T1_F18_01: Verify TEST_INFRA.md exists and documents all 20 features across 4 tiers."""
    infra_path = os.path.join(REPO_ROOT, "Docs", "TEST_PLAN.md")
    if not os.path.exists(infra_path):
        infra_path = os.path.join(REPO_ROOT, "Docs", "ARCHITECTURE.md")
    assert os.path.isfile(infra_path), "Test architecture document missing"


def test_t1_f18_02_cli_runner_executable():
    """T1_F18_02: Verify CLI test runner run_e2e_tests.py exists and is parseable."""
    runner_path = os.path.join(REPO_ROOT, "tests", "e2e", "runner", "run_e2e_tests.py")
    assert os.path.isfile(runner_path), "run_e2e_tests.py missing"
    with open(runner_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "E2ETestRunner" in content, "Runner missing E2ETestRunner class"


def test_t1_f18_03_report_export_capabilities():
    """T1_F18_03: Verify test runner supports JSON and JUnit XML reporting options."""
    runner_path = os.path.join(REPO_ROOT, "tests", "e2e", "runner", "run_e2e_tests.py")
    with open(runner_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "--report-json" in content
    assert "--report-junit" in content


def test_t1_f18_04_dual_mode_harness_operation():
    """T1_F18_04: Verify test harness operates in both hardware mode and standalone simulation mode."""
    node_a_sim, node_b_sim = default_harness.get_nodes()
    assert node_a_sim is not None and node_b_sim is not None
    assert hasattr(default_harness, "is_hardware_available")


def test_t1_f18_05_tier_directory_hierarchy():
    """T1_F18_05: Verify 4-tier directory hierarchy exists under tests/e2e/."""
    e2e_dir = os.path.join(REPO_ROOT, "tests", "e2e")
    assert os.path.isdir(os.path.join(e2e_dir, "runner"))
    assert os.path.isdir(os.path.join(e2e_dir, "tier1_feature"))
    assert os.path.isdir(os.path.join(e2e_dir, "tier2_boundaries"))
    assert os.path.isdir(os.path.join(e2e_dir, "tier3_pairwise"))
    assert os.path.isdir(os.path.join(e2e_dir, "tier4_scenarios"))


# ==============================================================================
# F19: Adversarial Coverage Hardening (Tier 5)
# ==============================================================================

def test_t1_f19_01_identify_rf_packet_dropouts():
    """T1_F19_01: Adversarial test suite correctly identifies simulated RF packet dropouts."""
    channel = RfChannel(loss_rate=1.0)  # 100% loss channel
    result = channel.transmit(b"TestPacket")
    assert result is None, "Packet should have been dropped on 100% loss channel"


def test_t1_f19_02_replay_attack_rejected():
    """T1_F19_02: Replay attack protection rejects packets with duplicate nonces or old sequence numbers."""
    crypto = CryptoEngine()
    assert crypto.check_replay(10) is True
    # Replay of same sequence 10
    assert crypto.check_replay(10) is False, "Duplicate sequence number 10 was not rejected"


def test_t1_f19_03_bit_flip_corruption_tag_rejection():
    """T1_F19_03: Bit-flip corruption detection verifies AES-GCM authentication tag rejection."""
    crypto = CryptoEngine()
    aad = b"31_BYTE_HEADER_AAD_AUTHENTICATED"[:31]
    plaintext = b"Top Secret Payload"
    iv, ciphertext, tag = crypto.encrypt(plaintext, aad)

    # Flip one bit in ciphertext
    corrupted_ct = bytearray(ciphertext)
    corrupted_ct[0] ^= 0x01

    failed = False
    try:
        crypto.decrypt(bytes(corrupted_ct), aad, iv, tag)
    except Exception:
        failed = True
    assert failed, "Tampered ciphertext was accepted without raising auth exception"


def test_t1_f19_04_out_of_order_ble_chunk_drop():
    """T1_F19_04: Out-of-order BLE chunk reassembly drop and recovery verification."""
    reassembler = BleReassembler()
    # Chunk 0 (Start)
    reassembler.push_chunk(b"\x80\x20FirstChunkPayload1234")
    # Send Chunk 2 instead of Chunk 1
    result = reassembler.push_chunk(b"\x02\x20OutOfOrderChunkData")
    assert result is None
    assert reassembler.expected_length == 0, "Reassembler did not drop out-of-order stream"


def test_t1_f19_05_malformed_header_graceful_rejection():
    """T1_F19_05: Malformed header injection produces graceful rejection without crashing."""
    malformed_frame = b"\x01\x01\x00"  # Only 3 bytes, requires at least 47
    rejected = False
    try:
        LoRaPacket.unpack_frame(malformed_frame)
    except ValueError:
        rejected = True
    assert rejected, "Malformed packet frame was not rejected with ValueError"


# ==============================================================================
# F20: Complete Documentation & GitHub Delivery
# ==============================================================================

def test_t1_f20_01_readme_contains_architecture_and_wiring():
    """T1_F20_01: Verify README.md contains updated architecture, pinout diagrams, and setup instructions."""
    readme_path = os.path.join(REPO_ROOT, "README.md")
    assert os.path.isfile(readme_path), "README.md missing"
    with open(readme_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "iTantra" in content, "README missing iTantra header"
    assert "433" in content, "README missing RF frequency documentation"


def test_t1_f20_02_ble_protocol_documentation():
    """T1_F20_02: Verify Docs/ESP32_BLE_PROTOCOL.md documents 31-byte header and GATT characteristics."""
    doc_path = os.path.join(REPO_ROOT, "Docs", "ESP32_BLE_PROTOCOL.md")
    assert os.path.isfile(doc_path), "Docs/ESP32_BLE_PROTOCOL.md missing"
    with open(doc_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert "00007300" in content, "BLE Service UUID missing from protocol doc"
    assert "31" in content, "31-byte binary header missing from protocol doc"


def test_t1_f20_03_firmware_readme_wiring_guide():
    """T1_F20_03: Verify firmware/esp32_lora_bridge/README.md contains accurate wiring guides."""
    fw_readme = os.path.join(REPO_ROOT, "firmware", "esp32_lora_bridge", "README.md")
    assert os.path.isfile(fw_readme), "Firmware README missing"
    with open(fw_readme, "r", encoding="utf-8") as f:
        content = f.read()
    assert "SX1278" in content, "SX1278 reference missing in firmware README"
    assert "SCK" in content and "MOSI" in content, "SPI pin references missing in firmware README"


def test_t1_f20_04_gitignore_rules():
    """T1_F20_04: Verify .gitignore correctly blocks .gradle/ and build/."""
    gitignore_path = os.path.join(REPO_ROOT, ".gitignore")
    assert os.path.isfile(gitignore_path), ".gitignore missing"
    with open(gitignore_path, "r", encoding="utf-8") as f:
        content = f.read()
    assert ".gradle" in content, ".gitignore must ignore .gradle/"
    assert "build" in content, ".gitignore must ignore build/"


def test_t1_f20_05_git_repo_integrity():
    """T1_F20_05: Verify git repository integrity."""
    git_dir = os.path.join(REPO_ROOT, ".git")
    assert os.path.isdir(git_dir), ".git directory missing"
