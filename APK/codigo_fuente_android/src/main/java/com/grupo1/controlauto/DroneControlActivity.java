package com.grupo1.controlauto;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Actividad de Teleoperación Manual Modo 2 + Seguimiento IA de Objetos Comunes (TFLite v3.0):
 *  - Detector IA TFLite 100% offline filtrado a 7 objetos comunes (Persona, Botella, Pelota,
 *    Celular, Vaso, Mochila y Libro), ignorando muebles y fondo.
 *  - Giro automático (Yaw), avance cuando el objeto se aleja y retroceso para mantener distancia
 *    cuando el objeto se acerca.
 *  - Prioridad absoluta e instantánea de los Joysticks Manuales Modo 2 @ 20 Hz sobre el modo autónomo.
 */
public class DroneControlActivity extends AppCompatActivity implements SensorEventListener {

    private TextureView cameraTextureView;
    private View cameraOverlayScrim;
    private TrackingOverlayView trackingOverlayView;
    private LinearLayout layoutVideoPlaceholder;
    private TextView tvVideoStatusHint;

    private TextView tvFlightState, tvActiveActionCommand, tvLastCmdReply, tvActiveSdkCommand;
    private SwitchCompat switchGyroDrone;

    // Joysticks virtuales Modo 2
    private JoystickView joystickLeft, joystickRight;

    // Botones de comandos discretos y cabina
    private Button btnTakeoff, btnHoverStop, btnLand, btnEmergency;
    private Button btnConnectDrone, btnToggleTracking, btnToggleCamera, btnExtraCommands, btnHelpGuide;
    private Button btnSpeedScale, btnCalibrate, btnBackMenu, btnSwitchCar;

    // Clientes UDP (Control + Telemetría + Video H.264)
    private TelloUdpClient telloClient;
    private TelloVideoDecoder videoDecoder;
    private Surface videoSurface;
    private String targetDroneIp = TelloUdpClient.DEFAULT_DRONE_IP;

    // Control de estado de conexión y reintentos
    private boolean userInitiatedConnect = false;
    private boolean takeoffQueuedAfterConnect = false;
    private boolean landQueuedAfterConnect = false;
    private boolean autoConnectAttemptedOnResume = false;
    private int streamAutoRetryCount = 0;

    // Estado de video FPV
    private boolean isStreamEnabled = true;
    private boolean hasRenderedVideoFrame = false;
    private int currentVideoWidth = TelloVideoDecoder.DEFAULT_VIDEO_WIDTH;
    private int currentVideoHeight = TelloVideoDecoder.DEFAULT_VIDEO_HEIGHT;

    // Escala de sticks: 100 (máxima SDK: salida = norm * 100) o 60 (modo suave interior)
    private volatile int stickScale = 100;

    // Valores normalizados actuales de ambos sticks [-1.0, +1.0]
    private volatile float leftYawNorm = 0f;       // d
    private volatile float leftThrottleNorm = 0f;  // c
    private volatile float rightRollNorm = 0f;     // a
    private volatile float rightPitchNorm = 0f;    // b

    // Motor IA TFLite para los 7 objetos comunes (Persona, Botella, Pelota, Celular, Vaso, Mochila, Libro)
    private final TelloAiObjectTracker aiTracker = new TelloAiObjectTracker();
    private final Handler mainUiHandler = new Handler(Looper.getMainLooper());
    private ExecutorService visionExecutor;
    private Bitmap aiFrameBitmap;
    private final TelloAiObjectTracker.AiTrackingOutput workerAiResult = new TelloAiObjectTracker.AiTrackingOutput();

    private volatile boolean objectTrackingActive = false;
    private volatile boolean visionWorkerBusy = false;
    private volatile boolean autoTargetLocked = false;
    private volatile boolean autoSearching = false;
    private volatile String autoLockedLabelEs = "OBJETO";
    private volatile int autoCmdPitchB = 0;
    private volatile int autoCmdThrottleC = 0;
    private volatile int autoCmdYawD = 0;

    // Modo Giroscopio opcional
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private volatile boolean isGyroActive = false;
    private boolean isGyroCalibrated = false;
    private float zeroPitch = 0f;
    private float zeroRoll = 0f;
    private volatile float gyroRollNorm = 0f;
    private volatile float gyroPitchNorm = 0f;
    private static final float GYRO_DEADZONE_MPS2 = 1.3f;
    private static final float GYRO_MAX_TILT_MPS2 = 6.0f;

    // Última telemetría recibida
    private volatile int telemetryBat = -1;
    private volatile int telemetryHeightCm = 0;
    private volatile int telemetryTofCm = -1;
    private volatile int telemetryTempC = -1;

