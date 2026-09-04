# 🛒 Lista de Compras en Mercado Libre - Auto Robot 4WD (Grupo 1)
**Proyecto:** Auto 4x4 (4 Ruedas) a Control Remoto por Sensores Móviles  
**Integrantes:** Onesti, Silvero, Mendieta  
**Materia:** Programación 7° Año • Especialidad Técnica  

---

> [!TIP]
> **Ventaja del Chasis 4WD (4 Ruedas):**
> Los chasis 4WD suelen venir con **doble piso de acrílico**. Esto es ideal porque abajo pones el portapilas y los cables de los 4 motores, y en el piso de arriba montas prolijamente el Arduino, el Bluetooth y el Puente H.

---

## Opción 1: Combo Completo 4WD (Recomendado para ahorrar en envíos)

* **Búsqueda exacta en Mercado Libre:**  
  `Kit Auto Robot 4wd Arduino Bluetooth L298n` o `Kit Chasis 4wd Inteligente Arduino Bluetooth`
* **Qué incluye este combo:**
  * Chasis acrílico 4WD (generalmente doble piso) con tornillería y separadores.
  * 4 motores amarillos tipo TT (DC 3V-6V) con caja reductora.
  * 4 ruedas de goma de alto agarre.
  * Placa Arduino Uno R3 con su cable USB.
  * Módulo Bluetooth (HC-05 o HC-06).
  * Módulo Puente H L298N (controla los 4 motores conectándolos de a 2 en paralelo).
  * Portapilas y cables jumpers.

---

## Opción 2: Lista Componente por Componente (4WD)

Si prefieren comprar las partes por separado o armarlo a medida:

### 1. Mecánica y Chasis 4x4
| Componente | Cantidad | Búsqueda en Mercado Libre | Detalle técnico |
| :--- | :---: | :--- | :--- |
| **Kit Chasis 4WD Robot** | 1 | `Kit Chasis 4wd Robot 4 Ruedas Motores Arduino` | Incluye las 2 placas acrílicas, 4 motores TT, 4 ruedas, discos de encoder y soportes. |

### 2. Procesamiento y Comunicación
| Componente | Cantidad | Búsqueda en Mercado Libre | Detalle técnico |
| :--- | :---: | :--- | :--- |
| **Arduino Uno R3 (con cable USB)** | 1 | `Arduino Uno R3 Atmega328p Con Cable Usb` | El microcontrolador que ejecutará el programa. |
| **Módulo Bluetooth HC-05** | 1 | `Modulo Bluetooth Hc-05 Arduino` | Para recibir los comandos de movimiento desde la aplicación móvil. |

### 3. Control de Potencia de los 4 Motores
| Componente | Cantidad | Búsqueda en Mercado Libre | Detalle técnico |
| :--- | :---: | :--- | :--- |
| **Driver Puente H L298N Dual** | 1 | `Modulo Driver Motor Puente H L298n Dual` | Soporta perfectamente los 4 motores: se conectan los **2 motores del lado izquierdo en paralelo** a una salida (OUT1-OUT2) y los **2 del lado derecho en paralelo** a la otra salida (OUT3-OUT4). |

### 4. Alimentación y Baterías (Clave para 4WD)
> [!IMPORTANT]
> Al tener **4 motores en lugar de 2**, el auto consume más corriente (~1.2A en picos de arranque).  
> Las baterías 18650 de Litio son **obligatorias** (entregan hasta 10A continuos sin despeinarse).

| Componente | Cantidad | Búsqueda en Mercado Libre | Detalle técnico |
| :--- | :---: | :--- | :--- |
| **Baterías 18650 Li-ion (3.7V)** | 2 | `Bateria 18650 Recargable Li-ion` | Dos baterías en serie suman 7.4V - 8.4V, la tensión ideal para que los 4 motores tengan fuerza y velocidad. |
| **Portapilas 2 Baterías 18650** | 1 | `Porta Pila 2 Baterias 18650 En Serie` | Con cables rojo y negro listos para el switch y bornera. |
| **Cargador 18650 USB** | 1 | `Cargador Bateria 18650 Doble Usb` | Para cargar ambas baterías juntas. |

### 5. Accesorios y Conexiones
| Componente | Cantidad | Búsqueda en Mercado Libre | Detalle técnico |
| :--- | :---: | :--- | :--- |
| **Cables Jumpers Dupont (Kit x40)** | 1 pack | `Cables Jumpers Dupont Macho Hembra y Macho Macho 20cm` | Para todas las conexiones entre módulos. |
| **Interruptor Llave Tecla Mini** | 1 | `Interruptor Llave Tecla Redonda Mini On Off` | Conmutador general de energía. |
| **Resistencias 1kΩ y 2.2kΩ (1/4W)** | 2 de c/u | `Resistencias 1k y 2.2k 1/4w` | Para el divisor resistivo del pin RX del Bluetooth. |
| **Mini Protoboard 170 o 400 pts** | 1 | `Mini Protoboard 170 Puntos` | Muy recomendada para derivar las alimentaciones de 5V y masa (GND). |

---

## ⚡ Cómo se conectan los 4 Motores al Puente H L298N

* **Lado Izquierdo (2 motores):** Se unen los cables positivos del motor delantero y trasero izquierdo a **OUT1**, y los negativos a **OUT2**.
* **Lado Derecho (2 motores):** Se unen los cables positivos del motor delantero y trasero derecho a **OUT3**, y los negativos a **OUT4**.
* **Modo de Giro (Tipo Oruga / Tanque):** Para doblar a la derecha, las ruedas izquierdas empujan hacia adelante y las derechas reducen velocidad o invierten marcha para girar en el lugar con máxima fuerza.
