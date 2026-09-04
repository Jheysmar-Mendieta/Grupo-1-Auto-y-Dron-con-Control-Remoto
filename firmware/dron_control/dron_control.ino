/*
 * =====================================================================
 * Proyecto: Control de Dron por Giroscopio Móvil
 * Grupo 1 - Integrantes: Onesti, Silvero, Mendieta
 * Materia: Programación 7° Año - Prof. Figueroa / Prof. Piris
 * Ciclo: 2026 | Proyecto Institucional: GEdeCon
 * =====================================================================
 */

void setup() {
  Serial.begin(9600);
  // Inicialización de servos / ESCs / control de vuelo
}

void loop() {
  if (Serial.available() > 0) {
    String cmd = Serial.readStringUntil('\n');
    cmd.trim();
    if (cmd.startsWith("M:DRONE")) {
      // Decodificación de Throttle, Pitch, Roll y Yaw para el dron
    }
  }
}
