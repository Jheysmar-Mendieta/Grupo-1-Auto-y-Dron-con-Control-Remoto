# Componentes Electrónicos y Asignación de Pines - Grupo 1

## Lista de Componentes Principales
1. **Placa de Control:** Arduino Uno / Arduino Nano / ESP32.
2. **Controlador de Motores:** Puente H L298N o DRV8833.
3. **Módulo de Comunicación:** Módulo Bluetooth HC-05 (o antena Wi-Fi integrada si es ESP32).
4. **Alimentación:** Pack de baterías Li-ion (7.4V o 11.1V) con regulador de voltaje.
5. **Motores:** 2 o 4 motorreductores DC tipo TT con ruedas de goma.

## Tabla de Conexión de Pines (Arduino - L298N)

| Pin Arduino | Conexión L298N | Función |
| :--- | :--- | :--- |
| **Pin D5 (PWM)** | ENA | Habilitación y velocidad motor izquierdo |
| **Pin D6** | IN1 | Dirección A motor izquierdo |
| **Pin D7** | IN2 | Dirección B motor izquierdo |
| **Pin D8** | IN3 | Dirección A motor derecho |
| **Pin D9** | IN4 | Dirección B motor derecho |
| **Pin D10 (PWM)**| ENB | Habilitación y velocidad motor derecho |
| **Pin D0 (RX)**  | TX del HC-05 | Recepción de comandos Bluetooth |
| **Pin D1 (TX)**  | RX del HC-05 | Transmisión hacia Bluetooth (con divisor resistivo 5V a 3.3V) |
| **GND**          | GND común | Masa compartida con batería y drivers |
