# AGENTS.md — Developer & Agent Guide for iTantra (SIH26173)

## Project Overview
iTantra: Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low Bitrate Links.
Transmits speech-over-text via on-device Indic STT -> Neural Compression -> ESP32 LoRa Bridge (SX1278 433 MHz) -> Indic TTS.

## Build & Test Commands

### Android Application
- Build Debug APK: `./gradlew assembleDebug` (Artifact: `app/build/outputs/apk/debug/app-debug.apk`)
- Run Unit Tests: `./gradlew test` (49 tests, Gradle 8.13, JDK 21 LTS, SDK 35)
- File-Scoped Test: `./gradlew test --tests "com.itantra.transport.StopAndWaitArqTest"`

### ESP32 LoRa Firmware
- Toolchain: Arduino CLI 1.6.0 (`esp32:esp32:esp32:PartitionScheme=huge_app`) / PlatformIO
- Compile Sketch:
  ```powershell
  & "C:\Program Files\Arduino CLI\arduino-cli.exe" compile --fqbn esp32:esp32:esp32:PartitionScheme=huge_app --build-path "firmware\esp32_lora_bridge\build" "firmware\esp32_lora_bridge"
  ```
- Flash Physical Nodes (COM6 & COM7):
  ```powershell
  powershell -ExecutionPolicy Bypass -File firmware\esp32_lora_bridge\flash_nodes.ps1
  ```
- Verify Over-The-Air RF Link:
  ```powershell
  powershell -ExecutionPolicy Bypass -File firmware\esp32_lora_bridge\test_lora_link.ps1
  ```

### Master E2E Test Suite (232 Tests)
- Run All Tiers (Tiers 1-4):
  ```powershell
  & "C:\Users\Admin\AppData\Local\Python\bin\python.exe" tests/e2e/runner/run_e2e_tests.py --tier all
  ```
- Run Specific Tier:
  ```powershell
  & "C:\Users\Admin\AppData\Local\Python\bin\python.exe" tests/e2e/runner/run_e2e_tests.py --tier 1
  ```

## Architecture & Code Conventions
- Android Architecture: Clean Architecture + Jetpack Compose + Kotlin Coroutines & Flow + Room DB + DataStore.
- Transport Protocol: Stop-and-Wait ARQ (`StopAndWaitArqEngine.kt`) over BLE GATT / USB-Serial OTG.
- Compression: Neural / semantic tokenization in `com.itantra.speech.compression` (10 Indic languages: hi, bn, ta, te, mr, gu, kn, ml, or, pa + en).
- Cryptography: AES-256-GCM authenticated encryption with monotonic nonces & QR-based HKDF key derivation.
- Hardware: ESP32-D0WD-V3 + Semtech SX1278 (Ra-02 433.175 MHz), WPC G.S.R. 347(E) compliant.
