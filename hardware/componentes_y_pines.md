# Componentes Electrónicos y Asignación de Pines - Auto Robot 4WD (Grupo 1)

## 1. Configuración de Tracción 4x4 (4WD)
El auto utiliza tracción total con 4 motorreductores DC tipo TT. Dado que el puente H L298N cuenta con dos canales independientes (Canal A y Canal B):
* **Canal A (OUT1 y OUT2):** Conecta en paralelo los dos motores del lado **izquierdo** (delantero izquierdo + trasero izquierdo).
* **Canal B (OUT3 y OUT4):** Conecta en paralelo los dos motores del lado **derecho** (delantero derecho + trasero derecho).

Este esquema permite tracción diferencial (tipo tanque / oruga), logrando giros en el lugar con alta estabilidad y excelente torque sobre cualquier superficie.

---

## 2. Tabla de Conexión de Pines (Arduino Uno - L298N)

| Pin Arduino | Conexión L298N | Función |
| :--- | :--- | :--- |
| **Pin D5 (PWM)** | ENA | Habilitación y velocidad PWM motores izquierdos |
| **Pin D6** | IN1 | Dirección A motores izquierdos |
| **Pin D7** | IN2 | Dirección B motores izquierdos |
| **Pin D8** | IN3 | Dirección A motores derechos |
| **Pin D9** | IN4 | Dirección B motores derechos |
| **Pin D10 (PWM)**| ENB | Habilitación y velocidad PWM motores derechos |
| **Pin D0 (RX)**  | TX del módulo HC-05 | Recepción de comandos Bluetooth |
| **Pin D1 (TX)**  | Divisor resistivo hacia RX del HC-05 | Transmisión segura a 3.3V hacia el Bluetooth |
| **GND**          | Bornera GND del L298N y polo negativo batería | Masa común compartida de todo el circuito |

---

## 3. Bornera de Alimentación del Puente H L298N

* **Bornera 12V / VMS:** Conectar al polo positivo (+) del pack de baterías (2x 18650 = 7.4V a 8.4V) pasando por el interruptor On/Off.
* **Bornera GND:** Conectar al polo negativo (-) del pack de baterías y a un pin **GND** del Arduino.
* **Bornera 5V:** Si el jumper regulador de 5V del L298N está puesto, este pin entrega 5V regulados estables para alimentar el pin **Vin** o **5V** del Arduino Uno y el módulo Bluetooth, evitando usar reguladores adicionales.
