package com.grupo1.controlauto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class DroneSimulatorActivity extends AppCompatActivity implements SensorEventListener {

    private TextView tvFlightState, tvFlightDirection, tvPitchAngle, tvRollAngle;
    private TextView tvMotorFL, tvMotorFR, tvMotorBL, tvMotorBR;
    private View viewHorizonBar;
    private Button btnHoldToFly, btnBackToCar;

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean isFlying = false;

    private static final float DEADZONE = 2.0f; // Zona muerta en m/s²

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_drone_simulator);

        tvFlightState = findViewById(R.id.tvFlightState);
        tvFlightDirection = findViewById(R.id.tvFlightDirection);
        tvPitchAngle = findViewById(R.id.tvPitchAngle);
        tvRollAngle = findViewById(R.id.tvRollAngle);
        tvMotorFL = findViewById(R.id.tvMotorFL);
        tvMotorFR = findViewById(R.id.tvMotorFR);
        tvMotorBL = findViewById(R.id.tvMotorBL);
        tvMotorBR = findViewById(R.id.tvMotorBR);
        viewHorizonBar = findViewById(R.id.viewHorizonBar);
        btnHoldToFly = findViewById(R.id.btnHoldToFly);
        btnBackToCar = findViewById(R.id.btnBackToCar);

        btnBackToCar.setOnClickListener(v -> finish());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        // Gatillo de Vuelo continuo
        btnHoldToFly.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    isFlying = true;
                    tvFlightState.setText("ESTADO: EN VUELO (SENSORES ACTIVOS)");
                    tvFlightState.setTextColor(ContextCompat.getColor(this, R.color.green_go));
                    btnHoldToFly.setText("VOLANDO... ¡INCLINA EL CELULAR!");
                    btnHoldToFly.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.cyan_accent));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isFlying = false;
                    tvFlightState.setText("ESTADO: EN TIERRA (MOTORES APAGADOS)");
                    tvFlightState.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                    tvFlightDirection.setText("ATERRIZADO (REPOSO)");
                    tvFlightDirection.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent));
                    btnHoldToFly.setText("MANTENER PARA VOLAR\n(Inclina el celular mientras apretas)");
                    btnHoldToFly.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_go));
                    actualizarMotores(0, 0, 0, 0);
                    resetearHorizonte();
                    return true;
                default:
                    return false;
            }
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isFlying || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        float x = event.values[0]; // Inclinación lateral (+ Izquierda / - Derecha)
        float y = event.values[1]; // Inclinación longitudinal (+ Atrás / - Adelante)

        int pitchDeg = (int) (y * -9.0f);
        int rollDeg = (int) (x * 9.0f);

        tvPitchAngle.setText("Pitch: " + pitchDeg + "°");
        tvRollAngle.setText("Roll: " + rollDeg + "°");

        // Animar barra de horizonte
        viewHorizonBar.setRotation(-x * 4.0f);
        viewHorizonBar.setTranslationY(y * 3.5f);

        boolean fwd = y < -DEADZONE;
        boolean bwd = y > DEADZONE;
        boolean left = x > DEADZONE;
        boolean right = x < -DEADZONE;

        String direction;
        int mFL = 65, mFR = 65, mBL = 65, mBR = 65;

        if (fwd && left) {
            direction = "ADELANTE-IZQUIERDA";
            mFL = 45; mFR = 85; mBL = 65; mBR = 95;
        } else if (fwd && right) {
            direction = "ADELANTE-DERECHA";
            mFL = 85; mFR = 45; mBL = 95; mBR = 65;
        } else if (bwd && left) {
            direction = "ATRAS-IZQUIERDA";
            mFL = 65; mFR = 95; mBL = 45; mBR = 85;
        } else if (bwd && right) {
            direction = "ATRAS-DERECHA";
            mFL = 95; mFR = 65; mBL = 85; mBR = 45;
        } else if (fwd) {
            direction = "AVANZANDO ADELANTE";
            mFL = 50; mFR = 50; mBL = 88; mBR = 88;
        } else if (bwd) {
            direction = "RETROCEDIENDO";
            mFL = 88; mFR = 88; mBL = 50; mBR = 50;
        } else if (left) {
            direction = "VIRANDO A LA IZQUIERDA";
            mFL = 50; mFR = 88; mBL = 50; mBR = 88;
        } else if (right) {
            direction = "VIRANDO A LA DERECHA";
            mFL = 88; mFR = 50; mBL = 88; mBR = 50;
        } else {
            direction = "VUELO ESTACIONARIO (HOVER)";
            mFL = 65; mFR = 65; mBL = 65; mBR = 65;
        }

        tvFlightDirection.setText(direction);
        actualizarMotores(mFL, mFR, mBL, mBR);
    }

    private void actualizarMotores(int fl, int fr, int bl, int br) {
        tvMotorFL.setText("M1 (Del-Izq):\n" + fl + "%");
        tvMotorFR.setText("M2 (Del-Der):\n" + fr + "%");
        tvMotorBL.setText("M3 (Tras-Izq):\n" + bl + "%");
        tvMotorBR.setText("M4 (Tras-Der):\n" + br + "%");
    }

    private void resetearHorizonte() {
        viewHorizonBar.setRotation(0);
        viewHorizonBar.setTranslationY(0);
        tvPitchAngle.setText("Pitch: 0°");
        tvRollAngle.setText("Roll: 0°");
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        isFlying = false;
    }
}
