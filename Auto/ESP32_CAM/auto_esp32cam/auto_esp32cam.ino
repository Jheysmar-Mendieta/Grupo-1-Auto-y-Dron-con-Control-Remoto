/**
 * ============================================================================
 * AUTO ROBOT: ESP32-CAM (PUENTE WI-FI ULTRA BAJA LATENCIA + VIDEO MJPEG) - GRUPO 1
 * Placa en Arduino IDE: "AI Thinker ESP32-CAM"
 *
 * Funciones:
 *   1. Genera la red Wi-Fi "AutoRobot_Grupo1" (Clave: 12345678, IP: 192.168.4.1)
 *   2. Transmite video FPV fluido (QVGA 25 FPS) en el puerto 81 (/stream) sin saturar el Wi-Fi
 *   3. Recibe órdenes de la App (/action?go=X y /action?servo=X) en puerto 80 con prioridad máxima
 *      y las retransmite al instante al Arduino Uno por cable Serial (U0T/U0R a 9600 baud)
 *   4. Recibe la telemetría del sensor ultrasónico y servo desde el Arduino ("D:100.0,S:90,O:0")
 *      sin bloquear el procesador y la expone en /status para el HUD de la App
 * ============================================================================
 */

#include <Arduino.h>
#include <WiFi.h>
#include "esp_camera.h"
#include "esp_http_server.h"
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// 1. Configuración de Red Wi-Fi
const char* AP_SSID  = "AutoRobot_Grupo1";
const char* AP_CLAVE = "12345678";

// 2. Variables de estado sincronizadas con el Arduino Uno
volatile float distanciaActualCm = 100.0f;
volatile bool bloqueoObstaculo = false;
volatile int anguloServoActual = 45;
volatile char marchaActual = 'S';
volatile unsigned long tUltimoComandoMs = 0;

httpd_handle_t servidorControl = nullptr; // Puerto 80 (Mandos + Telemetría)
httpd_handle_t servidorVideo   = nullptr; // Puerto 81 (Streaming MJPEG)

// Buffer no bloqueante para lectura Serial desde el Arduino Uno
char serialLineBuf[64];
uint8_t serialLineLen = 0;

// 3. Pines de la Cámara OV2640 (AI-Thinker)
#define CAM_PIN_PWDN    32
#define CAM_PIN_RESET   -1
#define CAM_PIN_XCLK     0
#define CAM_PIN_SIOD    26
#define CAM_PIN_SIOC    27
#define CAM_PIN_D7      35
#define CAM_PIN_D6      34
#define CAM_PIN_D5      39
#define CAM_PIN_D4      36
#define CAM_PIN_D3      21
#define CAM_PIN_D2      19
#define CAM_PIN_D1      18
#define CAM_PIN_D0       5
#define CAM_PIN_VSYNC   25
#define CAM_PIN_HREF    23
#define CAM_PIN_PCLK    22

#define PART_BOUNDARY "123456789000000000000987654321"
static const char* _STREAM_CONTENT_TYPE = "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* _STREAM_BOUNDARY = "\r\n--" PART_BOUNDARY "\r\n";
static const char* _STREAM_PART = "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

// ============================================================================
// STREAMING DE VIDEO MJPEG (PUERTO 81) CON PRIORIDAD A MANDOS DE MOTOR
// ============================================================================
esp_err_t atenderStreamVideo(httpd_req_t *req) {
  camera_fb_t *fb = nullptr;
  char part_buf[64];

  httpd_resp_set_type(req, _STREAM_CONTENT_TYPE);
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");

  while (true) {
    // Si el usuario acaba de tocar un botón, ceder la antena Wi-Fi para respuesta instantánea
    if (millis() - tUltimoComandoMs < 120) {
      vTaskDelay(pdMS_TO_TICKS(45));
    }

    fb = esp_camera_fb_get();
    if (!fb) {
      vTaskDelay(pdMS_TO_TICKS(15));
      continue;
    }

    size_t hlen = snprintf(part_buf, sizeof(part_buf), _STREAM_PART, fb->len);
    esp_err_t res = httpd_resp_send_chunk(req, _STREAM_BOUNDARY, strlen(_STREAM_BOUNDARY));
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, part_buf, hlen);
    if (res == ESP_OK) res = httpd_resp_send_chunk(req, (const char *)fb->buf, fb->len);

    esp_camera_fb_return(fb);
    if (res != ESP_OK) {
      break;
    }

    // Control de flujo (~25 FPS) para mantener libre la cola Wi-Fi del puerto 80
    vTaskDelay(pdMS_TO_TICKS(35));
  }
  return ESP_OK;
}

