package com.grupo1.controlauto;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente UDP de Teleoperación para Dron DJI / Ryze Tello & Tello EDU (v2.5).
 *
 * Garantías para el funcionamiento de los Sticks Modo 2 ("rc a b c d" @ 20 Hz):
 *  1. Espera el "ok" real de "takeoff" antes de iniciar el stream "rc" (evita saturar la cola del firmware
 *     o provocar "error: Not joystick" por enviar "rc 0 0 0 0" mientras el dron sigue en AUTO_TAKEOFF).
 *  2. Lectura directa en tiempo real (RcAxisProvider) de ambos sticks en cada ciclo de 50 ms (20 Hz),
 *     imposibilitando cualquier desincronización entre la vista táctil y el bucle UDP.
 *  3. Ráfaga garantizada de frenado ("rc 0 0 0 0") al soltar los sticks, incluso si airborne fuera false.
 *  4. Keepalive "command" inteligente solo en inactividad (>= 5 s sin paquetes UDP), sin interrumpir
 *     el flujo continuo de 20 Hz durante el vuelo manual ("rc 0 0 0 0" ya refresca el watchdog de 15 s del Tello).
 *  5. Auto-recuperación ante "Not joystick" tras maniobras discretas (flip / step).
 */
public class TelloUdpClient {

    public static final String DEFAULT_DRONE_IP = "192.168.10.1";
    public static final int COMMAND_PORT = 8889;
    public static final int TELEMETRY_PORT = 8890;
    public static final long RC_PERIOD_MS = 50L; // 20 Hz = 50 ms

    public static class TelemetryData {
        public int battery = -1;     // %
        public int heightCm = 0;     // h (cm)
        public int tofCm = 0;        // tof (cm)
        public int tempLowC = 0;     // templ (°C)
        public int tempHighC = 0;    // temph (°C)
        public int pitchDeg = 0;
        public int rollDeg = 0;
        public int yawDeg = 0;
        public int flightTimeSec = 0;
    }

    /**
     * Proveedor thread-safe leído directamente a 20 Hz (cada 50 ms) por el bucle de vuelo manual.
     * outAbcd[0] = a (Roll), outAbcd[1] = b (Pitch), outAbcd[2] = c (Throttle), outAbcd[3] = d (Yaw).
     */
    public interface RcAxisProvider {
        void readCurrentAxes(int[] outAbcd);
    }

    public interface Listener {
        void onConnectionStateChanged(boolean connected, String statusMessage);
        void onDronePoweredOffOrLost(int lastBattery, int lastTempC);
        void onCommandResponse(String commandSent, String response);
        void onTakeoffRejected(String droneError, int batteryPercent);
        void onTelemetryUpdated(TelemetryData data);
        void onRcSent(int a, int b, int c, int d, String formattedCmd, long txCount);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile RcAxisProvider rcAxisProvider;

    private String droneIp = DEFAULT_DRONE_IP;
    private InetAddress droneAddress;

    private DatagramSocket commandSocket;
    private DatagramSocket telemetrySocket;

    private Thread commandRxThread;
    private Thread telemetryRxThread;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> rcLoopTask;
    private ScheduledFuture<?> keepaliveAndWatchdogTask;

    private final AtomicLong flightOperationId = new AtomicLong(0L);
    private final AtomicLong rcTxPacketCount = new AtomicLong(0L);

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile boolean airborne = false;
    private volatile boolean commandInProgress = false;
    private volatile boolean landingInProgress = false;

    private volatile String activeExpectedCommand = "command";
    private volatile String latestCommandReply = null;
    private volatile long latestReplySeq = 0L;
    private volatile long pauseRcUntilMs = 0L;
    private volatile long lastUiRcNotifyMs = 0L;
    private volatile long lastPacketFromDroneMs = 0L;
    private volatile long lastAnyUdpSentMs = 0L;
    private volatile long lastLandActionMs = 0L;
    private volatile int remainingZeroBrakePackets = 0;

    private volatile int lastKnownBattery = -1;
    private volatile int lastKnownTempC = -1;
    private volatile int lastKnownHeightCm = 0;
    private volatile int lastKnownTofCm = 0;
    private volatile int prevFlightTimeSec = -1;
    private volatile long lastFlightTimeIncrementMs = 0L;

