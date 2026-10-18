#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>
#include <WiFi.h>
#include <driver/i2s.h>
#include <esp_camera.h>
#include <esp_timer.h>
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>
#include <esp_heap_caps.h>

// XIAO ESP32S3 Sense camera / microphone pin mapping from Seeed's hardware guide.
static constexpr int CAMERA_XCLK = 10;
static constexpr int CAMERA_SIOD = 40;
static constexpr int CAMERA_SIOC = 39;
static constexpr int CAMERA_Y9 = 48;
static constexpr int CAMERA_Y8 = 11;
static constexpr int CAMERA_Y7 = 12;
static constexpr int CAMERA_Y6 = 14;
static constexpr int CAMERA_Y5 = 16;
static constexpr int CAMERA_Y4 = 18;
static constexpr int CAMERA_Y3 = 17;
static constexpr int CAMERA_Y2 = 15;
static constexpr int CAMERA_VSYNC = 38;
static constexpr int CAMERA_HREF = 47;
static constexpr int CAMERA_PCLK = 13;

static constexpr uint32_t AUDIO_RATE = 16000;
#ifndef CAMERA_CPU_MHZ
#define CAMERA_CPU_MHZ 240
#endif
static constexpr size_t AUDIO_SAMPLES = 256;
// BLE is a low-throughput link. QVGA reduces image payload while preserving
// a usable live preview; audio is decimated to 8 kHz G.711 mu-law.
// Keep capture paced enough to leave time for Wi-Fi and audio, while allowing
// the sensor to reach its natural frame rate in the lower-resolution modes.
static constexpr uint32_t VIDEO_INTERVAL_MS = 20;
static constexpr uint8_t PACKET_VIDEO_JPEG = 1;
static constexpr uint8_t PACKET_AUDIO_PCM16 = 2;
static constexpr uint8_t PACKET_STATUS = 3;
static constexpr uint8_t PACKET_AUDIO_MULAW8 = 4;

static constexpr char BLE_NAME[] = "XIAO-Cam";
static constexpr char BLE_SERVICE_UUID[] = "8bbd0001-4b55-4f6b-8a8c-7a5f0d9b1000";
static constexpr char BLE_STREAM_UUID[]  = "8bbd0002-4b55-4f6b-8a8c-7a5f0d9b1000";
static constexpr char BLE_CONTROL_UUID[] = "8bbd0003-4b55-4f6b-8a8c-7a5f0d9b1000";
static constexpr char WIFI_SSID[] = "XIAO-Cam-Video";
static constexpr char WIFI_PASSWORD[] = "REPLACE_WITH_UNIQUE_PASSWORD";
static constexpr size_t BLE_FRAGMENT_HEADER_BYTES = 8;
// Keep each notification at or below a 247-byte ATT PDU (244 value bytes),
// which fits the common 251-byte BLE link-layer payload after L2CAP overhead.
// Larger values were negotiated but did not deliver JPEG notifications on the
// tested Android handset.
static constexpr size_t BLE_MAX_VALUE_BYTES = 244;
static volatile size_t ble_value_limit = 20; // ATT default until peer negotiates MTU
static BLECharacteristic *ble_stream = nullptr;
static WiFiServer media_server(8080);
static WiFiClient media_client;
static volatile bool ble_connected = false;
static volatile bool ble_notify_ready = false;
static volatile bool stream_requested = false;
static uint16_t ble_transfer_id = 0;
static uint32_t ble_tx_dropped = 0;
static uint32_t ble_video_tx_packets = 0;
static uint32_t ble_video_tx_bytes = 0;

// Wire header: magic[4], version[1], type[1], header_bytes[2], sequence[4],
// device_timestamp_us[8], payload_bytes[4]. All integer fields are little-endian.
static constexpr uint8_t PACKET_MAGIC[4] = {'X', 'S', 'A', 'V'};
static constexpr uint8_t PROTOCOL_VERSION = 1;
static constexpr uint16_t PACKET_HEADER_BYTES = 24;

struct __attribute__((packed)) PacketHeader {
  uint8_t magic[4];
  uint8_t version;
  uint8_t type;
  uint16_t header_bytes;
  uint32_t sequence;
  uint64_t device_timestamp_us;
  uint32_t payload_bytes;
};
static_assert(sizeof(PacketHeader) == PACKET_HEADER_BYTES, "wire header size mismatch");

