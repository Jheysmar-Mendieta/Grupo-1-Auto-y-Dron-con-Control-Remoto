package com.grupo1.controlauto;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Detector y Seguidor IA de Objetos Comunes (TensorFlow Lite Offline · v3.0).
 *
 * Diseñado específicamente para seguimiento autónomo estable con DJI / Ryze Tello:
 *  - Filtra estrictamente 7 objetos cotidianos comunes (Persona, Botella, Pelota, Celular,
 *    Vaso/Taza, Mochila y Libro), ignorando las otras 73 clases de fondo (sillas, mesas, TV,
 *    camas, autos, paredes, etc.) para evitar enganches accidentales con muebles.
 *  - Funciona 100% OFFLINE en el celular (compatible con Wi-Fi directo TELLO-XXXXXX sin Internet).
 *  - Lealtad temporal de clase (class lock): una vez fijado un objeto (o tocado en pantalla),
 *    mantiene el seguimiento sobre esa misma clase sin saltar a otros objetos cercanos.
 *  - Controlador PD de 3 ejes a 20 Hz:
 *      1) Giro (Yaw d): si el objeto se mueve a izquierda o derecha, el dron gira para centrarlo.
 *      2) Distancia (Pitch b): si el objeto avanza/se aleja, el dron avanza detrás de él;
 *         si el objeto se acerca al dron, el dron retrocede para mantener una distancia segura.
 *      3) Altura (Throttle c): mantiene encuadre vertical seguro con guarda de piso TOF <= 55 cm.
 */
public class TelloAiObjectTracker {

    public static final int CAPTURE_WIDTH = 480;
    public static final int CAPTURE_HEIGHT = 360;

    private static final String MODEL_ASSET_NAME = "detect.tflite";
    private static final String LABELS_ASSET_NAME = "labelmap.txt";

    // Límites seguros y suaves de velocidad automática RC [-100 .. +100]
    // Reducidos para evitar derrapes laterales por inercia y subidas bruscas
    private static final int MAX_AUTO_YAW = 28;
    private static final int MAX_AUTO_THROTTLE = 14;
    private static final int MAX_AUTO_PITCH_FWD = 26;
    private static final int MAX_AUTO_PITCH_BACK = 24;

    // Zonas muertas visuales para evitar oscilaciones en estacionario
    private static final float DEADBAND_X = 0.070f;
    private static final float DEADBAND_Y = 0.140f;
    private static final float DEADBAND_DIST = 0.100f;

    // Memoria corta y búsqueda activa cuando el objeto sale del cuadro por un costado
    private static final long LOST_MEMORY_MS = 300L;   // hover breve ante desenfoque
    private static final long SEARCH_MAX_MS = 1800L;   // gira hacia donde se fue durante 1.8 s
    private static final int SEARCH_YAW = 18;

    /**
     * Parámetros físicos y geométricos calibrados para cada uno de los 7 objetos comunes permitidos.
     */
    public static class TrackableClassSpec {
        public final String classEn;
        public final String labelEs;
        public final String menuTitleEs;
        public final float minConfInitial;
        public final float minConfContinuity;
        public final float minConfZoomPass;
        public final float defaultRefSize;
        public final float minRefSize;
        public final float maxRefSize;
        public final float tooCloseSize;
        public final float maxAspectWOverH;
        public final float aimYFactor; // 0.38 = pecho en personas, 0.50 = centro en objetos de mano
        public final boolean isPerson;

        TrackableClassSpec(String classEn, String labelEs, String menuTitleEs,
                           float minConfInitial, float minConfContinuity, float minConfZoomPass,
                           float defaultRefSize, float minRefSize, float maxRefSize,
                           float tooCloseSize, float maxAspectWOverH,
                           float aimYFactor, boolean isPerson) {
            this.classEn = classEn;
            this.labelEs = labelEs;
            this.menuTitleEs = menuTitleEs;
            this.minConfInitial = minConfInitial;
            this.minConfContinuity = minConfContinuity;
            this.minConfZoomPass = minConfZoomPass;
            this.defaultRefSize = defaultRefSize;
            this.minRefSize = minRefSize;
            this.maxRefSize = maxRefSize;
            this.tooCloseSize = tooCloseSize;
            this.maxAspectWOverH = maxAspectWOverH;
            this.aimYFactor = aimYFactor;
            this.isPerson = isPerson;
        }
    }