    // Consignas actuales de respaldo Modo 2 en [-100, 100]
    private volatile int rcRollA = 0;     // a: Roll (-100 izq, +100 der)
    private volatile int rcPitchB = 0;    // b: Pitch (-100 atrás, +100 adelante)
    private volatile int rcThrottleC = 0; // c: Throttle (-100 bajar, +100 subir)
    private volatile int rcYawD = 0;      // d: Yaw (-100 giro izq, +100 giro der)

    public TelloUdpClient(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void setRcAxisProvider(RcAxisProvider provider) {
        this.rcAxisProvider = provider;
    }

    public boolean isConnected() {
        return connected && running;
    }

    public boolean isAirborne() {
        return airborne;
    }

    public boolean isCommandInProgress() {
        return commandInProgress || landingInProgress;
    }

    public String getDroneIp() {
        return droneIp;
    }

    public static Network findActiveWifiNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void connect(String targetIp) {
        disconnectQuietly();

        if (targetIp != null && !targetIp.trim().isEmpty()) {
            this.droneIp = targetIp.trim();
        } else {
            this.droneIp = DEFAULT_DRONE_IP;
        }

        flightOperationId.incrementAndGet();
        rcTxPacketCount.set(0L);
        running = true;
        connected = false;
        airborne = false;
        commandInProgress = false;
        landingInProgress = false;
        remainingZeroBrakePackets = 0;
        prevFlightTimeSec = -1;
        lastFlightTimeIncrementMs = 0L;
        resetRcAxes();

        notifyConnectionState(false, "Conectando UDP a " + droneIp + ":" + COMMAND_PORT + "...");

        new Thread(() -> {
            try {
                Network wifiNetwork = findActiveWifiNetwork(appContext);
                bindProcessToWifi(wifiNetwork);

                droneAddress = InetAddress.getByName(droneIp);

                // 1. Socket UDP de Comandos (8889)
                try {
                    commandSocket = new DatagramSocket(null);
                    if (wifiNetwork != null) {
                        try { wifiNetwork.bindSocket(commandSocket); } catch (Exception ignored) {}
                    }
                    commandSocket.setReuseAddress(true);
                    commandSocket.bind(new InetSocketAddress(COMMAND_PORT));
                } catch (Exception bindEx) {
                    commandSocket = new DatagramSocket();
                    if (wifiNetwork != null) {
                        try { wifiNetwork.bindSocket(commandSocket); } catch (Exception ignored) {}
                    }
                }
                commandSocket.setSoTimeout(800);

                // 2. Socket UDP de Telemetría (8890)
                try {
                    telemetrySocket = new DatagramSocket(null);
                    if (wifiNetwork != null) {
                        try { wifiNetwork.bindSocket(telemetrySocket); } catch (Exception ignored) {}
                    }
                    telemetrySocket.setReuseAddress(true);
                    telemetrySocket.bind(new InetSocketAddress(TELEMETRY_PORT));
                    telemetrySocket.setSoTimeout(1200);
                } catch (Exception ignored) {}

                startCommandReceiverThread();
                if (telemetrySocket != null) {
                    startTelemetryReceiverThread();
                }

                // 3. Handshake SDK: enviar "command" (hasta 3 intentos)
                for (int attempt = 1; attempt <= 3 && running && !connected; attempt++) {
                    activeExpectedCommand = "command";
                    long seqBefore = latestReplySeq;
                    sendRawUdpPacket("command");
                    waitForNewReplySeq(seqBefore, 700L);
                }

                if (!running) return;

                if (connected) {
                    lastPacketFromDroneMs = System.currentTimeMillis();

                    // Activar video H.264 ("streamon")
                    activeExpectedCommand = "streamon";
                    long seqStream = latestReplySeq;
                    sendRawUdpPacket("streamon");
                    waitForNewReplySeq(seqStream, 600L);

                    // Consultar batería directamente por 8889
                    activeExpectedCommand = "battery?";
                    long seqBat = latestReplySeq;
                    sendRawUdpPacket("battery?");
                    waitForNewReplySeq(seqBat, 400L);

                    startSchedulers();
                    notifyConnectionState(true, "Conectado a Dron (" + droneIp + ")");
                } else {
                    String hint = (wifiNetwork == null)
                            ? "Wi-Fi apagado o sin conectar a la red TELLO-XXXXXX"
                            : "Sin respuesta de " + droneIp + " (verifica estar conectado al Wi-Fi del dron)";
                    disconnectQuietly();
                    notifyConnectionState(false, hint);
                }
            } catch (Exception e) {
                disconnectQuietly();
                notifyConnectionState(false, "Error UDP: " + e.getMessage());
            }
        }, "TelloConnectThread").start();
    }

    public void updateRcValues(int a, int b, int c, int d) {
        this.rcRollA = clamp100(a);
        this.rcPitchB = clamp100(b);
        this.rcThrottleC = clamp100(c);
        this.rcYawD = clamp100(d);
    }

    public void resetRcAxes() {
        this.rcRollA = 0;
        this.rcPitchB = 0;
        this.rcThrottleC = 0;
        this.rcYawD = 0;
    }

    private boolean isMotorsRecentlyIncrementing() {
        return lastFlightTimeIncrementMs > 0
                && (System.currentTimeMillis() - lastFlightTimeIncrementMs) < 2200L;
    }

    private boolean isMotorsSpinningOrElevated() {
        return isMotorsRecentlyIncrementing()
                || lastKnownHeightCm >= 20
                || (lastKnownTofCm >= 42 && lastKnownTofCm < 6500);
    }

    // =========================================================================
    // COMANDOS DISCRETOS CON SECUENCIACIÓN EXACTA Y SIN COLISIONES UDP
    // =========================================================================

    /**
     * Despegue automático ("takeoff"):
     * Espera a que el Tello termine su secuencia física de despegue y devuelva "ok" (~4.2 a 5.5 s)
     * ANTES de iniciar el stream continuo "rc a b c d" @ 20 Hz, evitando que el firmware
     * rechace los sticks con "error: Not joystick".
     */
    public void sendTakeoff() {
        if (!running || commandSocket == null || commandSocket.isClosed()) {
            notifyCommandResponse("takeoff", "Dron desconectado");
            return;
        }
        if (commandInProgress || landingInProgress) return;

        resetRcAxes();
        commandInProgress = true;
        final long opId = flightOperationId.incrementAndGet();

        new Thread(() -> {
            try {
                // 1. Re-confirmar modo SDK ("command") consumiendo su propio ACK primero
                activeExpectedCommand = "command";
                long seqCmd = latestReplySeq;
                sendRawUdpPacket("command");
                waitForNewReplySeq(seqCmd, 450L);
                waitUpToMs(80L);

                if (!running || opId != flightOperationId.get()) return;

                // 2. Enviar "takeoff" (1 solo paquete) y esperar su "ok" real de fin de ascenso
                activeExpectedCommand = "takeoff";
                long seqTakeoff = latestReplySeq;
                latestCommandReply = null;
                notifyCommandResponse("takeoff", "Subiendo... espera 'ok' (~4-5 s)");
                sendRawUdpPacket("takeoff");

                long startMs = System.currentTimeMillis();
                long currentSeq = seqTakeoff;

                while (running && opId == flightOperationId.get()
                        && (System.currentTimeMillis() - startMs) < 6200L) {
                    if (latestReplySeq > currentSeq && latestCommandReply != null) {
                        currentSeq = latestReplySeq;
                        String reply = latestCommandReply.trim();
                        if (isNumericBatteryReply(reply)) {
                            continue;
                        }
                        String lower = reply.toLowerCase(Locale.US);
                        if (lower.contains("ok")) {
                            // Breve respiro de 80 ms tras "ok" para que el firmware entre limpio en modo Joystick RC
                            waitUpToMs(80L);
                            if (opId == flightOperationId.get()) {
                                airborne = true;
                                commandInProgress = false;
                                notifyCommandResponse("takeoff", "ok -> ¡STICKS MODO 2 ACTIVOS!");
                            }
                            return;
                        } else if (lower.contains("error") || lower.contains("false")) {
                            airborne = false;
                            commandInProgress = false;
                            final String errText = reply;
                            final int bat = lastKnownBattery;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onTakeoffRejected(errText, bat));
                            }
                            return;
                        }
                    }
                    waitUpToMs(60L);
                }

                // Respaldo por si el paquete UDP "ok" se perdió en el aire tras 6.2 s de ascenso completo
                if (opId == flightOperationId.get()) {
                    airborne = true;
                    notifyCommandResponse("takeoff", "En vuelo -> ¡STICKS MODO 2 ACTIVOS!");
                }
            } finally {
                if (opId == flightOperationId.get()) {
                    commandInProgress = false;
                }
            }
        }, "TelloTakeoffThread").start();
    }