struct AudioBlock {
  uint32_t sequence;
  uint64_t timestamp_us;
  int16_t samples[AUDIO_SAMPLES];
};

static QueueHandle_t audio_queue;
static QueueHandle_t camera_queue;
static uint32_t video_sequence = 0;
struct CameraCommand { char name[20]; int value; };
struct CameraReply { char text[160]; };
static QueueHandle_t command_queue;
static QueueHandle_t reply_queue;
static uint32_t wakeups = 0;
static uint32_t dropped_audio_blocks = 0;
static bool camera_ready = false;
static bool microphone_ready = false;
static volatile bool stream_enabled = false;
static volatile uint32_t camera_frames_ok = 0;
static volatile uint32_t camera_frames_null = 0;
static volatile uint32_t camera_frames_dropped = 0;
static volatile uint32_t microphone_reads_ok = 0;
static volatile uint32_t microphone_reads_short = 0;
static volatile uint32_t microphone_last_bytes = 0;
static framesize_t max_frame_size = FRAMESIZE_VGA;
static uint8_t linear_to_mulaw(int16_t input) {
  const int BIAS = 0x84, CLIP = 32635;
  int sample = input;
  int sign = (sample < 0) ? 0x80 : 0;
  if (sample < 0) sample = -sample;
  if (sample > CLIP) sample = CLIP;
  sample += BIAS;
  int exponent = 7;
  for (int mask = 0x4000; exponent > 0 && (sample & mask) == 0; mask >>= 1) --exponent;
  const int mantissa = (sample >> (exponent + 3)) & 0x0f;
  return static_cast<uint8_t>(~(sign | (exponent << 4) | mantissa));
}

static void send_packet(uint8_t type, uint32_t sequence, uint64_t timestamp_us,
                        const uint8_t *payload, uint32_t payload_bytes);
static void send_status(const char *message);

class BleServerCallbacks final : public BLEServerCallbacks {
  void onConnect(BLEServer *) override {
    ble_connected = true;
    ble_notify_ready = false;
  }
  void onDisconnect(BLEServer *) override {
    ble_connected = false;
    ble_notify_ready = false;
    stream_requested = false;
    stream_enabled = false;
    BLEDevice::startAdvertising();
  }
  void onMtuChanged(BLEServer *, esp_ble_gatts_cb_param_t *param) override {
    if (param && param->mtu.mtu > 3) {
      size_t value_bytes = static_cast<size_t>(param->mtu.mtu - 3);
      if (value_bytes > BLE_MAX_VALUE_BYTES) value_bytes = BLE_MAX_VALUE_BYTES;
      ble_value_limit = value_bytes;
    }
  }
};

// The board has no display or keypad, so BLE uses Secure Connections bonding
// with Just Works. This encrypts the link and stores the bond, but does not
// provide MITM protection during the first pairing.
class BleSecurityCallbacks final : public BLESecurityCallbacks {
  uint32_t onPassKeyRequest() override { return 0; }
  void onPassKeyNotify(uint32_t) override {}
  bool onSecurityRequest() override { return true; }
  void onAuthenticationComplete(esp_ble_auth_cmpl_t) override {}
  bool onConfirmPIN(uint32_t) override { return true; }
};

class BleControlCallbacks final : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    const std::string value = characteristic->getValue();
    if (value == "START") {
      CameraCommand size_command{};
      strcpy(size_command.name, "size");
      size_command.value = max_frame_size;
      xQueueSend(command_queue, &size_command, 0);
      CameraCommand quality_command{};
      strcpy(quality_command.name, "quality");
      quality_command.value = 10;
      xQueueSend(command_queue, &quality_command, 0);
      if (i2s_start(I2S_NUM_0) == ESP_OK) {
        stream_requested = true;
        stream_enabled = true;
      }
    } else if (value == "STOP") {
      stream_requested = false;
      stream_enabled = false;
    } else if (value == "SUBSCRIBE") {
      ble_notify_ready = true;
    } else if (value.rfind("SET ", 0) == 0) {
      CameraCommand command{};
      if (sscanf(value.c_str(), "SET %19s %d", command.name, &command.value) == 2) {
        if (xQueueSend(command_queue, &command, 0) != pdTRUE) send_status("CMD_QUEUE_FULL");
      }
    } else if (value == "GET") {
      CameraCommand command{};
      strcpy(command.name, "get");
      if (!stream_enabled && i2s_start(I2S_NUM_0) == ESP_OK) stream_enabled = true;
      if (xQueueSend(command_queue, &command, 0) != pdTRUE) send_status("CMD_QUEUE_FULL");
    }
  }
};

