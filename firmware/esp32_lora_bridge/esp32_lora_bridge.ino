/**
 * iTantra — ESP32 to SX1278 LoRa & BLE Transparent Packet Bridge
 * Arduino IDE Sketch & PlatformIO Entry
 * Project: SIH26173
 *
 * Board: ESP32 Dev Module
 * Hardware: ESP32-D0WD-V3 + SX1278 (Ra-02 433 MHz)
 * Regulatory Compliance: Indian WPC G.S.R. 347(E) (433.05 - 434.79 MHz, 10% duty cycle, 10 mW ERP)
 */

#include <SPI.h>
#include <LoRa.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_task_wdt.h>
#include <esp_mac.h>

#define LORA_SCK_PIN   18
#define LORA_MISO_PIN  19
#define LORA_MOSI_PIN  23
#define LORA_SS_PIN    5
#define LORA_RST_PIN   14
#define LORA_DIO0_PIN  26

#define LORA_CARRIER_FREQ_HZ   433175000E0
#define LORA_BANDWIDTH_HZ      125E3
#define LORA_SPREADING_FACTOR  7
#define LORA_CODING_RATE       5
#define LORA_SYNC_WORD         0x73
#define LORA_TX_POWER_DBM      10
#define WPC_MAX_DUTY_CYCLE_SEC 360

#define SERVICE_UUID           "00007300-6954-616E-7472-614E6F646531"
#define CHAR_DEVICE_INFO_UUID  "00007301-6954-616E-7472-614E6F646531"
#define CHAR_TX_DATA_UUID      "00007302-6954-616E-7472-614E6F646531"
#define CHAR_RX_DATA_UUID      "00007303-6954-616E-7472-614E6F646531"
#define CHAR_CONTROL_UUID      "00007304-6954-616E-7472-614E6F646531"
#define CHAR_STATUS_UUID       "00007305-6954-616E-7472-614E6F646531"
#define CHAR_STATISTICS_UUID   "00007306-6954-616E-7472-614E6F646531"

#define BLE_CHUNK_HEADER_SIZE  2
#define CHUNK_FLAG_START       0x80
#define CHUNK_FLAG_END         0x40
#define CHUNK_SEQ_MASK         0x3F
#define MAX_LORA_FRAME_LEN     255

struct LinkStats {
  uint32_t txCount = 0;
  uint32_t rxCount = 0;
  uint16_t retriesCount = 0;
  uint16_t droppedCount = 0;
  int8_t   lastRssi = -120;
  int8_t   lastSnr = 0;
  uint32_t totalAirtimeMs = 0;
  uint32_t windowAirtimeMs = 0;
  uint16_t lastError = 0;
} g_stats;

BLEServer*         pServer = nullptr;
BLECharacteristic* pTxDataChar = nullptr;
BLECharacteristic* pRxDataChar = nullptr;
BLECharacteristic* pControlChar = nullptr;
BLECharacteristic* pStatusChar = nullptr;
BLECharacteristic* pStatsChar = nullptr;
bool               bBleConnected = false;
uint16_t           g_negotiatedMtu = 23;
bool               g_bRadioInitialized = false;

QueueHandle_t qBleToRadio;
QueueHandle_t qRadioToBle;
SemaphoreHandle_t xSpiMutex;

struct RadioPacket {
  uint8_t length;
  uint8_t data[MAX_LORA_FRAME_LEN];
};

struct BleReassembler {
  uint8_t buffer[MAX_LORA_FRAME_LEN];
  int     expectedLength = 0;
  int     currentOffset = 0;
  int     lastSeq = -1;
  uint32_t lastChunkTime = 0;

  void reset() {
    expectedLength = 0;
    currentOffset = 0;
    lastSeq = -1;
    lastChunkTime = 0;
  }
} g_reassembler;

void updateStatusRecord(uint8_t radioState, uint16_t errorCode) {
  g_stats.lastError = errorCode;
  uint8_t statusRecord[8];
  statusRecord[0] = radioState;
  statusRecord[1] = 0x00;
  statusRecord[2] = (uint8_t)(qBleToRadio ? uxQueueMessagesWaiting(qBleToRadio) : 0);
  statusRecord[3] = (uint8_t)(qRadioToBle ? uxQueueMessagesWaiting(qRadioToBle) : 0);
  statusRecord[4] = (errorCode >> 8) & 0xFF;
  statusRecord[5] = errorCode & 0xFF;
  statusRecord[6] = 0x00;
  statusRecord[7] = 0x00;

  if (pStatusChar != nullptr) {
    pStatusChar->setValue(statusRecord, 8);
    if (bBleConnected) {
      pStatusChar->notify();
    }
  }
}

