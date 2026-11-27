#include <SoftwareSerial.h>
#include <Servo.h>

/**
 * ============================================================================
 * AUTO ROBOT: ARDUINO UNO - FIRMWARE CERO PÉRDIDA DE BOTONES (GRUPO 1)
 * ============================================================================
 * Hardware:
 *   - L298N Motores: IN1=9, IN2=8, IN3=11, IN4=10
 *   - ESP32-CAM Comms: Pin 2 (RX) <- U0T, Pin 3 (TX) -> U0R
 *   - HC-SR04 Ultrasonico: Trig=5, Echo=6
 *   - SG90 Servo Camara: Pin 7 (Centrado en 45°, Rango 15°..75°, Rampa suave + Auto-Detach)
 * ============================================================================
 */

SoftwareSerial EspSerial(2, 3);
Servo servoCamara;

// 1. Pines L298N
const int IN1 = 9;
const int IN2 = 8;
const int IN3 = 11;
const int IN4 = 10;

// 2. Pines Sensor Ultrasonico
const int PIN_TRIG = 5;
const int PIN_ECHO = 6;
const float UMBRAL_FRENO_CM = 12.0;

// 3. Pin Servo con Rampa Suave (evita picos de corriente que apagan la placa)
const int PIN_SERVO = 7;
int anguloServoActual = 45;
int anguloServoObjetivo = 45;
bool servoActivo = false;
unsigned long tUltimoPasoServo = 0;
unsigned long tServoLlegada = 0;

// Variables de estado
char marchaActual = 'S';
float distanciaFiltrada = 100.0;
bool obstaculoDetectado = false;
uint8_t conteoObstaculoConsecutivo = 0;
unsigned long tUltimaMedicion = 0;
unsigned long tUltimoEnvio = 0;
unsigned long tUltimoComandoMs = 0;

void detener() {
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
  digitalWrite(LED_BUILTIN, LOW);
  marchaActual = 'S';
}

void avanzar() {
  if (obstaculoDetectado) {
    detener();
    return;
  }
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
  digitalWrite(LED_BUILTIN, HIGH);
  marchaActual = 'F';
}

void retroceder() {
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, HIGH);
  digitalWrite(IN4, LOW);
  digitalWrite(LED_BUILTIN, HIGH);
  marchaActual = 'B';
}

void girarIzquierda() {
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, HIGH);
  digitalWrite(IN4, LOW);
  digitalWrite(LED_BUILTIN, HIGH);
  marchaActual = 'L';
}

void girarDerecha() {
  digitalWrite(IN1, HIGH);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
  digitalWrite(LED_BUILTIN, HIGH);
  marchaActual = 'R';
}

// Programa el ángulo objetivo con movimiento suave (2 grados cada 18 ms) para NO tumbar los 5V
void moverServo(int angulo) {
  anguloServoObjetivo = constrain(angulo, 15, 75);
  if (!servoActivo) {
    servoCamara.attach(PIN_SERVO);
    servoActivo = true;
  }
  tServoLlegada = millis();
}

void actualizarServo() {
  if (!servoActivo) return;

  unsigned long ahora = millis();
  if (anguloServoActual != anguloServoObjetivo) {
    if (ahora - tUltimoPasoServo >= 18) {
      tUltimoPasoServo = ahora;
      if (anguloServoActual < anguloServoObjetivo) {
        anguloServoActual += min(2, anguloServoObjetivo - anguloServoActual);
      } else {
        anguloServoActual -= min(2, anguloServoActual - anguloServoObjetivo);
      }
      servoCamara.write(anguloServoActual);
      tServoLlegada = ahora;
    }
  } else if (ahora - tServoLlegada > 250) {
    // Una vez alcanzado el ángulo, apagar PWM para silencio total y 0 mA de consumo
    servoCamara.detach();
    servoActivo = false;
  }
}

