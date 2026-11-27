package com.grupo1.controlauto;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodificador por hardware (MediaCodec "video/avc") del stream FPV H.264 del dron Tello
 * recibido por UDP en el puerto 11111 tras enviar el comando "streamon".
 *
 * Características:
 *  - Reensambla fragmentos UDP de 1460 bytes hasta recibir el paquete final (< 1460 bytes).
 *  - Extrae dinámicamente SPS (NAL tipo 7) y PPS (NAL tipo 8) para inicializar csd-0 y csd-1.
 *  - Espera al primer IDR Keyframe (NAL tipo 5) para evitar artefactos al inicio o tras pérdida Wi-Fi.
 *  - Renderiza directamente sobre el Surface del TextureView con mínima latencia y sin contención de hilos.
 */
public class TelloVideoDecoder {

    public static final int VIDEO_UDP_PORT = 11111;
    public static final int DEFAULT_VIDEO_WIDTH = 960;
    public static final int DEFAULT_VIDEO_HEIGHT = 720;
    private static final int TELLO_UDP_MTU = 1460;
    private static final int MAX_FRAME_BYTES = 512 * 1024;

    public interface VideoListener {
        void onFirstFrameRendered(int width, int height);
        void onVideoSizeChanged(int width, int height);
        void onStreamStalled();
    }

    private final Context appContext;
    private final VideoListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object codecLock = new Object();
    private final MediaCodec.BufferInfo outputBufferInfo = new MediaCodec.BufferInfo();

    private DatagramSocket videoSocket;
    private Thread udpReceiveThread;

    private MediaCodec mediaCodec;
    private volatile Surface outputSurface;

    private volatile boolean running = false;
    private volatile boolean codecConfigured = false;
    private volatile boolean waitingForKeyframe = true;
    private volatile boolean firstFrameNotified = false;

    private byte[] cachedSps = null;
    private byte[] cachedPps = null;
    private long framePresentationIndex = 0L;
    private int currentWidth = DEFAULT_VIDEO_WIDTH;
    private int currentHeight = DEFAULT_VIDEO_HEIGHT;

