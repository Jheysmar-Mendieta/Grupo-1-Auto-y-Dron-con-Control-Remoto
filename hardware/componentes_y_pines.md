# Componentes y Asignacion de Pines - Auto Robot 4WD (Grupo 1)

## 1. Configuracion de Traccion 4x4 (4WD)
El auto usa 4 motores DC tipo TT conectados al puente H L298N:
* **Canal A (OUT1 y OUT2):** Conecta en paralelo los dos motores del lado **izquierdo**.
* **Canal B (OUT3 y OUT4):** Conecta en paralelo los dos motores del lado **derecho**.

---

## 2. Tabla de Conexion de Pines (Arduino Uno + ESP32-CAM + L298N + Sensores)

No se utiliza modulo Bluetooth HC-05. Toda la conexion inalambrica y el video se hacen por **Wi-Fi con la placa ESP32-CAM**, que se comunica por puerto serie con el **Arduino Uno**.

| Pin Arduino Uno | Conexion | Funcion |
| :--- | :--- | :--- |
| **Pin D2 (RX)** | Pin **U0T (GPIO 1)** de la ESP32-CAM | Recibe las ordenes de movimiento y servo desde la ESP32-CAM |
| **Pin D3 (TX)** | Pin **U0R (GPIO 3)** de la ESP32-CAM | Envia la distancia del sensor ultrasonico hacia la ESP32-CAM |
| **Pin D5** | Pin **TRIG** del sensor HC-SR04 | Disparo del pulso ultrasonico |
| **Pin D6** | Pin **ECHO** del sensor HC-SR04 | Lectura del eco de distancia (freno a 12 cm) |
| **Pin D7** | Cable de senal (Naranja/Amarillo) del Servo SG90 | Inclinacion de la camara (centrado en 45 grados) |
| **Pin D9** | **IN1** (L298N) | Control Motores Izquierdos |
| **Pin D8** | **IN2** (L298N) | Control Motores Izquierdos |
| **Pin D11** | **IN3** (L298N) | Control Motores Derechos |
| **Pin D10** | **IN4** (L298N) | Control Motores Derechos |
| **ENA / ENB** | Jumpers colocados en el L298N | Habilitacion de ambos canales de motores |
| **GND** | GND de ESP32-CAM, L298N, Servo, Sensor y Bateria (-) | Masa comun compartida de todo el circuito |

---

## 3. Bornera de Alimentacion del Puente H L298N

* **Bornera 12V / VMS:** Positivo (+) del portapilas (2 baterias 18650 en serie = 7.4V a 8.4V) pasando por el interruptor On/Off.
* **Bornera GND:** Negativo (-) de las baterias y unido al **GND** del Arduino Uno y de la ESP32-CAM.
* **Bornera 5V:** Entrega 5V regulados para alimentar la **ESP32-CAM**, el **Arduino Uno** y el **Servo SG90**.