void broadcastStatistics() {
  if (!bBleConnected || pStatsChar == nullptr) return;

  uint8_t record[16];
  record[0] = (g_stats.txCount >> 24) & 0xFF;
  record[1] = (g_stats.txCount >> 16) & 0xFF;
  record[2] = (g_stats.txCount >> 8) & 0xFF;
  record[3] = g_stats.txCount & 0xFF;

  record[4] = (g_stats.rxCount >> 24) & 0xFF;
  record[5] = (g_stats.rxCount >> 16) & 0xFF;
  record[6] = (g_stats.rxCount >> 8) & 0xFF;
  record[7] = g_stats.rxCount & 0xFF;

  record[8] = (g_stats.retriesCount >> 8) & 0xFF;
  record[9] = g_stats.retriesCount & 0xFF;

  record[10] = (g_stats.droppedCount >> 8) & 0xFF;
  record[11] = g_stats.droppedCount & 0xFF;

  record[12] = (uint8_t)g_stats.lastRssi;
  record[13] = (uint8_t)g_stats.lastSnr;

  uint16_t airtimeSec = g_stats.windowAirtimeMs / 1000;
  record[14] = (airtimeSec >> 8) & 0xFF;
  record[15] = airtimeSec & 0xFF;

  pStatsChar->setValue(record, 16);
  pStatsChar->notify();
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer, esp_ble_gatts_cb_param_t* param) override {
    bBleConnected = true;
    Serial.println("[BLE] Client phone connected");
    updateStatusRecord(0x01, 0x0000);
  }

  void onDisconnect(BLEServer* pServer) override {
    bBleConnected = false;
    g_negotiatedMtu = 23;
    Serial.println("[BLE] Client disconnected. Restarting advertising...");
    pServer->getAdvertising()->start();
  }

#if defined(CONFIG_BLUEDROID_ENABLED)
  void onMtuChanged(BLEServer* pServer, esp_ble_gatts_cb_param_t* param) override {
    if (param) {
      g_negotiatedMtu = param->mtu.mtu;
      Serial.printf("[BLE] ATT MTU negotiated: %d bytes\n", g_negotiatedMtu);
    }
  }
#endif
};

class TxDataCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    uint8_t* bytes = pChar->getData();
    size_t chunkLen = pChar->getLength();
    if (bytes == nullptr || chunkLen < BLE_CHUNK_HEADER_SIZE) return;

    uint8_t flags = bytes[0];
    uint8_t totalLength = bytes[1];
    bool isStart = (flags & CHUNK_FLAG_START) != 0;
    bool isEnd   = (flags & CHUNK_FLAG_END) != 0;
    uint8_t seq  = (flags & CHUNK_SEQ_MASK);

    uint8_t payloadLen = chunkLen - BLE_CHUNK_HEADER_SIZE;
    const uint8_t* payload = bytes + BLE_CHUNK_HEADER_SIZE;

    // Boundary checks to strictly prevent buffer overflow
    if (payloadLen > MAX_LORA_FRAME_LEN || totalLength > MAX_LORA_FRAME_LEN) {
      g_reassembler.reset();
      updateStatusRecord(0x01, 0x0004);
      return;
    }

    if (isStart) {
      g_reassembler.reset();
      g_reassembler.expectedLength = totalLength;
      g_reassembler.lastSeq = seq;
      memcpy(g_reassembler.buffer, payload, payloadLen);
      g_reassembler.currentOffset = payloadLen;
    } else {
      if (g_reassembler.expectedLength == 0) return;
      if (seq != ((g_reassembler.lastSeq + 1) & CHUNK_SEQ_MASK)) {
        g_reassembler.reset();
        return;
      }
      if ((g_reassembler.currentOffset + payloadLen) > MAX_LORA_FRAME_LEN ||
          (g_reassembler.currentOffset + payloadLen) > g_reassembler.expectedLength) {
        g_reassembler.reset();
        updateStatusRecord(0x01, 0x0004);
        return;
      }
      g_reassembler.lastSeq = seq;
      memcpy(g_reassembler.buffer + g_reassembler.currentOffset, payload, payloadLen);
      g_reassembler.currentOffset += payloadLen;
    }

    if (isEnd && g_reassembler.currentOffset == g_reassembler.expectedLength) {
      RadioPacket pkt;
      pkt.length = g_reassembler.expectedLength;
      memcpy(pkt.data, g_reassembler.buffer, pkt.length);

      if (xQueueSend(qBleToRadio, &pkt, pdMS_TO_TICKS(50)) != pdTRUE) {
        g_stats.droppedCount++;
        updateStatusRecord(0x01, 0x0003);
        Serial.println("[QUEUE] Outbound queue full. Packet dropped.");
      }
      g_reassembler.reset();
    }
  }
};

class ControlCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pChar) override {
    uint8_t* data = pChar->getData();
    size_t len = pChar->getLength();
    if (data == nullptr || len < 1) return;

    uint8_t cmdId = data[0];
    uint8_t payloadLen = (len >= 2) ? data[1] : 0;
    const uint8_t* payload = (len >= 2 + payloadLen) ? (data + 2) : nullptr;

    switch (cmdId) {
      case 0x01: { // PING_NODE
        Serial.println("[BLE CMD] PING_NODE received");
        updateStatusRecord(0x01, 0x0000);
        broadcastStatistics();
        break;
      }
      case 0x02: { // RESET_RADIO
        Serial.println("[BLE CMD] RESET_RADIO received");
        xSemaphoreTake(xSpiMutex, portMAX_DELAY);
        pinMode(LORA_RST_PIN, OUTPUT);
        digitalWrite(LORA_RST_PIN, LOW);
        delay(20);
        digitalWrite(LORA_RST_PIN, HIGH);
        delay(50);
        bool ok = LoRa.begin(LORA_CARRIER_FREQ_HZ);
        if (ok) {
          LoRa.setSignalBandwidth(LORA_BANDWIDTH_HZ);
          LoRa.setSpreadingFactor(LORA_SPREADING_FACTOR);
          LoRa.setCodingRate4(LORA_CODING_RATE);
          LoRa.setSyncWord(LORA_SYNC_WORD);
          LoRa.setTxPower(LORA_TX_POWER_DBM);
          LoRa.enableCrc();
          LoRa.receive();
          g_bRadioInitialized = true;
          updateStatusRecord(0x01, 0x0000);
          Serial.println("[RADIO] SX1278 Reinitialized successfully.");
        } else {
          g_bRadioInitialized = false;
          updateStatusRecord(0x04, 0x0001);
          Serial.println("[RADIO] SX1278 Reinitialization failed!");
        }
        xSemaphoreGive(xSpiMutex);
        break;
      }
      case 0x03: { // SET_FREQUENCY
        if (payload && payloadLen >= 4) {
          uint32_t freq = ((uint32_t)payload[0] << 24) |
                          ((uint32_t)payload[1] << 16) |
                          ((uint32_t)payload[2] << 8)  |
                          ((uint32_t)payload[3]);
          if (freq >= 433050000UL && freq <= 434790000UL) {
            xSemaphoreTake(xSpiMutex, portMAX_DELAY);
            LoRa.setFrequency(freq);
            xSemaphoreGive(xSpiMutex);
            Serial.printf("[BLE CMD] Frequency set to %u Hz\n", freq);
          } else {
            Serial.printf("[BLE CMD] Frequency %u Hz out of legal WPC band!\n", freq);
          }
        }
        break;
      }
      case 0x04: { // SET_TX_POWER
        if (payload && payloadLen >= 1) {
          int8_t power = (int8_t)payload[0];
          if (power > LORA_TX_POWER_DBM) power = LORA_TX_POWER_DBM;
          if (power < 2) power = 2;
          xSemaphoreTake(xSpiMutex, portMAX_DELAY);
          LoRa.setTxPower(power);
          xSemaphoreGive(xSpiMutex);
          Serial.printf("[BLE CMD] TX Power set to %d dBm\n", power);
        }
        break;
      }
      case 0x05: { // CLEAR_STATS
        Serial.println("[BLE CMD] CLEAR_STATS received");
        g_stats.txCount = 0;
        g_stats.rxCount = 0;
        g_stats.retriesCount = 0;
        g_stats.droppedCount = 0;
        g_stats.windowAirtimeMs = 0;
        g_stats.lastError = 0x0000;
        updateStatusRecord(0x01, 0x0000);
        broadcastStatistics();
        break;
      }
      default:
        Serial.printf("[BLE CMD] Unknown command 0x%02X\n", cmdId);
        break;
    }
  }
};