static void send_packet(uint8_t type, uint32_t sequence, uint64_t timestamp_us,
                        const uint8_t *payload, uint32_t payload_bytes);

static void send_status(const char *message) {
  send_packet(PACKET_STATUS, 0, static_cast<uint64_t>(esp_timer_get_time()),
              reinterpret_cast<const uint8_t *>(message),
              static_cast<uint32_t>(strlen(message)));
}

static void send_packet(uint8_t type, uint32_t sequence, uint64_t timestamp_us,
                        const uint8_t *payload, uint32_t payload_bytes) {
  if (!media_client || !media_client.connected()) return;
  if (type == PACKET_VIDEO_JPEG) { ++ble_video_tx_packets; ble_video_tx_bytes += payload_bytes; }
  PacketHeader header{};
  memcpy(header.magic, PACKET_MAGIC, sizeof(PACKET_MAGIC));
  header.version = PROTOCOL_VERSION;
  header.type = type;
  header.header_bytes = PACKET_HEADER_BYTES;
  header.sequence = sequence;
  header.device_timestamp_us = timestamp_us;
  header.payload_bytes = payload_bytes;
  size_t sent = 0;
  const uint8_t *parts[] = {reinterpret_cast<const uint8_t *>(&header), payload};
  const size_t sizes[] = {sizeof(header), payload_bytes};
  for (size_t part = 0; part < 2; ++part) {
    size_t offset = 0;
    while (offset < sizes[part] && media_client.connected()) {
      size_t written = media_client.write(parts[part] + offset, sizes[part] - offset);
      if (!written) { delay(1); continue; }
      offset += written;
    }
    sent += offset;
  }
  if (sent != sizeof(header) + payload_bytes) {
    ++ble_tx_dropped;
    media_client.stop();
  }
}

static bool start_camera() {
  camera_config_t config{};
  config.ledc_channel = LEDC_CHANNEL_0;
  config.ledc_timer = LEDC_TIMER_0;
  config.pin_d0 = CAMERA_Y2;
  config.pin_d1 = CAMERA_Y3;
  config.pin_d2 = CAMERA_Y4;
  config.pin_d3 = CAMERA_Y5;
  config.pin_d4 = CAMERA_Y6;
  config.pin_d5 = CAMERA_Y7;
  config.pin_d6 = CAMERA_Y8;
  config.pin_d7 = CAMERA_Y9;
  config.pin_xclk = CAMERA_XCLK;
  config.pin_pclk = CAMERA_PCLK;
  config.pin_vsync = CAMERA_VSYNC;
  config.pin_href = CAMERA_HREF;
  config.pin_sccb_sda = CAMERA_SIOD;
  config.pin_sccb_scl = CAMERA_SIOC;
  config.pin_pwdn = -1;
  config.pin_reset = -1;
  config.xclk_freq_hz = 20000000;
  config.pixel_format = PIXFORMAT_JPEG;
  config.frame_size = FRAMESIZE_VGA;
  config.jpeg_quality = 12;
  config.fb_count = 1;
  // This board produces frames reliably with one internal DMA frame buffer.
  // PSRAM frame buffers timed out in the previous hardware checks.
  config.fb_location = CAMERA_FB_IN_DRAM;
  config.grab_mode = CAMERA_GRAB_WHEN_EMPTY;
  if (esp_camera_init(&config) != ESP_OK) {
    esp_camera_deinit();
    config.frame_size = FRAMESIZE_QVGA;
    max_frame_size = FRAMESIZE_QVGA;
    if (esp_camera_init(&config) != ESP_OK) return false;
  }
  sensor_t *sensor = esp_camera_sensor_get();
  if (sensor->set_quality(sensor, 10) != 0) return false;
  if (sensor->set_gain_ctrl(sensor, 1) != 0 || sensor->set_exposure_ctrl(sensor, 1) != 0) return false;
  return sensor->set_framesize(sensor, max_frame_size) == 0;
}