    public void sendManualArmMotorsCsc() {
        if (!running || commandSocket == null || commandSocket.isClosed()) return;
        resetRcAxes();
        commandInProgress = true;
        final long opId = flightOperationId.incrementAndGet();

        new Thread(() -> {
            try {
                activeExpectedCommand = "command";
                long seq = latestReplySeq;
                sendRawUdpPacket("command");
                waitForNewReplySeq(seq, 300L);

                notifyCommandResponse("CSC Armado", "Armando motores manualmente...");
                for (int i = 0; i < 22 && running && opId == flightOperationId.get(); i++) {
                    sendRawUdpPacket("rc -100 -100 -100 100");
                    waitUpToMs(50L);
                }
                if (opId == flightOperationId.get()) {
                    sendRawUdpPacket("rc 0 0 0 0");
                    airborne = true;
                    notifyCommandResponse("CSC Armado", "Motores listos -> Sube el Stick Izquierdo");
                }
            } finally {
                if (opId == flightOperationId.get()) {
                    commandInProgress = false;
                }
            }
        }, "TelloManualCscThread").start();
    }

    /**
     * Aterrizaje seguro sin "error" (1 solo paquete "land" + recuperación "stop" + respaldo RC vertical).
     */
    public void sendLand() {
        if (!running || commandSocket == null || commandSocket.isClosed()) {
            notifyCommandResponse("land", "Dron desconectado");
            return;
        }
        if (landingInProgress) {
            notifyCommandResponse("land", "Descendiendo... (aterrizaje en curso)");
            return;
        }

        final boolean wasAirborne = this.airborne || isMotorsSpinningOrElevated();
        resetRcAxes();
        this.airborne = false;
        this.remainingZeroBrakePackets = 0;
        this.landingInProgress = true;
        this.commandInProgress = true;
        long now = System.currentTimeMillis();
        this.lastLandActionMs = now;
        this.pauseRcUntilMs = now + 7500L;
        final long opId = flightOperationId.incrementAndGet();

        new Thread(() -> {
            try {
                // 1. Frenar velocidad residual de sticks antes de pedir aterrizaje
                sendRawUdpPacket("rc 0 0 0 0");
                waitUpToMs(90L);
                if (!running || opId != flightOperationId.get()) return;

                // 2. Intento 1: UN SOLO paquete "land"
                activeExpectedCommand = "land";
                long seq1 = latestReplySeq;
                latestCommandReply = null;
                notifyCommandResponse("land", "Descendiendo...");
                sendRawUdpPacket("land");

                int res1 = waitForLandingReplyOrGround(opId, seq1, 4800L);
                if (!running || opId != flightOperationId.get()) return;

                if (res1 == 1) {
                    airborne = false;
                    lastLandActionMs = System.currentTimeMillis();
                    notifyCommandResponse("land", "ok (En tierra - motores apagados)");
                    return;
                }

                // Si devolvió "error", verificar si el dron YA estaba en tierra con motores detenidos
                if (!wasAirborne && !isMotorsRecentlyIncrementing()
                        && lastKnownHeightCm <= 15
                        && (lastKnownTofCm <= 38 || lastKnownTofCm >= 6500)) {
                    airborne = false;
                    notifyCommandResponse("land", "Listo en tierra (motores detenidos)");
                    return;
                }

                // 3. Intento 2: liberar estado ocupado con "stop" y reintentar 1 "land"
                activeExpectedCommand = "land";
                long seqStop = latestReplySeq;
                sendRawUdpPacket("stop");
                waitForNewReplySeq(seqStop, 380L);
                waitUpToMs(240L);
                if (!running || opId != flightOperationId.get()) return;

                long seq2 = latestReplySeq;
                latestCommandReply = null;
                notifyCommandResponse("land", "Estabilizando y aterrizando...");
                sendRawUdpPacket("land");

                int res2 = waitForLandingReplyOrGround(opId, seq2, 4800L);
                if (!running || opId != flightOperationId.get()) return;

                if (res2 == 1) {
                    airborne = false;
                    lastLandActionMs = System.currentTimeMillis();
                    notifyCommandResponse("land", "ok (En tierra - motores apagados)");
                    return;
                }

                // 4. Respaldo final: descenso vertical asistido por RC hasta tocar suelo y apagar motores
                if (wasAirborne || isMotorsSpinningOrElevated()) {
                    notifyCommandResponse("land", "Descenso asistido RC...");
                    long descentStart = System.currentTimeMillis();
                    long lowAltitudeSince = 0L;

                    while (running && opId == flightOperationId.get()
                            && (System.currentTimeMillis() - descentStart) < 4200L) {
                        boolean nearFloor = (lastKnownTofCm > 0 && lastKnownTofCm <= 36)
                                || (lastKnownTofCm <= 0 && lastKnownHeightCm <= 10
                                    && (System.currentTimeMillis() - descentStart) > 1600L);

                        if (nearFloor) {
                            if (lowAltitudeSince == 0L) {
                                lowAltitudeSince = System.currentTimeMillis();
                            }
                            sendRawUdpPacket("rc 0 0 -100 0");
                            if ((System.currentTimeMillis() - lowAltitudeSince) >= 1150L) {
                                break;
                            }
                        } else {
                            lowAltitudeSince = 0L;
                            sendRawUdpPacket("rc 0 0 -75 0");
                        }
                        waitUpToMs(50L);
                    }
                    sendRawUdpPacket("rc 0 0 0 0");
                    airborne = false;
                    lastLandActionMs = System.currentTimeMillis();
                    notifyCommandResponse("land", "ok (Aterrizaje completado)");
                } else {
                    airborne = false;
                    notifyCommandResponse("land", "Listo en tierra (motores detenidos)");
                }
            } finally {
                landingInProgress = false;
                if (opId == flightOperationId.get()) {
                    airborne = false;
                    commandInProgress = false;
                    lastLandActionMs = System.currentTimeMillis();
                    pauseRcUntilMs = System.currentTimeMillis() + 600L;
                }
            }
        }, "TelloLandThread").start();
    }

