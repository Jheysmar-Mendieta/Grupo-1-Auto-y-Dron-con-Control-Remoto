/*
 * =====================================================================
 * Proyecto: Auto a Control Remoto por Giroscopio y Gestos
 * Grupo 1 - Integrantes: Onesti, Silvero, Mendieta
 * Materia: Programación 7° Año - Prof. Figueroa / Prof. Piris
 * Ciclo: 2026 | Proyecto Institucional: GEdeCon
 * =====================================================================
 * Hardware:
 *  - Arduino Uno / Nano / ESP32
 *  - Módulo Bluetooth HC-05 (o Serial UART)
 *  - Driver de motores Puente H L298N
 * =====================================================================
 */

// Definición de Pines para Puente H L298N
const int PIN_ENA = 5;  // Control de velocidad Motor Izquierdo (PWM)
const int PIN_IN1 = 6;  // Dirección Motor Izquierdo A
const int PIN_IN2 = 7;  // Dirección Motor Izquierdo B
const int PIN_IN3 = 8;  // Dirección Motor Derecho A
const int PIN_IN4 = 9;  // Dirección Motor Derecho B
const int PIN_ENB = 10; // Control de velocidad Motor Derecho (PWM)

// Umbral mínimo de ángulo para activar movimiento
const int UMBRAL_ANGULO = 10;

void setup() {
  // Inicialización de comunicación serial (Bluetooth HC-05 a 9600 baudios)
  Serial.begin(9600);

  // Configuración de pines de motor como salidas
  pinMode(PIN_ENA, OUTPUT);
  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_IN3, OUTPUT);
  pinMode(PIN_IN4, OUTPUT);
  pinMode(PIN_ENB, OUTPUT);

  // Motores detenidos al iniciar por seguridad
  detenerMotores();
}

void loop() {
  // Verificar si llegan datos por el puerto serial
  if (Serial.available() > 0) {
    String linea = Serial.readStringUntil('\n');
    linea.trim();

    if (linea == "STOP") {
      detenerMotores();
    } else if (linea.startsWith("M:CAR")) {
      // Ejemplo de formato esperado: "M:CAR,P:20,R:-15"
      interpretarComandoAuto(linea);
    }
  }
}

/**
 * Parsea e interpreta los ángulos de Pitch (avance/retroceso) y Roll (giro)
 */
void interpretarComandoAuto(String cmd) {
  int idxP = cmd.indexOf("P:");
  int idxR = cmd.indexOf(",R:");

  if (idxP != -1 && idxR != -1) {
    int pitch = cmd.substring(idxP + 2, idxR).toInt();
    int roll = cmd.substring(idxR + 3).toInt();

    // Mapear Pitch a velocidad adelante / atrás
    if (pitch > UMBRAL_ANGULO) {
      int vel = map(pitch, UMBRAL_ANGULO, 45, 120, 255);
      avanzar(vel, roll);
    } else if (pitch < -UMBRAL_ANGULO) {
      int vel = map(abs(pitch), UMBRAL_ANGULO, 45, 120, 255);
      retroceder(vel, roll);
    } else {
      detenerMotores();
    }
  }
}

void avanzar(int velocidad, int giro) {
  int velIzq = velocidad;
  int velDer = velocidad;

  // Ajuste diferencial por giro de volante (Roll)
  if (giro > UMBRAL_ANGULO) { // Giro a la derecha
    velDer = max(0, velDer - abs(giro) * 3);
  } else if (giro < -UMBRAL_ANGULO) { // Giro a la izquierda
    velIzq = max(0, velIzq - abs(giro) * 3);
  }

  analogWrite(PIN_ENA, constrain(velIzq, 0, 255));
  analogWrite(PIN_ENB, constrain(velDer, 0, 255));

  digitalWrite(PIN_IN1, HIGH);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, HIGH);
  digitalWrite(PIN_IN4, LOW);
}

void retroceder(int velocidad, int giro) {
  analogWrite(PIN_ENA, constrain(velocidad, 0, 255));
  analogWrite(PIN_ENB, constrain(velocidad, 0, 255));

  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, HIGH);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, HIGH);
}

void detenerMotores() {
  analogWrite(PIN_ENA, 0);
  analogWrite(PIN_ENB, 0);
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}