    // Lista compacta de los 7 objetos cotidianos más comunes (ignora muebles y desorden de fondo)
    private static final TrackableClassSpec[] COMMON_SPECS = new TrackableClassSpec[]{
            new TrackableClassSpec(
                    "person", "PERSONA", "Solo Persona",
                    0.42f, 0.30f, 0.45f,
                    0.48f, 0.34f, 0.58f, 0.68f,
                    1.45f, 0.50f, true
            ),
            new TrackableClassSpec(
                    "bottle", "BOTELLA", "Solo Botella",
                    0.30f, 0.23f, 0.34f,
                    0.20f, 0.13f, 0.26f, 0.34f,
                    1.45f, 0.50f, false
            ),
            new TrackableClassSpec(
                    "sports ball", "PELOTA", "Solo Pelota",
                    0.32f, 0.24f, 0.35f,
                    0.18f, 0.11f, 0.25f, 0.33f,
                    1.55f, 0.50f, false
            ),
            new TrackableClassSpec(
                    "cell phone", "CELULAR", "Solo Celular",
                    0.34f, 0.25f, 0.37f,
                    0.16f, 0.10f, 0.23f, 0.31f,
                    1.85f, 0.50f, false
            ),
            new TrackableClassSpec(
                    "cup", "VASO", "Solo Vaso / Taza",
                    0.34f, 0.25f, 0.37f,
                    0.17f, 0.11f, 0.24f, 0.32f,
                    1.65f, 0.50f, false
            ),
            new TrackableClassSpec(
                    "backpack", "MOCHILA", "Solo Mochila",
                    0.36f, 0.27f, 0.39f,
                    0.30f, 0.19f, 0.39f, 0.49f,
                    1.65f, 0.50f, false
            ),
            new TrackableClassSpec(
                    "book", "LIBRO", "Solo Libro",
                    0.36f, 0.27f, 0.39f,
                    0.21f, 0.13f, 0.28f, 0.37f,
                    2.10f, 0.50f, false
            )
    };

    private static final Map<String, TrackableClassSpec> SPEC_BY_EN = new HashMap<>();
    static {
        for (TrackableClassSpec spec : COMMON_SPECS) {
            SPEC_BY_EN.put(spec.classEn, spec);
        }
    }

    public static class DetectedBox {
        public String classEn = "";
        public String labelEs = "";
        public float confidence; // [0.0 .. 1.0]
        public float left;       // [0.0 .. 1.0]
        public float top;        // [0.0 .. 1.0]
        public float right;      // [0.0 .. 1.0]
        public float bottom;     // [0.0 .. 1.0]
        public boolean isPrimaryTarget;
        public boolean fromZoomPass;

        public float centerX() {
            return (left + right) * 0.5f;
        }

        public float centerY() {
            return (top + bottom) * 0.5f;
        }
    }

    public static class AiTrackingOutput {
        public boolean modelReady = false;
        public boolean targetLocked = false;
        public boolean usedZoomPass = false;
        public String lockedLabelEs = "";
        public String filterShortLabelEs = "AUTO";
        public String distanceActionEs = "DISTANCIA OK";
        public int lockedConfidencePct = 0;
        public long inferenceTimeMs = 0L;

        // Búsqueda activa: el objeto salió del cuadro por un costado (-1 izquierda, +1 derecha)
        public boolean searching = false;
        public int searchDir = 0;

        // Estado de distancia: >0 el objeto está más lejos que la referencia, <0 más cerca
        public float distanceError = 0f;
        // Velocidad de acercamiento normalizada (1/s): >0 se acerca, <0 se aleja
        public float approachRate = 0f;
        public boolean tooClose = false;

        // Caja filtrada del objetivo principal [0.0 .. 1.0]
        public float normLeft = 0f;
        public float normTop = 0f;
        public float normRight = 0f;
        public float normBottom = 0f;
        public float normCenterX = 0.5f;
        public float normCenterY = 0.5f;

        // Objetos comunes detectados en el fotograma actual (permite tocar otro objeto para cambiar)
        public final List<DetectedBox> detections = new ArrayList<>();

        // Comandos RC Modo 2 calculados [-100 .. +100]
        public int cmdRollA = 0;
        public int cmdPitchB = 0;
        public int cmdThrottleC = 0;
        public int cmdYawD = 0;
    }

    private Interpreter interpreter;
    private final List<String> labels = new ArrayList<>();
    private int inputWidth = 300;
    private int inputHeight = 300;
    private boolean isQuantizedUint8 = true;
    private int maxDetections = 10;

    private ByteBuffer inputBuffer;
    private int[] capturePixels;
    private float[][][] outputLocations;
    private float[][] outputClasses;
    private float[][] outputScores;
    private float[] numDetections;

    private volatile boolean initialized = false;
    private volatile String initErrorMessage = null;

    // 0 = Automático (cualquiera de los 7 objetos comunes), 1..7 = objeto específico de COMMON_SPECS
    private volatile int selectedFilterIndex = 0;
    private volatile String selectedFilterClassEn = null;

    // Clase actualmente enganchada y su tamaño aparente de referencia para mantener distancia
    private String lockedClassEn = null;
    private volatile float referenceApparentSize = 0.20f;
    private volatile boolean needsInitialSizeLock = true;

    // Estado espacial suavizado (EMA) y seguimiento temporal
    private boolean hasSmoothedState = false;
    private float smoothCx = 0.5f;
    private float smoothAimY = 0.5f;
    private float smoothLeft = 0.45f;
    private float smoothTop = 0.35f;
    private float smoothRight = 0.55f;
    private float smoothBottom = 0.65f;
    private float smoothApparentSize = 0.20f;
    private int lastLockedConfPct = 0;
    private boolean lastUsedZoom = false;

