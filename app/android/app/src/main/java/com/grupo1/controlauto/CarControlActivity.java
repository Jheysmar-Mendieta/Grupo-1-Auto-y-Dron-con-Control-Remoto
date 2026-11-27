package com.grupo1.controlauto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cabina HUD del Auto Robot 4WD integrada con AI-Thinker ESP32-CAM (Wi-Fi HTTP + Stream MJPEG en vivo)
 * y soporte adicional de Bluetooth Clásico (HC-05).
 *
 * Endpoints sincronizados con firmware/auto_esp32cam/auto_esp32cam.ino:
 *  - Video MJPEG en vivo:  GET http://<IP>:81/stream
 *  - Comandos L298N:       GET http://<IP>/action?go=F|B|L|R|S
 *  - Telemetría HC-SR04:   GET http://<IP>/status -> {"state":"S","distance":42.5,"obstacle":false}
 */
public class CarControlActivity extends AppCompatActivity implements SensorEventListener {

    public static final String DEFAULT_ESP32_IP = "192.168.4.1";
    private static final String PREFS_NAME = "CarControlPrefs";
    private static final String PREF_KEY_ESP32_IP = "esp32_ip";
    private static final String PREF_KEY_MOTOR_MAP_MODE = "motor_map_mode_v3";
    private static final String PREF_KEY_SERVO_INVERT = "servo_invert";

    // Rango calibrado del Servo SG90 (Centro: 45°, Rango seguro: 15° .. 75°, Paso: 10°)
    private static final int SERVO_CENTER_DEG = 45;
    private static final int SERVO_MIN_DEG = 15;
    private static final int SERVO_MAX_DEG = 75;
    private static final int SERVO_STEP_DEG = 10;

    // Cliente Wi-Fi HTTP + MJPEG (ESP32-CAM) + Puente Serial a Arduino Uno (L298N + Servo + HC-SR04)
    private String esp32Ip = DEFAULT_ESP32_IP;
    private volatile boolean esp32WifiConnected = false;
    private volatile boolean hasRenderedMjpegFrame = false;
    private volatile float esp32DistanceCm = -1f;
    private volatile int esp32FirmwareVersion = 2; // >=2 = mapeo directo, 1 = mapeo chasis v1
    private volatile int motorMapMode = 2; // 2 = DIRECTO (F->F, B->B, L->L, R->R), 0 = AUTO, 1 = CORREGIR CHASIS
    private volatile int currentServoAngle = SERVO_CENTER_DEG; // 45° = centro calibrado
    private volatile boolean invertServoTilt = false;
    private volatile boolean isMjpegStreaming = false;
    private volatile boolean isStatusPolling = false;
    private Thread mjpegThread = null;
    private ExecutorService httpCmdExecutor;
    private ExecutorService statusPollExecutor;
    private final AtomicReference<String> pendingMotorCmd = new AtomicReference<>(null);
    private final AtomicReference<Integer> pendingServoAngle = new AtomicReference<>(null);
    private final AtomicBoolean motorCmdInFlight = new AtomicBoolean(false);
    private final AtomicBoolean statusReqInFlight = new AtomicBoolean(false);
    private final AtomicBoolean uiFrameRenderPending = new AtomicBoolean(false);
    private volatile long motorPriorityUntilMs = 0L;
    private volatile boolean isDirectionButtonHeld = false;
    private volatile String activeHeldCommand = null;
    private volatile Network cachedWifiNetwork = null;
    private volatile long lastWifiLookupMs = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean autoConnectAttemptedOnResume = false;

