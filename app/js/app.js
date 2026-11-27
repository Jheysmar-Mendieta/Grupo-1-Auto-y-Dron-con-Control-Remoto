/**
 * CONTROL DE AUTO & DRON - GRUPO 1 (GEdeCon 2026)
 * Lógica Integral de Control, Sensores, Cámara FPV y Comunicación
 * Integrantes: Onesti, Silvero, Mendieta
 */

document.addEventListener('DOMContentLoaded', () => {
  // ==========================================
  // 1. ESTADO GLOBAL DE LA APLICACIÓN
  // ==========================================
  const state = {
    mode: 'car', // 'car' | 'drone'
    gyroActive: false,
    activeCommand: 'S', // 'F', 'B', 'L', 'R', 'S'
    btConnected: false,
    btDeviceName: 'Desconectado',
    sensitivity: 15, // Umbral en grados para giroscopio
    cameraActive: false,
    facingMode: 'environment', // 'environment' (trasera) o 'user' (frontal)
    audioEnabled: true,
    obstacleAlert: false
  };

  // ==========================================
  // 2. ELEMENTOS DEL DOM
  // ==========================================
  const appRoot = document.getElementById('app-root');
  const viewCar = document.getElementById('view-car');
  const viewDrone = document.getElementById('view-drone');
  const lblModeTitle = document.getElementById('lbl-mode-title');
  const btnTopSwitch = document.getElementById('btn-top-switch');
  const btnTopGuide = document.getElementById('btn-top-guide');
  const btnTopBluetooth = document.getElementById('btn-top-bluetooth');
  const btnOpenMenu = document.getElementById('btn-open-menu');
  const btDot = document.getElementById('bt-dot');
  const btnBtText = document.getElementById('btn-bt-text');

  // Fondos y Cámara
  const liveVideo = document.getElementById('live-camera');
  const simBgCar = document.getElementById('simulated-bg-car');
  const simBgDrone = document.getElementById('simulated-bg-drone');

  // Mandos Auto
  const btnCarLeft = document.getElementById('btn-car-left');
  const btnCarRight = document.getElementById('btn-car-right');
  const btnCarForward = document.getElementById('btn-car-forward');
  const btnCarBackward = document.getElementById('btn-car-backward');
  const lblCarCommand = document.getElementById('lbl-car-command');
  const switchGyroCar = document.getElementById('switch-gyro-car');

  // Mandos Dron
  const btnDroneUp = document.getElementById('btn-drone-up');
  const btnDroneDown = document.getElementById('btn-drone-down');
  const lblDroneCommand = document.getElementById('lbl-drone-command');
  const switchGyroDrone = document.getElementById('switch-gyro-drone');
  const stickLeftKnob = document.getElementById('joystick-left-knob');
  const stickLeftBase = document.getElementById('joystick-left-container');
  const stickRightKnob = document.getElementById('joystick-right-knob');
  const stickRightBase = document.getElementById('joystick-right-container');

  // Alerta de Obstáculo
  const obstacleAlert = document.getElementById('obstacle-alert');

  // Modales
  const modalMenu = document.getElementById('modal-menu');
  const modalBluetooth = document.getElementById('modal-bluetooth');
  const modalGuideCar = document.getElementById('modal-guide-car');
  const modalGuideDrone = document.getElementById('modal-guide-drone');

  // Botones de Cierre de Modales
  document.getElementById('btn-close-menu').onclick = () => hideModal(modalMenu);
  document.getElementById('btn-close-bluetooth').onclick = () => hideModal(modalBluetooth);
  document.getElementById('btn-close-guide-car').onclick = () => hideModal(modalGuideCar);
  document.getElementById('btn-close-guide-drone').onclick = () => hideModal(modalGuideDrone);

  // Botones de Configuración en Menú
  const btnToggleCamera = document.getElementById('btn-toggle-camera');
  const btnFlipCamera = document.getElementById('btn-flip-camera');
  const rangeSensitivity = document.getElementById('range-sensitivity');
  const valSensitivity = document.getElementById('val-sensitivity');
  const chkAudio = document.getElementById('chk-audio');
  const btnEmergencyStop = document.getElementById('btn-emergency-stop');

  // Terminal y Bluetooth
  const btDeviceNameLabel = document.getElementById('bt-device-name');
  const btnScanWebBt = document.getElementById('btn-scan-web-bt');
  const btnSimConnectBt = document.getElementById('btn-sim-connect-bt');
  const btnDisconnectBt = document.getElementById('btn-disconnect-bt');
  const serialTerminal = document.getElementById('serial-terminal');

  // ==========================================
  // 3. SISTEMA DE AUDIO (WEB AUDIO API)
  // ==========================================
  let audioCtx = null;
  function initAudio() {
    if (!audioCtx) {
      const AudioContext = window.AudioContext || window.webkitAudioContext;
      if (AudioContext) audioCtx = new AudioContext();
    }
    if (audioCtx && audioCtx.state === 'suspended') {
      audioCtx.resume();
    }
  }

  function playTone(freq, duration = 0.1, type = 'sine') {
    if (!state.audioEnabled) return;
    initAudio();
    if (!audioCtx) return;
    try {
      const osc = audioCtx.createOscillator();
      const gain = audioCtx.createGain();
      osc.type = type;
      osc.frequency.setValueAtTime(freq, audioCtx.currentTime);
      gain.gain.setValueAtTime(0.08, audioCtx.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + duration);
      osc.connect(gain);
      gain.connect(audioCtx.destination);
      osc.start();
      osc.stop(audioCtx.currentTime + duration);
    } catch (e) {
      console.warn('Audio error:', e);
    }
  }

  function playEngineSound() {
    if (!state.audioEnabled) return;
    playTone(180, 0.15, 'sawtooth');
  }

  function playBeep(freq = 600) {
    playTone(freq, 0.08, 'sine');
  }

  function triggerHaptic(duration = 30) {
    if ('vibrate' in navigator) {
      try { navigator.vibrate(duration); } catch (_) {}
    }
  }

  // ==========================================
  // 4. CAMBIO DE MODO: AUTO <-> DRON
  // ==========================================
  function setMode(newMode) {
    state.mode = newMode;
    playBeep(newMode === 'drone' ? 880 : 540);

    if (newMode === 'car') {
      document.body.className = 'mode-auto';
      viewCar.classList.add('active');
      viewDrone.classList.remove('active');
      lblModeTitle.textContent = 'Auto';
      btnTopSwitch.textContent = 'Ir al dron';
      btnTopGuide.textContent = 'Guia de Manejo';
      simBgCar.classList.add('active');
      simBgDrone.classList.remove('active');
      switchGyroCar.checked = state.gyroActive;
      updateStatusDisplay('Frenado');
    } else {
      document.body.className = 'mode-drone';
      viewDrone.classList.add('active');
      viewCar.classList.remove('active');
      lblModeTitle.textContent = 'Dron';
      btnTopSwitch.textContent = 'Ir al auto';
      btnTopGuide.textContent = 'Guia de vuelo';
      simBgDrone.classList.add('active');
      simBgCar.classList.remove('active');
      switchGyroDrone.checked = state.gyroActive;
      updateStatusDisplay('Estable / Listo');
    }

    logSerial(`[SISTEMA] Cambio de modo a: ${newMode.toUpperCase()}`);
    sendVehicleCommand('S');
  }

  btnTopSwitch.addEventListener('click', () => {
    setMode(state.mode === 'car' ? 'drone' : 'car');
  });

  // ==========================================
  // 5. GESTIÓN DE MODALES
  // ==========================================
  function showModal(modal) {
    playBeep(700);
    modal.classList.remove('hidden');
  }

  function hideModal(modal) {
    playBeep(450);
    modal.classList.add('hidden');
  }

  btnOpenMenu.addEventListener('click', () => showModal(modalMenu));
  btnTopBluetooth.addEventListener('click', () => showModal(modalBluetooth));
  btnTopGuide.addEventListener('click', () => {
    if (state.mode === 'car') {
      showModal(modalGuideCar);
    } else {
      showModal(modalGuideDrone);
    }
  });

  // Cerrar al hacer clic en el fondo oscuro
  [modalMenu, modalBluetooth, modalGuideCar, modalGuideDrone].forEach(modal => {
    modal.addEventListener('click', (e) => {
      if (e.target === modal) hideModal(modal);
    });
  });

  // ==========================================
  // 6. GESTIÓN DE CÁMARA (REAL / SIMULADA)
  // ==========================================
  let cameraStream = null;

  async function toggleCamera(forceStop = false) {
    if (state.cameraActive || forceStop) {
      if (cameraStream) {
        cameraStream.getTracks().forEach(track => track.stop());
        cameraStream = null;
      }
      liveVideo.style.display = 'none';
      state.cameraActive = false;
      btnToggleCamera.textContent = 'Activar Cámara';
      btnToggleCamera.style.background = '#2563eb';
      if (state.mode === 'car') simBgCar.classList.add('active');
      else simBgDrone.classList.add('active');
      logSerial('[CÁMARA] Desactivada -> Modo simulación FPV activo.');
    } else {
      try {
        const constraints = {
          video: {
            facingMode: { ideal: state.facingMode },
            width: { ideal: 1280 },
            height: { ideal: 720 }
          },
          audio: false
        };
        cameraStream = await navigator.mediaDevices.getUserMedia(constraints);
        liveVideo.srcObject = cameraStream;
        liveVideo.style.display = 'block';
        simBgCar.classList.remove('active');
        simBgDrone.classList.remove('active');
        state.cameraActive = true;
        btnToggleCamera.textContent = 'Desactivar Cámara';
        btnToggleCamera.style.background = '#15803d';
        logSerial('[CÁMARA] Transmisión en vivo iniciada con éxito.');
      } catch (err) {
        console.error('Error de cámara:', err);
        alert('No se pudo acceder a la cámara. Se mantendrá el modo simulación FPV.');
      }
    }
  }

  btnToggleCamera.addEventListener('click', () => toggleCamera());

  btnFlipCamera.addEventListener('click', async () => {
    state.facingMode = (state.facingMode === 'environment') ? 'user' : 'environment';
    if (state.cameraActive) {
      await toggleCamera(true);
      await toggleCamera(false);
    }
  });

  // ==========================================
  // 7. TERMINAL SERIAL Y COMUNICACIÓN
  // ==========================================
  function logSerial(text, type = 'system') {
    const line = document.createElement('div');
    line.className = `log-line ${type}`;
    const time = new Date().toLocaleTimeString().split(' ')[0];
    line.textContent = `[${time}] ${text}`;
    serialTerminal.appendChild(line);
    serialTerminal.scrollTop = serialTerminal.scrollHeight;
  }

  function sendVehicleCommand(cmd, labelText) {
    if (state.obstacleAlert && cmd !== 'S' && cmd !== 'B') {
      logSerial(`[BLOQUEO] Freno activo por pared. Comando ${cmd} rechazado.`, 'alert');
      return;
    }

    state.activeCommand = cmd;
    if (labelText) {
      updateStatusDisplay(labelText);
    }

    // Protocolo serial hacia Arduino / Tello
    let serialPacket = cmd;
    if (state.mode === 'drone') {
      serialPacket = `DRONE:${cmd}`;
    }

    logSerial(`TX: ${serialPacket}`, 'tx');

    // Reproducir sonido motor o clic
    if (cmd !== 'S') {
      playEngineSound();
      triggerHaptic(25);
    }
  }

  function updateStatusDisplay(text) {
    lblCarCommand.textContent = text;
    lblDroneCommand.textContent = text;
  }

  // ==========================================
  // 8. SIMULADOR Y WEB BLUETOOTH
  // ==========================================
  btnSimConnectBt.addEventListener('click', () => {
    state.btConnected = true;
    state.btDeviceName = 'HC-05 Robot (Simulado)';
    btDeviceNameLabel.textContent = state.btDeviceName;
    btDot.className = 'bt-dot connected';
    btnBtText.textContent = 'HC-05 [Conectado]';
    btnDisconnectBt.disabled = false;
    btnSimConnectBt.disabled = true;
    btnScanWebBt.disabled = true;
    logSerial('[BT] Conexión establecida con HC-05 (00:14:03:05:5A:F1).', 'system');
    playTone(900, 0.2);
    hideModal(modalBluetooth);
  });

  btnScanWebBt.addEventListener('click', async () => {
    if (!navigator.bluetooth) {
      alert('Tu navegador no soporta Web Bluetooth API. Puedes usar el botón "Modo Práctica (Simular Conexión)".');
      return;
    }
    try {
      logSerial('[BT] Solicitando dispositivo Bluetooth SPP...', 'system');
      const device = await navigator.bluetooth.requestDevice({
        acceptAllDevices: true,
        optionalServices: ['generic_access']
      });
      state.btConnected = true;
      state.btDeviceName = device.name || 'Dispositivo BT';
      btDeviceNameLabel.textContent = state.btDeviceName;
      btDot.className = 'bt-dot connected';
      btnBtText.textContent = 'BT Conectado';
      btnDisconnectBt.disabled = false;
      logSerial(`[BT] Vinculado a: ${state.btDeviceName}`, 'system');
      hideModal(modalBluetooth);
    } catch (err) {
      logSerial(`[BT Error]: ${err.message}`, 'alert');
    }
  });

  btnDisconnectBt.addEventListener('click', () => {
    state.btConnected = false;
    state.btDeviceName = 'Desconectado';
    btDeviceNameLabel.textContent = state.btDeviceName;
    btDot.className = 'bt-dot disconnected';
    btnBtText.textContent = 'Conectar a BT';
    btnDisconnectBt.disabled = true;
    btnSimConnectBt.disabled = false;
    btnScanWebBt.disabled = false;
    logSerial('[BT] Dispositivo desconectado.', 'alert');
  });

  // ==========================================
  // 9. MANDOS DEL AUTO ROBOT (<, >, ^, v)
  // ==========================================
  function bindDirectionalButton(btn, startCmd, startText) {
    const handleStart = (e) => {
      e.preventDefault();
      btn.classList.add('pressed');
      sendVehicleCommand(startCmd, startText);
    };

    const handleEnd = (e) => {
      e.preventDefault();
      btn.classList.remove('pressed');
      sendVehicleCommand('S', 'Frenado');
    };

    btn.addEventListener('mousedown', handleStart);
    btn.addEventListener('mouseup', handleEnd);
    btn.addEventListener('mouseleave', handleEnd);
    btn.addEventListener('touchstart', handleStart, { passive: false });
    btn.addEventListener('touchend', handleEnd, { passive: false });
    btn.addEventListener('touchcancel', handleEnd, { passive: false });
  }

  bindDirectionalButton(btnCarForward, 'F', 'Adelante');
  bindDirectionalButton(btnCarBackward, 'B', 'Atrás');
  bindDirectionalButton(btnCarLeft, 'L', 'Giro Izquierda');
  bindDirectionalButton(btnCarRight, 'R', 'Giro Derecha');

  // Botones de altitud del dron
  bindDirectionalButton(btnDroneUp, 'UP', 'Subiendo');
  bindDirectionalButton(btnDroneDown, 'DOWN', 'Descendiendo');

  // ==========================================
  // 10. JOYSTICKS ANALÓGICOS 360° DEL DRON
  // ==========================================
  function setupAnalogStick(baseEl, knobEl, onMove, onRelease) {
    let isDragging = false;
    const maxRadius = 30; // Desplazamiento máximo en px

    function handleStart(e) {
      isDragging = true;
      initAudio();
      handleMove(e);
    }

    function handleMove(e) {
      if (!isDragging) return;
      const rect = baseEl.getBoundingClientRect();
      const centerX = rect.left + rect.width / 2;
      const centerY = rect.top + rect.height / 2;

      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;

      let dx = clientX - centerX;
      let dy = clientY - centerY;
      const distance = Math.sqrt(dx * dx + dy * dy);

      if (distance > maxRadius) {
        dx = (dx / distance) * maxRadius;
        dy = (dy / distance) * maxRadius;
      }

      knobEl.style.transform = `translate(${dx}px, ${dy}px)`;

      // Normalizar entre -1.0 y 1.0
      const normX = dx / maxRadius;
      const normY = dy / maxRadius;
      onMove(normX, normY);
    }

    function handleEnd() {
      if (!isDragging) return;
      isDragging = false;
      knobEl.style.transform = `translate(0px, 0px)`;
      onRelease();
    }

    baseEl.addEventListener('mousedown', handleStart);
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleEnd);

    baseEl.addEventListener('touchstart', handleStart, { passive: false });
    window.addEventListener('touchmove', handleMove, { passive: false });
    window.addEventListener('touchend', handleEnd, { passive: false });
  }

  // Joystick Izquierdo (Altitud & Yaw)
  setupAnalogStick(
    stickLeftBase,
    stickLeftKnob,
    (x, y) => {
      if (Math.abs(y) > Math.abs(x)) {
        if (y < -0.3) sendVehicleCommand('UP', 'Subiendo');
        else if (y > 0.3) sendVehicleCommand('DOWN', 'Descendiendo');
      } else {
        if (x < -0.3) sendVehicleCommand('YAW_L', 'Giro Eje Izq');
        else if (x > 0.3) sendVehicleCommand('YAW_R', 'Giro Eje Der');
      }
    },
    () => sendVehicleCommand('S', 'Estable')
  );

  // Joystick Derecho (Pitch & Roll)
  setupAnalogStick(
    stickRightBase,
    stickRightKnob,
    (x, y) => {
      if (Math.abs(y) > Math.abs(x)) {
        if (y < -0.3) sendVehicleCommand('F', 'Adelante');
        else if (y > 0.3) sendVehicleCommand('B', 'Atrás');
      } else {
        if (x < -0.3) sendVehicleCommand('L', 'Lateral Izq');
        else if (x > 0.3) sendVehicleCommand('R', 'Lateral Der');
      }
    },
    () => sendVehicleCommand('S', 'Estable')
  );

  // ==========================================
  // 11. MODO GIROSCOPIO (SENSOR MÓVIL)
  // ==========================================
  function handleOrientationEvent(event) {
    if (!state.gyroActive) return;

    const pitch = event.beta || 0; // Adelante / Atrás
    const roll = event.gamma || 0; // Izquierda / Derecha
    const threshold = state.sensitivity;

    if (pitch > threshold) {
      if (state.activeCommand !== 'F') sendVehicleCommand('F', 'Adelante (Gyro)');
    } else if (pitch < -threshold) {
      if (state.activeCommand !== 'B') sendVehicleCommand('B', 'Atrás (Gyro)');
    } else if (roll > threshold) {
      if (state.activeCommand !== 'R') sendVehicleCommand('R', 'Derecha (Gyro)');
    } else if (roll < -threshold) {
      if (state.activeCommand !== 'L') sendVehicleCommand('L', 'Izquierda (Gyro)');
    } else {
      if (state.activeCommand !== 'S') sendVehicleCommand('S', 'Neutral (Gyro)');
    }
  }

  async function toggleGyroscope(active) {
    state.gyroActive = active;
    switchGyroCar.checked = active;
    switchGyroDrone.checked = active;

    if (active) {
      // Solicitar permiso en iOS 13+
      if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
        try {
          const res = await DeviceOrientationEvent.requestPermission();
          if (res !== 'granted') {
            alert('Permiso de giroscopio denegado.');
            state.gyroActive = false;
            switchGyroCar.checked = false;
            switchGyroDrone.checked = false;
            return;
          }
        } catch (e) {
          console.warn('Gyro permission error:', e);
        }
      }

      window.addEventListener('deviceorientation', handleOrientationEvent, true);
      updateStatusDisplay('Giroscopio Listo');
      logSerial('[SENSOR] Modo giroscopio activado.');
      playTone(660, 0.15);
    } else {
      window.removeEventListener('deviceorientation', handleOrientationEvent, true);
      sendVehicleCommand('S', 'Frenado');
      logSerial('[SENSOR] Modo giroscopio desactivado.');
      playTone(330, 0.15);
    }
  }

  switchGyroCar.addEventListener('change', (e) => toggleGyroscope(e.target.checked));
  switchGyroDrone.addEventListener('change', (e) => toggleGyroscope(e.target.checked));

  // Ajuste de sensibilidad en el menú
  rangeSensitivity.addEventListener('input', (e) => {
    state.sensitivity = parseInt(e.target.value, 10);
    valSensitivity.textContent = `${state.sensitivity}°`;
  });

  chkAudio.addEventListener('change', (e) => {
    state.audioEnabled = e.target.checked;
  });

  // Parada de Emergencia Total
  btnEmergencyStop.addEventListener('click', () => {
    toggleGyroscope(false);
    sendVehicleCommand('S', '¡EMERGENCIA!');
    playTone(220, 0.4, 'sawtooth');
    triggerHaptic(200);
    alert('¡Parada de emergencia ejecutada! Todos los motores detenidos.');
    hideModal(modalMenu);
  });

  // ==========================================
  // 12. ATAJOS DE TECLADO PARA PC (W/A/S/D)
  // ==========================================
  window.addEventListener('keydown', (e) => {
    if (e.repeat) return;
    const key = e.key.toUpperCase();
    if (key === 'ARROWUP' || key === 'W') {
      sendVehicleCommand('F', 'Adelante');
      btnCarForward.classList.add('pressed');
    } else if (key === 'ARROWDOWN' || key === 'S') {
      sendVehicleCommand('B', 'Atrás');
      btnCarBackward.classList.add('pressed');
    } else if (key === 'ARROWLEFT' || key === 'A') {
      sendVehicleCommand('L', 'Izquierda');
      btnCarLeft.classList.add('pressed');
    } else if (key === 'ARROWRIGHT' || key === 'D') {
      sendVehicleCommand('R', 'Derecha');
      btnCarRight.classList.add('pressed');
    } else if (key === ' ') {
      sendVehicleCommand('S', 'Frenado');
    }
  });

  window.addEventListener('keyup', (e) => {
    const key = e.key.toUpperCase();
    if (['ARROWUP', 'ARROWDOWN', 'ARROWLEFT', 'ARROWRIGHT', 'W', 'A', 'S', 'D'].includes(key)) {
      btnCarForward.classList.remove('pressed');
      btnCarBackward.classList.remove('pressed');
      btnCarLeft.classList.remove('pressed');
      btnCarRight.classList.remove('pressed');
      sendVehicleCommand('S', 'Frenado');
    }
  });

  // Iniciar en Modo Auto por defecto y activar cámara en vivo automáticamente
  setMode('car');
  logSerial('[SISTEMA] Aplicación Grupo 1 iniciada y lista.');

  // Iniciar la cámara del celular automáticamente
  if (navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
    toggleCamera(false).catch(() => {
      console.log('Cámara en modo simulación hasta interacción del usuario.');
    });
  }
});
