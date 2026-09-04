/**
 * Módulo de Captura y Normalización de Sensores Móviles (Giroscopio / Acelerómetro)
 * Grupo 1 - Programación 7° Año
 */
class SensorManager {
  constructor(options = {}) {
    this.deadzone = options.deadzone || 5; // Grados de zona muerta para evitar vibración
    this.maxAngle = options.maxAngle || 45; // Ángulo máximo considerado
    this.onDataCallback = options.onData || null;

    this.pitch = 0; // Eje Beta (Adelante / Atrás)
    this.roll = 0;  // Eje Gamma (Izquierda / Derecha)
    this.isActive = false;

    this._handleOrientation = this._handleOrientation.bind(this);
  }

  async requestPermission() {
    // Compatibilidad con iOS 13+ que requiere permiso explícito
    if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
      try {
        const response = await DeviceOrientationEvent.requestPermission();
        if (response === 'granted') {
          this.start();
          return true;
        } else {
          alert('Permiso de giroscopio denegado');
          return false;
        }
      } catch (err) {
        console.error('Error al solicitar permiso de sensor:', err);
        return false;
      }
    } else {
      // Navegadores estándar / Android
      this.start();
      return true;
    }
  }

  start() {
    if (window.DeviceOrientationEvent) {
      window.addEventListener('deviceorientation', this._handleOrientation, true);
      this.isActive = true;
    } else {
      alert('Tu navegador o dispositivo no soporta DeviceOrientationEvent');
    }
  }

  stop() {
    window.removeEventListener('deviceorientation', this._handleOrientation, true);
    this.isActive = false;
    this.pitch = 0;
    this.roll = 0;
    if (this.onDataCallback) {
      this.onDataCallback({ pitch: 0, roll: 0, rawPitch: 0, rawRoll: 0 });
    }
  }

  _handleOrientation(event) {
    if (!this.isActive) return;

    // Beta: Inclinación adelante/atrás (-180 a 180)
    // Gamma: Inclinación izquierda/derecha (-90 a 90)
    let rawBeta = event.beta || 0;
    let rawGamma = event.gamma || 0;

    // Aplicar zona muerta (Deadzone)
    let filteredPitch = Math.abs(rawBeta) < this.deadzone ? 0 : rawBeta;
    let filteredRoll = Math.abs(rawGamma) < this.deadzone ? 0 : rawGamma;

    // Limitar al rango máximo definido
    filteredPitch = Math.max(-this.maxAngle, Math.min(this.maxAngle, filteredPitch));
    filteredRoll = Math.max(-this.maxAngle, Math.min(this.maxAngle, filteredRoll));

    this.pitch = Math.round(filteredPitch);
    this.roll = Math.round(filteredRoll);

    if (this.onDataCallback) {
      this.onDataCallback({
        pitch: this.pitch,
        roll: this.roll,
        rawPitch: Math.round(rawBeta),
        rawRoll: Math.round(rawGamma)
      });
    }
  }
}

window.SensorManager = SensorManager;
