# Lista de Componentes - Auto Robot 4WD con Camara Wi-Fi (Grupo 1)
**Proyecto:** Auto 4x4 a Control Remoto por Wi-Fi con Camara en Vivo  
**Integrantes:** Onesti, Silvero, Mendieta  
**Materia:** Programacion 7mo Año  

---

## Componentes Utilizados en el Auto Robot

### 1. Mecanica y Chasis 4x4
| Componente | Cantidad | Detalle |
| :--- | :---: | :--- |
| **Kit Chasis 4WD Robot** | 1 | 2 placas acrilicas, 4 motores DC tipo TT y 4 ruedas de goma. |

### 2. Placas y Sensores (Sin Bluetooth HC-05)
| Componente | Cantidad | Detalle |
| :--- | :---: | :--- |
| **ESP32-CAM (con camara OV2640)** | 1 | Genera la red Wi-Fi `AutoRobot_Grupo1`, transmite el video a la app y pasa los comandos al Arduino. |
| **Arduino Uno R3** | 1 | Controla el puente H L298N, el sensor ultrasonico y el servo de la camara. |
| **Sensor Ultrasonico HC-SR04** | 1 | Mide la distancia frontal para frenar ante obstaculos a menos de 12 cm. |
| **Micro-Servo SG90** | 1 | Mueve la inclinacion de la camara (calibrado a 45 grados). |

### 3. Potencia y Alimentacion
| Componente | Cantidad | Detalle |
| :--- | :---: | :--- |
| **Driver Puente H L298N** | 1 | Maneja los 4 motores (2 izquierdos en OUT1-OUT2 y 2 derechos en OUT3-OUT4). |
| **Baterias 18650 Li-ion (3.7V)** | 2 | Conectadas en serie (7.4V - 8.4V) en el portapilas con interruptor. |
| **Cables Jumpers Dupont** | 1 pack | Para las conexiones entre ESP32-CAM, Arduino Uno, L298N y sensores. |