    private int waitForLandingReplyOrGround(long opId, long seqBefore, long timeoutMs) {
        long start = System.currentTimeMillis();
        long currentSeq = seqBefore;

        while (running && opId == flightOperationId.get()
                && (System.currentTimeMillis() - start) < timeoutMs) {
            if (latestReplySeq > currentSeq && latestCommandReply != null) {
                currentSeq = latestReplySeq;
                String reply = latestCommandReply.trim();
                if (isNumericBatteryReply(reply)) {
                    continue;
                }
                String lower = reply.toLowerCase(Locale.US);
                if (lower.contains("ok") || lower.contains("motor stop") || lower.contains("auto land")) {
                    return 1;
                }
                if (lower.contains("error") || lower.contains("false") || lower.contains("not joystick")) {
                    return -1;
                }
            }

            if ((System.currentTimeMillis() - start) > 2600L
                    && !isMotorsRecentlyIncrementing()
                    && lastKnownHeightCm <= 10
                    && lastKnownTofCm > 0 && lastKnownTofCm <= 35) {
                return 1;
            }
            waitUpToMs(60L);
        }

        if (!isMotorsRecentlyIncrementing() && lastKnownHeightCm <= 12) {
            return 1;
        }
        return 0;
    }

    public void sendEmergencyStop() {
        resetRcAxes();
        airborne = false;
        remainingZeroBrakePackets = 0;
        landingInProgress = false;
        commandInProgress = false;
        long now = System.currentTimeMillis();
        lastLandActionMs = now;
        pauseRcUntilMs = now + 1500L;
        flightOperationId.incrementAndGet();

        new Thread(() -> {
            activeExpectedCommand = "emergency";
            for (int i = 0; i < 3 && running; i++) {
                sendRawUdpPacket("emergency");
                waitUpToMs(50L);
            }
            notifyCommandResponse("emergency", "CORTE DE MOTORES ENVIADO");
        }, "TelloEmergencyThread").start();
    }

