/**
 * Lógica Principal de la Aplicación de Control
 * Grupo 1 - Onesti, Silvero, Mendieta
 */

document.addEventListener('DOMContentLoaded', () => {
  // Elementos DOM
  const btnModeCar = document.getElementById('btn-mode-car');
  const btnModeDrone = document.getElementById('btn-mode-drone');
  const btnSensorToggle = document.getElementById('btn-sensor-toggle');
  const btnDeadman = document.getElementById('btn-deadman');
  const btnEmergency = document.getElementById('btn-emergency');
  
  const valPitch = document.getElementById('val-pitch');
  const valRoll = document.getElementById('val-roll');
  const horizonDisc = document.getElementById('horizon-disc');
  const lblStatus = document.getElementById('lbl-status');

  // Estado del sistema
  let currentMode = 'car'; // 'car' o 'drone'
  let isDeadmanPressed = false;
  let isSensorRunning = false;

  // Instanciar Gestor de Sensores
  const sensor = new SensorManager({
    deadzone: 4,
    maxAngle: 40,
    onData: (data) => {
      // Actualizar valores numéricos en pantalla
      valPitch.textContent = `${data.pitch}°`;
      valRoll.textContent = `${data.roll}°`;

      // Animar horizonte artificial visual
      if (horizonDisc) {
        horizonDisc.style.transform = `rotate(${-data.roll}deg) translateY(${data.pitch * 1.2}px)`;
      }

      // Si el botón de hombre muerto está presionado, procesar comando
      if (isDeadmanPressed) {
        procesarComando(data.pitch, data.roll);
      }
    }
  });

  // Selector de Modo
  btnModeCar.addEventListener('click', () => {
    currentMode = 'car';
    btnModeCar.classList.add('active');
    btnModeDrone.classList.remove('active');
    lblStatus.textContent = 'Modo Auto Activo';
  });

  btnModeDrone.addEventListener('click', () => {
    currentMode = 'drone';
    btnModeDrone.classList.add('active');
    btnModeCar.classList.remove('active');
    lblStatus.textContent = 'Modo Dron Activo';
  });

  // Activar / Desactivar Sensor
  btnSensorToggle.addEventListener('click', async () => {
    if (!isSensorRunning) {
      const granted = await sensor.requestPermission();
      if (granted) {
        isSensorRunning = true;
        btnSensorToggle.textContent = 'Detener Sensor';
        btnSensorToggle.style.background = '#00e676';
        lblStatus.textContent = 'Sensor Giroscopio Calibrado';
      }
    } else {
      sensor.stop();
      isSensorRunning = false;
      btnSensorToggle.textContent = 'Activar Sensor Móvil';
      btnSensorToggle.style.background = '';
      lblStatus.textContent = 'Sensor Detenido';
    }
  });

  // Gatillo de Seguridad (Deadman Switch)
  const startDrive = () => {
    isDeadmanPressed = true;
    btnDeadman.classList.add('active');
    lblStatus.textContent = `Operando en ${currentMode.toUpperCase()}`;
  };

  const stopDrive = () => {
    isDeadmanPressed = false;
    btnDeadman.classList.remove('active');
    lblStatus.textContent = 'Vehículo en Parada Neutral (Seguro)';
    enviarComandoDetencion();
  };

  // Eventos táctiles y de mouse para Deadman Switch
  btnDeadman.addEventListener('mousedown', startDrive);
  window.addEventListener('mouseup', stopDrive);
  btnDeadman.addEventListener('touchstart', (e) => { e.preventDefault(); startDrive(); });
  window.addEventListener('touchend', stopDrive);

  // Parada de Emergencia
  btnEmergency.addEventListener('click', () => {
    stopDrive();
    if (isSensorRunning) {
      sensor.stop();
      isSensorRunning = false;
      btnSensorToggle.textContent = 'Activar Sensor Móvil';
      btnSensorToggle.style.background = '';
    }
    lblStatus.textContent = '¡PARADA DE EMERGENCIA ACTIVADA!';
    alert('¡Parada de emergencia ejecutada! Motores desconectados.');
  });

  // Formateo y procesamiento de comandos para Arduino / ESP32
  function procesarComando(pitch, roll) {
    // Ejemplo de comando serial para Arduino: "M:MODO,P:PITCH,R:ROLL\n"
    const packet = `M:${currentMode.toUpperCase()},P:${pitch},R:${roll}`;
    console.log('[COMANDO TRANSMITIDO]:', packet);
    // Aquí se conecta con el módulo Web Bluetooth, WebSocket o HTTP
  }

  function enviarComandoDetencion() {
    console.log('[COMANDO TRANSMITIDO]: STOP');
  }
});