static void microphone_task(void *) {
  AudioBlock block{};
  uint32_t sequence = 0;
  for (;;) {
    if (!stream_enabled) { i2s_stop(I2S_NUM_0); vTaskDelay(pdMS_TO_TICKS(20)); continue; }
    const size_t wanted = sizeof(block.samples);
    size_t received_total = 0;
    while (received_total < wanted) {
      size_t bytes_read = 0;
      const esp_err_t result = i2s_read(I2S_NUM_0,
          reinterpret_cast<uint8_t *>(block.samples) + received_total,
          wanted - received_total, &bytes_read, pdMS_TO_TICKS(100));
      const int received = result == ESP_OK ? static_cast<int>(bytes_read) : 0;
      if (received <= 0) {
        if (!stream_enabled) { i2s_stop(I2S_NUM_0); break; }
        ++microphone_reads_short;
        vTaskDelay(pdMS_TO_TICKS(1));
        break;
      }
      received_total += static_cast<size_t>(received);
      microphone_last_bytes = static_cast<uint32_t>(received_total);
    }
    if (received_total != wanted) {
      if (!stream_enabled) { i2s_stop(I2S_NUM_0); continue; }
      continue;
    }
    if (!stream_enabled) { i2s_stop(I2S_NUM_0); continue; }
    ++microphone_reads_ok;
    block.sequence = sequence++;
    block.timestamp_us = static_cast<uint64_t>(esp_timer_get_time());
    if (xQueueSend(audio_queue, &block, 0) != pdTRUE) {
      ++dropped_audio_blocks;
    }
  }
}


static void apply_camera_command(const CameraCommand &command) {
  sensor_t *sensor = esp_camera_sensor_get();
  int result = -1;
  const int value = command.value;
  if (!strcmp(command.name, "get")) result = 0;
  else if (!strcmp(command.name, "size") &&
           (value == FRAMESIZE_QQVGA || value == FRAMESIZE_QVGA || value == FRAMESIZE_VGA) && value <= max_frame_size)
    result = sensor->set_framesize(sensor, static_cast<framesize_t>(value));
  else if (!strcmp(command.name, "quality") && value >= 10 && value <= 30)
    result = sensor->set_quality(sensor, value);
  else if (!strcmp(command.name, "motion") && (value == 0 || value == 1)) {
    // Shorten exposure for moving subjects. Automatic gain keeps the image
    // usable in dim rooms, at the cost of more visible sensor noise.
    if (value) {
      result = sensor->set_gain_ctrl(sensor, 1);
      if (result == 0) result = sensor->set_exposure_ctrl(sensor, 0);
      if (result == 0) result = sensor->set_aec_value(sensor, 300);
    } else {
      result = sensor->set_exposure_ctrl(sensor, 1);
    }
  }
  else if (!strcmp(command.name, "brightness") && value >= -2 && value <= 2)
    result = sensor->set_brightness(sensor, value);
  else if (!strcmp(command.name, "contrast") && value >= -2 && value <= 2)
    result = sensor->set_contrast(sensor, value);
  else if (!strcmp(command.name, "saturation") && value >= -2 && value <= 2)
    result = sensor->set_saturation(sensor, value);
  else if (!strcmp(command.name, "exposure") && value >= -2 && value <= 2)
    result = sensor->set_ae_level(sensor, value);
  else if (!strcmp(command.name, "aec") && (value == 0 || value == 1))
    result = sensor->set_exposure_ctrl(sensor, value);
  else if (!strcmp(command.name, "awb") && (value == 0 || value == 1))
    result = sensor->set_whitebal(sensor, value);
  else if (!strcmp(command.name, "sharpness") && value >= -2 && value <= 2 && sensor->set_sharpness)
    result = sensor->set_sharpness(sensor, value);
  CameraReply reply{};
  snprintf(reply.text, sizeof(reply.text),
           "CFG %s %s=%d PID=%04X size=%d quality=%d brightness=%d contrast=%d saturation=%d sharpness=%d exposure=%d aec=%d awb=%d CPU=%u",
           result == 0 ? "OK" : "ERR", command.name, value, sensor->id.PID,
           sensor->status.framesize, sensor->status.quality, sensor->status.brightness,
           sensor->status.contrast, sensor->status.saturation, sensor->status.sharpness,
           sensor->status.ae_level, sensor->status.aec, sensor->status.awb,
           getCpuFrequencyMhz());
  xQueueSend(reply_queue, &reply, 0);
}