// ============================================================================
// SERVIDOR WEB DE CONTROL (PUERTO 80 - ALTA PRIORIDAD)
// ============================================================================
esp_err_t atenderStatus(httpd_req_t *req) {
  char json[128];
  int len = snprintf(json, sizeof(json),
                     "{\"distance\":%.1f,\"obstacle\":%s,\"servo\":%d,\"mode\":\"%c\",\"fw\":2}",
                     distanciaActualCm,
                     bloqueoObstaculo ? "true" : "false",
                     anguloServoActual,
                     marchaActual);

  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
  httpd_resp_set_hdr(req, "Connection", "close");
  return httpd_resp_send(req, json, len);
}

void enviarAnguloServoAlArduino(int deg) {
  if (deg < 15) deg = 15;
  if (deg > 75) deg = 75;
  anguloServoActual = deg;
  Serial.print('V');
  Serial.println(deg);
}

esp_err_t atenderAction(httpd_req_t *req) {
  char query[64];
  tUltimoComandoMs = millis();

  // Manejo de error si la petición HTTP viene vacía o mal formada
  if (httpd_req_get_url_query_str(req, query, sizeof(query)) != ESP_OK) {
    marchaActual = 'S';
    Serial.write('S');
    httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Parametro query faltante o invalido");
    return ESP_FAIL;
  }

  char val[16];
  bool comandoValido = false;

  // 1. Mando de ruedas (F, B, L, R, S) o pasos rápidos de servo (U, D, C)
  if (httpd_query_key_value(query, "go", val, sizeof(val)) == ESP_OK) {
    char cmd = toupper(val[0]);
    if (cmd == 'U') {
      enviarAnguloServoAlArduino(anguloServoActual + 10);
      comandoValido = true;
    } else if (cmd == 'D') {
      enviarAnguloServoAlArduino(anguloServoActual - 10);
      comandoValido = true;
    } else if (cmd == 'C') {
      enviarAnguloServoAlArduino(45);
      comandoValido = true;
    } else if (cmd == 'F' || cmd == 'B' || cmd == 'L' || cmd == 'R' || cmd == 'S') {
      marchaActual = cmd;
      Serial.write(cmd); // Ráfaga doble inmediata al Arduino Uno (evita pérdida de byte en SoftwareSerial)
      Serial.write(cmd);
      comandoValido = true;
    } else {
      // Manejo de error: comando desconocido -> frenar por seguridad
      marchaActual = 'S';
      Serial.write('S');
    }
  }

  // 2. Mando directo de ángulo de servo: /action?servo=45
  if (httpd_query_key_value(query, "servo", val, sizeof(val)) == ESP_OK) {
    enviarAnguloServoAlArduino(atoi(val));
    comandoValido = true;
  }

  // 3. Mando relativo o absoluto en /action?tilt=X
  if (httpd_query_key_value(query, "tilt", val, sizeof(val)) == ESP_OK) {
    int v = atoi(val);
    if (v >= 15 && v <= 75) {
      enviarAnguloServoAlArduino(v);
    } else {
      enviarAnguloServoAlArduino(anguloServoActual + v);
    }
    comandoValido = true;
  }

  if (!comandoValido) {
    httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Comando no reconocido");
    return ESP_FAIL;
  }

  return atenderStatus(req);
}

void levantarServidoresHttp() {
  httpd_config_t cfgCtrl = HTTPD_DEFAULT_CONFIG();
  cfgCtrl.server_port      = 80;
  cfgCtrl.ctrl_port        = 32768;
  cfgCtrl.task_priority    = tskIDLE_PRIORITY + 6; // Prioridad superior para mandos
  cfgCtrl.lru_purge_enable = true;
  cfgCtrl.max_open_sockets = 7;

  httpd_uri_t uriStatus = { "/status", HTTP_GET, atenderStatus, nullptr };
  httpd_uri_t uriAction = { "/action", HTTP_GET, atenderAction, nullptr };

  if (httpd_start(&servidorControl, &cfgCtrl) == ESP_OK) {
    httpd_register_uri_handler(servidorControl, &uriStatus);
    httpd_register_uri_handler(servidorControl, &uriAction);
  }

  httpd_config_t cfgVideo = HTTPD_DEFAULT_CONFIG();
  cfgVideo.server_port      = 81;
  cfgVideo.ctrl_port        = 32769;
  cfgVideo.task_priority    = tskIDLE_PRIORITY + 3; // Prioridad menor para video
  cfgVideo.lru_purge_enable = true;
  cfgVideo.max_open_sockets = 3;

  httpd_uri_t uriStream = { "/stream", HTTP_GET, atenderStreamVideo, nullptr };

  if (httpd_start(&servidorVideo, &cfgVideo) == ESP_OK) {
    httpd_register_uri_handler(servidorVideo, &uriStream);
  }
}

