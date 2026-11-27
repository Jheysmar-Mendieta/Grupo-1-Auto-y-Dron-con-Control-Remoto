package com.grupo1.controlauto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    private BluetoothSocket bluetoothSocket = null;
    private OutputStream outputStream = null;

    // UUID estándar para Bluetooth SPP (Serial Port Profile) compatible con HC-05 / HC-06
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView tvStatus, tvGyroData, tvHint;
    private SwitchCompat switchGyro;
    private Button btnConnect, btnDisconnect, btnForward, btnBackward, btnLeft, btnRight, btnStop;

    // Manejo de sensores (Acelerómetro / Giroscopio)
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean isGyroActive = false;
    private String lastGyroCommand = "S";
    private long lastSendTime = 0;

    // Umbral de zona muerta en m/s² (aprox 15-20 grados de inclinación)
    private static final float UMBRAL_INCLINACION = 2.8f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvGyroData = findViewById(R.id.tvGyroData);
        tvHint = findViewById(R.id.tvHint);
        switchGyro = findViewById(R.id.switchGyro);

        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnForward = findViewById(R.id.btnForward);
        btnBackward = findViewById(R.id.btnBackward);
        btnLeft = findViewById(R.id.btnLeft);
        btnStop = findViewById(R.id.btnStop);

        // Inicializar SensorManager
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        solicitarPermisos();

        btnConnect.setOnClickListener(v -> mostrarDialogoDispositivos());
        btnDisconnect.setOnClickListener(v -> desconectar());

        // Configuración de botones táctiles manuales
        configurarBotonContinuo(btnForward, "F");
        configurarBotonContinuo(btnBackward, "B");
        configurarBotonContinuo(btnLeft, "L");
        configurarBotonContinuo(btnRight, "R");

        btnStop.setOnClickListener(v -> {
            if (isGyroActive) {
                switchGyro.setChecked(false);
            }
            enviarComando("S");
        });

        // Configurar Switch de Giroscopio
        switchGyro.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isGyroActive = isChecked;
            if (isChecked) {
                if (accelerometer != null) {
                    sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
                    tvGyroData.setText("Giroscopio ACTIVO: Incliná el teléfono");
                    tvHint.setText("Modo Giroscopio: Incliná para mover - Nivelá para frenar");
                    habilitarBotonesPad(false);
                } else {
                    Toast.makeText(this, "Tu dispositivo no posee acelerómetro/giroscopio", Toast.LENGTH_SHORT).show();
                    switchGyro.setChecked(false);
                }
            } else {
                sensorManager.unregisterListener(this);
                enviarComando("S");
                lastGyroCommand = "S";
                tvGyroData.setText("Giroscopio inactivo (Usa botones abajo)");
                tvHint.setText("Mantené presionado para mover - Soltá para frenar");
                habilitarBotonesPad(true);
            }
        });
    }

    private void habilitarBotonesPad(boolean habilitar) {
        float alpha = habilitar ? 1.0f : 0.4f;
        btnForward.setEnabled(habilitar);
        btnForward.setAlpha(alpha);
        btnBackward.setEnabled(habilitar);
        btnBackward.setAlpha(alpha);
        btnLeft.setEnabled(habilitar);
        btnLeft.setAlpha(alpha);
        btnRight.setEnabled(habilitar);
        btnRight.setAlpha(alpha);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isGyroActive || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // event.values[0]: Inclinación lateral (X)
        // event.values[1]: Inclinación longitudinal adelante/atrás (Y)
        float x = event.values[0];
        float y = event.values[1];

        String nuevoComando = "S";
        String estadoTexto = "Nivelado (Frenado)";

        if (y < -UMBRAL_INCLINACION) {
            nuevoComando = "F";
            estadoTexto = "Adelante (" + String.format("%.0f", Math.abs(y * 10)) + "°)";
        } else if (y > UMBRAL_INCLINACION) {
            nuevoComando = "B";
            estadoTexto = "Atrás (" + String.format("%.0f", Math.abs(y * 10)) + "°)";
        } else if (x > UMBRAL_INCLINACION) {
            nuevoComando = "L";
            estadoTexto = "Giro Izquierda";
        } else if (x < -UMBRAL_INCLINACION) {
            nuevoComando = "R";
            estadoTexto = "Giro Derecha";
        }

        tvGyroData.setText("Inclinación: " + estadoTexto);

        long now = System.currentTimeMillis();
        // Solo enviar si cambió el comando o cada 300ms de refresco
        if (!nuevoComando.equals(lastGyroCommand) || (now - lastSendTime > 300)) {
            enviarComando(nuevoComando);
            lastGyroCommand = nuevoComando;
            lastSendTime = now;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

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

    @SuppressLint("ClickableViewAccessibility")
    private void configurarBotonContinuo(Button boton, String comando) {
        boton.setOnTouchListener((v, event) -> {
            if (isGyroActive) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    enviarComando(comando);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    enviarComando("S");
                    return true;
                default:
                    return false;
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void mostrarDialogoDispositivos() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth no disponible en este dispositivo", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Por favor activá el Bluetooth primero", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();
        if (bondedDevices == null || bondedDevices.isEmpty()) {
            Toast.makeText(this, "No hay dispositivos vinculados. Vinculá el HC-05 en Ajustes.", Toast.LENGTH_LONG).show();
            return;
        }

        final List<BluetoothDevice> deviceList = new ArrayList<>(bondedDevices);
        String[] nombres = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++) {
            BluetoothDevice dev = deviceList.get(i);
            String name = dev.getName() != null ? dev.getName() : "Desconocido";
            nombres[i] = name + "\n" + dev.getAddress();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Seleccionar Módulo HC-05 / HC-06");
        builder.setItems(nombres, (dialog, which) -> conectar(deviceList.get(which)));
        builder.show();
    }

    @SuppressLint("MissingPermission")
    private void conectar(BluetoothDevice device) {
        tvStatus.setText("Conectando a " + device.getName() + "...");

        new Thread(() -> {
            try {
                if (bluetoothSocket != null) {
                    bluetoothSocket.close();
                }
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothSocket.connect();
                outputStream = bluetoothSocket.getOutputStream();

                runOnUiThread(() -> {
                    String devName = device.getName() != null ? device.getName() : device.getAddress();
                    tvStatus.setText("Conectado a: " + devName);
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_go));
                    Toast.makeText(this, "¡Conectado con éxito!", Toast.LENGTH_SHORT).show();
                });
            } catch (IOException e) {
                try {
                    if (bluetoothSocket != null) bluetoothSocket.close();
                } catch (IOException ignored) {}

                runOnUiThread(() -> {
                    tvStatus.setText("Error al conectar. ¿Está encendido el HC-05?");
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.red_stop));
                    Toast.makeText(this, "Error de conexión: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void desconectar() {
        try {
            enviarComando("S");
            if (isGyroActive) {
                switchGyro.setChecked(false);
            }
            if (outputStream != null) outputStream.close();
            if (bluetoothSocket != null) bluetoothSocket.close();
            outputStream = null;
            bluetoothSocket = null;
            tvStatus.setText("Estado: Desconectado");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_white));
            Toast.makeText(this, "Desconectado", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error al desconectar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void enviarComando(String cmd) {
        if (outputStream == null) return;
        new Thread(() -> {
            try {
                outputStream.write(cmd.getBytes());
                outputStream.flush();
            } catch (IOException ignored) {}
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isGyroActive && sensorManager != null) {
            sensorManager.unregisterListener(this);
            enviarComando("S");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isGyroActive && sensorManager != null && accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        desconectar();
    }
}
