# Manual del Programador - Arquitectura Técnica (Grupo 1)

## 1. Arquitectura del Sistema
El sistema se basa en una arquitectura cliente-servidor desacoplada:
* **Capa de Captura y Presentación (Frontend Móvil):**
  * `app/js/sensors.js`: Encapsula `window.DeviceOrientationEvent`, aplica filtros de umbral (Deadzone de ±4°) y normalización angular.
  * `app/js/app.js`: Coordina la máquina de estados de la interfaz, el horizonte artificial y la serialización de comandos.
* **Capa de Hardware y Actuación (Firmware Microcontrolador):**
  * `firmware/auto_arduino/auto_arduino.ino`: Decodifica la trama serial y aplica modulación por ancho de pulsos (PWM) en el puente H L298N.

## 2. Protocolo de Comunicación
Los paquetes se transmiten en formato ASCII delimitado por saltos de línea `\n`:
* **Comando de movimiento:** `M:<MODO>,P:<PITCH>,R:<ROLL>\n`
  * `MODO`: `CAR` o `DRONE`
  * `PITCH`: Ángulo entero entre -45 y +45 (Inclinación frontal/posterior)
  * `ROLL`: Ángulo entero entre -45 y +45 (Inclinación lateral)
* **Comando de parada neutral:** `STOP\n`

## 3. Mapeo Cinemático
La señal de Pitch se mapea linealmente a valores de PWM (0 a 255) para controlar la velocidad, mientras que el Roll ajusta de manera diferencial la velocidad entre las ruedas izquierdas y derechas para lograr el viraje.