    // Repetición automática mientras se mantiene presionado un botón direccional (evita pérdida de toques)
    private final Runnable heldCommandRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isDirectionButtonHeld || activeHeldCommand == null || isFinishing()) return;
            if (isWallAhead && "F".equals(activeHeldCommand)) return;
            enviarComando(activeHeldCommand);
            mainHandler.postDelayed(this, 220L);
        }
    };

    // Confirmación doble de frenado al soltar un botón (garantiza que nunca quede andando solo)
    private final Runnable stopConfirmRunnable = () -> {
        if (!isDirectionButtonHeld && !isFinishing()) {
            enviarComando("S");
        }
    };

    // Seguimiento IA de Objetos Comunes en el Auto (Reutiliza TelloAiObjectTracker TFLite Offline)
    private final TelloAiObjectTracker carAiTracker = new TelloAiObjectTracker();
    private ExecutorService carVisionExecutor;
    private Bitmap carAiFrameBitmap;
    private volatile Bitmap latestMjpegBitmap = null;
    private final TelloAiObjectTracker.AiTrackingOutput carWorkerAiResult = new TelloAiObjectTracker.AiTrackingOutput();
    private volatile boolean carTrackingActive = false;
    private volatile boolean carVisionBusy = false;
    private long lastCarAiServoStepMs = 0L;
    private long lastCarAiMotorStepMs = 0L;

    private final Runnable carAutoStopPulseRunnable = () -> {
        if (!isDirectionButtonHeld && carTrackingActive) {
            enviarComando("S");
        }
    };

    private final Runnable carTrackingLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!carTrackingActive || isFinishing()) return;
            ejecutarCicloVisionAuto();
            mainHandler.postDelayed(this, 90L);
        }
    };

    // Respaldo Bluetooth Clásico SPP (HC-05 / HC-06)
    private final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private BluetoothSocket bluetoothSocket = null;
    private OutputStream outputStream = null;
    private InputStream inputStream = null;
    private Thread btReadThread = null;
    private volatile boolean isBtReading = false;
    private String connectedDeviceName = null;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Vistas HUD y Video
    private ImageView ivEsp32Stream;
    private TextureView cameraTextureView;
    private View cameraOverlayScrim;
    private TrackingOverlayView carTrackingOverlayView;
    private LinearLayout layoutCarVideoPlaceholder;
    private TextView tvCarVideoStatusHint;

    private TextView tvStatus, tvActiveCommand, tvGyroData, tvWallAlertTitle;
    private LinearLayout overlayWallAlert;
    private SwitchCompat switchGyro;
    private Button btnConnect, btnDisconnect, btnToggleTrackingCar, btnToggleCameraCar, btnEmergencyCar;
    private Button btnBackMenu, btnSwitchDrone, btnHelpGuideCar;
    private Button btnForward, btnBackward, btnLeft, btnRight, btnBigStop, btnCalibrateGyro;
    private Button btnServoUp, btnServoCenter, btnServoDown;

    // Modo Volante (Acelerómetro)
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean isGyroActive = false;
    private boolean isCalibrated = false;
    private float zeroPitch = 0f;
    private float zeroRoll = 0f;
    private String lastGyroCommand = "S";
    private long lastSendTime = 0;
    private static final float UMBRAL_INCLINACION = 2.0f;
    private static final float UMBRAL_OBSTACULO_CM = 12.0f;

    // Estado de bloqueo por obstáculo ultrasónico
    private volatile boolean isWallAhead = false;
    private boolean isCameraEnabled = true;

        private final Runnable statusPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isStatusPolling || isFinishing()) return;
            consultarEstadoEsp32Async();
            mainHandler.postDelayed(this, 900L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_landscape);
        activarPantallaInmersiva();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        esp32Ip = prefs.getString(PREF_KEY_ESP32_IP, DEFAULT_ESP32_IP);
        motorMapMode = prefs.getInt(PREF_KEY_MOTOR_MAP_MODE, 2);
        invertServoTilt = prefs.getBoolean(PREF_KEY_SERVO_INVERT, false);

        httpCmdExecutor = Executors.newSingleThreadExecutor();
        statusPollExecutor = Executors.newSingleThreadExecutor();
        carVisionExecutor = Executors.newSingleThreadExecutor();
        carAiFrameBitmap = Bitmap.createBitmap(
                TelloAiObjectTracker.CAPTURE_WIDTH,
                TelloAiObjectTracker.CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
        );
        final Context appCtx = getApplicationContext();
        carVisionExecutor.execute(() -> carAiTracker.initialize(appCtx));

        ivEsp32Stream = findViewById(R.id.ivEsp32Stream);
        cameraTextureView = findViewById(R.id.cameraTextureView);
        cameraOverlayScrim = findViewById(R.id.cameraOverlayScrim);
        carTrackingOverlayView = findViewById(R.id.carTrackingOverlayView);
        layoutCarVideoPlaceholder = findViewById(R.id.layoutCarVideoPlaceholder);
        tvCarVideoStatusHint = findViewById(R.id.tvCarVideoStatusHint);

        tvStatus = findViewById(R.id.tvStatus);
        tvActiveCommand = findViewById(R.id.tvActiveCommand);
        tvGyroData = findViewById(R.id.tvGyroData);
        overlayWallAlert = findViewById(R.id.overlayWallAlert);
        tvWallAlertTitle = findViewById(R.id.tvWallAlertTitle);
        switchGyro = findViewById(R.id.switchGyro);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnToggleTrackingCar = findViewById(R.id.btnToggleTrackingCar);
        btnToggleCameraCar = findViewById(R.id.btnToggleCameraCar);
        btnEmergencyCar = findViewById(R.id.btnEmergencyCar);
        btnBackMenu = findViewById(R.id.btnBackMenu);
        btnSwitchDrone = findViewById(R.id.btnSwitchDrone);
        btnHelpGuideCar = findViewById(R.id.btnHelpGuideCar);

        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnBigStop = findViewById(R.id.btnBigStop);
        btnCalibrateGyro = findViewById(R.id.btnCalibrateGyro);
        btnServoUp = findViewById(R.id.btnServoUp);
        btnServoCenter = findViewById(R.id.btnServoCenter);
        btnServoDown = findViewById(R.id.btnServoDown);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        solicitarPermisos();
        configurarBotonCamaraFpv();
        configurarServoYSeguimientoIaAuto();

        btnConnect.setOnClickListener(v -> {
            if (esp32WifiConnected || isBluetoothConnected()) {
                mostrarDialogoOpcionesConexion();
            } else {
                conectarEsp32Cam(true);
            }
        });

        btnConnect.setOnLongClickListener(v -> {
            mostrarDialogoOpcionesConexion();
            return true;
        });

        btnDisconnect.setOnClickListener(v -> desconectarTodo());
        btnHelpGuideCar.setOnClickListener(v -> mostrarGuiaAutoDialog());

        btnBackMenu.setOnClickListener(v -> finish());
        btnSwitchDrone.setOnClickListener(v -> {
            detenerRecursos();
            Intent intent = new Intent(CarControlActivity.this, DroneControlActivity.class);
            startActivity(intent);
            finish();
        });

        btnCalibrateGyro.setOnClickListener(v -> calibrarCentroGiroscopio());

        // Mandos continuos táctiles: presionar mueve, soltar envía 'S' (Parar)
        configurarBotonContinuo(btnForward, "F", "AVANZANDO (ADELANTE)");
        configurarBotonContinuo(btnBackward, "B", "RETROCEDIENDO (REVERSA)");
        configurarBotonContinuo(btnLeft, "L", "GIRO IZQUIERDA");
        configurarBotonContinuo(btnRight, "R", "GIRO DERECHA");

        btnBigStop.setOnClickListener(v -> {
            desactivarSeguimientoIaAutoSilencioso();
            ejecutarFrenoInmediato("FRENADO (STOP)");
        });
        btnEmergencyCar.setOnClickListener(v -> {
            desactivarSeguimientoIaAutoSilencioso();
            if (isGyroActive) {
                switchGyro.setChecked(false);
            }
            ejecutarFrenoInmediato("¡PARADA TOTAL [S]!");
            Toast.makeText(this, "Parada inmediata enviada [S]", Toast.LENGTH_SHORT).show();
        });

        switchGyro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGyroActive = isChecked;
            if (isChecked) {
                if (accelerometer == null) {
                    Toast.makeText(this, "No se detectó acelerómetro en este celular", Toast.LENGTH_SHORT).show();
                    switchGyro.setChecked(false);
                    return;
                }
                desactivarSeguimientoIaAutoSilencioso();
                isCalibrated = false;
                btnCalibrateGyro.setVisibility(View.VISIBLE);
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
                tvGyroData.setText("Calibrando volante a tu postura actual...");
                habilitarBotonesPad(false);
            } else {
                if (sensorManager != null) {
                    sensorManager.unregisterListener(this);
                }
                btnCalibrateGyro.setVisibility(View.GONE);
                habilitarBotonesPad(!isWallAhead);
                ejecutarFrenoInmediato("FRENADO (STOP)");
                lastGyroCommand = "S";
                isCalibrated = false;
            }
            actualizarHudSuperior();
        });

        actualizarHudSuperior();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            activarPantallaInmersiva();
        }
    }

    private void activarPantallaInmersiva() {
        try {
            WindowInsetsControllerCompat controller =
                    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // 1. CONEXIÓN WI-FI HTTP + STREAM DE VIDEO MJPEG (ESP32-CAM)
    // =========================================================================

    /**
     * Abre una conexión HTTP forzando la interfaz Wi-Fi activa del celular.
     * Fundamental cuando el celular está conectado al AP "AutoRobot_Grupo1" (sin Internet)
     * con los datos móviles 4G/5G encendidos.
     * Mantiene HTTP/1.1 Keep-Alive activo en el puerto 80 para reutilizar un único socket TCP
     * y no agotar el límite max_open_sockets (7) de esp_http_server en la ESP32-CAM.
     */
    private HttpURLConnection abrirConexionHttpPorWifi(String urlStr, int connectTimeoutMs, int readTimeoutMs)
            throws IOException {
        URL url = new URL(urlStr);
        long now = SystemClock.uptimeMillis();
        Network wifiNet = cachedWifiNetwork;
        if (wifiNet == null || (now - lastWifiLookupMs > 3000L)) {
            wifiNet = TelloUdpClient.findActiveWifiNetwork(this);
            cachedWifiNetwork = wifiNet;
            lastWifiLookupMs = now;
            if (wifiNet != null) {
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        cm.bindProcessToNetwork(wifiNet);
                    }
                } catch (Exception ignored) {}
            }
        }
        HttpURLConnection conn;
        if (wifiNet != null) {
            conn = (HttpURLConnection) wifiNet.openConnection(url);
        } else {
            conn = (HttpURLConnection) url.openConnection();
        }
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setUseCaches(false);
        return conn;
    }

    private void conectarEsp32Cam(final boolean mostrarDialogoSiFalla) {
        if (tvCarVideoStatusHint != null && !hasRenderedMjpegFrame) {
            tvCarVideoStatusHint.setText("Conectando con ESP32-CAM en http://" + esp32Ip + " ...");
        }
        tvStatus.setText("WIFI: CONECTANDO A " + esp32Ip + "...");

        if (httpCmdExecutor == null || httpCmdExecutor.isShutdown()) return;
        httpCmdExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = abrirConexionHttpPorWifi("http://" + esp32Ip + "/status", 1800, 1800);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    String body = leerTextoStream(conn.getInputStream());
                    procesarJsonTelemetriaEsp32(body);

                    mainHandler.post(() -> {
                        if (isFinishing()) return;
                        marcarEsp32Conectado();
                        iniciarPollingEstadoEsp32();
                        if (isCameraEnabled) {
                            iniciarStreamVideoMjpeg();
                        }
                        Toast.makeText(this, "ESP32-CAM conectada (" + esp32Ip + ")", Toast.LENGTH_SHORT).show();
                    });
                    return;
                } else {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored2) {}
                }
            }

            mainHandler.post(() -> {
                if (isFinishing()) return;
                actualizarHudSuperior();
                if (tvCarVideoStatusHint != null && !hasRenderedMjpegFrame) {
                    tvCarVideoStatusHint.setText(
                            "Sin respuesta en http://" + esp32Ip + "\n"
                            + "Conéctate al Wi-Fi 'AutoRobot_Grupo1' (clave: 12345678) y toca CONECTAR AUTO");
                }
                if (mostrarDialogoSiFalla) {
                    mostrarDialogoOpcionesConexion();
                }
            });
        });
    }

    private void marcarEsp32Conectado() {
        esp32WifiConnected = true;
        btnConnect.setText("AUTO CONECTADO ✓");
        btnConnect.setBackgroundResource(R.drawable.btn_takeoff_green);
        actualizarHudSuperior();
    }

    private void iniciarPollingEstadoEsp32() {
        if (isStatusPolling) return;
        isStatusPolling = true;
        mainHandler.removeCallbacks(statusPollRunnable);
        mainHandler.post(statusPollRunnable);
    }

    private void detenerPollingEstadoEsp32() {
        isStatusPolling = false;
        mainHandler.removeCallbacks(statusPollRunnable);
    }

    private void consultarEstadoEsp32Async() {
        if (statusPollExecutor == null || statusPollExecutor.isShutdown()) return;
        // No consultar /status mientras se mantiene presionado un mando o recién se envió orden de motor
        if (isDirectionButtonHeld
                || pendingMotorCmd.get() != null
                || motorCmdInFlight.get()
                || SystemClock.uptimeMillis() < motorPriorityUntilMs) {
            return;
        }
        if (!statusReqInFlight.compareAndSet(false, true)) return;

        statusPollExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                if (isDirectionButtonHeld
                        || pendingMotorCmd.get() != null
                        || motorCmdInFlight.get()
                        || SystemClock.uptimeMillis() < motorPriorityUntilMs) {
                    return;
                }
                conn = abrirConexionHttpPorWifi("http://" + esp32Ip + "/status", 500, 500);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    String json = leerTextoStream(conn.getInputStream());
                    procesarJsonTelemetriaEsp32(json);
                } else {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored2) {}
                }
            } finally {
                statusReqInFlight.set(false);
            }
        });
    }

    private void procesarJsonTelemetriaEsp32(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(rawJson);
            final float dist = (float) obj.optDouble("distance", -1.0);
            final boolean obstacle = obj.optBoolean("obstacle", false)
                    || (dist > 0f && dist <= UMBRAL_OBSTACULO_CM);
            if (obj.has("fw")) {
                esp32FirmwareVersion = obj.optInt("fw", 1);
            } else {
                esp32FirmwareVersion = 1;
            }
            if (obj.has("servo")) {
                currentServoAngle = Math.max(SERVO_MIN_DEG, Math.min(SERVO_MAX_DEG, obj.optInt("servo", SERVO_CENTER_DEG)));
            }

            esp32DistanceCm = dist;

            mainHandler.post(() -> {
                if (isFinishing()) return;
                if (!esp32WifiConnected) {
                    marcarEsp32Conectado();
                }
                if (btnServoCenter != null) {
                    btnServoCenter.setText(currentServoAngle + "°");
                }
                if (obstacle) {
                    mostrarAlertaParedCentral();
                } else {
                    ocultarAlertaParedCentral();
                }
                actualizarHudSuperior();
            });
        } catch (Exception ignored) {}
    }

    /**
     * Lector nativo de streaming MJPEG de ultra-baja latencia (http://<IP>:81/stream).
     * Drena el socket TCP sin pausas artificiales (sin Thread.sleep) y descarta cuadros viejos
     * acumulados en el buffer cuando ya llegó un fotograma más reciente.
     */
    private synchronized void iniciarStreamVideoMjpeg() {
        detenerStreamVideoMjpeg();
        if (!isCameraEnabled) return;

        isMjpegStreaming = true;
        uiFrameRenderPending.set(false);
        final String streamUrl = "http://" + esp32Ip + ":81/stream";

        mjpegThread = new Thread(() -> {
            byte[] reusableJpegBuf = new byte[65536];
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565;

            while (isMjpegStreaming && !Thread.currentThread().isInterrupted()) {
                HttpURLConnection conn = null;
                try {
                    conn = abrirConexionHttpPorWifi(streamUrl, 2000, 4000);
                    conn.setRequestMethod("GET");
                    conn.connect();

                    BufferedInputStream bis = new BufferedInputStream(conn.getInputStream(), 32768);
                    int skippedInRow = 0;

                    while (isMjpegStreaming && !Thread.currentThread().isInterrupted()) {
                        int contentLength = leerCabeceraContentLengthMjpeg(bis);
                        Bitmap bmp;

                        if (contentLength > 0 && contentLength < 800000) {
                            if (reusableJpegBuf.length < contentLength) {
                                reusableJpegBuf = new byte[contentLength + 8192];
                            }
                            leerBytesExactos(bis, reusableJpegBuf, contentLength);

                            // Si ya hay otro fotograma esperando en el buffer TCP o la UI sigue ocupada,
                            // saltar la decodificación de este cuadro viejo para mantener latencia cero
                            if ((bis.available() > 512 || uiFrameRenderPending.get()) && skippedInRow < 3) {
                                skippedInRow++;
                                continue;
                            }
                            skippedInRow = 0;
                            bmp = BitmapFactory.decodeByteArray(reusableJpegBuf, 0, contentLength, decodeOpts);
                        } else {
                            byte[] fallbackBytes = leerFrameJpegPorMarcadores(bis);
                            if (fallbackBytes == null) break;
                            if ((bis.available() > 512 || uiFrameRenderPending.get()) && skippedInRow < 3) {
                                skippedInRow++;
                                continue;
                            }
                            skippedInRow = 0;
                            bmp = BitmapFactory.decodeByteArray(fallbackBytes, 0, fallbackBytes.length, decodeOpts);
                        }

                        if (bmp != null && uiFrameRenderPending.compareAndSet(false, true)) {
                            final Bitmap finalBmp = bmp;
                            mainHandler.post(() -> {
                                try {
                                    if (!isMjpegStreaming || isFinishing()) return;
                                    if (!esp32WifiConnected) {
                                        marcarEsp32Conectado();
                                        iniciarPollingEstadoEsp32();
                                    }
                                    if (!hasRenderedMjpegFrame) {
                                        hasRenderedMjpegFrame = true;
                                        if (layoutCarVideoPlaceholder != null) {
                                            layoutCarVideoPlaceholder.setVisibility(View.GONE);
                                        }
                                        if (cameraOverlayScrim != null) {
                                            cameraOverlayScrim.setBackgroundColor(0x00000000);
                                        }
                                    }
                                    ivEsp32Stream.setImageBitmap(finalBmp);
                                    latestMjpegBitmap = finalBmp;
                                    if (carTrackingOverlayView != null && ivEsp32Stream.getWidth() > 0 && ivEsp32Stream.getHeight() > 0) {
                                        float viewAspect = (float) ivEsp32Stream.getWidth() / (float) ivEsp32Stream.getHeight();
                                        float videoAspect = (float) finalBmp.getWidth() / (float) Math.max(1, finalBmp.getHeight());
                                        float sx = 1.0f, sy = 1.0f;
                                        if (viewAspect > videoAspect) {
                                            sx = videoAspect / viewAspect;
                                        } else {
                                            sy = viewAspect / videoAspect;
                                        }
                                        carTrackingOverlayView.setVideoScaleTransform(sx, sy);
                                    }
                                } finally {
                                    uiFrameRenderPending.set(false);
                                }
                            });
                        }
                    }
                } catch (Exception e) {
                    if (!isMjpegStreaming) break;
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException ie) {
                        break;
                    }
                } finally {
                    uiFrameRenderPending.set(false);
                    if (conn != null) {
                        try { conn.disconnect(); } catch (Exception ignored) {}
                    }
                }
            }
        }, "Esp32MjpegThread");
        mjpegThread.setDaemon(true);
        mjpegThread.start();
    }

    private synchronized void detenerStreamVideoMjpeg() {
        isMjpegStreaming = false;
        if (mjpegThread != null) {
            mjpegThread.interrupt();
            mjpegThread = null;
        }
    }

    /**
     * Lee las líneas de cabecera del bloque MJPEG hasta encontrar el salto en blanco (\r\n\r\n)
     * posterior a Content-Length y devuelve el tamaño en bytes del JPEG.
     */
    private static int leerCabeceraContentLengthMjpeg(InputStream in) throws IOException {
        int contentLength = -1;
        StringBuilder line = new StringBuilder(64);
        boolean seenHeader = false;

        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("Fin de stream MJPEG");
            }
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                String s = line.toString().trim();
                line.setLength(0);

                if (s.isEmpty()) {
                    if (seenHeader) {
                        return contentLength;
                    }
                    continue;
                }
                seenHeader = true;
                String lower = s.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(lower.substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                }
            } else {
                line.append((char) b);
                if (line.length() > 512) {
                    line.setLength(0);
                }
            }
        }
    }

    private static void leerBytesExactos(InputStream in, byte[] dest, int len) throws IOException {
        int offset = 0;
        while (offset < len) {
            int read = in.read(dest, offset, len - offset);
            if (read == -1) {
                throw new IOException("Stream cortado a mitad de frame JPEG");
            }
            offset += read;
        }
    }

    private static byte[] leerFrameJpegPorMarcadores(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32768);
        int prev = 0;
        boolean capturing = false;

        while (true) {
            int cur = in.read();
            if (cur == -1) return null;

            if (!capturing) {
                if (prev == 0xFF && cur == 0xD8) {
                    capturing = true;
                    bos.write(0xFF);
                    bos.write(0xD8);
                }
            } else {
                bos.write(cur);
                if (prev == 0xFF && cur == 0xD9) {
                    return bos.toByteArray();
                }
                if (bos.size() > 600000) {
                    return null;
                }
            }
            prev = cur;
        }
    }

    private static String leerTextoStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private void configurarBotonCamaraFpv() {
        if (btnToggleCameraCar == null) return;
        btnToggleCameraCar.setOnClickListener(v -> {
            isCameraEnabled = !isCameraEnabled;
            if (isCameraEnabled) {
                btnToggleCameraCar.setText("CAM ON");
                ivEsp32Stream.setVisibility(View.VISIBLE);
                if (!hasRenderedMjpegFrame && layoutCarVideoPlaceholder != null) {
                    layoutCarVideoPlaceholder.setVisibility(View.VISIBLE);
                }
                iniciarStreamVideoMjpeg();
                Toast.makeText(this, "Stream FPV ESP32-CAM activado (:81/stream)", Toast.LENGTH_SHORT).show();
            } else {
                desactivarSeguimientoIaAutoSilencioso();
                btnToggleCameraCar.setText("CAM OFF");
                detenerStreamVideoMjpeg();
                hasRenderedMjpegFrame = false;
                latestMjpegBitmap = null;
                ivEsp32Stream.setImageDrawable(null);
                ivEsp32Stream.setVisibility(View.INVISIBLE);
                if (layoutCarVideoPlaceholder != null) {
                    layoutCarVideoPlaceholder.setVisibility(View.GONE);
                }
                if (cameraOverlayScrim != null) {
                    cameraOverlayScrim.setBackgroundColor(0xFF050811);
                }
                Toast.makeText(this, "Stream de cámara en pausa", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // =========================================================================
    // 1.5. CONTROL DEL SERVO DE CÁMARA (45° CENTRADO EN PIN 7 ARDUINO) Y SEGUIMIENTO IA
    // =========================================================================

    private void configurarServoYSeguimientoIaAuto() {
        if (btnServoCenter != null) {
            btnServoCenter.setText(SERVO_CENTER_DEG + "°");
        }
        if (btnServoUp != null) {
            btnServoUp.setOnClickListener(v -> {
                desactivarSeguimientoIaAutoSilencioso();
                int step = invertServoTilt ? -SERVO_STEP_DEG : +SERVO_STEP_DEG;
                enviarAnguloServo(currentServoAngle + step, true);
            });
        }
        if (btnServoCenter != null) {
            btnServoCenter.setOnClickListener(v -> {
                desactivarSeguimientoIaAutoSilencioso();
                enviarAnguloServo(SERVO_CENTER_DEG, true);
            });
        }
        if (btnServoDown != null) {
            btnServoDown.setOnClickListener(v -> {
                desactivarSeguimientoIaAutoSilencioso();
                int step = invertServoTilt ? +SERVO_STEP_DEG : -SERVO_STEP_DEG;
                enviarAnguloServo(currentServoAngle + step, true);
            });
        }

        if (carTrackingOverlayView != null) {
            carTrackingOverlayView.setOnTargetTapListener((vNormX, vNormY) -> {
                if (!carTrackingActive) return;
                String lockedObj = carAiTracker.lockObjectAtPoint(vNormX, vNormY);
                if (lockedObj != null) {
                    tvActiveCommand.setText("FIJADO: " + lockedObj);
                    Toast.makeText(this, "Objetivo fijado en Auto: " + lockedObj, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            "Toca el recuadro de un objeto común detectado (Persona, Botella, Pelota, Celular, Vaso, Mochila o Libro)",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnToggleTrackingCar != null) {
            btnToggleTrackingCar.setOnClickListener(v -> {
                if (carTrackingActive) {
                    desactivarSeguimientoIaAutoSilencioso();
                    ejecutarFrenoInmediato("FRENADO (STOP)");
                    Toast.makeText(this, "Seguimiento IA del Auto OFF -> Control Manual", Toast.LENGTH_SHORT).show();
                } else {
                    activarSeguimientoIaAuto();
                }
            });

            btnToggleTrackingCar.setOnLongClickListener(v -> {
                mostrarSelectorObjetoIaAuto();
                return true;
            });
        }
    }

    private void enviarAnguloServo(int targetDeg, boolean mostrarToast) {
        int clamped = Math.max(SERVO_MIN_DEG, Math.min(SERVO_MAX_DEG, targetDeg));
        currentServoAngle = clamped;
        if (btnServoCenter != null) {
            btnServoCenter.setText(clamped + "°");
        }
        motorPriorityUntilMs = SystemClock.uptimeMillis() + 350L;
        pendingServoAngle.set(clamped);
        despacharComandoMotorPendiente();
        if (outputStream != null) {
            final String cmdBtServo = "V" + clamped + "\n";
            new Thread(() -> {
                try {
                    outputStream.write(cmdBtServo.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ignored) {}
            }).start();
        }
        if (mostrarToast) {
            tvActiveCommand.setText("SERVO CÁMARA: " + clamped + "°");
        }
    }

    private void activarSeguimientoIaAuto() {
        if (!isCameraEnabled && btnToggleCameraCar != null) {
            btnToggleCameraCar.performClick();
        }
        if (isGyroActive && switchGyro != null) {
            switchGyro.setChecked(false);
        }
        carAiTracker.resetTrackingState();
        carTrackingActive = true;
        if (carTrackingOverlayView != null) {
            carTrackingOverlayView.setTrackingEnabled(true);
        }
        actualizarEstiloBotonTrackingAuto();
        actualizarHudSuperior();

        mainHandler.removeCallbacks(carTrackingLoopRunnable);
        mainHandler.post(carTrackingLoopRunnable);

        String filtro = carAiTracker.getSelectedFilterShortLabel();
        tvActiveCommand.setText("IA AUTO BUSCANDO [" + filtro + "]");
        Toast.makeText(this,
                "Seguimiento IA activo en Auto (" + filtro + "). El servo mueve la cámara Arriba/Abajo y las ruedas giran/avanzan.",
                Toast.LENGTH_SHORT).show();
    }

    private void desactivarSeguimientoIaAutoSilencioso() {
        if (!carTrackingActive) return;
        carTrackingActive = false;
        mainHandler.removeCallbacks(carTrackingLoopRunnable);
        mainHandler.removeCallbacks(carAutoStopPulseRunnable);
        if (carTrackingOverlayView != null) {
            carTrackingOverlayView.setTrackingEnabled(false);
        }
        actualizarEstiloBotonTrackingAuto();
        actualizarHudSuperior();
    }

    private void actualizarEstiloBotonTrackingAuto() {
        if (btnToggleTrackingCar == null) return;
        if (!carTrackingActive) {
            btnToggleTrackingCar.setText("SEGUIR IA: OFF");
            btnToggleTrackingCar.setTextColor(0xFF38BDF8);
            btnToggleTrackingCar.setBackgroundResource(R.drawable.btn_hover_cyan);
        } else {
            btnToggleTrackingCar.setText("IA: " + carAiTracker.getSelectedFilterShortLabel());
            btnToggleTrackingCar.setTextColor(0xFF4ADE80);
            btnToggleTrackingCar.setBackgroundResource(R.drawable.btn_takeoff_green);
        }
    }

    private void mostrarSelectorObjetoIaAuto() {
        String[] opciones = TelloAiObjectTracker.getFilterMenuLabels();
        int actual = carAiTracker.getSelectedFilterIndex();

        new AlertDialog.Builder(this)
                .setTitle("Elegir Objeto Común a Seguir con el Auto")
                .setSingleChoiceItems(opciones, actual, (dialog, which) -> {
                    carAiTracker.setSelectedFilterIndex(which);
                    dialog.dismiss();
                    if (!carTrackingActive) {
                        activarSeguimientoIaAuto();
                    } else {
                        actualizarEstiloBotonTrackingAuto();
                        Toast.makeText(this,
                                "Filtro IA del Auto: " + carAiTracker.getSelectedFilterShortLabel(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void ejecutarCicloVisionAuto() {
        if (!carTrackingActive) return;

        Bitmap srcBmp = this.latestMjpegBitmap;
        if (!hasRenderedMjpegFrame || srcBmp == null || srcBmp.isRecycled()) {
            TelloAiObjectTracker.AiTrackingOutput idleAi = new TelloAiObjectTracker.AiTrackingOutput();
            idleAi.modelReady = carAiTracker.isInitialized();
            idleAi.targetLocked = false;
            idleAi.filterShortLabelEs = carAiTracker.getSelectedFilterShortLabel();
            if (carTrackingOverlayView != null) {
                carTrackingOverlayView.updateTrackingState(idleAi, true);
            }
            return;
        }

        if (carVisionBusy || carVisionExecutor == null || carVisionExecutor.isShutdown()) {
            return;
        }

        if (carAiFrameBitmap == null || carAiFrameBitmap.isRecycled()) {
            carAiFrameBitmap = Bitmap.createBitmap(
                    TelloAiObjectTracker.CAPTURE_WIDTH,
                    TelloAiObjectTracker.CAPTURE_HEIGHT,
                    Bitmap.Config.ARGB_8888
            );
        }

        try {
            Canvas canvas = new Canvas(carAiFrameBitmap);
            Rect dst = new Rect(0, 0, TelloAiObjectTracker.CAPTURE_WIDTH, TelloAiObjectTracker.CAPTURE_HEIGHT);
            canvas.drawBitmap(srcBmp, null, dst, null);
        } catch (Exception e) {
            return;
        }

        carVisionBusy = true;
        final Context appCtx = getApplicationContext();
        carVisionExecutor.execute(() -> {
            try {
                if (!carAiTracker.isInitialized()) {
                    carAiTracker.initialize(appCtx);
                }
                carAiTracker.processFrame(carAiFrameBitmap, 100, carWorkerAiResult);
                if (!carTrackingActive) return;

                final TelloAiObjectTracker.AiTrackingOutput uiAi = new TelloAiObjectTracker.AiTrackingOutput();
                uiAi.modelReady = carWorkerAiResult.modelReady;
                uiAi.targetLocked = carWorkerAiResult.targetLocked;
                uiAi.searching = carWorkerAiResult.searching;
                uiAi.searchDir = carWorkerAiResult.searchDir;
                uiAi.usedZoomPass = carWorkerAiResult.usedZoomPass;
                uiAi.lockedLabelEs = carWorkerAiResult.lockedLabelEs;
                uiAi.filterShortLabelEs = carWorkerAiResult.filterShortLabelEs;
                uiAi.distanceActionEs = carWorkerAiResult.distanceActionEs;
                uiAi.approachRate = carWorkerAiResult.approachRate;
                uiAi.lockedConfidencePct = carWorkerAiResult.lockedConfidencePct;
                uiAi.inferenceTimeMs = carWorkerAiResult.inferenceTimeMs;
                uiAi.normLeft = carWorkerAiResult.normLeft;
                uiAi.normTop = carWorkerAiResult.normTop;
                uiAi.normRight = carWorkerAiResult.normRight;
                uiAi.normBottom = carWorkerAiResult.normBottom;
                uiAi.normCenterX = carWorkerAiResult.normCenterX;
                uiAi.normCenterY = carWorkerAiResult.normCenterY;
                uiAi.cmdPitchB = carWorkerAiResult.cmdPitchB;
                uiAi.cmdThrottleC = carWorkerAiResult.cmdThrottleC;
                uiAi.cmdYawD = carWorkerAiResult.cmdYawD;
                uiAi.detections.addAll(carWorkerAiResult.detections);

                mainHandler.post(() -> {
                    if (!carTrackingActive || isFinishing()) return;
                    if (carTrackingOverlayView != null) {
                        carTrackingOverlayView.updateTrackingState(uiAi, true);
                    }
                    aplicarControlIaEnAutoYServo(uiAi);
                });
            } finally {
                carVisionBusy = false;
            }
        });
    }

    private void aplicarControlIaEnAutoYServo(TelloAiObjectTracker.AiTrackingOutput uiAi) {
        if (!uiAi.targetLocked) {
            mainHandler.removeCallbacks(carAutoStopPulseRunnable);
            if (uiAi.searching) {
                long now = SystemClock.uptimeMillis();
                if (now - lastCarAiMotorStepMs >= 280L) {
                    lastCarAiMotorStepMs = now;
                    String searchCmd = (uiAi.searchDir < 0) ? "L" : "R";
                    enviarComando(searchCmd);
                    mainHandler.removeCallbacks(carAutoStopPulseRunnable);
                    mainHandler.postDelayed(carAutoStopPulseRunnable, 90L);
                }
                String dirTxt = (uiAi.searchDir > 0) ? "DERECHA →" : "← IZQUIERDA";
                tvActiveCommand.setText("BUSCANDO OBJETO (" + dirTxt + ") | CÁM " + currentServoAngle + "°");
            } else {
                enviarComando("S");
                tvActiveCommand.setText("IA AUTO BUSCANDO [" + uiAi.filterShortLabelEs + "]");
            }
            return;
        }

        long now = SystemClock.uptimeMillis();
        String objName = (uiAi.lockedLabelEs != null && !uiAi.lockedLabelEs.isEmpty())
                ? uiAi.lockedLabelEs : "OBJETO";

        // 1. Eje Vertical (Arriba / Abajo) -> Servo de Cámara (U0T / GPIO 1)
        float errY = 0.50f - uiAi.normCenterY; // >0 objeto arriba en imagen, <0 objeto abajo
        if (Math.abs(errY) > 0.09f && (now - lastCarAiServoStepMs >= 160L)) {
            lastCarAiServoStepMs = now;
            int stepDeg = Math.round(errY * 22.0f);
            if (Math.abs(stepDeg) < 3) stepDeg = (errY > 0) ? 3 : -3;
            if (invertServoTilt) stepDeg = -stepDeg;
            enviarAnguloServo(currentServoAngle + stepDeg, false);
        }

        // 2. Eje Horizontal (Giro L/R) y Distancia (Avance F / Retroceso B) con las ruedas
        if (now - lastCarAiMotorStepMs < 240L) {
            return;
        }
        lastCarAiMotorStepMs = now;

        float errX = uiAi.normCenterX - 0.50f; // <0 izquierda, >0 derecha
        if (Math.abs(errX) > 0.15f) {
            String turnCmd = (errX < 0f) ? "L" : "R";
            enviarComando(turnCmd);
            mainHandler.removeCallbacks(carAutoStopPulseRunnable);
            mainHandler.postDelayed(carAutoStopPulseRunnable, 120L);
            tvActiveCommand.setText("IA SIGUIENDO " + objName + " · GIRO " + turnCmd + " | CÁM " + currentServoAngle + "°");
        } else if (uiAi.cmdPitchB > 0 && !isWallAhead && (esp32DistanceCm < 0f || esp32DistanceCm > 18.0f)) {
            enviarComando("F");
            mainHandler.removeCallbacks(carAutoStopPulseRunnable);
            mainHandler.postDelayed(carAutoStopPulseRunnable, 165L);
            tvActiveCommand.setText("IA SIGUIENDO " + objName + " · AVANZANDO | CÁM " + currentServoAngle + "°");
        } else if (uiAi.cmdPitchB < -12) {
            enviarComando("B");
            mainHandler.removeCallbacks(carAutoStopPulseRunnable);
            mainHandler.postDelayed(carAutoStopPulseRunnable, 140L);
            tvActiveCommand.setText("IA " + objName + " · MANT. DISTANCIA (ATRÁS) | CÁM " + currentServoAngle + "°");
        } else {
            mainHandler.removeCallbacks(carAutoStopPulseRunnable);
            enviarComando("S");
            tvActiveCommand.setText("IA ENFOCANDO " + objName + " (" + uiAi.lockedConfidencePct + "%) | CÁM " + currentServoAngle + "°");
        }
    }

    // =========================================================================
    // 2. ENVÍO UNIFICADO DE COMANDOS (ESP32-CAM HTTP /action?go=X + BLUETOOTH)
    // =========================================================================

    private boolean isBluetoothConnected() {
        return bluetoothSocket != null && bluetoothSocket.isConnected() && outputStream != null;
    }

    /**
     * Traduce el comando lógico de la interfaz ("F", "B", "L", "R", "S") al comando físico
     * esperado por el puente H L298N en el chasis:
     * - En el firmware v1 actualmente grabado en el auto:
     *   'L' = Avanzar (Adelante), 'R' = Retroceder (Atrás), 'B' = Girar Izquierda, 'F' = Girar Derecha.
     * - Si el usuario re-flashea el firmware v2 ("fw":2 en /status), usa el mapeo directo.
     */
    private String traducirComandoMotorEsp32(String cmdLogico) {
        if (cmdLogico == null || "S".equals(cmdLogico)) return "S";
        boolean corregirPolaridadChasis;
        if (motorMapMode == 1) {
            corregirPolaridadChasis = true;
        } else if (motorMapMode == 2) {
            corregirPolaridadChasis = false;
        } else {
            corregirPolaridadChasis = (esp32FirmwareVersion <= 1);
        }

        if (!corregirPolaridadChasis) {
            return cmdLogico;
        }
        switch (cmdLogico) {
            case "F": return "L"; // Adelante físico en el chasis actual
            case "B": return "R"; // Atrás físico en el chasis actual
            case "L": return "B"; // Giro izquierda físico en el chasis actual
            case "R": return "F"; // Giro derecha físico en el chasis actual
            default:  return "S";
        }
    }

    private void ejecutarFrenoInmediato(String etiqueta) {
        isDirectionButtonHeld = false;
        activeHeldCommand = null;
        mainHandler.removeCallbacks(heldCommandRepeatRunnable);
        mainHandler.removeCallbacks(stopConfirmRunnable);
        tvActiveCommand.setText(etiqueta);
        tvActiveCommand.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent));
        mostrarTramaEnMonitor("S");
        enviarComando("S");
        mainHandler.postDelayed(stopConfirmRunnable, 110L);
    }

    private void mostrarTramaEnMonitor(String cmdLogico) {
        String cmdEsp32 = traducirComandoMotorEsp32(cmdLogico);
        if (esp32WifiConnected) {
            tvGyroData.setText(String.format(Locale.US, "HTTP GET /action?go=%s [%s] (%s)", cmdEsp32, cmdLogico, esp32Ip));
        } else if (isBluetoothConnected()) {
            tvGyroData.setText(String.format(Locale.US, "UART BT -> [%s]  (Activo)", cmdLogico));
        } else {
            tvGyroData.setText(String.format(Locale.US, "CMD [%s] -> http://%s/action?go=%s", cmdLogico, esp32Ip, cmdEsp32));
        }
    }

    private void enviarComandoYMostrar(String cmd, String label) {
        tvActiveCommand.setText(label);
        tvActiveCommand.setTextColor(ContextCompat.getColor(this, R.color.green_go));
        mostrarTramaEnMonitor(cmd);
        enviarComando(cmd);
    }

    private void enviarComando(final String cmdLogico) {
        // 1. Prioridad inmediata para el canal HTTP de motores (/action?go=...)
        motorPriorityUntilMs = SystemClock.uptimeMillis() + 450L;
        pendingMotorCmd.set(cmdLogico);
        despacharComandoMotorPendiente();

        // 2. Si además hay un módulo Bluetooth conectado, enviar también por UART SPP
        if (outputStream != null) {
            new Thread(() -> {
                try {
                    outputStream.write(cmdLogico.getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ignored) {}
            }).start();
        }
    }

    private void despacharComandoMotorPendiente() {
        if (httpCmdExecutor == null || httpCmdExecutor.isShutdown()) return;
        if (!motorCmdInFlight.compareAndSet(false, true)) return;

        httpCmdExecutor.execute(() -> {
            try {
                while (true) {
                    String cmdLogico = pendingMotorCmd.getAndSet(null);
                    Integer servoDeg = pendingServoAngle.getAndSet(null);
                    if (cmdLogico == null && servoDeg == null) break;

                    String cmdHttp = (cmdLogico != null) ? traducirComandoMotorEsp32(cmdLogico) : null;
                    boolean enviadoOk = false;

                    for (int intento = 0; intento < 2 && !enviadoOk; intento++) {
                        String masNuevo = pendingMotorCmd.get();
                        if (cmdLogico != null && masNuevo != null && !masNuevo.equals(cmdLogico)) {
                            break;
                        }
                        motorPriorityUntilMs = SystemClock.uptimeMillis() + 400L;
                        HttpURLConnection conn = null;
                        try {
                            StringBuilder urlSb = new StringBuilder("http://").append(esp32Ip).append("/action?");
                            if (cmdHttp != null) {
                                urlSb.append("go=").append(cmdHttp);
                                if (servoDeg != null) {
                                    urlSb.append("&servo=").append(servoDeg);
                                }
                            } else {
                                urlSb.append("servo=").append(servoDeg);
                            }

                            conn = abrirConexionHttpPorWifi(urlSb.toString(), 450, 450);
                            conn.setRequestMethod("GET");
                            int code = conn.getResponseCode();
                            if (code == 200) {
                                String body = leerTextoStream(conn.getInputStream());
                                procesarJsonTelemetriaEsp32(body);
                                enviadoOk = true;
                                final String okLogico = cmdLogico;
                                final String okHttp = cmdHttp;
                                final Integer okServo = servoDeg;
                                mainHandler.post(() -> {
                                    if (!isFinishing() && tvGyroData != null) {
                                        if (okLogico != null) {
                                            tvGyroData.setText(String.format(Locale.US,
                                                    "✓ MOTOR [%s -> go=%s] | SERVO %d° (%s)",
                                                    okLogico, okHttp, currentServoAngle, esp32Ip));
                                        } else if (okServo != null) {
                                            tvGyroData.setText(String.format(Locale.US,
                                                    "✓ SERVO CÁMARA -> %d° (%s)", okServo, esp32Ip));
                                        }
                                    }
                                });
                            } else {
                                conn.disconnect();
                            }
                        } catch (Exception ignored) {
                            if (conn != null) {
                                try { conn.disconnect(); } catch (Exception ignored2) {}
                            }
                        }
                    }
                }
            } finally {
                motorCmdInFlight.set(false);
                if (pendingMotorCmd.get() != null || pendingServoAngle.get() != null) {
                    despacharComandoMotorPendiente();
                }
            }
        });
    }

    private void actualizarHudSuperior() {
        String linkStr;
        if (esp32WifiConnected) {
            linkStr = "WIFI: " + esp32Ip;
        } else if (isBluetoothConnected()) {
            linkStr = "BT: " + (connectedDeviceName != null ? connectedDeviceName : "ON");
        } else {
            linkStr = "AUTO: DESCONECTADO";
        }

        String modoStr = carTrackingActive
                ? ("IA: " + carAiTracker.getSelectedFilterShortLabel())
                : (isGyroActive ? "MODO: VOLANTE" : "MODO: MANUAL");

        String sonarStr;
        if (isWallAhead) {
            sonarStr = esp32DistanceCm > 0
                    ? String.format(Locale.US, "SONAR: %.1f cm (¡FRENO!)", esp32DistanceCm)
                    : String.format(Locale.US, "SONAR: ¡OBSTÁCULO <= %.0f cm!", UMBRAL_OBSTACULO_CM);
        } else if (esp32DistanceCm > 0) {
            sonarStr = String.format(Locale.US, "SONAR: %.1f cm (LIBRE)", esp32DistanceCm);
        } else {
            sonarStr = "SONAR: LIBRE";
        }

        tvStatus.setText(String.format(Locale.US, "%s | %s | %s", linkStr, modoStr, sonarStr));

        if (isWallAhead) {
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red_stop));
        } else if (esp32WifiConnected || isBluetoothConnected()) {
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_go));
        } else {
            tvStatus.setTextColor(0xFFE2E8F0);
        }
    }

    // =========================================================================
    // 3. MANDOS TÁCTILES Y MODO VOLANTE (ACELERÓMETRO)
    // =========================================================================

    private void calibrarCentroGiroscopio() {
        if (!isGyroActive) {
            Toast.makeText(this, "Activa primero el Modo Volante", Toast.LENGTH_SHORT).show();
            return;
        }
        isCalibrated = false;
        Toast.makeText(this, "Volante centrado a tu postura actual", Toast.LENGTH_SHORT).show();
    }

    private void habilitarBotonesPad(boolean habilitar) {
        float alpha = habilitar ? 1.0f : 0.35f;
        btnForward.setEnabled(habilitar);
        btnForward.setAlpha(alpha);
        btnBackward.setEnabled(!isGyroActive);
        btnBackward.setAlpha(isGyroActive ? 0.35f : 1.0f);

        btnLeft.setEnabled(!isGyroActive);
        btnLeft.setAlpha(isGyroActive ? 0.35f : 1.0f);
        btnRight.setEnabled(!isGyroActive);
        btnRight.setAlpha(isGyroActive ? 0.35f : 1.0f);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void configurarBotonContinuo(Button boton, String comando, String labelComando) {
        boton.setOnTouchListener((v, event) -> {
            if (isGyroActive) return false;

            if (isWallAhead && "F".equals(comando)) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    String distReal = formatearDistanciaObstaculo();
                    Toast.makeText(this, "¡Obstáculo a " + distReal + "! Retrocede o gira para despejar.", Toast.LENGTH_SHORT).show();
                    ejecutarFrenoInmediato("¡OBSTÁCULO A " + distReal.toUpperCase(Locale.US) + "!");
                }
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    desactivarSeguimientoIaAutoSilencioso();
                    mainHandler.removeCallbacks(stopConfirmRunnable);
                    mainHandler.removeCallbacks(heldCommandRepeatRunnable);
                    v.setPressed(true);
                    isDirectionButtonHeld = true;
                    activeHeldCommand = comando;
                    enviarComandoYMostrar(comando, labelComando);
                    mainHandler.postDelayed(heldCommandRepeatRunnable, 220L);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    ejecutarFrenoInmediato("FRENADO (STOP)");
                    return true;

                default:
                    return false;
            }
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isGyroActive || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        int rotation = Surface.ROTATION_90;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getDisplay() != null) rotation = getDisplay().getRotation();
        } else {
            rotation = getWindowManager().getDefaultDisplay().getRotation();
        }

        float x = event.values[0];
        float y = event.values[1];
        float rawScreenPitch;
        float rawScreenRoll;

        switch (rotation) {
            case Surface.ROTATION_90:
                rawScreenPitch = -x;
                rawScreenRoll = y;
                break;
            case Surface.ROTATION_270:
                rawScreenPitch = x;
                rawScreenRoll = -y;
                break;
            case Surface.ROTATION_180:
                rawScreenPitch = -y;
                rawScreenRoll = x;
                break;
            case Surface.ROTATION_0:
            default:
                rawScreenPitch = y;
                rawScreenRoll = -x;
                break;
        }

        if (!isCalibrated) {
            zeroPitch = rawScreenPitch;
            zeroRoll = rawScreenRoll;
            isCalibrated = true;
        }

        float deltaPitch = rawScreenPitch - zeroPitch;
        float deltaRoll = rawScreenRoll - zeroRoll;

        String nuevoComando = "S";
        String estadoTexto = "NIVELADO (STOP)";

        if (Math.abs(deltaPitch) > Math.abs(deltaRoll)) {
            if (deltaPitch > UMBRAL_INCLINACION) {
                if (isWallAhead) {
                    nuevoComando = "S";
                    estadoTexto = "¡BLOQUEADO POR OBSTÁCULO!";
                } else {
                    nuevoComando = "F";
                    estadoTexto = "AVANZANDO (INCLINACIÓN)";
                }
            } else if (deltaPitch < -UMBRAL_INCLINACION) {
                nuevoComando = "B";
                estadoTexto = "RETROCEDIENDO (INCLINACIÓN)";
            }
        } else {
            if (deltaRoll < -UMBRAL_INCLINACION) {
                nuevoComando = "L";
                estadoTexto = "VOLANTE IZQUIERDA";
            } else if (deltaRoll > UMBRAL_INCLINACION) {
                nuevoComando = "R";
                estadoTexto = "VOLANTE DERECHA";
            }
        }

        tvGyroData.setText(String.format(Locale.US, "CMD [%s] | Vol: %+.1f | Incl: %+.1f",
                nuevoComando, deltaRoll, deltaPitch));
        tvActiveCommand.setText(estadoTexto);

        long now = System.currentTimeMillis();
        if (!nuevoComando.equals(lastGyroCommand) || (now - lastSendTime > 250)) {
            enviarComando(nuevoComando);
            lastGyroCommand = nuevoComando;
            lastSendTime = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =========================================================================
    // 4. DIÁLOGOS DE CONEXIÓN (ESP32-CAM WI-FI + IP + BLUETOOTH) Y GUÍA
    // =========================================================================

    private void mostrarDialogoOpcionesConexion() {
        String descMapeo;
        if (motorMapMode == 1) {
            descMapeo = "Mapeo Motores: CORREGIDO CHASIS (F→L, B→R, L→B, R→F)";
        } else if (motorMapMode == 2) {
            descMapeo = "Mapeo Motores: DIRECTO (F→F, B→B, L→L, R→R)";
        } else {
            descMapeo = "Mapeo Motores: AUTOMÁTICO ("
                    + (esp32FirmwareVersion <= 1 ? "Chasis F→L, B→R, L→B, R→F" : "Directo v2") + ")";
        }

        String descServo = invertServoTilt
                ? "Dirección Servo Cámara: INVERTIDA [Tocar para normal]"
                : "Dirección Servo Cámara: NORMAL [Tocar para invertir]";

        String[] opciones = new String[]{
                "Conectar / Reconectar a ESP32-CAM (" + esp32Ip + ")",
                "Elegir Objeto Común a Seguir con IA (Actual: " + carAiTracker.getSelectedFilterShortLabel() + ")",
                descServo,
                "Usar IP directa de ESP32-CAM (192.168.4.1 - Red 'AutoRobot_Grupo1')",
                "Cambiar dirección IP de la ESP32-CAM (ej. 192.168.1.XX)",
                descMapeo + " [Tocar para cambiar]",
                "Abrir Ajustes Wi-Fi del Celular",
                "Conectar por Bluetooth Clásico (HC-05 / Arduino)",
                "Desconectar Auto"
        };

        new AlertDialog.Builder(this)
                .setTitle("Conexión y Ajustes del Auto (ESP32-CAM)")
                .setItems(opciones, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            conectarEsp32Cam(false);
                            iniciarStreamVideoMjpeg();
                            break;
                        case 1:
                            mostrarSelectorObjetoIaAuto();
                            break;
                        case 2:
                            invertServoTilt = !invertServoTilt;
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putBoolean(PREF_KEY_SERVO_INVERT, invertServoTilt)
                                    .apply();
                            Toast.makeText(this,
                                    invertServoTilt ? "Servo Arriba/Abajo invertido" : "Servo Arriba/Abajo normal",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            guardarIpEsp32(DEFAULT_ESP32_IP);
                            conectarEsp32Cam(false);
                            iniciarStreamVideoMjpeg();
                            break;
                        case 4:
                            mostrarDialogoConfigurarIpEsp32();
                            break;
                        case 5:
                            motorMapMode = (motorMapMode + 1) % 3;
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit()
                                    .putInt(PREF_KEY_MOTOR_MAP_MODE, motorMapMode)
                                    .apply();
                            String msgModo = (motorMapMode == 1)
                                    ? "Mapeo Chasis F→L, B→R, L→B, R→F activado"
                                    : (motorMapMode == 2 ? "Mapeo Directo F→F, B→B, L→L, R→R activado"
                                    : "Mapeo Automático activado");
                            Toast.makeText(this, msgModo, Toast.LENGTH_SHORT).show();
                            break;
                        case 6:
                            abrirAjustesWifi();
                            break;
                        case 7:
                            mostrarDialogoDispositivosBluetooth();
                            break;
                        case 8:
                            desconectarTodo();
                            break;
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void mostrarDialogoConfigurarIpEsp32() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(esp32Ip);
        input.setSelection(esp32Ip.length());

        new AlertDialog.Builder(this)
                .setTitle("Dirección IP de la ESP32-CAM")
                .setMessage("• Si te conectas a la red Wi-Fi del auto ('AutoRobot_Grupo1'), la IP es siempre 192.168.4.1\n"
                        + "• Si la ESP32-CAM está conectada al router de tu casa, ingresa la IP asignada (ej. 192.168.1.45):")
                .setView(input)
                .setPositiveButton("Guardar y Conectar", (d, w) -> {
                    String nuevaIp = input.getText().toString().trim();
                    if (!nuevaIp.isEmpty()) {
                        guardarIpEsp32(nuevaIp);
                        hasRenderedMjpegFrame = false;
                        conectarEsp32Cam(false);
                        iniciarStreamVideoMjpeg();
                    }
                })
                .setNeutralButton("Usar 192.168.4.1", (d, w) -> {
                    guardarIpEsp32(DEFAULT_ESP32_IP);
                    hasRenderedMjpegFrame = false;
                    conectarEsp32Cam(false);
                    iniciarStreamVideoMjpeg();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void guardarIpEsp32(String nuevaIp) {
        esp32Ip = nuevaIp;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY_ESP32_IP, nuevaIp)
                .apply();
    }

    private void abrirAjustesWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Abre el panel Wi-Fi y conecta a 'AutoRobot_Grupo1'", Toast.LENGTH_LONG).show();
        }
    }

    private void mostrarGuiaAutoDialog() {
        new AlertDialog.Builder(this)
                .setTitle("GUÍA: AUTO ESP32-CAM + ARDUINO UNO + SERVO 45° + IA")
                .setMessage(
                        "1. ARQUITECTURA DUAL WI-FI + SERIAL:\n"
                        + "• ESP32-CAM genera la red 'AutoRobot_Grupo1' (Clave: 12345678, IP: 192.168.4.1), transmite video MJPEG (:81/stream) y envía mandos por Serial (U0T→Pin 2, U0R←Pin 3) al Arduino Uno.\n"
                        + "• Arduino Uno controla el puente H L298N (IN1=9, IN2=8, IN3=11, IN4=10), el sensor HC-SR04 (Trig=5, Echo=6) y el servo SG90 (Pin 7).\n\n"
                        + "2. SERVO DE CÁMARA CALIBRADO A 45° (PIN 7 ARDUINO):\n"
                        + "• Usa los botones '▲CÁM', '45°' y '▼CÁM' arriba de Tracción para inclinar la cámara en su rango seguro (15° a 75°, centro en 45°) con Auto-Detach silencioso.\n\n"
                        + "3. SEGUIMIENTO IA DE OBJETOS COMUNES ('SEGUIR IA'):\n"
                        + "• Toca 'SEGUIR IA' para seguir Persona, Botella, Pelota, Celular, Vaso, Mochila o Libro.\n"
                        + "• Respeta el freno autónomo ultrasónico a <= 12 cm."
                )
                .setPositiveButton("Entendido", null)
                .setNeutralButton("Elegir Objeto IA", (d, w) -> mostrarSelectorObjetoIaAuto())
                .setNegativeButton("Cambiar IP", (d, w) -> mostrarDialogoConfigurarIpEsp32())
                .show();
    }

    // =========================================================================
    // 5. SOPORTE ADICIONAL BLUETOOTH CLÁSICO (HC-05 / ARDUINO)
    // =========================================================================

    @SuppressLint("MissingPermission")
    private void mostrarDialogoDispositivosBluetooth() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Activa primero el Bluetooth del celular", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(this, "No hay módulos Bluetooth vinculados", Toast.LENGTH_LONG).show();
            return;
        }

        final List<BluetoothDevice> deviceList = new ArrayList<>(bondedDevices);
        String[] nombres = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice dev = deviceList.get(i);
            String name = dev.getName() != null ? dev.getName() : "Dispositivo BT";
            nombres[i] = name + "\n" + dev.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Seleccionar Módulo Bluetooth (HC-05)")
                .setItems(nombres, (dialog, which) -> conectarBluetooth(deviceList.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void conectarBluetooth(BluetoothDevice device) {
        String targetName = device.getName() != null ? device.getName() : device.getAddress();
        tvStatus.setText("BT: CONECTANDO A " + targetName + "...");

        new Thread(() -> {
            try {
                desconectarBluetoothSilencioso();
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();
                inputStream = bluetoothSocket.getInputStream();
                connectedDeviceName = targetName;
                iniciarRecepcionBluetooth();

                mainHandler.post(() -> {
                    btnConnect.setText("BT CONECTADO ✓");
                    btnConnect.setBackgroundResource(R.drawable.btn_takeoff_green);
                    actualizarHudSuperior();
                    Toast.makeText(this, "Bluetooth conectado a " + targetName, Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                desconectarBluetoothSilencioso();
                mainHandler.post(() -> {
                    actualizarHudSuperior();
                    Toast.makeText(this, "Error Bluetooth: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void iniciarRecepcionBluetooth() {
        isBtReading = true;
        btReadThread = new Thread(() -> {
            byte[] buffer = new byte[256];
            StringBuilder sb = new StringBuilder();
            while (isBtReading && !Thread.currentThread().isInterrupted()) {
                try {
                    if (inputStream == null) break;
                    int bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        sb.append(new String(buffer, 0, bytes));
                        int n;
                        while ((n = sb.indexOf("\n")) != -1) {
                            String linea = sb.substring(0, n).trim();
                            sb.delete(0, n + 1);
                            if (linea.contains("WALL_DETECTED") || linea.contains("PARED")) {
                                mainHandler.post(this::mostrarAlertaParedCentral);
                            } else if (linea.contains("WALL_CLEAR") || linea.contains("DESPEJADO")) {
                                mainHandler.post(this::ocultarAlertaParedCentral);
                            }
                        }
                    }
                } catch (IOException e) {
                    break;
                }
            }
        });
        btReadThread.start();
    }

    // =========================================================================
    // 6. ALERTA CENTRAL ANTICHOQUE Y CICLO DE VIDA
    // =========================================================================

    private String formatearDistanciaObstaculo() {
        if (esp32DistanceCm > 0f) {
            return String.format(Locale.US, "%.1f cm", esp32DistanceCm);
        }
        return String.format(Locale.US, "<= %.0f cm", UMBRAL_OBSTACULO_CM);
    }

    private void actualizarTextoAlertaObstaculo() {
        String distTexto = formatearDistanciaObstaculo().toUpperCase(Locale.US);
        if (tvWallAlertTitle != null) {
            tvWallAlertTitle.setText("⚠ ¡OBSTÁCULO DETECTADO A " + distTexto + "!");
        }
        tvActiveCommand.setText("¡OBSTÁCULO A " + distTexto + "!");
        tvActiveCommand.setTextColor(ContextCompat.getColor(this, R.color.red_stop));
    }

    private void mostrarAlertaParedCentral() {
        if (isWallAhead) {
            // Seguir actualizando en vivo los centímetros reales mientras siga frente al obstáculo
            actualizarTextoAlertaObstaculo();
            return;
        }
        isWallAhead = true;

        if (overlayWallAlert != null) {
            overlayWallAlert.setVisibility(View.VISIBLE);
        }

        actualizarTextoAlertaObstaculo();
        habilitarBotonesPad(false);
        enviarComando("S");
        actualizarHudSuperior();

        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(450);
                }
            }
        } catch (Exception ignored) {}
    }

    private void ocultarAlertaParedCentral() {
        if (!isWallAhead) return;
        isWallAhead = false;

        if (overlayWallAlert != null) {
            overlayWallAlert.setVisibility(View.GONE);
        }

        tvActiveCommand.setText("FRENADO (STOP)");
        tvActiveCommand.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent));
        habilitarBotonesPad(!isGyroActive);
        actualizarHudSuperior();
    }

    private void desconectarTodo() {
        desactivarSeguimientoIaAutoSilencioso();
        detenerStreamVideoMjpeg();
        detenerPollingEstadoEsp32();
        desconectarBluetoothSilencioso();
        esp32WifiConnected = false;
        hasRenderedMjpegFrame = false;
        latestMjpegBitmap = null;
        esp32DistanceCm = -1f;
        ocultarAlertaParedCentral();
        if (isGyroActive) {
            switchGyro.setChecked(false);
        }
        if (ivEsp32Stream != null) {
            ivEsp32Stream.setImageDrawable(null);
        }
        if (layoutCarVideoPlaceholder != null && isCameraEnabled) {
            layoutCarVideoPlaceholder.setVisibility(View.VISIBLE);
        }
        btnConnect.setText("CONECTAR AUTO");
        btnConnect.setBackgroundResource(R.drawable.btn_blue_header);
        actualizarHudSuperior();
        tvActiveCommand.setText("FRENADO (STOP)");
        tvGyroData.setText("HTTP /action?go=S  (Desconectado)");
    }

    private void desconectarBluetoothSilencioso() {
        try {
            if (outputStream != null) {
                try {
                    outputStream.write("S".getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ignored) {}
            }
            isBtReading = false;
            if (btReadThread != null) {
                btReadThread.interrupt();
                btReadThread = null;
            }
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
        } catch (IOException ignored) {}
        inputStream = null;
        outputStream = null;
        bluetoothSocket = null;
        connectedDeviceName = null;
    }

    private void solicitarPermisos() {
        List<String> permisos = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT);
            permisos.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        List<String> pendientes = new ArrayList<>();
        for (String p : permisos) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                pendientes.add(p);
            }
        }
        if (!pendientes.isEmpty()) {
            ActivityCompat.requestPermissions(this, pendientes.toArray(new String[0]), 101);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activarPantallaInmersiva();
        if (isGyroActive && sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
        if (isCameraEnabled && esp32WifiConnected) {
            iniciarStreamVideoMjpeg();
            iniciarPollingEstadoEsp32();
        } else if (!autoConnectAttemptedOnResume && TelloUdpClient.findActiveWifiNetwork(this) != null) {
            autoConnectAttemptedOnResume = true;
            mainHandler.postDelayed(() -> {
                if (!isFinishing() && !esp32WifiConnected) {
                    conectarEsp32Cam(false);
                    if (isCameraEnabled) {
                        iniciarStreamVideoMjpeg();
                    }
                }
            }, 250L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoConnectAttemptedOnResume = false;
        desactivarSeguimientoIaAutoSilencioso();
        isDirectionButtonHeld = false;
        activeHeldCommand = null;
        enviarComando("S");
        detenerStreamVideoMjpeg();
        detenerPollingEstadoEsp32();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detenerRecursos();
    }

    private void detenerRecursos() {
        desactivarSeguimientoIaAutoSilencioso();
        isDirectionButtonHeld = false;
        activeHeldCommand = null;
        detenerStreamVideoMjpeg();
        detenerPollingEstadoEsp32();
        desconectarBluetoothSilencioso();
        if (carVisionExecutor != null) {
            carVisionExecutor.shutdownNow();
            carVisionExecutor = null;
        }
        carAiTracker.close();
        if (carAiFrameBitmap != null && !carAiFrameBitmap.isRecycled()) {
            carAiFrameBitmap.recycle();
            carAiFrameBitmap = null;
        }
        if (httpCmdExecutor != null) {
            httpCmdExecutor.shutdownNow();
            httpCmdExecutor = null;
        }
        if (statusPollExecutor != null) {
            statusPollExecutor.shutdownNow();
            statusPollExecutor = null;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.bindProcessToNetwork(null);
            }
        } catch (Exception ignored) {}
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }
}