    private final Runnable trackingVisionLoopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!objectTrackingActive || isFinishing()) {
                return;
            }
            ejecutarCicloVisionObjetos();
            mainUiHandler.postDelayed(this, 75L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drone_landscape);
        activarPantallaInmersivaCabina();

        visionExecutor = Executors.newSingleThreadExecutor();
        aiFrameBitmap = Bitmap.createBitmap(
                TelloAiObjectTracker.CAPTURE_WIDTH,
                TelloAiObjectTracker.CAPTURE_HEIGHT,
                Bitmap.Config.ARGB_8888
        );

        // Pre-cargar el modelo TFLite (detector de botella) en segundo plano para respuesta inmediata
        final Context appCtx = getApplicationContext();
        visionExecutor.execute(() -> aiTracker.initialize(appCtx));

        vincularVistas();
        inicializarSensores();
        inicializarClientesUdp();
        configurarSuperficieVideo();
        configurarSeguimientoObjetos();
        configurarJoysticksModo2();
        configurarBotonesDiscretos();
        actualizarHudTelemetria();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            activarPantallaInmersivaCabina();
        }
    }

    private void activarPantallaInmersivaCabina() {
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

    private void vincularVistas() {
        cameraTextureView = findViewById(R.id.cameraTextureView);
        cameraOverlayScrim = findViewById(R.id.cameraOverlayScrim);
        trackingOverlayView = findViewById(R.id.trackingOverlayView);
        layoutVideoPlaceholder = findViewById(R.id.layoutVideoPlaceholder);
        tvVideoStatusHint = findViewById(R.id.tvVideoStatusHint);

        tvFlightState = findViewById(R.id.tvFlightState);
        tvActiveActionCommand = findViewById(R.id.tvActiveActionCommand);
        tvLastCmdReply = findViewById(R.id.tvLastCmdReply);
        tvActiveSdkCommand = findViewById(R.id.tvActiveSdkCommand);

        joystickLeft = findViewById(R.id.joystickLeft);
        joystickRight = findViewById(R.id.joystickRight);

        btnTakeoff = findViewById(R.id.btnTakeoff);
        btnHoverStop = findViewById(R.id.btnHoverStop);
        btnLand = findViewById(R.id.btnLand);
        btnEmergency = findViewById(R.id.btnEmergency);

        btnConnectDrone = findViewById(R.id.btnConnectDrone);
        btnToggleTracking = findViewById(R.id.btnToggleTracking);
        btnToggleCamera = findViewById(R.id.btnToggleCamera);
        btnExtraCommands = findViewById(R.id.btnExtraCommands);
        btnHelpGuide = findViewById(R.id.btnHelpGuide);
        btnSpeedScale = findViewById(R.id.btnSpeedScale);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        btnBackMenu = findViewById(R.id.btnBackMenu);
        btnSwitchCar = findViewById(R.id.btnSwitchCar);
        switchGyroDrone = findViewById(R.id.switchGyroDrone);
    }

    private void inicializarSensores() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    // =========================================================================
    // 1. CLIENTE UDP (COMANDOS + RC 20Hz + TELEMETRÍA) Y DECODIFICADOR H.264
    // =========================================================================

    private void inicializarClientesUdp() {
        telloClient = new TelloUdpClient(this, new TelloUdpClient.Listener() {
            @Override
            public void onConnectionStateChanged(boolean connected, String statusMessage) {
                if (connected) {
                    streamAutoRetryCount = 0;
                    btnConnectDrone.setText("CONECTADO ✓");
                    btnConnectDrone.setBackgroundResource(R.drawable.btn_takeoff_green);
                    tvLastCmdReply.setText("DRON: SDK Listo (" + targetDroneIp + ")");
                    tvLastCmdReply.setTextColor(ContextCompat.getColor(DroneControlActivity.this, R.color.green_go));

                    if (!hasRenderedVideoFrame) {
                        tvVideoStatusHint.setText("Enlace UDP activo (" + targetDroneIp + "). Recibiendo stream H.264...");
                    }
                    Toast.makeText(DroneControlActivity.this, statusMessage, Toast.LENGTH_SHORT).show();

                    if (landQueuedAfterConnect) {
                        landQueuedAfterConnect = false;
                        takeoffQueuedAfterConnect = false;
                        telloClient.sendLand();
                    } else if (takeoffQueuedAfterConnect) {
                        takeoffQueuedAfterConnect = false;
                        ejecutarDespegueDirecto();
                    }
                } else {
                    btnConnectDrone.setText("CONECTAR DRON");
                    btnConnectDrone.setBackgroundResource(R.drawable.btn_blue_header);
                    tvLastCmdReply.setText("DRON: " + statusMessage);
                    tvLastCmdReply.setTextColor(0xFFFBBF24);
                    tvVideoStatusHint.setText(statusMessage + "\nConecta al Wi-Fi TELLO-XXXXXX y pulsa CONECTAR DRON");

                    if (userInitiatedConnect && (statusMessage.startsWith("Sin respuesta") || statusMessage.startsWith("Wi-Fi apagado"))) {
                        userInitiatedConnect = false;
                        takeoffQueuedAfterConnect = false;
                        landQueuedAfterConnect = false;
                        mostrarDialogoAyudaConexionWifi(statusMessage);
                    }
                }
                actualizarHudTelemetria();
            }

            @Override
            public void onDronePoweredOffOrLost(int lastBattery, int lastTempC) {
                desactivarSeguimientoSilencioso();
                videoDecoder.stop();
                hasRenderedVideoFrame = false;
                layoutVideoPlaceholder.setVisibility(View.VISIBLE);
                btnConnectDrone.setText("CONECTAR DRON");
                btnConnectDrone.setBackgroundResource(R.drawable.btn_blue_header);
                tvActiveActionCommand.setText("DRON APAGADO / SIN WI-FI");
                tvLastCmdReply.setText("¡Enlace perdido con el dron!");
                tvLastCmdReply.setTextColor(ContextCompat.getColor(DroneControlActivity.this, R.color.red_stop));
                actualizarHudTelemetria();
                mostrarDialogoDronApagado(lastBattery, lastTempC);
            }

            @Override
            public void onCommandResponse(String commandSent, String response) {
                String msg = String.format(Locale.US, "CMD [%s] -> %s", commandSent, response);
                tvLastCmdReply.setText(msg);

                String lower = response.toLowerCase(Locale.US);
                if (lower.contains("error") || lower.contains("false")) {
                    tvLastCmdReply.setTextColor(ContextCompat.getColor(DroneControlActivity.this, R.color.red_stop));
                } else if (lower.contains("ok") || lower.contains("vuelo")
                        || lower.contains("listo") || lower.contains("en tierra")
                        || lower.contains("sticks")) {
                    tvLastCmdReply.setTextColor(ContextCompat.getColor(DroneControlActivity.this, R.color.green_go));
                    if ("land".equals(commandSent)) {
                        tvActiveActionCommand.setText("EN TIERRA (MOTORES OFF)");
                        tvActiveSdkCommand.setText("land -> ok");
                    } else if ("takeoff".equals(commandSent) && !objectTrackingActive) {
                        tvActiveActionCommand.setText("ESTACIONARIO (HOVER)");
                    }
                } else {
                    tvLastCmdReply.setTextColor(0xFFFBBF24);
                }
            }

            @Override
            public void onTakeoffRejected(String droneError, int batteryPercent) {
                tvActiveActionCommand.setText("DESPEGUE RECHAZADO");
                tvLastCmdReply.setText("CMD [takeoff] -> " + droneError);
                tvLastCmdReply.setTextColor(ContextCompat.getColor(DroneControlActivity.this, R.color.red_stop));
                mostrarDialogoDiagnosticoDespegue(droneError, batteryPercent);
            }

            @Override
            public void onTelemetryUpdated(TelloUdpClient.TelemetryData data) {
                telemetryBat = data.battery;
                telemetryHeightCm = data.heightCm;
                if (data.tofCm > 0) telemetryTofCm = data.tofCm;
                if (data.tempHighC > 0 || data.tempLowC > 0) {
                    telemetryTempC = data.tempHighC > 0 ? data.tempHighC : data.tempLowC;
                }
                actualizarHudTelemetria();
            }

            @Override
            public void onRcSent(int a, int b, int c, int d, String formattedCmd, long txCount) {
                String prefix = "";
                if (objectTrackingActive) {
                    if (autoTargetLocked) {
                        prefix = "IA-" + autoLockedLabelEs + " ";
                    } else if (autoSearching) {
                        prefix = "IA-BUSCA ";
                    }
                }
                tvActiveSdkCommand.setText(String.format(Locale.US, "%s%s  @ 20Hz · TX#%d", prefix, formattedCmd, txCount));
            }
        });

        // Proveedor directo @ 20 Hz: los Sticks Manuales SIEMPRE tienen prioridad inmediata sobre la IA
        telloClient.setRcAxisProvider(outAbcd -> {
            int scale = this.stickScale;
            float rollN = isGyroActive ? gyroRollNorm
                    : (joystickRight != null ? joystickRight.getNormalizedX() : rightRollNorm);
            float pitchN = isGyroActive ? gyroPitchNorm
                    : (joystickRight != null ? joystickRight.getNormalizedY() : rightPitchNorm);
            float throttleN = (joystickLeft != null) ? joystickLeft.getNormalizedY() : leftThrottleNorm;
            float yawN = (joystickLeft != null) ? joystickLeft.getNormalizedX() : leftYawNorm;

            int manA = Math.round(rollN * scale);
            int manB = Math.round(pitchN * scale);
            int manC = Math.round(throttleN * scale);
            int manD = Math.round(yawN * scale);

            boolean manualMoved = (manA != 0 || manB != 0 || manC != 0 || manD != 0);
            if (manualMoved) {
                outAbcd[0] = manA;
                outAbcd[1] = manB;
                outAbcd[2] = manC;
                outAbcd[3] = manD;

                if (objectTrackingActive
                        && (Math.abs(manA) >= 20 || Math.abs(manB) >= 20
                        || Math.abs(manC) >= 20 || Math.abs(manD) >= 20)) {
                    mainUiHandler.post(this::desactivarSeguimientoPorMandoManual);
                }
            } else if (objectTrackingActive && telloClient.isAirborne() && (autoTargetLocked || autoSearching)) {
                outAbcd[0] = 0; // Roll 0 absoluto: la IA nunca se desplaza de costado
                outAbcd[1] = autoTargetLocked ? autoCmdPitchB : 0;
                int thrott = autoTargetLocked ? autoCmdThrottleC : 0;
                // Guarda de seguridad de techo en interiores: no seguir subiendo si ya supera 140 cm
                if (thrott > 0 && (telemetryHeightCm >= 140 || telemetryTofCm >= 140)) {
                    thrott = 0;
                }
                outAbcd[2] = thrott;
                outAbcd[3] = autoCmdYawD;
            } else {
                outAbcd[0] = 0;
                outAbcd[1] = 0;
                outAbcd[2] = 0;
                outAbcd[3] = 0;
            }
        });

        videoDecoder = new TelloVideoDecoder(this, new TelloVideoDecoder.VideoListener() {
            @Override
            public void onFirstFrameRendered(int width, int height) {
                hasRenderedVideoFrame = true;
                currentVideoWidth = width;
                currentVideoHeight = height;
                layoutVideoPlaceholder.setVisibility(View.GONE);
                cameraOverlayScrim.setBackgroundColor(0x00000000);
                aplicarProporcionVideo(cameraTextureView.getWidth(), cameraTextureView.getHeight());
            }

            @Override
            public void onVideoSizeChanged(int width, int height) {
                currentVideoWidth = width;
                currentVideoHeight = height;
                aplicarProporcionVideo(cameraTextureView.getWidth(), cameraTextureView.getHeight());
            }

            @Override
            public void onStreamStalled() {
                if (telloClient != null && telloClient.isConnected()
                        && !telloClient.isCommandInProgress()
                        && isStreamEnabled && !hasRenderedVideoFrame
                        && streamAutoRetryCount < 1) {
                    streamAutoRetryCount++;
                    telloClient.sendStreamOn();
                }
            }
        });
    }

    private void configurarSuperficieVideo() {
        cameraTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                aplicarProporcionVideo(width, height);
                if (videoSurface != null) {
                    videoSurface.release();
                }
                videoSurface = new Surface(surfaceTexture);
                videoDecoder.setSurface(videoSurface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                aplicarProporcionVideo(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                videoDecoder.setSurface(null);
                if (videoSurface != null) {
                    videoSurface.release();
                    videoSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}
        });
    }

    private void aplicarProporcionVideo(int viewWidth, int viewHeight) {
        if (cameraTextureView == null || viewWidth <= 0 || viewHeight <= 0
                || currentVideoWidth <= 0 || currentVideoHeight <= 0) {
            return;
        }
        float viewAspect = (float) viewWidth / (float) viewHeight;
        float videoAspect = (float) currentVideoWidth / (float) currentVideoHeight;

        float scaleX = 1.0f;
        float scaleY = 1.0f;

        if (viewAspect > videoAspect) {
            scaleX = videoAspect / viewAspect;
        } else {
            scaleY = viewAspect / videoAspect;
        }

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        cameraTextureView.setTransform(matrix);

        if (trackingOverlayView != null) {
            trackingOverlayView.setVideoScaleTransform(scaleX, scaleY);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (cameraTextureView != null) {
            cameraTextureView.post(() ->
                    aplicarProporcionVideo(cameraTextureView.getWidth(), cameraTextureView.getHeight()));
        }
    }

    // =========================================================================
    // 1.5. SEGUIMIENTO IA DE OBJETOS COMUNES (GIRO + AVANCE / MANTENER DISTANCIA)
    // =========================================================================

    private void configurarSeguimientoObjetos() {
        trackingOverlayView.setOnTargetTapListener((vNormX, vNormY) -> {
            if (!objectTrackingActive) return;

            if (!hasRenderedVideoFrame || cameraTextureView == null || !cameraTextureView.isAvailable()) {
                Toast.makeText(this,
                        "Conecta el dron primero para ver el video FPV en vivo",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            String lockedObj = aiTracker.lockObjectAtPoint(vNormX, vNormY);
            if (lockedObj != null) {
                tvActiveActionCommand.setText("FIJADO: " + lockedObj);
                Toast.makeText(this, "Objetivo fijado y distancia calibrada: " + lockedObj, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Toca el recuadro de un objeto común detectado (Persona, Botella, Pelota, Celular, Vaso, Mochila o Libro)",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void activarSeguimientoObjetos() {
        if (!isStreamEnabled) {
            btnToggleCamera.performClick();
        }
        aiTracker.resetTrackingState();
        objectTrackingActive = true;
        autoTargetLocked = false;
        autoSearching = false;
        autoLockedLabelEs = "OBJETO";
        autoCmdPitchB = 0;
        autoCmdThrottleC = 0;
        autoCmdYawD = 0;

        trackingOverlayView.setTrackingEnabled(true);
        actualizarEstiloBotonTracking();

        mainUiHandler.removeCallbacks(trackingVisionLoopRunnable);
        mainUiHandler.post(trackingVisionLoopRunnable);

        String filtro = aiTracker.getSelectedFilterShortLabel();
        tvActiveActionCommand.setText("IA BUSCANDO [" + filtro + "]");
        Toast.makeText(this,
                "Seguimiento IA activo (" + filtro + "). Mantén presionado el botón para filtrar un objeto específico.",
                Toast.LENGTH_SHORT).show();
    }

    private void mostrarSelectorObjetoIa() {
        String[] opciones = TelloAiObjectTracker.getFilterMenuLabels();
        int actual = aiTracker.getSelectedFilterIndex();

        new AlertDialog.Builder(this)
                .setTitle("Elegir Objeto Común a Seguir con IA")
                .setSingleChoiceItems(opciones, actual, (dialog, which) -> {
                    aiTracker.setSelectedFilterIndex(which);
                    dialog.dismiss();
                    if (!objectTrackingActive) {
                        activarSeguimientoObjetos();
                    } else {
                        autoTargetLocked = false;
                        autoSearching = false;
                        autoCmdPitchB = 0;
                        autoCmdThrottleC = 0;
                        autoCmdYawD = 0;
                        actualizarEstiloBotonTracking();
                        Toast.makeText(this,
                                "Filtro IA actualizado: " + aiTracker.getSelectedFilterShortLabel(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void desactivarSeguimientoSilencioso() {
        objectTrackingActive = false;
        autoTargetLocked = false;
        autoSearching = false;
        autoCmdPitchB = 0;
        autoCmdThrottleC = 0;
        autoCmdYawD = 0;
        mainUiHandler.removeCallbacks(trackingVisionLoopRunnable);
        if (trackingOverlayView != null) {
            trackingOverlayView.setTrackingEnabled(false);
        }
        actualizarEstiloBotonTracking();
    }

    private void desactivarSeguimientoPorMandoManual() {
        if (!objectTrackingActive) return;
        desactivarSeguimientoSilencioso();
        Toast.makeText(this, "Control manual retomado (Seguimiento IA OFF)", Toast.LENGTH_SHORT).show();
    }

    private void actualizarEstiloBotonTracking() {
        if (btnToggleTracking == null) return;
        if (!objectTrackingActive) {
            btnToggleTracking.setText("SEGUIR IA: OFF");
            btnToggleTracking.setTextColor(0xFF38BDF8);
            btnToggleTracking.setBackgroundResource(R.drawable.btn_hover_cyan);
        } else {
            btnToggleTracking.setText("IA: " + aiTracker.getSelectedFilterShortLabel());
            btnToggleTracking.setTextColor(0xFF4ADE80);
            btnToggleTracking.setBackgroundResource(R.drawable.btn_takeoff_green);
        }
    }

    private void ejecutarCicloVisionObjetos() {
        if (!objectTrackingActive) return;

        boolean airborneNow = (telloClient != null && telloClient.isAirborne());

        if (!hasRenderedVideoFrame || cameraTextureView == null || !cameraTextureView.isAvailable()) {
            autoTargetLocked = false;
            autoSearching = false;
            autoCmdPitchB = 0;
            autoCmdThrottleC = 0;
            autoCmdYawD = 0;
            TelloAiObjectTracker.AiTrackingOutput idleAi = new TelloAiObjectTracker.AiTrackingOutput();
            idleAi.modelReady = aiTracker.isInitialized();
            idleAi.targetLocked = false;
            idleAi.searching = false;
            idleAi.filterShortLabelEs = aiTracker.getSelectedFilterShortLabel();
            trackingOverlayView.updateTrackingState(idleAi, airborneNow);
            return;
        }

        if (visionWorkerBusy || visionExecutor == null || visionExecutor.isShutdown()) {
            return;
        }

        final int currentTof = (this.telemetryHeightCm > 0) ? this.telemetryHeightCm : this.telemetryTofCm;
        if (aiFrameBitmap == null || aiFrameBitmap.isRecycled()) {
            aiFrameBitmap = Bitmap.createBitmap(
                    TelloAiObjectTracker.CAPTURE_WIDTH,
                    TelloAiObjectTracker.CAPTURE_HEIGHT,
                    Bitmap.Config.ARGB_8888
            );
        }
        Bitmap captured = cameraTextureView.getBitmap(aiFrameBitmap);
        if (captured == null) return;

        visionWorkerBusy = true;
        final Context appCtx = getApplicationContext();
        visionExecutor.execute(() -> {
            try {
                if (!aiTracker.isInitialized()) {
                    aiTracker.initialize(appCtx);
                }
                aiTracker.processFrame(aiFrameBitmap, currentTof, workerAiResult);

                if (!objectTrackingActive) return;

                autoTargetLocked = workerAiResult.targetLocked;
                autoSearching = workerAiResult.searching;
                if (workerAiResult.targetLocked) {
                    autoLockedLabelEs = (workerAiResult.lockedLabelEs != null && !workerAiResult.lockedLabelEs.isEmpty())
                            ? workerAiResult.lockedLabelEs : "OBJETO";
                    autoCmdPitchB = workerAiResult.cmdPitchB;
                    autoCmdThrottleC = workerAiResult.cmdThrottleC;
                    autoCmdYawD = workerAiResult.cmdYawD;
                } else if (workerAiResult.searching) {
                    autoCmdPitchB = 0;
                    autoCmdThrottleC = 0;
                    autoCmdYawD = workerAiResult.cmdYawD;
                } else {
                    autoCmdPitchB = 0;
                    autoCmdThrottleC = 0;
                    autoCmdYawD = 0;
                }

                final TelloAiObjectTracker.AiTrackingOutput uiAi = new TelloAiObjectTracker.AiTrackingOutput();
                uiAi.modelReady = workerAiResult.modelReady;
                uiAi.targetLocked = workerAiResult.targetLocked;
                uiAi.searching = workerAiResult.searching;
                uiAi.searchDir = workerAiResult.searchDir;
                uiAi.usedZoomPass = workerAiResult.usedZoomPass;
                uiAi.lockedLabelEs = workerAiResult.lockedLabelEs;
                uiAi.filterShortLabelEs = workerAiResult.filterShortLabelEs;
                uiAi.distanceActionEs = workerAiResult.distanceActionEs;
                uiAi.approachRate = workerAiResult.approachRate;
                uiAi.lockedConfidencePct = workerAiResult.lockedConfidencePct;
                uiAi.inferenceTimeMs = workerAiResult.inferenceTimeMs;
                uiAi.normLeft = workerAiResult.normLeft;
                uiAi.normTop = workerAiResult.normTop;
                uiAi.normRight = workerAiResult.normRight;
                uiAi.normBottom = workerAiResult.normBottom;
                uiAi.normCenterX = workerAiResult.normCenterX;
                uiAi.normCenterY = workerAiResult.normCenterY;
                uiAi.cmdPitchB = workerAiResult.cmdPitchB;
                uiAi.cmdThrottleC = workerAiResult.cmdThrottleC;
                uiAi.cmdYawD = workerAiResult.cmdYawD;
                uiAi.detections.addAll(workerAiResult.detections);

                mainUiHandler.post(() -> {
                    if (!objectTrackingActive || isFinishing()) return;
                    boolean flying = telloClient != null && telloClient.isAirborne();
                    trackingOverlayView.updateTrackingState(uiAi, flying);

                    if (telloClient != null && !telloClient.isCommandInProgress()) {
                        if (uiAi.targetLocked) {
                            String objName = (uiAi.lockedLabelEs != null && !uiAi.lockedLabelEs.isEmpty())
                                    ? uiAi.lockedLabelEs : "OBJETO";
                            if (flying) {
                                StringBuilder actionSb = new StringBuilder("SIGUIENDO ").append(objName);
                                if (uiAi.cmdPitchB > 0) {
                                    actionSb.append(" · AVANZANDO");
                                } else if (uiAi.cmdPitchB < 0) {
                                    actionSb.append(" · MANT. DISTANCIA (ATRÁS)");
                                } else if (uiAi.cmdYawD != 0) {
                                    actionSb.append(uiAi.cmdYawD > 0 ? " · GIRANDO DERECHA" : " · GIRANDO IZQUIERDA");
                                } else {
                                    actionSb.append(" · FIJADO (").append(uiAi.lockedConfidencePct).append("%)");
                                }
                                tvActiveActionCommand.setText(actionSb.toString());
                            } else {
                                tvActiveActionCommand.setText(objName + " DETECTADO ("
                                        + uiAi.lockedConfidencePct + "%)");
                            }
                        } else if (uiAi.searching) {
                            String dirTxt = (uiAi.searchDir > 0) ? "DERECHA →" : "← IZQUIERDA";
                            tvActiveActionCommand.setText("BUSCANDO OBJETO (" + dirTxt + ")");
                        } else {
                            tvActiveActionCommand.setText("IA BUSCANDO [" + uiAi.filterShortLabelEs + "] (HOVER)");
                        }
                    }
                });
            } finally {
                visionWorkerBusy = false;
            }
        });
    }

    // =========================================================================
    // 2. FLUJO DE CONTROL MANUAL: MAPEO DE STICKS TÁCTILES MODO 2 A "rc a b c d"
    // =========================================================================

    private void configurarJoysticksModo2() {
        joystickLeft.setOnJoystickMoveListener((normX, normY) -> {
            leftYawNorm = normX;
            leftThrottleNorm = normY;
            recalcularYActualizarRc();
        });

        joystickRight.setOnJoystickMoveListener((normX, normY) -> {
            rightRollNorm = normX;
            rightPitchNorm = normY;
            recalcularYActualizarRc();
        });
    }

    private void recalcularYActualizarRc() {
        float activeRollNorm = isGyroActive ? gyroRollNorm : rightRollNorm;
        float activePitchNorm = isGyroActive ? gyroPitchNorm : rightPitchNorm;

        int a = Math.round(activeRollNorm * stickScale);
        int b = Math.round(activePitchNorm * stickScale);
        int c = Math.round(leftThrottleNorm * stickScale);
        int d = Math.round(leftYawNorm * stickScale);

        telloClient.updateRcValues(a, b, c, d);
        if (!objectTrackingActive || a != 0 || b != 0 || c != 0 || d != 0) {
            actualizarEtiquetaManiobra(a, b, c, d);
        }
    }

    private void actualizarEtiquetaManiobra(int a, int b, int c, int d) {
        if (a == 0 && b == 0 && c == 0 && d == 0) {
            if (!telloClient.isCommandInProgress() && !objectTrackingActive) {
                tvActiveActionCommand.setText("ESTACIONARIO (HOVER)");
            }
            if (!objectTrackingActive) {
                tvActiveSdkCommand.setText("rc 0 0 0 0  @ 20Hz");
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (c > 0) sb.append("SUBIR ");
        else if (c < 0) sb.append("BAJAR ");

        if (b > 0) sb.append("ADELANTE ");
        else if (b < 0) sb.append("ATRÁS ");

        if (a > 0) sb.append("DERECHA ");
        else if (a < 0) sb.append("IZQUIERDA ");

        if (d > 0) sb.append("GIRO↻ ");
        else if (d < 0) sb.append("GIRO↺ ");

        tvActiveActionCommand.setText(sb.toString().trim());
        tvActiveSdkCommand.setText(String.format(Locale.US, "rc %d %d %d %d  @ 20Hz", a, b, c, d));
    }

    // =========================================================================
    // 3. COMANDOS DISCRETOS UDP (TAKEOFF, LAND, EMERGENCY, HOVER, BOTELLA, STREAM)
    // =========================================================================

    private void configurarBotonesDiscretos() {
        btnConnectDrone.setOnClickListener(v -> {
            if (telloClient.isConnected()) {
                mostrarDialogoDesconectar();
            } else {
                userInitiatedConnect = true;
                takeoffQueuedAfterConnect = false;
                conectarAlDron();
            }
        });

        btnConnectDrone.setOnLongClickListener(v -> {
            mostrarDialogoConfigurarIp();
            return true;
        });

        // Botón directo 1-toque: activa/desactiva seguimiento IA; mantener presionado permite filtrar objeto
        btnToggleTracking.setOnClickListener(v -> {
            if (objectTrackingActive) {
                desactivarSeguimientoSilencioso();
                tvActiveActionCommand.setText("ESTACIONARIO (HOVER)");
                Toast.makeText(this, "Seguimiento IA OFF -> Control Manual", Toast.LENGTH_SHORT).show();
            } else {
                activarSeguimientoObjetos();
            }
        });

        btnToggleTracking.setOnLongClickListener(v -> {
            mostrarSelectorObjetoIa();
            return true;
        });

        // DESPEGAR (takeoff)
        btnTakeoff.setOnClickListener(v -> {
            if (!telloClient.isConnected()) {
                userInitiatedConnect = true;
                takeoffQueuedAfterConnect = true;
                tvActiveActionCommand.setText("CONECTANDO Y DESPEGANDO...");
                conectarAlDron();
                return;
            }
            ejecutarDespegueDirecto();
        });

        // HOVER / FRENO EN EL AIRE
        btnHoverStop.setOnClickListener(v -> {
            desactivarSeguimientoSilencioso();
            resetearSticks();
            tvActiveActionCommand.setText("FRENADO / HOVER (stop)");
            if (telloClient.isConnected()) {
                telloClient.sendHoverStop();
            }
        });

        // ATERRIZAR
        btnLand.setOnClickListener(v -> {
            desactivarSeguimientoSilencioso();
            resetearSticks();
            tvActiveActionCommand.setText("ATERRIZANDO (land)");
            tvActiveSdkCommand.setText("land");
            if (telloClient.isConnected()) {
                telloClient.sendLand();
                Toast.makeText(this, "Aterrizando dron...", Toast.LENGTH_SHORT).show();
            } else {
                userInitiatedConnect = true;
                landQueuedAfterConnect = true;
                takeoffQueuedAfterConnect = false;
                conectarAlDron();
                Toast.makeText(this, "Enlazando UDP y aterrizando...", Toast.LENGTH_SHORT).show();
            }
        });

        // CORTE DE EMERGENCIA
        btnEmergency.setOnClickListener(v -> {
            desactivarSeguimientoSilencioso();
            resetearSticks();
            tvActiveActionCommand.setText("¡PARADA DE EMERGENCIA!");
            telloClient.sendEmergencyStop();
            Toast.makeText(this, "¡EMERGENCIA! Corte inmediato de motores enviado", Toast.LENGTH_LONG).show();
        });

        // Cámara FPV H.264
        btnToggleCamera.setOnClickListener(v -> {
            isStreamEnabled = !isStreamEnabled;
            if (isStreamEnabled) {
                btnToggleCamera.setText("CAM ON");
                cameraTextureView.setVisibility(View.VISIBLE);
                if (!hasRenderedVideoFrame) {
                    layoutVideoPlaceholder.setVisibility(View.VISIBLE);
                }
                if (videoSurface != null && videoSurface.isValid()) {
                    videoDecoder.setSurface(videoSurface);
                }
                videoDecoder.restart();
                if (telloClient.isConnected()) {
                    telloClient.sendStreamOn();
                }
                Toast.makeText(this, "Stream FPV H.264 activado (streamon)", Toast.LENGTH_SHORT).show();
            } else {
                desactivarSeguimientoSilencioso();
                btnToggleCamera.setText("CAM OFF");
                if (telloClient.isConnected()) {
                    telloClient.sendStreamOff();
                }
                videoDecoder.stop();
                hasRenderedVideoFrame = false;
                cameraTextureView.setVisibility(View.INVISIBLE);
                layoutVideoPlaceholder.setVisibility(View.GONE);
                cameraOverlayScrim.setBackgroundColor(0xFF050811);
                Toast.makeText(this, "Cámara FPV apagada (streamoff)", Toast.LENGTH_SHORT).show();
            }
        });

        btnExtraCommands.setOnClickListener(v -> mostrarMenuComandosExtra());

        btnSpeedScale.setOnClickListener(v -> {
            stickScale = (stickScale == 100) ? 60 : 100;
            btnSpeedScale.setText("Escala: " + stickScale + "%");
            recalcularYActualizarRc();
            if (telloClient.isConnected()) {
                telloClient.sendSpeed(stickScale);
            }
            String modo = (stickScale == 100)
                    ? "Escala 100% (Potencia máxima RC: -100 a +100)"
                    : "Escala 60% (Modo suave para interior / aula)";
            Toast.makeText(this, modo, Toast.LENGTH_SHORT).show();
        });

        btnHelpGuide.setOnClickListener(v -> mostrarGuiaConexionYVuelo());

        switchGyroDrone.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGyroActive = isChecked;
            if (isChecked) {
                if (accelerometer == null) {
                    Toast.makeText(this, "Este dispositivo no tiene acelerómetro", Toast.LENGTH_SHORT).show();
                    switchGyroDrone.setChecked(false);
                    return;
                }
                desactivarSeguimientoSilencioso();
                isGyroCalibrated = false;
                btnCalibrate.setVisibility(View.VISIBLE);
                joystickRight.setAlpha(0.35f);
                joystickRight.setEnabled(false);
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
                Toast.makeText(this, "Giroscopio activo: inclina el celular para Avance/Lateral", Toast.LENGTH_SHORT).show();
            } else {
                if (sensorManager != null) {
                    sensorManager.unregisterListener(this);
                }
                isGyroCalibrated = false;
                gyroRollNorm = 0f;
                gyroPitchNorm = 0f;
                btnCalibrate.setVisibility(View.GONE);
                joystickRight.setAlpha(1.0f);
                joystickRight.setEnabled(true);
                recalcularYActualizarRc();
                Toast.makeText(this, "Control 100% Manual por Sticks Modo 2", Toast.LENGTH_SHORT).show();
            }
        });

        btnCalibrate.setOnClickListener(v -> {
            isGyroCalibrated = false;
            Toast.makeText(this, "Centro del giroscopio recalibrado", Toast.LENGTH_SHORT).show();
        });

        btnBackMenu.setOnClickListener(v -> finish());

        btnSwitchCar.setOnClickListener(v -> {
            detenerTodo();
            Intent intent = new Intent(this, CarControlActivity.class);
            startActivity(intent);
            finish();
        });
    }

    private void ejecutarDespegueDirecto() {
        if (telemetryBat >= 0 && telemetryBat <= 10) {
            mostrarDialogoDiagnosticoDespegue("error Low battery (" + telemetryBat + "%)", telemetryBat);
            return;
        }
        resetearSticks();
        tvActiveActionCommand.setText("DESPEGANDO... (4-5 seg)");
        tvLastCmdReply.setText("CMD [takeoff] -> Iniciando motores...");
        tvLastCmdReply.setTextColor(0xFFFBBF24);
        telloClient.sendTakeoff();
        Toast.makeText(this, "Enviando takeoff: espera ~4.5 s al cartel verde de Sticks Activos...", Toast.LENGTH_SHORT).show();
    }

    private void conectarAlDron() {
        streamAutoRetryCount = 0;
        if (isStreamEnabled) {
            hasRenderedVideoFrame = false;
            layoutVideoPlaceholder.setVisibility(View.VISIBLE);
            if (videoSurface != null && videoSurface.isValid()) {
                videoDecoder.setSurface(videoSurface);
            }
            videoDecoder.restart();
        }
        tvVideoStatusHint.setText("Conectando con el dron en " + targetDroneIp + ":8889...");
        tvLastCmdReply.setText("DRON: Conectando a " + targetDroneIp + ":8889...");
        tvLastCmdReply.setTextColor(0xFFFBBF24);
        telloClient.connect(targetDroneIp);
    }

    private boolean verificarConectadoAntesDeComando() {
        if (telloClient.isConnected()) {
            return true;
        }
        userInitiatedConnect = true;
        conectarAlDron();
        Toast.makeText(this, "Conectando al dron primero...", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void resetearSticks() {
        leftYawNorm = 0f;
        leftThrottleNorm = 0f;
        rightRollNorm = 0f;
        rightPitchNorm = 0f;
        gyroRollNorm = 0f;
        gyroPitchNorm = 0f;
        if (joystickLeft != null) joystickLeft.resetStick();
        if (joystickRight != null) joystickRight.resetStick();
        if (telloClient != null) telloClient.resetRcAxes();
    }

    private void actualizarHudTelemetria() {
        String link = (telloClient != null && telloClient.isConnected()) ? "UDP: ON" : "UDP: OFF";
        String batStr = telemetryBat >= 0 ? (telemetryBat + "%") : "--%";
        String hStr = telemetryHeightCm + " cm";
        String tofStr = (telemetryTofCm >= 0 && telemetryTofCm < 6500) ? (telemetryTofCm + " cm") : "-- cm";
        String tempStr = telemetryTempC >= 0 ? (telemetryTempC + "°C") : "--°C";

        tvFlightState.setText(String.format(Locale.US,
                "%s | BAT: %s | H: %s | TOF: %s | TEMP: %s",
                link, batStr, hStr, tofStr, tempStr));

        if ((telemetryBat >= 0 && telemetryBat <= 20) || telemetryTempC >= 83) {
            tvFlightState.setTextColor(ContextCompat.getColor(this, R.color.red_stop));
        } else if (telloClient != null && telloClient.isConnected()) {
            tvFlightState.setTextColor(ContextCompat.getColor(this, R.color.green_go));
        } else {
            tvFlightState.setTextColor(0xFFE2E8F0);
        }
    }

    private void mostrarDialogoDronApagado(int lastBat, int lastTempC) {
        if (isFinishing()) return;
        String causaProbable;
        if (lastBat >= 0 && lastBat <= 30) {
            causaProbable = "• CAUSA DETECTADA — BATERÍA BAJA (" + lastBat + "%):\n"
                    + "Al acelerar los 4 motores para despegar, el voltaje de la batería cayó por debajo del mínimo y el dron se apagó por protección. Pon a cargar la batería al 100%.\n\n";
        } else if (lastTempC >= 80) {
            causaProbable = "• CAUSA DETECTADA — SOBRECALENTAMIENTO (" + lastTempC + "°C):\n"
                    + "El Tello no tiene ventilador interno y solo se enfría con el aire de sus hélices. Si queda encendido varios minutos en tierra supera los 85°C y se apaga solo. Déjalo enfriar 3 minutos apagado y despega apenas lo enciendas.\n\n";
        } else {
            causaProbable = "• Última telemetría antes de cortarse: BAT="
                    + (lastBat >= 0 ? lastBat + "%" : "N/D") + " | TEMP="
                    + (lastTempC >= 0 ? lastTempC + "°C" : "N/D") + "\n\n"
                    + "Posibles motivos si el dron se apagó por completo:\n"
                    + "1. Caída de voltaje de la batería al exigir los 4 motores (cárgala al 100%).\n"
                    + "2. Sobrecalentamiento por estar encendido en la mesa sin volar.\n"
                    + "3. Tu celular cambió automáticamente a tu Wi-Fi de casa o Datos Móviles.\n\n";
        }

        new AlertDialog.Builder(this)
                .setTitle("Se perdió la conexión con el Dron")
                .setMessage(causaProbable + "Enciende nuevamente el dron, verifica estar en la red Wi-Fi TELLO-XXXXXX y pulsa 'Reconectar'.")
                .setPositiveButton("Reconectar", (d, w) -> {
                    userInitiatedConnect = true;
                    conectarAlDron();
                })
                .setNeutralButton("Abrir Wi-Fi", (d, w) -> abrirAjustesWifiAndroid())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // =========================================================================
    // 4. DIAGNÓSTICO DE ERRORES DEL DRON Y COMANDOS DE RESPALDO
    // =========================================================================

    private void mostrarDialogoDiagnosticoDespegue(String respuestaDron, int bat) {
        String detalleBateria = (bat >= 0 && bat <= 15)
                ? ("• BATERÍA CRÍTICA (" + bat + "%): El firmware del Tello bloquea el despegue cuando la batería baja del 15%. Pon a cargar el dron.\n\n")
                : (bat > 15 ? ("• Batería actual: " + bat + "% (OK).\n") : "");

        new AlertDialog.Builder(this)
                .setTitle("El Dron rechazó el despegue (" + respuestaDron + ")")
                .setMessage(
                        "El dron está conectado pero devolvió: [" + respuestaDron + "]\n\n"
                        + detalleBateria
                        + "Causas más frecuentes en el Tello:\n"
                        + "1. Poca luz ambiental / piso muy oscuro o brillante (el sensor óptico inferior necesita buena luz encendida).\n"
                        + "2. El dron no está apoyado quieto en piso plano al encenderse.\n"
                        + "3. Batería por debajo del 15% o hélices invertidas.\n\n"
                        + "Si hay poca luz y la batería está cargada, puedes usar 'Armar Motores Manual (CSC)' y luego subir suave el Stick Izquierdo."
                )
                .setPositiveButton("Reintentar Takeoff", (d, w) -> ejecutarDespegueDirecto())
                .setNeutralButton("Armar Manual (CSC)", (d, w) -> {
                    telloClient.sendManualArmMotorsCsc();
                    Toast.makeText(this, "Armando motores... empuja el Stick Izquierdo hacia ARRIBA para elevar", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void mostrarMenuComandosExtra() {
        String toggleIaTxt = objectTrackingActive
                ? "Desactivar Seguimiento IA (volver a Manual)"
                : "Activar Seguimiento IA de Objetos Comunes (TFLite)";
        String filtroActualTxt = "Elegir Objeto a Seguir con IA (Actual: "
                + aiTracker.getSelectedFilterShortLabel() + ")";
        String[] opciones = new String[]{
                toggleIaTxt,
                filtroActualTxt,
                "Armar Motores Manual CSC (si hay poca luz para auto-takeoff)",
                "Paso SDK: Subir +30 cm (up 30)",
                "Paso SDK: Bajar -30 cm (down 30)",
                "Paso SDK: Avanzar +40 cm (forward 40)",
                "Paso SDK: Retroceder +40 cm (back 40)",
                "Paso SDK: Girar Derecha 45° (cw 45)",
                "Acrobacia Flip Adelante (flip f - requiere BAT > 50%)",
                "Acrobacia Flip Atrás (flip b - requiere BAT > 50%)",
                "Re-inicializar Cámara FPV (streamon)",
                "Abrir Ajustes Wi-Fi del Celular",
                "Cambiar IP del Dron (Actual: " + targetDroneIp + ")"
        };

        new AlertDialog.Builder(this)
                .setTitle("Comandos SDK y Seguimiento IA")
                .setItems(opciones, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            btnToggleTracking.performClick();
                            break;
                        case 1:
                            mostrarSelectorObjetoIa();
                            break;
                        case 2:
                            if (verificarConectadoAntesDeComando()) {
                                telloClient.sendManualArmMotorsCsc();
                                Toast.makeText(this, "Motores armados: sube el Stick Izquierdo", Toast.LENGTH_LONG).show();
                            }
                            break;
                        case 3:
                            if (verificarConectadoAntesDeComando()) telloClient.sendStepMove("up 30");
                            break;
                        case 4:
                            if (verificarConectadoAntesDeComando()) telloClient.sendStepMove("down 30");
                            break;
                        case 5:
                            if (verificarConectadoAntesDeComando()) telloClient.sendStepMove("forward 40");
                            break;
                        case 6:
                            if (verificarConectadoAntesDeComando()) telloClient.sendStepMove("back 40");
                            break;
                        case 7:
                            if (verificarConectadoAntesDeComando()) telloClient.sendStepMove("cw 45");
                            break;
                        case 8:
                            if (verificarConectadoAntesDeComando()) telloClient.sendFlip('f');
                            break;
                        case 9:
                            if (verificarConectadoAntesDeComando()) telloClient.sendFlip('b');
                            break;
                        case 10:
                            if (!isStreamEnabled) btnToggleCamera.performClick();
                            else if (telloClient.isConnected()) telloClient.sendStreamOn();
                            break;
                        case 11:
                            abrirAjustesWifiAndroid();
                            break;
                        case 12:
                            mostrarDialogoConfigurarIp();
                            break;
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void mostrarDialogoAyudaConexionWifi(String motivo) {
        new AlertDialog.Builder(this)
                .setTitle("¿Cómo conectar al Dron?")
                .setMessage(motivo + "\n\n"
                        + "Pasos rápidos:\n"
                        + "1. Presiona el botón lateral del dron Tello (luces parpadeando en amarillo).\n"
                        + "2. Desactiva los Datos Móviles (4G/5G) temporalmente y conéctate al Wi-Fi 'TELLO-XXXXXX'.\n"
                        + "3. Si Android pregunta 'Esta red no tiene Internet, ¿mantener conexión?', elige SÍ / MANTENER.\n"
                        + "4. Vuelve aquí y pulsa 'Reintentar'.")
                .setPositiveButton("Reintentar", (d, w) -> {
                    userInitiatedConnect = true;
                    conectarAlDron();
                })
                .setNeutralButton("Abrir Wi-Fi", (d, w) -> abrirAjustesWifiAndroid())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void mostrarDialogoDesconectar() {
        new AlertDialog.Builder(this)
                .setTitle("Enlace UDP Activo (" + targetDroneIp + ")")
                .setMessage("¿Deseas desconectar el enlace UDP con el dron o cambiar la dirección IP?")
                .setPositiveButton("Desconectar", (d, w) -> {
                    desactivarSeguimientoSilencioso();
                    videoDecoder.stop();
                    telloClient.disconnect();
                    hasRenderedVideoFrame = false;
                    layoutVideoPlaceholder.setVisibility(View.VISIBLE);
                    actualizarHudTelemetria();
                })
                .setNeutralButton("Cambiar IP", (d, w) -> mostrarDialogoConfigurarIp())
                .setNegativeButton("Seguir Volando", null)
                .show();
    }

    private void mostrarDialogoConfigurarIp() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(targetDroneIp);
        input.setSelection(targetDroneIp.length());

        new AlertDialog.Builder(this)
                .setTitle("Dirección IP del Dron (UDP 8889)")
                .setMessage("Por defecto DJI / Ryze Tello usa 192.168.10.1:")
                .setView(input)
                .setPositiveButton("Guardar y Conectar", (d, w) -> {
                    String nuevaIp = input.getText().toString().trim();
                    if (!nuevaIp.isEmpty()) {
                        targetDroneIp = nuevaIp;
                        userInitiatedConnect = true;
                        conectarAlDron();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void abrirAjustesWifiAndroid() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Abre el panel Wi-Fi de tu celular y elige TELLO-XXXXXX", Toast.LENGTH_LONG).show();
        }
    }

    private void mostrarGuiaConexionYVuelo() {
        new AlertDialog.Builder(this)
                .setTitle("GUÍA DE VUELO Y SEGUIMIENTO IA DE OBJETOS")
                .setMessage(
                        "1. CONEXIÓN AL DRON:\n"
                        + "• Enciende el dron (LED parpadeando en AMARILLO) y conecta al Wi-Fi 'TELLO-XXXXXX'.\n"
                        + "• Al entrar a esta cabina se conecta solo, o toca 'CONECTAR DRON'.\n\n"
                        + "2. VUELO MANUAL MODO 2 (rc @ 20 Hz):\n"
                        + "• Pulsa '▲ DESPEGAR' y espera ~4.5 s al aviso verde '¡STICKS MODO 2 ACTIVOS!'.\n"
                        + "• Stick Izquierdo: Altura (c) y Giro (d) | Stick Derecho: Avance (b) y Lateral (a).\n\n"
                        + "3. SEGUIMIENTO IA DE OBJETOS COMUNES ('SEGUIR IA'):\n"
                        + "• Reconoce 7 objetos comunes (Persona, Botella, Pelota, Celular, Vaso, Mochila y Libro), ignorando sillas, mesas y muebles.\n"
                        + "• Si el objeto se mueve a los lados el dron GIRA; si avanza/se aleja el dron AVANZA; si se acerca el dron RETROCEDE para mantener la distancia.\n"
                        + "• Mantén presionado 'SEGUIR IA' para elegir un objeto específico, o toca su recuadro en el video para fijarlo.\n"
                        + "• Mueve cualquier stick o toca '■ HOVER' para retomar el mando manual al instante."
                )
                .setPositiveButton("Entendido", null)
                .setNeutralButton("Elegir Objeto IA", (d, w) -> mostrarSelectorObjetoIa())
                .setNegativeButton(objectTrackingActive ? "Apagar IA" : "Activar IA",
                        (d, w) -> btnToggleTracking.performClick())
                .show();
    }

    // =========================================================================
    // 5. MODO GIROSCOPIO OPCIONAL INTEGRADO EN EL BUCLE RC @ 20 Hz
    // =========================================================================

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

        float rawPitch, rawRoll;
        switch (rotation) {
            case Surface.ROTATION_90:
                rawPitch = -x;
                rawRoll = y;
                break;
            case Surface.ROTATION_270:
                rawPitch = x;
                rawRoll = -y;
                break;
            case Surface.ROTATION_180:
                rawPitch = -y;
                rawRoll = x;
                break;
            case Surface.ROTATION_0:
            default:
                rawPitch = y;
                rawRoll = -x;
                break;
        }

        if (!isGyroCalibrated) {
            zeroPitch = rawPitch;
            zeroRoll = rawRoll;
            isGyroCalibrated = true;
        }

        gyroPitchNorm = mapTiltToNormalized(rawPitch - zeroPitch);
        gyroRollNorm = mapTiltToNormalized(rawRoll - zeroRoll);
        recalcularYActualizarRc();
    }

    private static float mapTiltToNormalized(float deltaMps2) {
        float abs = Math.abs(deltaMps2);
        if (abs < GYRO_DEADZONE_MPS2) return 0f;
        float norm = (abs - GYRO_DEADZONE_MPS2) / (GYRO_MAX_TILT_MPS2 - GYRO_DEADZONE_MPS2);
        return Math.signum(deltaMps2) * Math.min(1.0f, norm);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // =========================================================================
    // 6. CICLO DE VIDA Y AUTO-CONEXIÓN INTELIGENTE
    // =========================================================================

    @Override
    protected void onResume() {
        super.onResume();
        activarPantallaInmersivaCabina();
        if (isGyroActive && sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }

        if (telloClient != null && telloClient.isConnected()) {
            if (isStreamEnabled) {
                videoDecoder.start();
                if (videoSurface != null && videoSurface.isValid()) {
                    videoDecoder.setSurface(videoSurface);
                }
            }
        } else if (TelloUdpClient.findActiveWifiNetwork(this) != null && !autoConnectAttemptedOnResume) {
            autoConnectAttemptedOnResume = true;
            userInitiatedConnect = false;
            cameraTextureView.postDelayed(() -> {
                if (!isFinishing() && telloClient != null && !telloClient.isConnected()) {
                    conectarAlDron();
                }
            }, 300L);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoConnectAttemptedOnResume = false;
        desactivarSeguimientoSilencioso();
        resetearSticks();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detenerTodo();
    }

    private void detenerTodo() {
        desactivarSeguimientoSilencioso();
        resetearSticks();
        if (visionExecutor != null) {
            visionExecutor.shutdownNow();
            visionExecutor = null;
        }
        aiTracker.close();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (telloClient != null) {
            telloClient.disconnect();
        }
        if (videoSurface != null) {
            videoSurface.release();
            videoSurface = null;
        }
        if (aiFrameBitmap != null && !aiFrameBitmap.isRecycled()) {
            aiFrameBitmap.recycle();
            aiFrameBitmap = null;
        }
    }
}