    public void sendHoverStop() {
        resetRcAxes();
        new Thread(() -> {
            pauseRcUntilMs = System.currentTimeMillis() + 350L;
            activeExpectedCommand = "stop";
            sendRawUdpPacket("rc 0 0 0 0");
            waitUpToMs(40L);
            sendRawUdpPacket("stop");
        }, "TelloHoverThread").start();
    }

    public void sendStreamOn() {
        if (commandInProgress || landingInProgress) return;
        sendDiscreteCommand("streamon", 1, 350L);
    }

    public void sendStreamOff() {
        if (commandInProgress || landingInProgress) return;
        sendDiscreteCommand("streamoff", 1, 300L);
    }

    public void sendFlip(char direction) {
        if (landingInProgress) return;
        resetRcAxes();
        sendDiscreteCommand("flip " + direction, 1, 2300L);
    }

    public void sendStepMove(String moveCmd) {
        if (landingInProgress) return;
        resetRcAxes();
        airborne = true;
        sendDiscreteCommand(moveCmd, 1, 2300L);
    }

    public void sendSpeed(int speedCmPerSec) {
        if (landingInProgress) return;
        int clamped = Math.max(10, Math.min(100, speedCmPerSec));
        sendDiscreteCommand("speed " + clamped, 1, 300L);
    }