float medirSonar() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);

  // Timeout corto de 6500 us (~1.1 metros) para no bloquear nunca la recepción de botones
  unsigned long duracion = pulseIn(PIN_ECHO, HIGH, 6500UL);
  if (duracion == 0) return 100.0;

  float d = (duracion * 0.0343f) * 0.5f;
  if (d < 4.5f || d > 250.0f) return 100.0;
  return d;
}

void actualizarObstaculo() {
  // No bloquear con pulseIn si acaban de tocar un botón o el servo se está moviendo
  if (EspSerial.available() || servoActivo || (millis() - tUltimoComandoMs < 250)) return;

  float dInstantanea = medirSonar();
  distanciaFiltrada = (distanciaFiltrada * 0.5f) + (dInstantanea * 0.5f);

  if (dInstantanea <= UMBRAL_FRENO_CM && distanciaFiltrada <= UMBRAL_FRENO_CM) {
    if (conteoObstaculoConsecutivo < 10) conteoObstaculoConsecutivo++;
    if (conteoObstaculoConsecutivo >= 2) {
      obstaculoDetectado = true;
      if (marchaActual == 'F') {
        detener();
      }
    }
  } else if (dInstantanea > (UMBRAL_FRENO_CM + 3.0)) {
    conteoObstaculoConsecutivo = 0;
    obstaculoDetectado = false;
  }
}

void procesarCaracter(char c, Stream &fuente) {
  if (millis() < 2000) return;

  if (c == 'V') {
    int ang = fuente.parseInt();
    if (ang >= 15 && ang <= 160) {
      tUltimoComandoMs = millis();
      moverServo(ang);
    }
    return;
  }

  switch (c) {
    case 'F': tUltimoComandoMs = millis(); avanzar();        break;
    case 'B': tUltimoComandoMs = millis(); retroceder();      break;
    case 'L': tUltimoComandoMs = millis(); girarIzquierda();  break;
    case 'R': tUltimoComandoMs = millis(); girarDerecha();    break;
    case 'S': tUltimoComandoMs = millis(); detener();         break;
    default:  break;
  }
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);

  pinMode(2, INPUT_PULLUP);
  Serial.begin(9600);
  Serial.setTimeout(25);

  EspSerial.begin(9600);
  EspSerial.setTimeout(25);

  // Motores
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);
  detener();

  // Sensor Ultrasonico
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);
  digitalWrite(PIN_TRIG, LOW);

  // Posicionar servo inicialmente en 45 grados y apagarlo
  servoCamara.attach(PIN_SERVO);
  servoCamara.write(45);
  delay(250);
  servoCamara.detach();
}

void loop() {
  // 1. Prioridad absoluta: leer comandos desde ESP32-CAM (Pin 2)
  while (EspSerial.available()) {
    char c = EspSerial.read();
    procesarCaracter(c, EspSerial);
  }

  // 2. Comandos de respaldo desde USB
  while (Serial.available()) {
    char c = Serial.read();
    procesarCaracter(c, Serial);
  }

  // 3. Mover servo suavemente (si hay movimiento pendiente) y apagar al llegar
  actualizarServo();

  unsigned long t = millis();

  // 4. Medicion rapida de distancia cada 300 ms
  if (t - tUltimaMedicion >= 300) {
    tUltimaMedicion = t;
    actualizarObstaculo();
  }

  // 5. Envio de telemetria a ESP32-CAM cada 900 ms SOLO cuando no se estan enviando mandos
  //    (Evita que SoftwareSerial TX bloquee la recepcion RX cuando tocas botones)
  if (!EspSerial.available() && marchaActual == 'S' && (t - tUltimoComandoMs > 350) && (t - tUltimoEnvio >= 900)) {
    tUltimoEnvio = t;
    EspSerial.print(F("D:"));
    EspSerial.print(distanciaFiltrada, 1);
    EspSerial.print(F(",S:"));
    EspSerial.print(anguloServoActual);
    EspSerial.print(F(",O:"));
    EspSerial.println(obstaculoDetectado ? 1 : 0);
  }
}
