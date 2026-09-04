# 🚗🚁 Auto y Dron con Control Remoto - Grupo 1

[![Estado](https://img.shields.io/badge/Estado-En%20Desarrollo-brightgreen.svg)](#)
[![Curso](https://img.shields.io/badge/Curso-7mo%20A%C3%B1o%20Programaci%C3%B3n-blue.svg)](#)
[![Ciclo](https://img.shields.io/badge/Ciclo-2026-orange.svg)](#)

Proyecto escolar orientado al desarrollo de una solución integral de control de vehículos (auto y dron) mediante una **aplicación móvil** y **microcontroladores (Arduino / ESP32)**, aprovechando los **sensores de movimiento (giroscopio y acelerómetro)** integrados en el smartphone.

---

## 👥 Integrantes (Grupo 1)

* **Onesti**
* **Silvero**
* **Mendieta**

**Docentes a cargo:**
* Prof. Figueroa Hernán
* Prof. Piris Eduardo  
* **Materia:** Programación 7° Año

---

## 📋 Descripción del Proyecto

El sistema se compone de dos módulos principales coordinados desde un mando centralizado en el dispositivo móvil:

1. **Auto a Control Remoto:**
   * Control motriz mediante Arduino y puente H.
   * Manejo dinámico a través de gestos e inclinación del teléfono (giroscopio/acelerómetro).
2. **Dron:**
   * Control de vuelo mediante app móvil con interfaz táctil (Throttle/Yaw) y control de inclinación (Pitch/Roll) por giroscopio.
3. **Sensor Móvil Obligatorio:**
   * Uso de los sensores físicos del dispositivo (`DeviceOrientation` y `DeviceMotion`) para detectar ángulos de inclinación y convertirlos en señales de control.
4. **Integración Institucional:**
   * Participación y compatibilidad con el proyecto común **GEdeCon**.

---

## 📂 Estructura del Repositorio

```text
Grupo_1_auto_dron/
├── app/                      # Aplicación móvil (interfaz, giroscopio y conexión)
│   ├── css/                  # Estilos visuales UX/UI (modo cockpit/HUD)
│   ├── js/                   # Lógica modular: sensores, mandos y comunicación
│   └── index.html            # Pantalla principal de control
├── firmware/                 # Código fuente para microcontroladores
│   ├── auto_arduino/         # Sketch de Arduino para control de motores y puente H
│   └── dron_control/         # Sketch/código de control de estabilidad y comandos del dron
├── docs/                     # Documentación técnica e informes
│   ├── informe_apa/          # Informe formal con formato y normas APA 7ma edición
│   ├── manual_usuario/       # Guía práctica para operar la app y los vehículos
│   └── manual_programador/   # Especificaciones técnicas, arquitectura y diagramas
├── hardware/                 # Esquemas de conexión, pinout y lista de componentes
└── assets/                   # Recursos gráficos, capturas y diagramas de flujo
```

---

## 🛠️ Tecnologías y Herramientas

* **App Móvil:** HTML5, CSS3, JavaScript (Web App / PWA con API de Sensores de Orientación).
* **Hardware & Firmware:** C++ / Arduino IDE, microcontroladores Arduino Uno/Nano o ESP32.
* **Comunicación:** Módulo Bluetooth (HC-05/06) o Wi-Fi / WebSockets.
* **Documentación:** Formato APA 7.ª edición.

---

## 🚀 Puesta en Marcha Rápida

1. **Clonar el repositorio:**
   ```bash
   git clone https://github.com/Jheysmar-Mendieta/Grupo-1-Auto-y-Dron-con-Control-Remoto.git
   cd Grupo-1-Auto-y-Dron-con-Control-Remoto
   ```
2. **Probar la Aplicación Móvil:**
   * Abrir el archivo `app/index.html` en un navegador web o servirlo localmente en la misma red Wi-Fi para probarlo desde el celular.
3. **Cargar el Firmware:**
   * Abrir `firmware/auto_arduino/auto_arduino.ino` en el Arduino IDE y subirlo a la placa con los pines correspondientes.

---

## 📄 Licencia y Uso Académico
Proyecto desarrollado con fines pedagógicos para la especialidad de Programación técnica.
