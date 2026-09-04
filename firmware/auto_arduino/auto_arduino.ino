/*
 * =====================================================================
 * Proyecto: Auto Robot 4WD a Control Remoto por Giroscopio y Gestos
 * Grupo 1 - Integrantes: Onesti, Silvero, Mendieta
 * Materia: Programación 7° Año - Prof. Figueroa / Prof. Piris
 * Ciclo: 2026 | Proyecto Institucional: GEdeCon
 * =====================================================================
 * Hardware:
 *  - Placa Arduino Uno R3 / Nano
 *  - Chasis 4WD (4 motores DC tipo TT con tracción en las 4 ruedas)
 *  - Driver de potencia Puente H L298N:
 *      * OUT1 y OUT2: 2 motores lado izquierdo en paralelo
 *      * OUT3 y OUT4: 2 motores lado derecho en paralelo
 *  - Módulo Bluetooth HC-05 (o HC-06)
 *  - Baterías Li-ion 18650 (7.4V - 8.4V)
 * =====================================================================
 */

// Definición de Pines PWM y Dirección para L298N
const int PIN_ENA = 5;  // PWM Velocidad Motores Izquierdos (Delantero + Trasero)
const int PIN_IN1 = 6;  // Sentido A Motores Izquierdos
const int PIN_IN2 = 7;  // Sentido B Motores Izquierdos
const int PIN_IN3 = 8;  // Sentido A Motores Derechos
const int PIN_IN4 = 9;  // Sentido B Motores Derechos
const int PIN_ENB = 10; // PWM Velocidad Motores Derechos (Delantero + Trasero)

// Umbral de zona muerta para evitar temblores involuntarios
const int UMBRAL_ANGULO = 8;

void setup() {
  // Comunicación serial con módulo Bluetooth HC-05 (9600 baudios por defecto)
  Serial.begin(9600);

  // Configurar pines de control del puente H como salidas
  pinMode(PIN_ENA, OUTPUT);
  pinMode(PIN_IN1, OUTPUT);
  pinMode(PIN_IN2, OUTPUT);
  pinMode(PIN_IN3, OUTPUT);
  pinMode(PIN_IN4, OUTPUT);
  pinMode(PIN_ENB, OUTPUT);

  // Estado inicial seguro: motores apagados
  detenerMotores();
}

void loop() {
  if (Serial.available() > 0) {
    String linea = Serial.readStringUntil('\n');
    linea.trim();

    if (linea == "STOP") {
      detenerMotores();
    } else if (linea.startsWith("M:CAR")) {
      // Formato esperado enviado por la app: "M:CAR,P:<pitch>,R:<roll>"
      interpretarComando4WD(linea);
    }
  }
}

/**
 * Procesa los ángulos recibidos por el sensor del teléfono:
 * - Pitch (Beta): Inclinación adelante/atrás -> Acelerar o Retroceder
 * - Roll (Gamma): Inclinación lateral -> Viraje diferencial de 4 ruedas
 */
void interpretarComando4WD(String cmd) {
  int idxP = cmd.indexOf("P:");
  int idxR = cmd.indexOf(",R:");

  if (idxP != -1 && idxR != -1) {
    int pitch = cmd.substring(idxP + 2, idxR).toInt();
    int roll = cmd.substring(idxR + 3).toInt();

    // 1. Control de marcha principal (Adelante / Reversa)
    if (pitch > UMBRAL_ANGULO) {
      // Inclinación hacia adelante: avance proporcional
      int baseSpeed = map(pitch, UMBRAL_ANGULO, 45, 130, 255);
      controlDiferencial4WD(baseSpeed, roll, true);
    } else if (pitch < -UMBRAL_ANGULO) {
      // Inclinación hacia atrás: retroceso proporcional
      int baseSpeed = map(abs(pitch), UMBRAL_ANGULO, 45, 130, 255);
      controlDiferencial4WD(baseSpeed, roll, false);
    } else if (abs(roll) > UMBRAL_ANGULO) {
      // Sin inclinación frontal pero con inclinación lateral: GIRO EN EL LUGAR (Skid-steer)
      giroSobreSuEje(roll);
    } else {
      // En reposo dentro de la zona muerta
      detenerMotores();
    }
  }
}

/**
 * Tracción diferencial para chasis 4x4 (Skid-steering)
 * Ajusta la velocidad entre el lado izquierdo y derecho según el ángulo Roll
 */
void controlDiferencial4WD(int baseSpeed, int roll, bool haciaAdelante) {
  int velIzq = baseSpeed;
  int velDer = baseSpeed;

  int correccionGiro = map(abs(roll), 0, 45, 0, 110);

  if (roll > UMBRAL_ANGULO) {
    // Viraje hacia la DERECHA: se reduce la potencia del lado derecho
    velDer = max(0, velDer - correccionGiro);
    velIzq = min(255, velIzq + (correccionGiro / 2));
  } else if (roll < -UMBRAL_ANGULO) {
    // Viraje hacia la IZQUIERDA: se reduce la potencia del lado izquierdo
    velIzq = max(0, velIzq - correccionGiro);
    velDer = min(255, velDer + (correccionGiro / 2));
  }

  // Aplicar modulación PWM a cada par de ruedas
  analogWrite(PIN_ENA, constrain(velIzq, 0, 255));
  analogWrite(PIN_ENB, constrain(velDer, 0, 255));

  // Configurar sentido de giro de los motores
  if (haciaAdelante) {
    digitalWrite(PIN_IN1, HIGH);
    digitalWrite(PIN_IN2, LOW);
    digitalWrite(PIN_IN3, HIGH);
    digitalWrite(PIN_IN4, LOW);
  } else {
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, HIGH);
    digitalWrite(PIN_IN3, LOW);
    digitalWrite(PIN_IN4, HIGH);
  }
}

/**
 * Giro cerrado en el propio eje del auto (tipo tanque) cuando el teléfono se inclina solo a los costados
 */
void giroSobreSuEje(int roll) {
  int velGiro = map(abs(roll), UMBRAL_ANGULO, 45, 140, 240);
  velGiro = constrain(velGiro, 0, 255);

  analogWrite(PIN_ENA, velGiro);
  analogWrite(PIN_ENB, velGiro);

  if (roll > 0) {
    // Giro horario sobre su eje (Ruedas izquierdas adelante, ruedas derechas atrás)
    digitalWrite(PIN_IN1, HIGH);
    digitalWrite(PIN_IN2, LOW);
    digitalWrite(PIN_IN3, LOW);
    digitalWrite(PIN_IN4, HIGH);
  } else {
    // Giro antihorario sobre su eje (Ruedas izquierdas atrás, ruedas derechas adelante)
    digitalWrite(PIN_IN1, LOW);
    digitalWrite(PIN_IN2, HIGH);
    digitalWrite(PIN_IN3, HIGH);
    digitalWrite(PIN_IN4, LOW);
  }
}

/**
 * Desactiva la alimentación a todos los motores de inmediato
 */
void detenerMotores() {
  analogWrite(PIN_ENA, 0);
  analogWrite(PIN_ENB, 0);
  digitalWrite(PIN_IN1, LOW);
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}