    public void sendDiscreteCommand(String cmd, int repeatCount, long pauseRcMs) {
        if (!running || commandSocket == null || commandSocket.isClosed()) {
            notifyCommandResponse(cmd, "Dron desconectado");
            return;
        }
        pauseRcUntilMs = System.currentTimeMillis() + pauseRcMs;
        activeExpectedCommand = cmd;

        new Thread(() -> {
            int count = Math.max(1, repeatCount);
            for (int i = 0; i < count && running; i++) {
                sendRawUdpPacket(cmd);
                if (i + 1 < count) {
                    waitUpToMs(70L);
                }
            }
        }, "TelloDiscreteCmdThread").start();
    }

    // =========================================================================
    // BUCLE DE VUELO MANUAL @ 20 Hz (LECTURA DIRECTA DE STICKS CADA 50 ms)
    // =========================================================================

    private void startSchedulers() {
        stopSchedulers();
        scheduler = Executors.newScheduledThreadPool(2);

        final int[] polledAxes = new int[4];

        // 1. Bucle de vuelo manual @ 20 Hz (cada 50 ms exactos)
        rcLoopTask = scheduler.scheduleAtFixedRate(() -> {
            if (!running || !connected || commandSocket == null || commandSocket.isClosed()) {
                return;
            }
            if (commandInProgress || landingInProgress) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < pauseRcUntilMs) {
                return;
            }

            // Leer directamente la posición actual en vivo de ambos sticks (o usar valores cacheados)
            int a, b, c, d;
            RcAxisProvider provider = this.rcAxisProvider;
            if (provider != null) {
                provider.readCurrentAxes(polledAxes);
                a = clamp100(polledAxes[0]);
                b = clamp100(polledAxes[1]);
                c = clamp100(polledAxes[2]);
                d = clamp100(polledAxes[3]);
                this.rcRollA = a;
                this.rcPitchB = b;
                this.rcThrottleC = c;
                this.rcYawD = d;
            } else {
                a = rcRollA;
                b = rcPitchB;
                c = rcThrottleC;
                d = rcYawD;
            }

            boolean sticksMoved = (a != 0 || b != 0 || c != 0 || d != 0);
            if (sticksMoved) {
                // Garantizar al menos 12 paquetes "rc 0 0 0 0" (600 ms) de frenado al soltar los sticks
                remainingZeroBrakePackets = 12;
            }

            if (airborne || sticksMoved || remainingZeroBrakePackets > 0) {
                if (!sticksMoved && remainingZeroBrakePackets > 0) {
                    remainingZeroBrakePackets--;
                }

                String rcCmd = String.format(Locale.US, "rc %d %d %d %d", a, b, c, d);
                boolean sentOk = sendRawUdpPacket(rcCmd);
                if (sentOk) {
                    long count = rcTxPacketCount.incrementAndGet();
                    if (now - lastUiRcNotifyMs >= 95L || sticksMoved) {
                        lastUiRcNotifyMs = now;
                        final int fa = a, fb = b, fc = c, fd = d;
                        if (listener != null) {
                            mainHandler.post(() -> listener.onRcSent(fa, fb, fc, fd, rcCmd, count));
                        }
                    }
                }
            }
        }, 80L, RC_PERIOD_MS, TimeUnit.MILLISECONDS);