bool iniciarCamaraOv2640() {
  camera_config_t camCfg;
  camCfg.ledc_channel = LEDC_CHANNEL_0;
  camCfg.ledc_timer   = LEDC_TIMER_0;
  camCfg.pin_d0       = CAM_PIN_D0;
  camCfg.pin_d1       = CAM_PIN_D1;
  camCfg.pin_d2       = CAM_PIN_D2;
  camCfg.pin_d3       = CAM_PIN_D3;
  camCfg.pin_d4       = CAM_PIN_D4;
  camCfg.pin_d5       = CAM_PIN_D5;
  camCfg.pin_d6       = CAM_PIN_D6;
  camCfg.pin_d7       = CAM_PIN_D7;
  camCfg.pin_xclk     = CAM_PIN_XCLK;
  camCfg.pin_pclk     = CAM_PIN_PCLK;
  camCfg.pin_vsync    = CAM_PIN_VSYNC;
  camCfg.pin_href     = CAM_PIN_HREF;
  camCfg.pin_sccb_sda = CAM_PIN_SIOD;
  camCfg.pin_sccb_scl = CAM_PIN_SIOC;
  camCfg.pin_pwdn     = CAM_PIN_PWDN;
  camCfg.pin_reset    = CAM_PIN_RESET;
  camCfg.xclk_freq_hz = 20000000;
  camCfg.pixel_format = PIXFORMAT_JPEG;

  // QVGA (320x240): fotogramas ultralivianos (~3.5 KB) para transmisión fluida sin lag en los controles
  if (psramFound()) {
    camCfg.frame_size   = FRAMESIZE_QVGA;
    camCfg.jpeg_quality = 15;
    camCfg.fb_count     = 2;
    camCfg.grab_mode    = CAMERA_GRAB_LATEST;
  } else {
    camCfg.frame_size   = FRAMESIZE_QVGA;
    camCfg.jpeg_quality = 16;
    camCfg.fb_count     = 1;
    camCfg.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;
  }

  return (esp_camera_init(&camCfg) == ESP_OK);
}

void procesarLineaSerialArduino(const char* linea) {
  // Formato recibido desde el Arduino Uno: "D:45.2,S:90,O:0"
  if (linea[0] != 'D' || linea[1] != ':') return;

  float d = atof(linea + 2);
  if (d >= 2.0f && d <= 400.0f) {
    distanciaActualCm = d;
  }

  const char* ptrS = strstr(linea, ",S:");
  if (ptrS) {
    int s = atoi(ptrS + 3);
    if (s >= 15 && s <= 75) {
      anguloServoActual = s;
    }
  }

  const char* ptrO = strstr(linea, ",O:");
  if (ptrO) {
    bloqueoObstaculo = (atoi(ptrO + 3) == 1);
  }
}

void setup() {
  WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

  // Inicializar UART0 a 9600 baudios para comunicarse con el Arduino Uno
  // U0T (GPIO 1) -> Pin 2 del Arduino Uno (RX)
  // U0R (GPIO 3) <- Pin 3 del Arduino Uno (TX)
  Serial.begin(9600);

  WiFi.mode(WIFI_AP);
  WiFi.softAP(AP_SSID, AP_CLAVE);
  WiFi.setTxPower(WIFI_POWER_15dBm);
  WiFi.setSleep(false);

  iniciarCamaraOv2640();
  levantarServidoresHttp();
}

void loop() {
  // 1. Fail-Safe (Watchdog por pérdida de conexión Wi-Fi):
  //    Si el auto estaba en movimiento y pasan más de 700 ms sin recibir paquetes de la App, frenar
  if (marchaActual != 'S' && (millis() - tUltimoComandoMs > 700)) {
    marchaActual = 'S';
    Serial.write('S');
    Serial.write('S');
  }

  // 2. Lectura Serial carácter por carácter (100% no bloqueante) con manejo de desbordamiento
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (serialLineLen > 0) {
        serialLineBuf[serialLineLen] = '\0';
        procesarLineaSerialArduino(serialLineBuf);
        serialLineLen = 0;
      }
    } else if (serialLineLen < sizeof(serialLineBuf) - 1) {
      serialLineBuf[serialLineLen++] = c;
    } else {
      // Error de trama demasiado larga (ruido serial): limpiar buffer
      serialLineLen = 0;
    }
  }
  vTaskDelay(pdMS_TO_TICKS(10));
}
