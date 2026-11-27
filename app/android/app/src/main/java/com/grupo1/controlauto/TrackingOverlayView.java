package com.grupo1.controlauto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Capa visual HUD táctica para el Detector y Seguidor IA de Objetos Comunes (TFLite v3.0).
 *
 * Muestra los 7 objetos comunes detectados, con corchetes tácticos verde neón sobre el objetivo
 * principal enganchado, cajas cian secundarias tocables para cambiar de objetivo, vector guía al
 * centro de la cámara y estado en vivo de giro / avance / mantenimiento de distancia.
 */
public class TrackingOverlayView extends View {

    public interface OnTargetTapListener {
        void onVideoTargetTapped(float videoNormX, float videoNormY);
    }

    private Paint crosshairPaint, vectorLinePaint, boxFillPaint, boxBracketPaint;
    private Paint secBoxPaint, secTagBgPaint, secTagTextPaint;
    private Paint primaryTagBgPaint, primaryTagTextPaint;
    private Paint badgeBgPaint, badgeBorderPaint, textPaint;

    private final RectF boxRect = new RectF();
    private final RectF tagRect = new RectF();
    private final RectF badgeRect = new RectF();

    private boolean trackingEnabled = false;
    private boolean droneAirborne = false;

    private boolean aiModelReady = false;
    private boolean targetLocked = false;
    private boolean usedZoomPass = false;
    private String lockedLabelEs = "OBJETO";
    private String filterShortLabelEs = "AUTO";
    private String distanceActionEs = "DISTANCIA OK";
    private boolean searching = false;
    private int searchDir = 0;
    private int aiLockedConfPct = 0;
    private long aiInferenceMs = 0L;
    private float normLeft = 0.45f, normTop = 0.35f, normRight = 0.55f, normBottom = 0.65f;
    private float normCx = 0.5f, normCy = 0.5f;
    private int cmdPitchB = 0, cmdThrottleC = 0, cmdYawD = 0;

    private final List<TelloAiObjectTracker.DetectedBox> detectedBoxes = new ArrayList<>();

    // Escalado actual del TextureView para alinear cajas y toques al píxel exacto del video 4:3
    private float videoScaleX = 1.0f;
    private float videoScaleY = 1.0f;

    private OnTargetTapListener tapListener;

    public TrackingOverlayView(Context context) {
        super(context);
        init(context);
    }

    public TrackingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TrackingOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;

        crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setColor(0x8838BDF8);
        crosshairPaint.setStrokeWidth(1.5f * dp);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{5f * dp, 5f * dp}, 0f));

        vectorLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        vectorLinePaint.setStyle(Paint.Style.STROKE);
        vectorLinePaint.setColor(0xCC00E676);
        vectorLinePaint.setStrokeWidth(2.2f * dp);

        boxFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxFillPaint.setStyle(Paint.Style.FILL);
        boxFillPaint.setColor(0x2200E676);

        boxBracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxBracketPaint.setStyle(Paint.Style.STROKE);
        boxBracketPaint.setColor(0xFF00E676);
        boxBracketPaint.setStrokeWidth(2.8f * dp);
        boxBracketPaint.setStrokeCap(Paint.Cap.ROUND);

        secBoxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secBoxPaint.setStyle(Paint.Style.STROKE);
        secBoxPaint.setColor(0xAA38BDF8);
        secBoxPaint.setStrokeWidth(1.6f * dp);
        secBoxPaint.setPathEffect(new DashPathEffect(new float[]{4f * dp, 3f * dp}, 0f));

        secTagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secTagBgPaint.setStyle(Paint.Style.FILL);
        secTagBgPaint.setColor(0xCC0B1528);

        secTagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secTagTextPaint.setColor(0xFF38BDF8);
        secTagTextPaint.setTextSize(9.5f * dp);
        secTagTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        primaryTagBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        primaryTagBgPaint.setStyle(Paint.Style.FILL);
        primaryTagBgPaint.setColor(0xEE052E16);

        primaryTagTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        primaryTagTextPaint.setColor(0xFF4ADE80);
        primaryTagTextPaint.setTextSize(10.5f * dp);
        primaryTagTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));

        badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBgPaint.setStyle(Paint.Style.FILL);
        badgeBgPaint.setColor(0xDD081020);

        badgeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeBorderPaint.setStyle(Paint.Style.STROKE);
        badgeBorderPaint.setColor(0xCC38BDF8);
        badgeBorderPaint.setStrokeWidth(1.3f * dp);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(10.5f * dp);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
    }

    public void setOnTargetTapListener(OnTargetTapListener listener) {
        this.tapListener = listener;
    }

    public void setVideoScaleTransform(float scaleX, float scaleY) {
        this.videoScaleX = (scaleX > 0.05f) ? scaleX : 1.0f;
        this.videoScaleY = (scaleY > 0.05f) ? scaleY : 1.0f;
        if (trackingEnabled) {
            invalidate();
        }
    }

    public void setTrackingEnabled(boolean enabled) {
        this.trackingEnabled = enabled;
        if (!enabled) {
            this.targetLocked = false;
            this.detectedBoxes.clear();
        }
        invalidate();
    }

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    public void updateTrackingState(TelloAiObjectTracker.AiTrackingOutput res, boolean isAirborne) {
        this.aiModelReady = res.modelReady;
        this.targetLocked = res.targetLocked;
        this.usedZoomPass = res.usedZoomPass;
        this.droneAirborne = isAirborne;
        this.lockedLabelEs = (res.lockedLabelEs != null && !res.lockedLabelEs.isEmpty())
                ? res.lockedLabelEs : "OBJETO";
        this.filterShortLabelEs = (res.filterShortLabelEs != null && !res.filterShortLabelEs.isEmpty())
                ? res.filterShortLabelEs : "AUTO";
        this.distanceActionEs = (res.distanceActionEs != null && !res.distanceActionEs.isEmpty())
                ? res.distanceActionEs : "DISTANCIA OK";
        this.aiLockedConfPct = res.lockedConfidencePct;
        this.searching = res.searching;
        this.searchDir = res.searchDir;
        this.aiInferenceMs = res.inferenceTimeMs;
        this.normLeft = res.normLeft;
        this.normTop = res.normTop;
        this.normRight = res.normRight;
        this.normBottom = res.normBottom;
        this.normCx = res.normCenterX;
        this.normCy = res.normCenterY;
        this.cmdPitchB = res.cmdPitchB;
        this.cmdThrottleC = res.cmdThrottleC;
        this.cmdYawD = res.cmdYawD;

        this.detectedBoxes.clear();
        this.detectedBoxes.addAll(res.detections);
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!trackingEnabled || tapListener == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return false;

            float cx = w / 2f;
            float cy = h / 2f;
            float videoW = w * videoScaleX;
            float videoH = h * videoScaleY;

            float vNormX = ((event.getX() - cx) / videoW) + 0.5f;
            float vNormY = ((event.getY() - cy) / videoH) + 0.5f;

            if (vNormX >= 0.0f && vNormX <= 1.0f && vNormY >= 0.0f && vNormY <= 1.0f) {
                tapListener.onVideoTargetTapped(vNormX, vNormY);
                return true;
            }
        }
        return false;
    }

    private float mapVideoXToScreen(float vNormX, int viewW) {
        float cx = viewW / 2f;
        return cx + (vNormX - 0.5f) * (viewW * videoScaleX);
    }

    private float mapVideoYToScreen(float vNormY, int viewH) {
        float cy = viewH / 2f;
        return cy + (vNormY - 0.5f) * (viewH * videoScaleY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!trackingEnabled) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float dp = getResources().getDisplayMetrics().density;
        float screenCx = w / 2f;
        float screenCy = h / 2f;

        // 1. Mira central de referencia del dron (+)
        float crossR = 22f * dp;
        canvas.drawLine(screenCx - crossR, screenCy, screenCx + crossR, screenCy, crosshairPaint);
        canvas.drawLine(screenCx, screenCy - crossR, screenCx, screenCy + crossR, crosshairPaint);
        canvas.drawCircle(screenCx, screenCy, 14f * dp, crosshairPaint);

        if (!aiModelReady) {
            drawTopBanner(canvas, w, dp, 0xFF38BDF8,
                    "CARGANDO DETECTOR IA TFLITE (OBJETOS COMUNES)...");
            return;
        }

        // 2. Dibujar objetos comunes secundarios en cian (el usuario puede tocarlos para fijarlos)
        for (int i = 0; i < detectedBoxes.size(); i++) {
            TelloAiObjectTracker.DetectedBox b = detectedBoxes.get(i);
            if (b.isPrimaryTarget) continue;

            float l = mapVideoXToScreen(b.left, w);
            float t = mapVideoYToScreen(b.top, h);
            float r = mapVideoXToScreen(b.right, w);
            float bot = mapVideoYToScreen(b.bottom, h);

            boxRect.set(l, t, r, bot);
            canvas.drawRect(boxRect, secBoxPaint);

            String tag = String.format(Locale.US, "%s %d%%", b.labelEs, Math.round(b.confidence * 100f));
            drawBoxLabel(canvas, l, t, dp, tag, secTagBgPaint, secTagTextPaint);
        }

        // 3. Si no hay objeto enganchado -> búsqueda activa hacia donde se fue, o Hover seguro
        if (!targetLocked) {
            if (searching && searchDir != 0) {
                String msg = (searchDir > 0)
                        ? String.format(Locale.US, "%s SALIÓ POR LA DERECHA · GIRANDO → PARA BUSCARLO", lockedLabelEs)
                        : String.format(Locale.US, "← GIRANDO PARA BUSCARLO · %s SALIÓ POR LA IZQUIERDA", lockedLabelEs);
                drawTopBanner(canvas, w, dp, 0xFFFB923C, msg);
                return;
            }
            String targetHint = "AUTO".equals(filterShortLabelEs) ? "OBJ. COMÚN" : filterShortLabelEs;
            String msg = String.format(Locale.US,
                    "IA BUSCANDO [%s]... HOVER (0,0,0,0) · %dms", targetHint, aiInferenceMs);
            drawTopBanner(canvas, w, dp, 0xFFFBBF24, msg);
            return;
        }

        // 4. Dibujar el objeto principal enganchado con corchetes tácticos verdes y vector guía
        drawPrimaryLockedTargetBox(canvas, w, h, dp, screenCx, screenCy);

        float left = mapVideoXToScreen(normLeft, w);
        float top = mapVideoYToScreen(normTop, h);
        String zoomSuffix = usedZoomPass ? " [ZOOM]" : "";
        String lockTag = String.format(Locale.US, "%s %d%%%s · %s",
                lockedLabelEs, aiLockedConfPct, zoomSuffix, distanceActionEs);
        drawBoxLabel(canvas, left, top, dp, lockTag, primaryTagBgPaint, primaryTagTextPaint);

        String statePrefix = droneAirborne ? ("SIGUIENDO " + lockedLabelEs) : ("VISTA PREVIA " + lockedLabelEs);
        String banner = String.format(Locale.US, "%s [%d%%] · GIRO:%+d AV:%+d ALT:%+d (%s)",
                statePrefix, aiLockedConfPct, cmdYawD, cmdPitchB, cmdThrottleC, distanceActionEs);
        drawTopBanner(canvas, w, dp, 0xFF00E676, banner);
    }

    private void drawPrimaryLockedTargetBox(Canvas canvas, int w, int h, float dp, float screenCx, float screenCy) {
        float left = mapVideoXToScreen(normLeft, w);
        float top = mapVideoYToScreen(normTop, h);
        float right = mapVideoXToScreen(normRight, w);
        float bottom = mapVideoYToScreen(normBottom, h);
        float targetX = mapVideoXToScreen(normCx, w);
        float targetY = mapVideoYToScreen(normCy, h);

        canvas.drawLine(screenCx, screenCy, targetX, targetY, vectorLinePaint);
        canvas.drawCircle(targetX, targetY, 4.5f * dp, boxBracketPaint);

        boxRect.set(left, top, right, bottom);
        canvas.drawRect(boxRect, boxFillPaint);

        float corner = Math.max(10f * dp, Math.min(boxRect.width(), boxRect.height()) * 0.26f);
        canvas.drawLine(left, top, left + corner, top, boxBracketPaint);
        canvas.drawLine(left, top, left, top + corner, boxBracketPaint);

        canvas.drawLine(right - corner, top, right, top, boxBracketPaint);
        canvas.drawLine(right, top, right, top + corner, boxBracketPaint);

        canvas.drawLine(left, bottom - corner, left, bottom, boxBracketPaint);
        canvas.drawLine(left, bottom, left + corner, bottom, boxBracketPaint);

        canvas.drawLine(right - corner, bottom, right, bottom, boxBracketPaint);
        canvas.drawLine(right, bottom - corner, right, bottom, boxBracketPaint);
    }

    private void drawBoxLabel(Canvas canvas, float boxLeft, float boxTop, float dp,
                              String text, Paint bgPaint, Paint txtPaint) {
        float padH = 6f * dp;
        float tagH = 17f * dp;
        float tagW = txtPaint.measureText(text) + padH * 2f;

        float top = (boxTop - tagH - 2f * dp > 40f * dp) ? (boxTop - tagH - 2f * dp) : (boxTop + 2f * dp);
        tagRect.set(boxLeft, top, boxLeft + tagW, top + tagH);

        canvas.drawRoundRect(tagRect, 4f * dp, 4f * dp, bgPaint);
        Paint.FontMetrics fm = txtPaint.getFontMetrics();
        float baseline = tagRect.centerY() - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, boxLeft + padH, baseline, txtPaint);
    }

    private void drawTopBanner(Canvas canvas, int viewW, float dp, int accentColor, String label) {
        badgeBorderPaint.setColor(accentColor);
        textPaint.setColor(accentColor);

        float textWidth = textPaint.measureText(label);
        float padH = 11f * dp;
        float boxW = textWidth + padH * 2f;
        float boxH = 24f * dp;

        float left = (viewW - boxW) / 2f;
        float top = 46f * dp;
        badgeRect.set(left, top, left + boxW, top + boxH);

        canvas.drawRoundRect(badgeRect, 8f * dp, 8f * dp, badgeBgPaint);
        canvas.drawRoundRect(badgeRect, 8f * dp, 8f * dp, badgeBorderPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = (top + boxH / 2f) - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, left + padH, baseline, textPaint);
    }
}