        // 2. Watchdog de apagado físico (cada 2.5 s) + Keepalive "command" en reposo (sin interrumpir el stream RC de vuelo)
        keepaliveAndWatchdogTask = scheduler.scheduleAtFixedRate(() -> {
            if (!running || !connected) return;

            long now = System.currentTimeMillis();
            Network wifi = findActiveWifiNetwork(appContext);

            // Detectar si el dron se apagó físicamente o se perdió el Wi-Fi por > 8.5 s
            if (wifi == null || (lastPacketFromDroneMs > 0 && (now - lastPacketFromDroneMs) > 8500L)) {
                final int lastBat = lastKnownBattery;
                final int lastTemp = lastKnownTempC;
                disconnectQuietly();
                if (listener != null) {
                    mainHandler.post(() -> listener.onDronePoweredOffOrLost(lastBat, lastTemp));
                }
                return;
            }

            // En vuelo "rc a b c d" @ 20 Hz ya refresca 20 veces/seg el reloj de 15 s del Tello.
            // En tierra (cuando no se emiten paquetes RC), enviar "command" cada >= 5 s mantiene vivo el modo SDK.
            if (!commandInProgress && !landingInProgress
                    && now >= pauseRcUntilMs
                    && (now - lastAnyUdpSentMs) >= 5000L) {
                activeExpectedCommand = "keepalive";
                sendRawUdpPacket("command");
            }
        }, 2500L, 2500L, TimeUnit.MILLISECONDS);
    }

    private void stopSchedulers() {
        if (rcLoopTask != null) {
            rcLoopTask.cancel(true);
            rcLoopTask = null;
        }
        if (keepaliveAndWatchdogTask != null) {
            keepaliveAndWatchdogTask.cancel(true);
            keepaliveAndWatchdogTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // =========================================================================
    // RECEPCIÓN DE RESPUESTAS (8889) Y TELEMETRÍA (8890)
    // =========================================================================

    private void startCommandReceiverThread() {
        commandRxThread = new Thread(() -> {
            byte[] buf = new byte[512];
            while (running && commandSocket != null && !commandSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    commandSocket.receive(packet);
                    String resp = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!resp.isEmpty()) {
                        long now = System.currentTimeMillis();
                        lastPacketFromDroneMs = now;
                        latestCommandReply = resp;
                        latestReplySeq++;

                        String lower = resp.toLowerCase(Locale.US);
                        if (!connected && (lower.contains("ok") || isNumericBatteryReply(resp))) {
                            connected = true;
                        }

                        if (landingInProgress) {
                            continue;
                        }

                        // Auto-recuperación si una maniobra previa devolvió "Not joystick": dar 600 ms para estabilizar hover
                        if (lower.contains("not joystick")) {
                            pauseRcUntilMs = Math.max(pauseRcUntilMs, now + 600L);
                            continue;
                        }

                        if ("battery?".equals(activeExpectedCommand) && isNumericBatteryReply(resp)) {
                            int bat = Integer.parseInt(resp);
                            lastKnownBattery = bat;
                            TelemetryData td = new TelemetryData();
                            td.battery = bat;
                            td.heightCm = lastKnownHeightCm;
                            td.tofCm = lastKnownTofCm;
                            td.tempHighC = lastKnownTempC;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onTelemetryUpdated(td));
                            }
                        } else if ("keepalive".equals(activeExpectedCommand)) {
                            if (lower.contains("auto land") || lower.contains("motor stop")) {
                                airborne = false;
                                notifyCommandResponse("dron", resp);
                            }
                        } else if (lower.contains("error") || lower.contains("auto land")
                                || lower.contains("motor stop") || lower.contains("unknown")) {
                            if (lower.contains("auto land") || lower.contains("motor stop")) {
                                airborne = false;
                            }
                            notifyCommandResponse(activeExpectedCommand, resp);
                        } else {
                            notifyCommandResponse(activeExpectedCommand, resp);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }, "TelloCmdRxThread");
        commandRxThread.start();
    }

    private static boolean isNumericBatteryReply(String s) {
        if (s == null || s.isEmpty() || s.length() > 3) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private void startTelemetryReceiverThread() {
        telemetryRxThread = new Thread(() -> {
            byte[] buf = new byte[1024];
            long lastTelemetryUiUpdate = 0L;

            while (running && telemetrySocket != null && !telemetrySocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    telemetrySocket.receive(packet);
                    String stateStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (!stateStr.isEmpty()) {
                        long now = System.currentTimeMillis();
                        lastPacketFromDroneMs = now;
                        if (!connected && stateStr.contains("bat:")) {
                            connected = true;
                        }
                        TelemetryData parsed = parseTelemetryState(stateStr);
                        if (parsed.battery >= 0) {
                            lastKnownBattery = parsed.battery;
                            lastKnownHeightCm = parsed.heightCm;
                            if (parsed.tofCm > 0) lastKnownTofCm = parsed.tofCm;
                            int temp = parsed.tempHighC > 0 ? parsed.tempHighC : parsed.tempLowC;
                            if (temp > 0) lastKnownTempC = temp;

                            // Detectar si los motores están girando realmente por incremento activo de "time:"
                            if (prevFlightTimeSec >= 0 && parsed.flightTimeSec > prevFlightTimeSec) {
                                lastFlightTimeIncrementMs = now;
                                if (!commandInProgress && !landingInProgress
                                        && (now - lastLandActionMs) > 5000L) {
                                    airborne = true;
                                }
                            }
                            prevFlightTimeSec = parsed.flightTimeSec;
                        }
                        if (parsed.battery >= 0 && (now - lastTelemetryUiUpdate >= 180L)) {
                            lastTelemetryUiUpdate = now;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onTelemetryUpdated(parsed));
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }
        }, "TelloTelemetryThread");
        telemetryRxThread.start();
    }

    private static TelemetryData parseTelemetryState(String raw) {
        TelemetryData data = new TelemetryData();
        String[] pairs = raw.split(";");
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon <= 0 || colon >= pair.length() - 1) continue;
            String key = pair.substring(0, colon).trim();
            String val = pair.substring(colon + 1).trim();
            try {
                switch (key) {
                    case "bat":
                        data.battery = (int) Float.parseFloat(val);
                        break;
                    case "h":
                        data.heightCm = (int) Float.parseFloat(val);
                        break;
                    case "tof":
                        data.tofCm = (int) Float.parseFloat(val);
                        break;
                    case "templ":
                        data.tempLowC = (int) Float.parseFloat(val);
                        break;
                    case "temph":
                        data.tempHighC = (int) Float.parseFloat(val);
                        break;
                    case "pitch":
                        data.pitchDeg = (int) Float.parseFloat(val);
                        break;
                    case "roll":
                        data.rollDeg = (int) Float.parseFloat(val);
                        break;
                    case "yaw":
                        data.yawDeg = (int) Float.parseFloat(val);
                        break;
                    case "time":
                        data.flightTimeSec = (int) Float.parseFloat(val);
                        break;
                }
            } catch (NumberFormatException ignored) {}
        }
        return data;
    }

    private synchronized boolean sendRawUdpPacket(String asciiCommand) {
        if (commandSocket == null || commandSocket.isClosed() || droneAddress == null) return false;
        try {
            byte[] bytes = asciiCommand.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, droneAddress, COMMAND_PORT);
            commandSocket.send(packet);
            lastAnyUdpSentMs = System.currentTimeMillis();
            return true;
        } catch (IOException e) {
            // Re-vincular al Wi-Fi activo si Android rotó el handle de red
            try {
                Network wifi = findActiveWifiNetwork(appContext);
                if (wifi != null && commandSocket != null) {
                    wifi.bindSocket(commandSocket);
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    private void waitForNewReplySeq(long previousSeq, long maxWaitMs) {
        long start = System.currentTimeMillis();
        while (running && latestReplySeq <= previousSeq && (System.currentTimeMillis() - start) < maxWaitMs) {
            waitUpToMs(35L);
        }
    }

    private static void waitUpToMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public void disconnect() {
        boolean wasConnected = connected;
        disconnectQuietly();
        if (wasConnected) {
            notifyConnectionState(false, "Desconectado del dron");
        }
    }

    private void disconnectQuietly() {
        flightOperationId.incrementAndGet();
        running = false;
        connected = false;
        airborne = false;
        commandInProgress = false;
        landingInProgress = false;
        remainingZeroBrakePackets = 0;
        resetRcAxes();
        stopSchedulers();

        if (commandSocket != null) {
            try { commandSocket.close(); } catch (Exception ignored) {}
            commandSocket = null;
        }
        if (telemetrySocket != null) {
            try { telemetrySocket.close(); } catch (Exception ignored) {}
            telemetrySocket = null;
        }
        if (commandRxThread != null) {
            commandRxThread.interrupt();
            commandRxThread = null;
        }
        if (telemetryRxThread != null) {
            telemetryRxThread.interrupt();
            telemetryRxThread = null;
        }
        bindProcessToWifi(null);
    }

    private void bindProcessToWifi(Network wifiNetwork) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                cm.bindProcessToNetwork(wifiNetwork);
            }
        } catch (Exception ignored) {}
    }

    private void notifyConnectionState(boolean isConn, String msg) {
        if (listener != null) {
            mainHandler.post(() -> listener.onConnectionStateChanged(isConn, msg));
        }
    }

    private void notifyCommandResponse(String cmd, String resp) {
        if (listener != null) {
            mainHandler.post(() -> listener.onCommandResponse(cmd, resp));
        }
    }

    private static int clamp100(int v) {
        return Math.max(-100, Math.min(100, v));
    }
}