static void camera_task(void *) {
  for (;;) {
    if (!stream_enabled) { vTaskDelay(pdMS_TO_TICKS(20)); continue; }
    CameraCommand command{};
    while (xQueueReceive(command_queue, &command, 0) == pdTRUE) apply_camera_command(command);
    camera_fb_t *frame = esp_camera_fb_get();
    if (frame == nullptr) {
      ++camera_frames_null;
      vTaskDelay(pdMS_TO_TICKS(10));
      continue;
    }
    if (xQueueSend(camera_queue, &frame, 0) != pdTRUE) {
      ++camera_frames_dropped;
    camera_fb_t *stale = nullptr;
    if (xQueueReceive(camera_queue, &stale, 0) == pdTRUE && stale != nullptr)
      esp_camera_fb_return(stale);
    if (xQueueSend(camera_queue, &frame, 0) != pdTRUE) esp_camera_fb_return(frame);
      continue;
    }
    ++camera_frames_ok;
    vTaskDelay(pdMS_TO_TICKS(VIDEO_INTERVAL_MS));
  }
}

void setup() {
  delay(100);

  if (!psramFound() || !start_camera()) {
    for (;;) {
      send_status(psramFound() ? "CAMERA_INIT_FAIL" : "PSRAM_NOT_FOUND");
      delay(1000);
    }
  }
  // Lower CPU clock only after initializing clock-sensitive camera peripherals.
  // Both video cadence and 16 kHz microphone acquisition are retained.
  if (!setCpuFrequencyMhz(CAMERA_CPU_MHZ)) send_status("CPU_CLOCK_CHANGE_FAIL");
  camera_ready = true;
  sensor_t *sensor = esp_camera_sensor_get();
  char camera_status[32];
  snprintf(camera_status, sizeof(camera_status), "CAMERA_PID_%04X",
           sensor ? sensor->id.PID : 0);
  send_status(camera_status);

  i2s_config_t mic_config{};
  mic_config.mode = static_cast<i2s_mode_t>(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM);
  mic_config.sample_rate = AUDIO_RATE;
  mic_config.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  mic_config.channel_format = I2S_CHANNEL_FMT_ONLY_LEFT;
  mic_config.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  mic_config.intr_alloc_flags = ESP_INTR_FLAG_LEVEL1;
  mic_config.dma_buf_count = 4;
  mic_config.dma_buf_len = AUDIO_SAMPLES;
  i2s_pin_config_t mic_pins{};
  mic_pins.mck_io_num = I2S_PIN_NO_CHANGE;
  mic_pins.bck_io_num = I2S_PIN_NO_CHANGE;
  mic_pins.ws_io_num = 42;
  mic_pins.data_out_num = I2S_PIN_NO_CHANGE;
  mic_pins.data_in_num = 41;
  if (i2s_driver_install(I2S_NUM_0, &mic_config, 0, nullptr) != ESP_OK ||
      i2s_set_pin(I2S_NUM_0, &mic_pins) != ESP_OK) {
    for (;;) {
      send_status("MIC_INIT_FAIL");
      delay(1000);
    }
  }
  i2s_stop(I2S_NUM_0);
  microphone_ready = true;

  audio_queue = xQueueCreate(24, sizeof(AudioBlock));
  // Keep only the freshest queued frame so slow consumers do not accumulate
  // latency that makes moving subjects appear farther behind.
  camera_queue = xQueueCreate(1, sizeof(camera_fb_t *));
  command_queue = xQueueCreate(16, sizeof(CameraCommand));
  reply_queue = xQueueCreate(16, sizeof(CameraReply));
  if (!audio_queue || !camera_queue || !command_queue || !reply_queue) {
    for (;;) {
      send_status("AUDIO_QUEUE_FAIL");
      delay(1000);
    }
  }
  WiFi.mode(WIFI_AP);
  WiFi.setSleep(false);
  if (!WiFi.softAP(WIFI_SSID, WIFI_PASSWORD, 6, false, 2)) {
    for (;;) { delay(1000); }
  }
  media_server.begin();
  media_server.setNoDelay(true);
  BLEDevice::init(BLE_NAME);
  BLEDevice::setMTU(517);
  BLESecurity *security = new BLESecurity();
  security->setAuthenticationMode(ESP_LE_AUTH_REQ_SC_BOND);
  security->setCapability(ESP_IO_CAP_NONE);
  BLEDevice::setSecurityCallbacks(new BleSecurityCallbacks());
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new BleServerCallbacks());
  BLEService *service = server->createService(BLE_SERVICE_UUID);
  ble_stream = service->createCharacteristic(BLE_STREAM_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  BLE2902 *stream_cccd = new BLE2902();
  stream_cccd->setAccessPermissions(ESP_GATT_PERM_READ_ENCRYPTED | ESP_GATT_PERM_WRITE_ENCRYPTED);
  ble_stream->addDescriptor(stream_cccd);
  BLECharacteristic *control = service->createCharacteristic(
      BLE_CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  control->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
  control->setCallbacks(new BleControlCallbacks());
  service->start();
  BLEAdvertising *advertising = server->getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->start();
  // The PDM reader must not starve ESP-IDF system work. Camera frame acquisition
  // runs at a higher priority on the other core; both outputs are queued to loop().
  if (xTaskCreatePinnedToCore(microphone_task, "pdm_capture", 4096, nullptr, 1, nullptr, 0) != pdPASS ||
      xTaskCreatePinnedToCore(camera_task, "camera_capture", 4096, nullptr, 3, nullptr, 1) != pdPASS) {
    for (;;) { send_status("TASK_CREATE_FAIL"); delay(1000); }
  }
  send_status("READY");
}

void loop() {
  if (media_client && !media_client.connected()) {
    media_client.stop();
    stream_enabled = false;
  }
  if (!media_client || !media_client.connected()) {
    WiFiClient incoming = media_server.available();
    if (incoming) {
      media_client = incoming;
      media_client.setNoDelay(true);
      if (ble_connected && stream_requested && i2s_start(I2S_NUM_0) == ESP_OK)
        stream_enabled = true;
      send_status("WIFI_MEDIA_CONNECTED_VGA_QUALITY10");
    }
  }
  // Restore the proven CPU clock only on real queue/frame loss. A short I2S
  // read at STOP is expected when disconnecting and must not upclock the board.
  static bool clock_fallback = false;
  if (stream_enabled && !clock_fallback && getCpuFrequencyMhz() < 240 &&
      (dropped_audio_blocks || camera_frames_null || camera_frames_dropped)) {
    setCpuFrequencyMhz(240);
    clock_fallback = true;
    send_status("CPU_FALLBACK_240_DATA_LOSS");
  }
  ++wakeups;
  CameraReply reply{};
  while (xQueueReceive(reply_queue, &reply, 0) == pdTRUE) send_status(reply.text);
  static uint32_t last_status_ms = 0;
  if (millis() - last_status_ms >= 2000) {
    last_status_ms = millis();
    char status[160];
    snprintf(status, sizeof(status), "R c%lu/%lu/%lu a%lu/%lu b%lu drop%lu cpu%u heap%u wake%lu tempC%.1f bleDrop%lu vtx%lu/%lu q%u",
             static_cast<unsigned long>(camera_frames_ok),
             static_cast<unsigned long>(camera_frames_null),
             static_cast<unsigned long>(camera_frames_dropped),
             static_cast<unsigned long>(microphone_reads_ok),
             static_cast<unsigned long>(microphone_reads_short),
             static_cast<unsigned long>(microphone_last_bytes),
             static_cast<unsigned long>(dropped_audio_blocks), getCpuFrequencyMhz(),
             heap_caps_get_free_size(MALLOC_CAP_INTERNAL), static_cast<unsigned long>(wakeups),
             static_cast<double>(temperatureRead()), static_cast<unsigned long>(ble_tx_dropped),
             static_cast<unsigned long>(ble_video_tx_packets), static_cast<unsigned long>(ble_video_tx_bytes),
             static_cast<unsigned>(uxQueueMessagesWaiting(camera_queue)));
    wakeups = 0;
    send_status(status);
  }
  AudioBlock block{};
  if (xQueueReceive(audio_queue, &block, pdMS_TO_TICKS(20)) == pdTRUE) {
    uint8_t compressed[AUDIO_SAMPLES / 2];
    for (size_t i = 0; i < AUDIO_SAMPLES / 2; ++i)
      compressed[i] = linear_to_mulaw(block.samples[i * 2]);
    send_packet(PACKET_AUDIO_MULAW8, block.sequence, block.timestamp_us,
                compressed, sizeof(compressed));
  }

  camera_fb_t *frame = nullptr;
  if (xQueueReceive(camera_queue, &frame, 0) == pdTRUE && frame != nullptr) {
    send_packet(PACKET_VIDEO_JPEG, video_sequence++,
                static_cast<uint64_t>(esp_timer_get_time()), frame->buf,
                static_cast<uint32_t>(frame->len));
    esp_camera_fb_return(frame);
  }
}