void notifyBleInboundFrame(const uint8_t* frame, size_t length) {
  if (!bBleConnected || pRxDataChar == nullptr || length == 0) return;

  size_t maxChunkPayload = (g_negotiatedMtu > 23 ? g_negotiatedMtu : 23) - 5;
  if (maxChunkPayload > 240) maxChunkPayload = 240;

  size_t offset = 0;
  uint8_t seq = 0;

  while (offset < length) {
    size_t chunkPayloadLen = min(length - offset, maxChunkPayload);
    bool isStart = (offset == 0);
    bool isEnd   = (offset + chunkPayloadLen >= length);

    uint8_t flags = (seq & CHUNK_SEQ_MASK);
    if (isStart) flags |= CHUNK_FLAG_START;
    if (isEnd)   flags |= CHUNK_FLAG_END;

    uint8_t chunk[256];
    chunk[0] = flags;
    chunk[1] = (uint8_t)length;
    memcpy(chunk + BLE_CHUNK_HEADER_SIZE, frame + offset, chunkPayloadLen);

    pRxDataChar->setValue(chunk, chunkPayloadLen + BLE_CHUNK_HEADER_SIZE);
    pRxDataChar->notify();

    offset += chunkPayloadLen;
    seq = (seq + 1) & CHUNK_SEQ_MASK;
    vTaskDelay(pdMS_TO_TICKS(15));
  }
}

void radioTask(void* pvParameters) {
  esp_task_wdt_add(NULL);
  RadioPacket outbound;
  static uint8_t rxBuffer[MAX_LORA_FRAME_LEN];
  static uint32_t windowStartMs = 0;
  static uint32_t windowAirtimeMs = 0;

  for (;;) {
    esp_task_wdt_reset();

    if (!g_bRadioInitialized) {
      if (xQueueReceive(qBleToRadio, &outbound, pdMS_TO_TICKS(100)) == pdTRUE) {
        g_stats.droppedCount++;
        Serial.println("[RADIO] Dropping packet: SX1278 transceiver uninitialized.");
      }
      vTaskDelay(pdMS_TO_TICKS(100));
      continue;
    }

    // 1. Check outbound queue from BLE or Serial CLI
    if (xQueueReceive(qBleToRadio, &outbound, pdMS_TO_TICKS(20)) == pdTRUE) {
      uint32_t now = millis();
      // 1-hour rolling window reset (3,600,000 ms)
      if (windowStartMs == 0 || (now - windowStartMs >= 3600000UL)) {
        windowStartMs = now;
        windowAirtimeMs = 0;
      }

      if ((windowAirtimeMs / 1000) >= WPC_MAX_DUTY_CYCLE_SEC) {
        Serial.println("[RADIO] WPC Duty Cycle limit reached (10%). TX blocked.");
        updateStatusRecord(0x01, 0x0005);
        continue;
      }

      updateStatusRecord(0x02, 0x0000);
      xSemaphoreTake(xSpiMutex, portMAX_DELAY);
      uint32_t txStart = millis();

      LoRa.beginPacket();
      LoRa.write(outbound.data, outbound.length);
      LoRa.endPacket();

      uint32_t duration = millis() - txStart;
      windowAirtimeMs += duration;
      g_stats.windowAirtimeMs = windowAirtimeMs;
      g_stats.totalAirtimeMs += duration;
      g_stats.txCount++;

      LoRa.receive();
      xSemaphoreGive(xSpiMutex);

      updateStatusRecord(0x01, 0x0000);

      Serial.printf("[LORA TX] Sent %d bytes in %d ms (Total TX: %d, Window: %d s)\n",
                    outbound.length, duration, g_stats.txCount, windowAirtimeMs / 1000);

      broadcastStatistics();
    }

    // 2. Poll inbound packets from LoRa SX1278
    xSemaphoreTake(xSpiMutex, portMAX_DELAY);
    int packetSize = LoRa.parsePacket();
    if (packetSize > 0) {
      updateStatusRecord(0x03, 0x0000);
      int len = 0;
      while (LoRa.available() && len < MAX_LORA_FRAME_LEN) {
        rxBuffer[len++] = LoRa.read();
      }

      g_stats.lastRssi = (int8_t)LoRa.packetRssi();
      g_stats.lastSnr  = (int8_t)LoRa.packetSnr();
      g_stats.rxCount++;

      xSemaphoreGive(xSpiMutex);

      Serial.printf("[LORA RX] Received %d bytes (RSSI: %d dBm, SNR: %d dB)\n",
                    len, g_stats.lastRssi, g_stats.lastSnr);

      Serial.print("[LORA RX DATA] ");
      for (int i = 0; i < len; i++) {
        if (rxBuffer[i] >= 32 && rxBuffer[i] <= 126) Serial.print((char)rxBuffer[i]);
        else Serial.printf("\\x%02X", rxBuffer[i]);
      }
      Serial.println();

      // Enqueue to qRadioToBle for Core 0 decoupling (does NOT block Core 1 with BLE delay)
      RadioPacket rxPkt;
      rxPkt.length = len;
      memcpy(rxPkt.data, rxBuffer, len);
      if (xQueueSend(qRadioToBle, &rxPkt, 0) != pdTRUE) {
        g_stats.droppedCount++;
        updateStatusRecord(0x01, 0x0003);
        Serial.println("[QUEUE] Inbound radio queue full. Packet dropped.");
      }

      updateStatusRecord(0x01, 0x0000);
      broadcastStatistics();
    } else {
      xSemaphoreGive(xSpiMutex);
    }

    vTaskDelay(pdMS_TO_TICKS(10));
  }
}

