package com.grupo1.controlauto;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.Collections;

/**
 * Joystick virtual analógico Modo 2 para teleoperación de dron (v2.5).
 *
 * Algoritmo de mapeo táctil:
 * 1. Distancia vectorial (dx, dy) respecto al centro (centerX, centerY).
 * 2. Normalización respecto al radio máximo R: clamp(delta / R, -1.0, 1.0),
 *    con convención cartesiana estándar RC Modo 2:
 *      - Eje Horizontal (X): Izquierda = -1.0, Derecha = +1.0
 *      - Eje Vertical   (Y): Abajo     = -1.0, Arriba  = +1.0
 * 3. Zona muerta (deadband) central del 7% (0.07) con reescalado suave continuo.
 * 4. Resorte virtual: al soltar el dedo, vuelve automáticamente a (0, 0).
 * 5. Exclusión de gestos de borde de Android 10+ (evita que el gesto "Atrás/Inicio"
 *    del sistema corte el movimiento del pulgar con ACTION_CANCEL).
 */
public class JoystickView extends View {

    public interface OnJoystickMoveListener {
        /**
         * @param normX Valor horizontal normalizado [-1.0, +1.0] con deadband aplicada (+ = Derecha)
         * @param normY Valor vertical normalizado [-1.0, +1.0] con deadband aplicada (+ = Arriba)
         */
        void onJoystickMove(float normX, float normY);
    }

    // Zona muerta del 7% (dentro del rango recomendado 5% - 8%)
    public static final float DEFAULT_DEADBAND = 0.07f;

    private Paint basePaint, baseStrokePaint, baseActiveStrokePaint, axisPaint, deadbandPaint, vectorPaint;
    private Paint knobPaint, knobStrokePaint, knobActiveStrokePaint, knobCenterPaint;

    private float centerX = 0f;
    private float centerY = 0f;
    private float baseRadius = 0f;
    private float maxTravelRadius = 1f;
    private float knobRadius = 0f;

    private float knobX = 0f;
    private float knobY = 0f;

    private float deadband = DEFAULT_DEADBAND;
    private volatile float normalizedX = 0f;
    private volatile float normalizedY = 0f;
    private volatile boolean isTouched = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private final Rect gestureExclusionRect = new Rect();
    private OnJoystickMoveListener listener;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setClickable(true);
        setFocusable(false);

        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(0xC20F172A);

        baseStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseStrokePaint.setStyle(Paint.Style.STROKE);
        baseStrokePaint.setColor(0xCC38BDF8);
        baseStrokePaint.setStrokeWidth(3.5f);

        baseActiveStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseActiveStrokePaint.setStyle(Paint.Style.STROKE);
        baseActiveStrokePaint.setColor(0xEE00E676);
        baseActiveStrokePaint.setStrokeWidth(4.5f);

        axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setColor(0x5564748B);
        axisPaint.setStrokeWidth(2f);
        axisPaint.setPathEffect(new DashPathEffect(new float[]{6f, 6f}, 0f));

        deadbandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        deadbandPaint.setStyle(Paint.Style.STROKE);
        deadbandPaint.setColor(0x4494A3B8);
        deadbandPaint.setStrokeWidth(1.5f);

        vectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        vectorPaint.setStyle(Paint.Style.STROKE);
        vectorPaint.setColor(0xCC00E5FF);
        vectorPaint.setStrokeWidth(4.5f);

        knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(0xF01E293B);

        knobStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        knobStrokePaint.setStyle(Paint.Style.STROKE);
        knobStrokePaint.setColor(0xFF94A3B8);
        knobStrokePaint.setStrokeWidth(3f);

        knobActiveStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        knobActiveStrokePaint.setStyle(Paint.Style.STROKE);
        knobActiveStrokePaint.setColor(0xFF00E676);
        knobActiveStrokePaint.setStrokeWidth(4.5f);

        knobCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        knobCenterPaint.setStyle(Paint.Style.FILL);
        knobCenterPaint.setColor(0xFF38BDF8);
    }

    public void setOnJoystickMoveListener(OnJoystickMoveListener listener) {
        this.listener = listener;
    }

    public void setDeadband(float deadband) {
        this.deadband = Math.max(0.0f, Math.min(0.30f, deadband));
    }

    /**
     * Lectura thread-safe del eje horizontal [-1.0, +1.0] (+ = Derecha) para el bucle de 20 Hz.
     */
    public float getNormalizedX() {
        return normalizedX;
    }

    /**
     * Lectura thread-safe del eje vertical [-1.0, +1.0] (+ = Arriba) para el bucle de 20 Hz.
     */
    public float getNormalizedY() {
        return normalizedY;
    }

    public boolean isStickTouched() {
        return isTouched;
    }

    /**
     * Fuerza el retorno inmediato al centro (resorte virtual -> 0, 0).
     */
    public void resetStick() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        isTouched = false;
        knobX = centerX;
        knobY = centerY;
        normalizedX = 0f;
        normalizedY = 0f;
        postInvalidate();
        if (listener != null) {
            listener.onJoystickMove(0f, 0f);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f - 6f;
        knobRadius = baseRadius * 0.34f;
        maxTravelRadius = Math.max(1f, baseRadius - (knobRadius * 0.55f));
        if (!isTouched) {
            knobX = centerX;
            knobY = centerY;
        }
        actualizarExclusionGestosSistema(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        actualizarExclusionGestosSistema(right - left, bottom - top);
    }

    private void actualizarExclusionGestosSistema(int w, int h) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w > 0 && h > 0) {
            // Excluir toda la zona del joystick (más margen exterior) de los gestos de navegación de Android 10+
            gestureExclusionRect.set(-40, -40, w + 40, h + 40);
            setSystemGestureExclusionRects(Collections.singletonList(gestureExclusionRect));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Base circular exterior semitransparente
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, isTouched ? baseActiveStrokePaint : baseStrokePaint);

        // 2. Guías de ejes cartesianos (+) y anillo de zona muerta (deadband)
        float pad = baseRadius * 0.14f;
        canvas.drawLine(centerX - baseRadius + pad, centerY, centerX + baseRadius - pad, centerY, axisPaint);
        canvas.drawLine(centerX, centerY - baseRadius + pad, centerX, centerY + baseRadius - pad, axisPaint);
        canvas.drawCircle(centerX, centerY, maxTravelRadius * deadband, deadbandPaint);

        // 3. Vector activo desde el centro al pomo cuando el usuario toca el stick
        if (isTouched) {
            canvas.drawLine(centerX, centerY, knobX, knobY, vectorPaint);
        }

        // 4. Pomo interior (Thumbstick)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
        canvas.drawCircle(knobX, knobY, knobRadius, isTouched ? knobActiveStrokePaint : knobStrokePaint);
        canvas.drawCircle(knobX, knobY, knobRadius * 0.28f, knobCenterPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                ViewParent parent = getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                if (!isTouched || activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    int idx = (action == MotionEvent.ACTION_POINTER_DOWN) ? event.getActionIndex() : 0;
                    activePointerId = event.getPointerId(idx);
                    isTouched = true;
                    updateStickFromTouch(event.getX(idx), event.getY(idx));
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0 && event.getPointerCount() > 0) {
                    pointerIndex = 0;
                    activePointerId = event.getPointerId(0);
                }
                if (pointerIndex >= 0) {
                    isTouched = true;
                    updateStickFromTouch(event.getX(pointerIndex), event.getY(pointerIndex));
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    // Si queda otro dedo dentro de este mismo stick, pasarle el control; si no, centrar
                    if (event.getPointerCount() > 1) {
                        int newIdx = (pointerIndex == 0) ? 1 : 0;
                        activePointerId = event.getPointerId(newIdx);
                        updateStickFromTouch(event.getX(newIdx), event.getY(newIdx));
                    } else {
                        returnToCenter();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                returnToCenter();
                return true;
            }
        }
        return true;
    }

    private void updateStickFromTouch(float touchX, float touchY) {
        if (maxTravelRadius <= 0f) return;

        // 1. Distancia vectorial (dx, dy) respecto al centro
        float dx = touchX - centerX;
        float dy = touchY - centerY;

        // Posición visual del pomo limitada al círculo de radio maxTravelRadius
        float distance = (float) Math.hypot(dx, dy);
        float visualDx = dx;
        float visualDy = dy;
        if (distance > maxTravelRadius) {
            visualDx = (dx / distance) * maxTravelRadius;
            visualDy = (dy / distance) * maxTravelRadius;
        }

        knobX = centerX + visualDx;
        knobY = centerY + visualDy;

        // 2. Normalización respecto al radio máximo R: clamp(delta / R, -1.0, 1.0)
        // Nota: En pantalla Android +Y apunta hacia abajo, por lo que invertimos dy (-dy)
        // para que empujar el stick hacia ARRIBA sea +1.0 y hacia ABAJO sea -1.0.
        float rawNormX = clamp(dx / maxTravelRadius, -1.0f, 1.0f);
        float rawNormY = clamp(-dy / maxTravelRadius, -1.0f, 1.0f);

        // 3. Aplicación de zona muerta (deadband 7%)
        normalizedX = applyDeadband(rawNormX, deadband);
        normalizedY = applyDeadband(rawNormY, deadband);

        invalidate();

        if (listener != null) {
            listener.onJoystickMove(normalizedX, normalizedY);
        }
    }

    private void returnToCenter() {
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        isTouched = false;
        knobX = centerX;
        knobY = centerY;
        normalizedX = 0f;
        normalizedY = 0f;
        invalidate();

        if (listener != null) {
            listener.onJoystickMove(0f, 0f);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float applyDeadband(float value, float deadband) {
        float abs = Math.abs(value);
        if (abs < deadband) {
            return 0f;
        }
        float rescaled = (abs - deadband) / (1.0f - deadband);
        return Math.signum(value) * Math.min(1.0f, rescaled);
    }
}