    private float prevErrX = 0f;
    private float prevErrDist = 0f;
    private long prevFrameTimeMs = 0L;
    private int consecutiveLostFrames = 12;

    // Memoria temporal del objetivo y estimación de velocidad de acercamiento
    private long lastSeenMs = 0L;
    private float lastSeenErrX = 0f;
    private float prevSizeForRate = 0f;
    private float approachRateEma = 0f;

    private final List<DetectedBox> lastDetections = new ArrayList<>();

    public static String[] getFilterMenuLabels() {
        String[] items = new String[COMMON_SPECS.length + 1];
        items[0] = "Automático (7 objetos comunes: Persona, Botella, Pelota, Celular, Vaso, Mochila, Libro)";
        for (int i = 0; i < COMMON_SPECS.length; i++) {
            items[i + 1] = COMMON_SPECS[i].menuTitleEs;
        }
        return items;
    }

    public synchronized void setSelectedFilterIndex(int index) {
        if (index <= 0 || index > COMMON_SPECS.length) {
            this.selectedFilterIndex = 0;
            this.selectedFilterClassEn = null;
        } else {
            this.selectedFilterIndex = index;
            this.selectedFilterClassEn = COMMON_SPECS[index - 1].classEn;
        }
        resetTrackingState();
    }

    public int getSelectedFilterIndex() {
        return selectedFilterIndex;
    }

    public String getSelectedFilterShortLabel() {
        int idx = selectedFilterIndex;
        if (idx <= 0 || idx > COMMON_SPECS.length) {
            return "AUTO";
        }
        return COMMON_SPECS[idx - 1].labelEs;
    }

