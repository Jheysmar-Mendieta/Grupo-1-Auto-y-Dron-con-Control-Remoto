# Componentes Electrónicos y Asignación de Pines - Auto Robot 4WD (Grupo 1)

## 1. Configuración de Tracción 4x4 (4WD)
El auto utiliza tracción total con 4 motorreductores DC tipo TT. Dado que el puente H L298N cuenta con dos canales independientes (Canal A y Canal B):
* **Canal A (OUT1 y OUT2):** Conecta en paralelo los dos motores del lado **izquierdo** (delantero izquierdo + trasero izquierdo).
* **Canal B (OUT3 y OUT4):** Conecta en paralelo los dos motores del lado **derecho** (delantero derecho + trasero derecho).

Este esquema permite tracción diferencial (tipo tanque / oruga), logrando giros en el lugar con alta estabilidad y excelente torque sobre cualquier superficie.

---

## 2. Tabla de Conexión de Pines (Arduino Uno - L298N)

| Pin Arduino | Conexión L298N / HC-05 | Función |
| :--- | :--- | :--- |
| **Pin D2 (RX)**  | TXD del módulo HC-05 | Recepción serie por SoftwareSerial |
| **Pin D3 (TX)**  | RXD del módulo HC-05 | Transmisión serie por SoftwareSerial |
| **Pin D8** | IN1 (L298N) | Sentido A Motores Izquierdos (Adelante) |
| **Pin D9** | IN2 (L298N) | Sentido B Motores Izquierdos (Atrás) |
| **Pin D10**| IN3 (L298N) | Sentido A Motores Derechos (Adelante) |
| **Pin D11**| IN4 (L298N) | Sentido B Motores Derechos (Atrás) |
| **ENA / ENB**| Jumpers colocados a 5V | Velocidad máxima por hardware sin requerir pines PWM adicionales |
| **GND**          | GND del L298N y polo negativo (-) batería | Masa común compartida de todo el circuito |

> **Nota técnica:** El uso de `SoftwareSerial` en los pines 2 y 3 permite reprogramar el Arduino por cable USB sin necesidad de desconectar el módulo Bluetooth HC-05 cada vez.

---

## 3. Bornera de Alimentación del Puente H L298N

* **Bornera 12V / VMS:** Conectar al polo positivo (+) del pack de baterías (2x 18650 = 7.4V a 8.4V) pasando por el interruptor On/Off.
* **Bornera GND:** Conectar al polo negativo (-) del pack de baterías y a un pin **GND** del Arduino.
* **Bornera 5V:** Si el jumper regulador de 5V del L298N está puesto, este pin entrega 5V regulados estables para alimentar el pin **Vin** o **5V** del Arduino Uno y el módulo Bluetooth, evitando usar reguladores adicionales.