void telemetryTask(void* pvParameters) {
  esp_task_wdt_add(NULL);
  for (;;) {
    esp_task_wdt_reset();
    vTaskDelay(pdMS_TO_TICKS(3000));
    broadcastStatistics();
  }
}

void setup() {
  Serial.begin(115200);
  Serial.setTimeout(50);
  delay(1000);
  Serial.println("==================================================");
  Serial.println("   iTantra ESP32 LoRa-BLE Bridge (SIH26173)");
  Serial.println("==================================================");

  qBleToRadio = xQueueCreate(20, sizeof(RadioPacket));
  qRadioToBle = xQueueCreate(20, sizeof(RadioPacket));
  xSpiMutex   = xSemaphoreCreateMutex();

  SPI.begin(LORA_SCK_PIN, LORA_MISO_PIN, LORA_MOSI_PIN, LORA_SS_PIN);
  LoRa.setPins(LORA_SS_PIN, LORA_RST_PIN, LORA_DIO0_PIN);

  Serial.print("[RADIO] Initializing SX1278 at 433.175 MHz ... ");
  g_bRadioInitialized = LoRa.begin(LORA_CARRIER_FREQ_HZ);
  if (!g_bRadioInitialized) {
    pinMode(LORA_RST_PIN, OUTPUT);
    digitalWrite(LORA_RST_PIN, LOW);
    delay(20);
    digitalWrite(LORA_RST_PIN, HIGH);
    delay(50);
    g_bRadioInitialized = LoRa.begin(LORA_CARRIER_FREQ_HZ);
  }

  if (g_bRadioInitialized) {
    Serial.println("SUCCESS!");
    LoRa.setSignalBandwidth(LORA_BANDWIDTH_HZ);
    LoRa.setSpreadingFactor(LORA_SPREADING_FACTOR);
    LoRa.setCodingRate4(LORA_CODING_RATE);
    LoRa.setSyncWord(LORA_SYNC_WORD);
    LoRa.setTxPower(LORA_TX_POWER_DBM);
    LoRa.enableCrc();
    LoRa.receive();
  } else {
    Serial.println("FAILED! Check SPI wiring and power supply.");
    pinMode(LORA_SS_PIN, OUTPUT);
    digitalWrite(LORA_SS_PIN, LOW);
    uint8_t regVer = SPI.transfer(0x42 & 0x7F);
    regVer = SPI.transfer(0x00);
    digitalWrite(LORA_SS_PIN, HIGH);
    Serial.printf("[RADIO DIAG] SPI Read RegVersion (0x42) returned: 0x%02X (expected 0x12)\n", regVer);
    g_stats.lastError = 0x0001;
  }

  // Base MAC address derivation for node naming (iTantra-Node-CD14 and iTantra-Node-DD00)
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  char deviceName[32];
  snprintf(deviceName, sizeof(deviceName), "iTantra-Node-%02X%02X", mac[4], mac[5]);

  Serial.printf("[BLE] Initializing as Peripheral '%s' ...\n", deviceName);
  BLEDevice::init(deviceName);
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new ServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  BLECharacteristic* pInfoChar = pService->createCharacteristic(
      CHAR_DEVICE_INFO_UUID,
      BLECharacteristic::PROPERTY_READ
  );
  pInfoChar->setValue("iTantra-Node-v1.0.0;SX1278-433MHz");

  pTxDataChar = pService->createCharacteristic(
      CHAR_TX_DATA_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  pTxDataChar->setCallbacks(new TxDataCallbacks());

  pRxDataChar = pService->createCharacteristic(
      CHAR_RX_DATA_UUID,
      BLECharacteristic::PROPERTY_NOTIFY
  );
  pRxDataChar->addDescriptor(new BLE2902());

  pControlChar = pService->createCharacteristic(
      CHAR_CONTROL_UUID,
      BLECharacteristic::PROPERTY_WRITE
  );
  pControlChar->setCallbacks(new ControlCallbacks());

  pStatusChar = pService->createCharacteristic(
      CHAR_STATUS_UUID,
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  pStatusChar->addDescriptor(new BLE2902());
  updateStatusRecord(g_bRadioInitialized ? 0x01 : 0x04, g_bRadioInitialized ? 0x0000 : 0x0001);

  pStatsChar = pService->createCharacteristic(
      CHAR_STATISTICS_UUID,
      BLECharacteristic::PROPERTY_NOTIFY
  );
  pStatsChar->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("[BLE] GATT Server active & advertising started.");

#if ESP_IDF_VERSION >= ESP_IDF_VERSION_VAL(5, 0, 0)
  esp_task_wdt_config_t twdt_config = {
      .timeout_ms = 5000,
      .idle_core_mask = 0,
      .trigger_panic = true
  };
  esp_task_wdt_reconfigure(&twdt_config);
#else
  esp_task_wdt_init(5, true);
#endif
  esp_task_wdt_add(NULL);

  xTaskCreatePinnedToCore(radioTask, "RadioTask", 8192, NULL, 5, NULL, 1);
  xTaskCreatePinnedToCore(telemetryTask, "TelemetryTask", 4096, NULL, 1, NULL, 0);

  Serial.println("[SYSTEM] System online. Ready for transparent packet bridging.");
}

void loop() {
  esp_task_wdt_reset();

  // 1. Decoupled Core 0 processing: Drain inbound radio packets to BLE
  RadioPacket rxPkt;
  while (xQueueReceive(qRadioToBle, &rxPkt, 0) == pdTRUE) {
    notifyBleInboundFrame(rxPkt.data, rxPkt.length);
  }

  // 2. Serial CLI Handler
  if (Serial.available()) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.startsWith("TX:")) {
      String payload = cmd.substring(3);
      RadioPacket pkt;
      pkt.length = min((size_t)payload.length(), (size_t)MAX_LORA_FRAME_LEN);
      memcpy(pkt.data, payload.c_str(), pkt.length);
      if (xQueueSend(qBleToRadio, &pkt, pdMS_TO_TICKS(100)) == pdTRUE) {
        Serial.printf("[SERIAL CMD] Queued LoRa TX: '%s' (%d bytes)\n", payload.c_str(), pkt.length);
      } else {
        g_stats.droppedCount++;
        updateStatusRecord(0x01, 0x0003);
        Serial.println("[SERIAL CMD] ERROR: Outbound radio queue full. Packet dropped.");
      }
    } else if (cmd == "PING") {
      Serial.println("[PONG] iTantra Node Online");
    } else if (cmd == "STATS") {
      Serial.printf("[STATS] TX: %d | RX: %d | Retries: %d | Dropped: %d | RSSI: %d dBm | SNR: %d dB | Airtime(1h): %d s\n",
                    g_stats.txCount, g_stats.rxCount, g_stats.retriesCount, g_stats.droppedCount,
                    g_stats.lastRssi, g_stats.lastSnr, g_stats.windowAirtimeMs / 1000);
    } else if (cmd.length() > 0) {
      Serial.printf("[SERIAL CMD] Unknown command: '%s'\n", cmd.c_str());
    }
  }

  vTaskDelay(pdMS_TO_TICKS(10));
}