    public synchronized boolean initialize(Context context) {
        if (initialized && interpreter != null) {
            return true;
        }
        try {
            loadLabels(context);
            ByteBuffer modelBuffer = loadModelBuffer(context);

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            try {
                options.setUseXNNPACK(true);
            } catch (Throwable ignored) {}

            interpreter = new Interpreter(modelBuffer, options);

            int[] inShape = interpreter.getInputTensor(0).shape(); // [1, H, W, 3]
            inputHeight = inShape[1];
            inputWidth = inShape[2];
            isQuantizedUint8 = (interpreter.getInputTensor(0).dataType() == DataType.UINT8);

            int bytesPerChannel = isQuantizedUint8 ? 1 : 4;
            inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3 * bytesPerChannel);
            inputBuffer.order(ByteOrder.nativeOrder());
            capturePixels = new int[CAPTURE_WIDTH * CAPTURE_HEIGHT];

            int[] outLocShape = interpreter.getOutputTensor(0).shape(); // [1, N, 4]
            maxDetections = outLocShape[1];
            outputLocations = new float[1][maxDetections][4];
            outputClasses = new float[1][maxDetections];
            outputScores = new float[1][maxDetections];
            numDetections = new float[1];

            initialized = true;
            initErrorMessage = null;
            return true;
        } catch (Throwable t) {
            initialized = false;
            initErrorMessage = t.getMessage();
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getInitErrorMessage() {
        return initErrorMessage;
    }

    public synchronized void resetTrackingState() {
        this.hasSmoothedState = false;
        this.lockedClassEn = null;
        this.needsInitialSizeLock = true;
        this.consecutiveLostFrames = 0;
        this.prevErrX = 0f;
        this.prevErrDist = 0f;
        this.referenceApparentSize = 0.20f;
        this.lastSeenMs = 0L;
        this.lastSeenErrX = 0f;
        this.approachRateEma = 0f;
        this.prevSizeForRate = 0f;
        this.lastDetections.clear();
    }

    /**
     * Si el usuario toca un objeto común detectado en el video, fija inmediatamente ese objeto
     * y calibra su distancia actual como la distancia objetivo a mantener.
     */
    public synchronized String lockObjectAtPoint(float vNormX, float vNormY) {
        DetectedBox bestHit = null;
        float bestDist = Float.MAX_VALUE;

        for (DetectedBox box : lastDetections) {
            if (vNormX >= (box.left - 0.05f) && vNormX <= (box.right + 0.05f)
                    && vNormY >= (box.top - 0.05f) && vNormY <= (box.bottom + 0.05f)) {
                float d = (float) Math.hypot(vNormX - box.centerX(), vNormY - box.centerY());
                if (d < bestDist) {
                    bestDist = d;
                    bestHit = box;
                }
            }
        }

        if (bestHit == null) {
            return null;
        }

        TrackableClassSpec spec = SPEC_BY_EN.get(bestHit.classEn);
        if (spec == null) {
            return null;
        }

        this.lockedClassEn = spec.classEn;
        this.smoothCx = bestHit.centerX();
        this.smoothAimY = computeAimY(bestHit, spec);
        this.smoothLeft = bestHit.left;
        this.smoothTop = bestHit.top;
        this.smoothRight = bestHit.right;
        this.smoothBottom = bestHit.bottom;

        float apparent = computeApparentSize(bestHit, spec);
        this.referenceApparentSize = clampFloat(apparent, spec.minRefSize, spec.maxRefSize);
        this.smoothApparentSize = apparent;
        this.needsInitialSizeLock = false;
        this.hasSmoothedState = true;
        this.consecutiveLostFrames = 0;
        this.prevErrX = 0f;
        this.prevErrDist = 0f;
        this.lastSeenMs = System.currentTimeMillis();
        this.lastSeenErrX = (this.smoothCx - 0.5f) * 2.0f;
        this.prevSizeForRate = apparent;
        this.approachRateEma = 0f;
        this.lastLockedConfPct = Math.round(bestHit.confidence * 100f);
        this.lastUsedZoom = bestHit.fromZoomPass;
        return spec.labelEs + " (" + this.lastLockedConfPct + "%)";
    }

    public synchronized void processFrame(Bitmap frameBitmap, int currentTofCm, AiTrackingOutput out) {
        out.modelReady = initialized;
        out.usedZoomPass = false;
        out.filterShortLabelEs = getSelectedFilterShortLabel();
        out.lockedLabelEs = "";
        out.distanceActionEs = "DISTANCIA OK";
        out.searching = false;
        out.searchDir = 0;
        out.distanceError = 0f;
        out.approachRate = 0f;
        out.tooClose = false;
        out.detections.clear();
        out.cmdRollA = 0;
        out.cmdPitchB = 0;
        out.cmdThrottleC = 0;
        out.cmdYawD = 0;

        if (!initialized || interpreter == null || frameBitmap == null || frameBitmap.isRecycled()) {
            out.targetLocked = false;
            return;
        }

        int srcW = frameBitmap.getWidth();
        int srcH = frameBitmap.getHeight();
        if (srcW <= 0 || srcH <= 0) {
            out.targetLocked = false;
            return;
        }

        if (capturePixels == null || capturePixels.length != srcW * srcH) {
            capturePixels = new int[srcW * srcH];
        }

        long t0 = System.currentTimeMillis();
        frameBitmap.getPixels(capturePixels, 0, srcW, 0, 0, srcW, srcH);

        lastDetections.clear();

        // 1. PASADA 1: Cuadro completo -> detectar únicamente entre los 7 objetos comunes permitidos
        runInferenceOnRegion(srcW, srcH, 0f, 0f, 1f, 1f, false);

        // 2. PASADA 2 (ZOOM DIGITAL 1.8x): Si el objeto seguido (o filtrado) se alejó y no salió en Pasada 1
        boolean recentlySeen = hasSmoothedState && (t0 - lastSeenMs) <= SEARCH_MAX_MS;
        String desiredClass = (selectedFilterClassEn != null)
                ? selectedFilterClassEn
                : (recentlySeen ? lockedClassEn : null);

        boolean needZoomPass;
        if (desiredClass != null) {
            needZoomPass = !containsDetectionOfClass(desiredClass);
        } else {
            needZoomPass = lastDetections.isEmpty();
        }

        if (needZoomPass) {
            float zoomSpan = 0.56f; // ~1.8x zoom
            float focusX = (hasSmoothedState && consecutiveLostFrames <= 6) ? smoothCx : 0.50f;
            float focusY = (hasSmoothedState && consecutiveLostFrames <= 6) ? smoothAimY : 0.50f;

            float roiLeft = clampFloat(focusX - zoomSpan * 0.5f, 0f, 1f - zoomSpan);
            float roiTop = clampFloat(focusY - zoomSpan * 0.5f, 0f, 1f - zoomSpan);
            runInferenceOnRegion(srcW, srcH, roiLeft, roiTop, roiLeft + zoomSpan, roiTop + zoomSpan, true);
        }

        out.inferenceTimeMs = System.currentTimeMillis() - t0;

        // 3. Selección inteligente de objetivo con lealtad de clase y continuidad espacial
        DetectedBox bestTarget = selectBestTarget(t0);

        if (bestTarget != null) {
            bestTarget.isPrimaryTarget = true;
        }
        out.detections.addAll(lastDetections);

        // 4. Objetivo no visible en este fotograma
        if (bestTarget == null) {
            consecutiveLostFrames++;
            long lostMs = t0 - lastSeenMs;
            TrackableClassSpec prevSpec = (lockedClassEn != null) ? SPEC_BY_EN.get(lockedClassEn) : null;

            if (hasSmoothedState && prevSpec != null && lostMs <= LOST_MEMORY_MS) {
                // a) Memoria corta (~300 ms) en Hover 0,0,0,0 ante desenfoque rápido
                out.targetLocked = true;
                out.lockedLabelEs = prevSpec.labelEs;
                out.distanceActionEs = "HOVER";
                out.usedZoomPass = lastUsedZoom;
                out.lockedConfidencePct = lastLockedConfPct;
                out.normLeft = smoothLeft;
                out.normTop = smoothTop;
                out.normRight = smoothRight;
                out.normBottom = smoothBottom;
                out.normCenterX = smoothCx;
                out.normCenterY = smoothAimY;
            } else if (hasSmoothedState && prevSpec != null && lostMs <= SEARCH_MAX_MS
                    && Math.abs(lastSeenErrX) > 0.28f) {
                // b) BÚSQUEDA ACTIVA: el objeto salió por un costado -> girar hacia ese lado para reencontrarlo
                out.targetLocked = false;
                out.searching = true;
                out.searchDir = (lastSeenErrX > 0f) ? 1 : -1;
                out.lockedLabelEs = prevSpec.labelEs;
                out.cmdYawD = out.searchDir * SEARCH_YAW;
                out.distanceActionEs = (out.searchDir > 0) ? "BUSCANDO →" : "← BUSCANDO";
                prevErrX = 0f;
                prevErrDist = 0f;
            } else {
                // c) Perdido: Hover seguro (rc 0 0 0 0) y liberar el objetivo tras 1.8 s
                out.targetLocked = false;
                if (lostMs > SEARCH_MAX_MS) {
                    hasSmoothedState = false;
                    lockedClassEn = null;
                    needsInitialSizeLock = true;
                    approachRateEma = 0f;
                }
                prevErrX = 0f;
                prevErrDist = 0f;
            }
            return;
        }

        TrackableClassSpec activeSpec = SPEC_BY_EN.get(bestTarget.classEn);
        if (activeSpec == null) {
            out.targetLocked = false;
            return;
        }

        // Si cambió la clase enganchada (ej. al adquirir por primera vez), calibrar su referencia propia
        if (lockedClassEn == null || !lockedClassEn.equals(activeSpec.classEn)) {
            lockedClassEn = activeSpec.classEn;
            needsInitialSizeLock = true;
            hasSmoothedState = false;
            prevErrX = 0f;
            prevErrDist = 0f;
            approachRateEma = 0f;
        }

        // 5. Actualizar estado suavizado (EMA) del objeto fijado
        consecutiveLostFrames = 0;
        lastSeenMs = t0;
        lastLockedConfPct = Math.round(bestTarget.confidence * 100f);
        lastUsedZoom = bestTarget.fromZoomPass;

        float rawCx = bestTarget.centerX();
        float rawAimY = computeAimY(bestTarget, activeSpec);
        float rawApparentSize = computeApparentSize(bestTarget, activeSpec);

        if (needsInitialSizeLock) {
            this.referenceApparentSize = clampFloat(rawApparentSize, activeSpec.minRefSize, activeSpec.maxRefSize);
            this.needsInitialSizeLock = false;
        }

        if (!hasSmoothedState) {
            smoothCx = rawCx;
            smoothAimY = rawAimY;
            smoothLeft = bestTarget.left;
            smoothTop = bestTarget.top;
            smoothRight = bestTarget.right;
            smoothBottom = bestTarget.bottom;
            smoothApparentSize = rawApparentSize;
            prevSizeForRate = rawApparentSize;
            approachRateEma = 0f;
            hasSmoothedState = true;
        } else {
            final float alphaPos = 0.60f;
            smoothCx += alphaPos * (rawCx - smoothCx);
            smoothAimY += alphaPos * (rawAimY - smoothAimY);
            smoothLeft += alphaPos * (bestTarget.left - smoothLeft);
            smoothTop += alphaPos * (bestTarget.top - smoothTop);
            smoothRight += alphaPos * (bestTarget.right - smoothRight);
            smoothBottom += alphaPos * (bestTarget.bottom - smoothBottom);
            smoothApparentSize += 0.40f * (rawApparentSize - smoothApparentSize);
        }

        out.targetLocked = true;
        out.lockedLabelEs = activeSpec.labelEs;
        out.usedZoomPass = lastUsedZoom;
        out.lockedConfidencePct = lastLockedConfPct;
        out.normLeft = smoothLeft;
        out.normTop = smoothTop;
        out.normRight = smoothRight;
        out.normBottom = smoothBottom;
        out.normCenterX = smoothCx;
        out.normCenterY = smoothAimY;

        // =====================================================================
        // 6. CONTROLADOR VISUAL DE 3 EJES (GIRO + AVANCE / RETROCESO PARA MANTENER DISTANCIA)
        // =====================================================================
        long now = System.currentTimeMillis();
        float dtSec = (prevFrameTimeMs > 0L) ? ((now - prevFrameTimeMs) / 1000f) : 0.080f;
        prevFrameTimeMs = now;
        if (dtSec < 0.025f || dtSec > 0.6f) dtSec = 0.080f;

        // A) Giro Horizontal (Yaw d): si el objeto se mueve a los lados, girar para seguirlo con la cámara
        float errX = (smoothCx - 0.5f) * 2.0f; // [-1.0 .. +1.0]
        float dErrX = clampFloat((errX - prevErrX) / dtSec, -4f, 4f);
        prevErrX = errX;
        lastSeenErrX = errX;

        int cmdYaw = 0;
        if (Math.abs(errX) > DEADBAND_X) {
            float signX = Math.signum(errX);
            float effX = (Math.abs(errX) - DEADBAND_X) / (1.0f - DEADBAND_X);
            // Término D: si el objeto se está moviendo hacia un lado, anticipar el giro
            float rawYaw = signX * (10.0f + 44.0f * effX) + (7.0f * dErrX);
            if (rawYaw * signX < 8.0f) {
                rawYaw = signX * 8.0f;
            }
            cmdYaw = clampInt(Math.round(rawYaw), -MAX_AUTO_YAW, MAX_AUTO_YAW);
        }

        // B) Altura Vertical (Throttle c): mantener encuadre suave con guardas de seguridad de piso y techo
        float errY = (0.50f - smoothAimY) * 2.0f;
        int cmdThrottle = 0;
        if (Math.abs(errY) > DEADBAND_Y) {
            float signY = Math.signum(errY);
            float effY = (Math.abs(errY) - DEADBAND_Y) / (1.0f - DEADBAND_Y);
            // Salida vertical suave y contenida
            cmdThrottle = clampInt(Math.round(signY * (5.0f + 11.0f * effY)), -MAX_AUTO_THROTTLE, MAX_AUTO_THROTTLE);
            // Guarda de piso: nunca descender si el dron está a 60 cm o menos del piso
            if (currentTofCm > 0 && currentTofCm <= 60 && cmdThrottle < 0) {
                cmdThrottle = 0;
            }
            // Guarda de techo en interiores: si ya está a 135 cm o más, no permitir subir descontrolado
            if (currentTofCm >= 135 && cmdThrottle > 0) {
                cmdThrottle = 0;
            }
        }

        // C) Distancia Frontal (Pitch b):
        //    - El objeto avanza / se aleja (se ve más chico que la referencia) -> AVANZAR detrás de él
        //    - El objeto se acerca (se ve más grande que la referencia)      -> RETROCEDER manteniendo distancia
        //    - Además se estima la VELOCIDAD de acercamiento para reaccionar antes (anticipación)
        float refSize = Math.max(activeSpec.minRefSize, referenceApparentSize);
        float errDist = (refSize - smoothApparentSize) / refSize; // >0 lejos, <0 cerca
        prevErrDist = errDist;

        float rawRate = ((smoothApparentSize - prevSizeForRate) / refSize) / dtSec; // >0 se acerca
        prevSizeForRate = smoothApparentSize;
        rawRate = clampFloat(rawRate, -3.0f, 3.0f);
        approachRateEma += 0.35f * (rawRate - approachRateEma);

        boolean tooClose = smoothApparentSize >= activeSpec.tooCloseSize;
        int cmdPitch = 0;
        String distAction = "DISTANCIA OK";

        if (tooClose) {
            // Objeto demasiado cerca -> retroceder de inmediato (más fuerte si además sigue acercándose)
            float closeExcess = (smoothApparentSize - activeSpec.tooCloseSize)
                    / Math.max(0.10f, activeSpec.tooCloseSize);
            float extra = Math.max(0f, approachRateEma) * 10.0f;
            int backOff = -Math.round(15.0f + 35.0f * closeExcess + extra);
            cmdPitch = clampInt(backOff, -MAX_AUTO_PITCH_BACK, -14);
            distAction = "SE ACERCÓ · RETROCEDIENDO";
        } else if (Math.abs(errX) < 0.75f) {
            float pitchCmd = 0f;
            if (errDist > DEADBAND_DIST) {
                pitchCmd = 11.0f + 34.0f * (errDist - DEADBAND_DIST);
            } else if (errDist < -DEADBAND_DIST) {
                pitchCmd = -(11.0f + 38.0f * (-errDist - DEADBAND_DIST));
            }
            // Anticipación: si se acerca/aleja rápido, empezar a moverse antes de salir de la zona muerta
            if (Math.abs(approachRateEma) > 0.25f) {
                pitchCmd += clampFloat(-approachRateEma * 14.0f, -12.0f, 12.0f);
            }
            // Girar y ajustar distancia al mismo tiempo, más suave si el objeto está muy a un costado
            float turnScale = 1.0f - 0.45f * Math.min(1.0f, Math.abs(errX));
            pitchCmd *= turnScale;

            if (pitchCmd >= 8.0f) {
                cmdPitch = clampInt(Math.round(pitchCmd), 8, MAX_AUTO_PITCH_FWD);
                distAction = "SE ALEJÓ · AVANZANDO";
            } else if (pitchCmd <= -8.0f) {
                cmdPitch = clampInt(Math.round(pitchCmd), -MAX_AUTO_PITCH_BACK, -8);
                distAction = "SE ACERCÓ · RETROCEDIENDO";
            }
        }

        out.distanceActionEs = distAction;
        out.distanceError = errDist;
        out.approachRate = approachRateEma;
        out.tooClose = tooClose;
        out.cmdRollA = 0;
        out.cmdPitchB = cmdPitch;
        out.cmdThrottleC = cmdThrottle;
        out.cmdYawD = cmdYaw;
    }

    private boolean containsDetectionOfClass(String classEn) {
        for (DetectedBox b : lastDetections) {
            if (classEn.equals(b.classEn)) {
                return true;
            }
        }
        return false;
    }

    private DetectedBox selectBestTarget(long nowMs) {
        // Caso 1: Ya estamos siguiendo un objeto -> mantener lealtad a esa misma clase hasta 1.8 s sin verlo
        long lostMs = nowMs - lastSeenMs;
        if (hasSmoothedState && lockedClassEn != null && lostMs <= SEARCH_MAX_MS) {
            TrackableClassSpec lockedSpec = SPEC_BY_EN.get(lockedClassEn);
            if (lockedSpec != null) {
                boolean reacquiring = lostMs > LOST_MEMORY_MS; // tras búsqueda puede reaparecer en otro lado
                DetectedBox bestLocked = null;
                float bestScore = -1f;
                for (DetectedBox cand : lastDetections) {
                    if (!lockedClassEn.equals(cand.classEn)) continue;
                    float distToPrev = (float) Math.hypot(cand.centerX() - smoothCx, cand.centerY() - smoothAimY);
                    if (!reacquiring && cand.confidence < lockedSpec.minConfInitial && distToPrev > 0.28f) {
                        continue;
                    }
                    float score = cand.confidence / (1.0f + (reacquiring ? 0.6f : 2.2f) * distToPrev);
                    if (score > bestScore) {
                        bestScore = score;
                        bestLocked = cand;
                    }
                }
                return bestLocked;
            }
        }

        // Caso 2: Adquisición inicial (o tras perder el objeto anterior por > 8 cuadros)
        DetectedBox bestInitial = null;
        float bestScore = -1f;
        for (DetectedBox cand : lastDetections) {
            TrackableClassSpec spec = SPEC_BY_EN.get(cand.classEn);
            if (spec == null) continue;
            if (cand.confidence < spec.minConfInitial) continue;

            float distCenter = (float) Math.hypot(cand.centerX() - 0.5f, cand.centerY() - 0.5f);
            // Si alguien sostiene un objeto común (botella, pelota, celular, vaso, etc.) frente al centro,
            // darle prioridad ligera sobre el cuerpo de fondo; si solo hay una persona, sigue a la persona.
            float priority = (!spec.isPerson && distCenter < 0.34f) ? 1.24f : 1.00f;
            float score = (cand.confidence * priority) / (1.0f + 0.85f * distCenter);
            if (score > bestScore) {
                bestScore = score;
                bestInitial = cand;
            }
        }
        return bestInitial;
    }

    /**
     * Ejecuta SSD MobileNet sobre la región normalizada [roiL, roiT, roiR, roiB] y conserva
     * únicamente detecciones válidas dentro de los 7 objetos comunes (o el filtro elegido).
     */
    private void runInferenceOnRegion(
            int srcW, int srcH,
            float roiL, float roiT, float roiR, float roiB,
            boolean isZoomPass) {

        float roiW = roiR - roiL;
        float roiH = roiB - roiT;
        int startX = Math.max(0, Math.round(roiL * srcW));
        int startY = Math.max(0, Math.round(roiT * srcH));
        int regW = Math.max(1, Math.min(srcW - startX, Math.round(roiW * srcW)));
        int regH = Math.max(1, Math.min(srcH - startY, Math.round(roiH * srcH)));

        inputBuffer.rewind();
        if (isQuantizedUint8) {
            for (int ty = 0; ty < inputHeight; ty++) {
                int sy = startY + (ty * regH) / inputHeight;
                int rowOffset = sy * srcW;
                for (int tx = 0; tx < inputWidth; tx++) {
                    int sx = startX + (tx * regW) / inputWidth;
                    int px = capturePixels[rowOffset + sx];
                    inputBuffer.put((byte) ((px >> 16) & 0xFF));
                    inputBuffer.put((byte) ((px >> 8) & 0xFF));
                    inputBuffer.put((byte) (px & 0xFF));
                }
            }
        } else {
            for (int ty = 0; ty < inputHeight; ty++) {
                int sy = startY + (ty * regH) / inputHeight;
                int rowOffset = sy * srcW;
                for (int tx = 0; tx < inputWidth; tx++) {
                    int sx = startX + (tx * regW) / inputWidth;
                    int px = capturePixels[rowOffset + sx];
                    inputBuffer.putFloat((((px >> 16) & 0xFF) - 127.5f) / 127.5f);
                    inputBuffer.putFloat((((px >> 8) & 0xFF) - 127.5f) / 127.5f);
                    inputBuffer.putFloat(((px & 0xFF) - 127.5f) / 127.5f);
                }
            }
        }

        Object[] inputs = new Object[]{inputBuffer};
        Map<Integer, Object> outputs = new HashMap<>(4);
        outputs.put(0, outputLocations);
        outputs.put(1, outputClasses);
        outputs.put(2, outputScores);
        outputs.put(3, numDetections);

        interpreter.runForMultipleInputsOutputs(inputs, outputs);

        String filterClass = this.selectedFilterClassEn;
        int count = Math.min(maxDetections, Math.max(0, (int) numDetections[0]));
        for (int i = 0; i < count; i++) {
            float score = outputScores[0][i];
            if (score < 0.22f) continue;

            // En coco_ssd_mobilenet_v1_1.0_quant, labelOffset = +1 respecto a labelmap.txt
            int labelIdx = ((int) outputClasses[0][i]) + 1;
            if (labelIdx < 0 || labelIdx >= labels.size()) continue;

            String rawEn = labels.get(labelIdx).trim().toLowerCase(Locale.US);
            if (filterClass != null && !filterClass.equals(rawEn)) continue;

            TrackableClassSpec spec = SPEC_BY_EN.get(rawEn);
            if (spec == null) continue; // Ignorar las otras 73 clases COCO no permitidas

            boolean isContinuingThisClass = hasSmoothedState && rawEn.equals(lockedClassEn);
            float minRequired = isZoomPass
                    ? spec.minConfZoomPass
                    : (isContinuingThisClass ? spec.minConfContinuity : spec.minConfInitial);
            if (score < minRequired) continue;

            // En pasada de zoom no re-proyectar personas gigantes cortadas
            if (isZoomPass && spec.isPerson) continue;

            float cTop = clamp01(outputLocations[0][i][0]);
            float cLeft = clamp01(outputLocations[0][i][1]);
            float cBottom = clamp01(outputLocations[0][i][2]);
            float cRight = clamp01(outputLocations[0][i][3]);

            float cW = cRight - cLeft;
            float cH = cBottom - cTop;
            if (cW < 0.02f || cH < 0.035f) continue;
            if (isZoomPass && (cW > 0.80f || cH > 0.90f)) continue;

            float fullLeft = clamp01(roiL + cLeft * roiW);
            float fullTop = clamp01(roiT + cTop * roiH);
            float fullRight = clamp01(roiL + cRight * roiW);
            float fullBottom = clamp01(roiT + cBottom * roiH);

            float fullW = fullRight - fullLeft;
            float fullH = fullBottom - fullTop;
            if (fullW < 0.016f || fullH < 0.035f) continue;
            if (fullW > spec.maxAspectWOverH * fullH) continue;

            DetectedBox box = new DetectedBox();
            box.classEn = spec.classEn;
            box.labelEs = spec.labelEs;
            box.confidence = score;
            box.left = fullLeft;
            box.top = fullTop;
            box.right = fullRight;
            box.bottom = fullBottom;
            box.isPrimaryTarget = false;
            box.fromZoomPass = isZoomPass;
            lastDetections.add(box);
        }
    }

    private static float computeAimY(DetectedBox box, TrackableClassSpec spec) {
        return box.top + spec.aimYFactor * (box.bottom - box.top);
    }

    private static float computeApparentSize(DetectedBox box, TrackableClassSpec spec) {
        float w = Math.max(0.02f, box.right - box.left);
        float h = Math.max(0.04f, box.bottom - box.top);
        float linear = (float) Math.sqrt(w * h);
        if (spec.isPerson) {
            // En personas cercanas la cabeza/pies tocan los bordes verticales, pero el ancho del torso sigue creciendo
            return (0.50f * h) + (0.50f * linear);
        }
        return (0.60f * h) + (0.40f * linear);
    }

    private void loadLabels(Context context) throws IOException {
        labels.clear();
        try (InputStream is = context.getAssets().open(LABELS_ASSET_NAME);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line.trim());
            }
        }
    }

    private static ByteBuffer loadModelBuffer(Context context) throws IOException {
        try {
            AssetFileDescriptor fd = context.getAssets().openFd(MODEL_ASSET_NAME);
            try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
                FileChannel channel = fis.getChannel();
                MappedByteBuffer mapped = channel.map(
                        FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
                fd.close();
                return mapped;
            }
        } catch (IOException fallbackEx) {
            try (InputStream is = context.getAssets().open(MODEL_ASSET_NAME);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] tmp = new byte[16384];
                int n;
                while ((n = is.read(tmp)) != -1) {
                    bos.write(tmp, 0, n);
                }
                byte[] bytes = bos.toByteArray();
                ByteBuffer direct = ByteBuffer.allocateDirect(bytes.length);
                direct.order(ByteOrder.nativeOrder());
                direct.put(bytes);
                direct.rewind();
                return direct;
            }
        }
    }

    public synchronized void close() {
        initialized = false;
        if (interpreter != null) {
            try { interpreter.close(); } catch (Throwable ignored) {}
            interpreter = null;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float clampFloat(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
