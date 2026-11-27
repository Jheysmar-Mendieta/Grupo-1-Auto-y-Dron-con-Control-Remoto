# Manual del Programador (Grupo 1)

## 1. Estructura del Sistema
* **Aplicacion Android (`APK/`):**
  * Hecha en Java para Android. Se conecta por Wi-Fi HTTP a la IP `192.168.4.1`.
  * Recibe el video MJPEG en el puerto `81` (`/stream`) y envia los comandos en el puerto `80` (`/action?go=F|B|L|R|S` y `/action?servo=45`).
* **ESP32-CAM (`Auto/ESP32_CAM/`):**
  * Crea el punto de acceso Wi-Fi `AutoRobot_Grupo1`, transmite el video de la camara OV2640 y le pasa los comandos por puerto serie (`9600 baudios`) al Arduino Uno.
* **Arduino Uno (`Auto/Arduino_Uno/`):**
  * Recibe los comandos en el Pin 2 (RX) desde la ESP32-CAM.
  * Controla el puente H L298N (Pines 8, 9, 10 y 11), el servo SG90 en el Pin 7 (centrado en 45 grados) y el sensor ultrasonico HC-SR04 en los Pines 5 (Trig) y 6 (Echo).
