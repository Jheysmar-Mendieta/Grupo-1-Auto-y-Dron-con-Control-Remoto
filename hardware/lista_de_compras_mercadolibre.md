# 🛒 Lista de Compras en Mercado Libre - Auto Robot (Grupo 1)
**Proyecto:** Auto a Control Remoto por Sensores Móviles  
**Integrantes:** Onesti, Silvero, Mendieta  
**Materia:** Programación 7° Año • Especialidad Técnica  

---

> [!TIP]
> **Recomendación para ahorrar en envíos:**
> En Mercado Libre suele convenir buscar un **"Kit Chasis Auto Robot 2WD Arduino Bluetooth"** que ya trae el chasis, motores, puente H, cables y Arduino juntos en una sola compra. Más abajo tienes tanto la opción en combo como la lista pieza por pieza.

---

## Opción 1: Combo / Kit Completo (La opción más económica y práctica)

* **Búsqueda exacta en Mercado Libre:**  
  `Kit Auto Robot 2wd Arduino Bluetooth L298n` o `Kit Robot Inteligente Arduino Bluetooth Chasis 2wd`
* **Qué suele incluir:**
  * Chasis acrílico + 2 motores DC con caja reductora + ruedas + rueda loca.
  * Placa Arduino Uno R3 con cable USB.
  * Módulo Bluetooth (HC-05 o HC-06).
  * Driver de motores Puente H L298N.
  * Portapilas y cables jumpers.

---

## Opción 2: Lista Componente por Componente

Si compran las partes por separado o ya tienen algunas en el colegio:

### 1. Mecánica y Chasis
| Componente | Cantidad | Búsqueda en Mercado Libre | Para qué sirve |
| :--- | :---: | :--- | :--- |
| **Kit Chasis 2WD Robot** | 1 | `Kit Chasis 2wd Robot Arduino Rueda Loca` | Estructura acrílica, 2 motorreductores amarillos TT (3V-6V), 2 ruedas de goma, 1 rueda loca frontal y tornillería. |

### 2. Procesamiento y Comunicación
| Componente | Cantidad | Búsqueda en Mercado Libre | Para qué sirve |
| :--- | :---: | :--- | :--- |
| **Arduino Uno R3 (con cable USB)** | 1 | `Arduino Uno R3 Atmega328p Con Cable Usb` | El cerebro del auto. Procesa las señales del teléfono y maneja los motores. |
| **Módulo Bluetooth HC-05** | 1 | `Modulo Bluetooth Hc-05 Arduino` | Recibe las órdenes que envía la app móvil por puerto Serial UART. *(El HC-05 permite modo maestro/esclavo; el HC-06 también sirve).* |

### 3. Control de Potencia (Motores)
| Componente | Cantidad | Búsqueda en Mercado Libre | Para qué sirve |
| :--- | :---: | :--- | :--- |
| **Driver Puente H L298N** | 1 | `Modulo Driver Motor Puente H L298n Dual` | Permite controlar el sentido de giro (adelante/atrás) y velocidad (PWM) de los motores sin quemar el Arduino. |

### 4. Alimentación y Baterías (¡MUY IMPORTANTE!)
> [!CAUTION]
> **NO utilicen la batería cuadrada de 9V típica.**  
> Las baterías comunes de 9V (alcalinas/carbón) no entregan suficiente corriente para mover motores; el voltaje se cae y el Arduino se reinicia constantemente.

| Componente | Cantidad | Búsqueda en Mercado Libre | Para qué sirve |
| :--- | :---: | :--- | :--- |
| **Baterías 18650 Li-ion (3.7V)** | 2 | `Bateria 18650 Recargable Li-ion` | Entregan alta corriente constante. Dos en serie suman 7.4V nominal (8.4V max), ideal para alimentar el L298N y el Arduino. |
| **Portapilas para 2 Baterías 18650** | 1 | `Porta Pila 2 Baterias 18650 En Serie` | Contenedor con cables para conectar las baterías al puente H. |
| **Cargador para Baterías 18650** | 1 | `Cargador Bateria 18650 Doble Usb` | Para recargar las baterías y no quedarse sin energía en las pruebas. |

### 5. Cables, Conexión y Prototipado
| Componente | Cantidad | Búsqueda en Mercado Libre | Para qué sirve |
| :--- | :---: | :--- | :--- |
| **Cables Jumpers Dupont (Kit x40)** | 1 plancha | `Cables Jumpers Dupont Macho Hembra Hembra Hembra 20cm` | Para conectar Arduino con el Bluetooth y el Puente H sin soldar. |
| **Mini Protoboard (170 o 400 puntos)** | 1 | `Mini Protoboard 170 Puntos` o `Protoboard 400 Puntos` | Opcional pero muy útil para distribuir pines de 5V y masa (GND). |
| **Llave tecla interruptor On/Off** | 1 | `Interruptor Llave Tecla Redonda Mini On Off` | Para encender y apagar el auto fácilmente sin tener que desconectar cables. |
| **Resistencias de 1kΩ y 2.2kΩ (1/4W)** | 2 de c/u | `Resistencias 1k y 2.2k 1/4w` | Para armar el divisor de voltaje protector en el pin RX del módulo Bluetooth (3.3V). |

---

## 🛠️ Herramientas básicas de taller recomendadas
* Destornillador Phillips pequeño (estrella).
* Cinta aisladora o termocontraíble.
* Pistola de silicona caliente o cinta bifaz de alto impacto (para fijar las placas al chasis).
