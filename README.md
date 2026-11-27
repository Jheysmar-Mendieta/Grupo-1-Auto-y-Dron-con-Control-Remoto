# Auto y Dron con Control Remoto - Grupo 1

Proyecto de 7mo Año Programacion (Ciclo 2026) para el control de un auto robot y un dron desde una aplicacion movil en Android.

## Integrantes - Grupo 1
* Onesti
* Silvero
* Mendieta

**Profesores:**
* Figueroa Hernan
* Piris Eduardo

---

## Descargar Aplicacion Android (APK)
* **[Descargar ControlAutoDron_Grupo1.apk](APK/ControlAutoDron_Grupo1.apk)**

---

## Carpetas del proyecto

* **`APK/`**: Instalador de la app para Android (`ControlAutoDron_Grupo1.apk`) y codigo fuente (`codigo_fuente_android/`).
* **`Auto/`**: Codigos para el auto robot:
  * `Arduino_Uno/auto_arduino/auto_arduino.ino`: Control de los 4 motores con puente H L298N, sensor ultrasonico HC-SR04 y servo de la camara a 45 grados.
  * `ESP32_CAM/auto_esp32cam/auto_esp32cam.ino`: Red Wi-Fi (`AutoRobot_Grupo1`), video en vivo y puente serial con el Arduino Uno.
  * `conexiones_auto.txt`: Resumen de pines conectados entre las placas.
* **`Dron/`**: Modulos de control del dron por Wi-Fi UDP, giroscopio y camara.
* **`hardware/`**: Esquema de pines y lista de componentes usados.