    public TelloVideoDecoder(Context context, VideoListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void setSurface(Surface surface) {
        synchronized (codecLock) {
            if (this.outputSurface == surface) return;
            releaseCodecLocked();
            this.outputSurface = surface;
            this.waitingForKeyframe = true;
            if (running && cachedSps != null && cachedPps != null && surface != null && surface.isValid()) {
                tryConfigureCodecLocked();
            }
        }
    }

    public void start() {
        if (running) return;
        running = true;
        firstFrameNotified = false;
        waitingForKeyframe = true;

        udpReceiveThread = new Thread(this::runUdpReceiveLoop, "TelloVideoUdpThread");
        udpReceiveThread.setPriority(Thread.MAX_PRIORITY);
        udpReceiveThread.start();
    }

    public void restart() {
        stop();
        start();
    }

    public void stop() {
        running = false;
        if (videoSocket != null) {
            try { videoSocket.close(); } catch (Exception ignored) {}
            videoSocket = null;
        }
        if (udpReceiveThread != null) {
            udpReceiveThread.interrupt();
            udpReceiveThread = null;
        }
        synchronized (codecLock) {
            releaseCodecLocked();
            cachedSps = null;
            cachedPps = null;
            firstFrameNotified = false;
            waitingForKeyframe = true;
        }
    }

    private void runUdpReceiveLoop() {
        ByteArrayOutputStream frameAccumulator = new ByteArrayOutputStream(65536);
        byte[] packetBuf = new byte[2048];
        int consecutiveTimeouts = 0;

        try {
            videoSocket = new DatagramSocket(null);
            videoSocket.setReuseAddress(true);
            try { videoSocket.setReceiveBufferSize(512 * 1024); } catch (Exception ignored) {}
            videoSocket.bind(new InetSocketAddress(VIDEO_UDP_PORT));
            videoSocket.setSoTimeout(1200);

            Network wifiNetwork = TelloUdpClient.findActiveWifiNetwork(appContext);
            if (wifiNetwork != null) {
                try { wifiNetwork.bindSocket(videoSocket); } catch (Exception ignored) {}
            }

            while (running && videoSocket != null && !videoSocket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(packetBuf, packetBuf.length);
                    videoSocket.receive(packet);
                    consecutiveTimeouts = 0;

                    int len = packet.getLength();
                    if (len > 0) {
                        if (frameAccumulator.size() + len > MAX_FRAME_BYTES) {
                            frameAccumulator.reset();
                            waitingForKeyframe = true;
                        }
                        frameAccumulator.write(packet.getData(), 0, len);
                    }

                    // En el protocolo H.264 del Tello, un paquete < 1460 bytes cierra la unidad de acceso
                    if (len < TELLO_UDP_MTU && frameAccumulator.size() > 0) {
                        byte[] accessUnit = frameAccumulator.toByteArray();
                        frameAccumulator.reset();
                        processAccessUnit(accessUnit);
                    }
                } catch (IOException timeoutOrClose) {
                    if (!running) break;
                    frameAccumulator.reset();
                    synchronized (codecLock) {
                        drainOutputBuffersLocked(0L);
                    }
                    consecutiveTimeouts++;
                    if (consecutiveTimeouts >= 3) {
                        consecutiveTimeouts = 0;
                        if (listener != null) {
                            mainHandler.post(listener::onStreamStalled);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (videoSocket != null) {
                try { videoSocket.close(); } catch (Exception ignored) {}
                videoSocket = null;
            }
        }
    }

    private static class NalScanResult {
        boolean hasIdr = false;
        boolean hasVclSlice = false;
        byte[] sps = null;
        byte[] pps = null;
    }

    private static NalScanResult scanAnnexBNals(byte[] data) {
        NalScanResult res = new NalScanResult();
        int len = data.length;
        if (len < 5) return res;

        List<Integer> starts = new ArrayList<>(8);
        List<Integer> prefixLens = new ArrayList<>(8);

        int i = 0;
        while (i <= len - 4) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 0 && data[i + 3] == 1) {
                    starts.add(i);
                    prefixLens.add(4);
                    i += 4;
                    continue;
                } else if (data[i + 2] == 1) {
                    starts.add(i);
                    prefixLens.add(3);
                    i += 3;
                    continue;
                }
            }
            i++;
        }

        for (int idx = 0; idx < starts.size(); idx++) {
            int start = starts.get(idx);
            int pLen = prefixLens.get(idx);
            int end = (idx + 1 < starts.size()) ? starts.get(idx + 1) : len;
            int headerPos = start + pLen;
            if (headerPos >= end) continue;

            int nalType = data[headerPos] & 0x1F;
            if (nalType == 7) {
                res.sps = copyWith4ByteStartCode(data, headerPos, end - headerPos);
            } else if (nalType == 8) {
                res.pps = copyWith4ByteStartCode(data, headerPos, end - headerPos);
            } else if (nalType == 5) {
                res.hasIdr = true;
                res.hasVclSlice = true;
            } else if (nalType == 1) {
                res.hasVclSlice = true;
            }
        }
        return res;
    }

    private static byte[] copyWith4ByteStartCode(byte[] src, int payloadOffset, int payloadLen) {
        byte[] out = new byte[4 + payloadLen];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(src, payloadOffset, out, 4, payloadLen);
        return out;
    }

    private void processAccessUnit(byte[] accessUnit) {
        NalScanResult scan = scanAnnexBNals(accessUnit);

        synchronized (codecLock) {
            boolean newConfig = false;
            if (scan.sps != null) {
                cachedSps = scan.sps;
                newConfig = true;
            }
            if (scan.pps != null) {
                cachedPps = scan.pps;
                newConfig = true;
            }

            if (!codecConfigured) {
                if (cachedSps != null && cachedPps != null && outputSurface != null && outputSurface.isValid()) {
                    tryConfigureCodecLocked();
                }
                if (!codecConfigured) return;
            } else if (newConfig && !scan.hasVclSlice) {
                return;
            }

            if (!scan.hasVclSlice) {
                return;
            }

            if (waitingForKeyframe) {
                if (!scan.hasIdr) {
                    return;
                }
                waitingForKeyframe = false;
            }

            if (mediaCodec == null) return;

            try {
                // Drenar buffers de salida listos antes de encolar nueva entrada
                drainOutputBuffersLocked(0L);

                int inIndex = mediaCodec.dequeueInputBuffer(10_000L);
                if (inIndex >= 0) {
                    ByteBuffer inputBuffer = mediaCodec.getInputBuffer(inIndex);
                    if (inputBuffer != null) {
                        inputBuffer.clear();
                        if (accessUnit.length <= inputBuffer.remaining()) {
                            inputBuffer.put(accessUnit, 0, accessUnit.length);
                            int flags = scan.hasIdr ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                            long ptsUs = framePresentationIndex * 33_333L;
                            framePresentationIndex++;
                            mediaCodec.queueInputBuffer(inIndex, 0, accessUnit.length, ptsUs, flags);
                        } else {
                            mediaCodec.queueInputBuffer(inIndex, 0, 0, 0L, 0);
                        }
                    }
                }

                // Drenar inmediatamente los frames recién decodificados para mínima latencia FPV
                drainOutputBuffersLocked(3_000L);
            } catch (Exception codecErr) {
                releaseCodecLocked();
                waitingForKeyframe = true;
            }
        }
    }

    private void drainOutputBuffersLocked(long firstWaitUs) {
        if (!codecConfigured || mediaCodec == null) return;

        long timeoutUs = firstWaitUs;
        while (codecConfigured && mediaCodec != null) {
            int outIndex = mediaCodec.dequeueOutputBuffer(outputBufferInfo, timeoutUs);
            timeoutUs = 0L; // Las iteraciones subsiguientes no bloquean

            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = mediaCodec.getOutputFormat();
                int w = outFmt.containsKey(MediaFormat.KEY_WIDTH)
                        ? outFmt.getInteger(MediaFormat.KEY_WIDTH) : DEFAULT_VIDEO_WIDTH;
                int h = outFmt.containsKey(MediaFormat.KEY_HEIGHT)
                        ? outFmt.getInteger(MediaFormat.KEY_HEIGHT) : DEFAULT_VIDEO_HEIGHT;
                if (w > 0 && h > 0) {
                    currentWidth = w;
                    currentHeight = h;
                    if (listener != null) {
                        mainHandler.post(() -> listener.onVideoSizeChanged(w, h));
                    }
                }
            } else if (outIndex >= 0) {
                boolean render = outputBufferInfo.size > 0;
                mediaCodec.releaseOutputBuffer(outIndex, render);
                if (render && !firstFrameNotified) {
                    firstFrameNotified = true;
                    final int w = currentWidth;
                    final int h = currentHeight;
                    if (listener != null) {
                        mainHandler.post(() -> listener.onFirstFrameRendered(w, h));
                    }
                }
            } else {
                break;
            }
        }
    }

    private void tryConfigureCodecLocked() {
        if (codecConfigured || outputSurface == null || !outputSurface.isValid()
                || cachedSps == null || cachedPps == null) {
            return;
        }

        try {
            MediaCodec codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaFormat format = buildAvcFormat(true);
            try {
                codec.configure(format, outputSurface, null, 0);
            } catch (Exception lowLatencyUnsupported) {
                codec.release();
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                codec.configure(buildAvcFormat(false), outputSurface, null, 0);
            }

            codec.start();
            this.mediaCodec = codec;
            this.codecConfigured = true;
            this.waitingForKeyframe = true;
            this.framePresentationIndex = 0L;
        } catch (Exception e) {
            releaseCodecLocked();
        }
    }

    private MediaFormat buildAvcFormat(boolean requestLowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(cachedSps));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(cachedPps));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_BYTES);
        if (requestLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        return format;
    }

    private void releaseCodecLocked() {
        codecConfigured = false;
        if (mediaCodec != null) {
            try { mediaCodec.stop(); } catch (Exception ignored) {}
            try { mediaCodec.release(); } catch (Exception ignored) {}
            mediaCodec = null;
        }
    }
}
