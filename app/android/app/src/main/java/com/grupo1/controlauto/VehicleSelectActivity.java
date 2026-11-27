package com.grupo1.controlauto;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class VehicleSelectActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_select);

        LinearLayout cardCar = findViewById(R.id.cardSelectCar);
        LinearLayout cardDrone = findViewById(R.id.cardSelectDrone);
        Button btnOpenCar = findViewById(R.id.btnOpenCar);
        Button btnOpenDrone = findViewById(R.id.btnOpenDrone);

        // Click en Auto
        cardCar.setOnClickListener(v -> abrirAuto());
        btnOpenCar.setOnClickListener(v -> abrirAuto());

        // Click en Dron
        cardDrone.setOnClickListener(v -> abrirDron());
        btnOpenDrone.setOnClickListener(v -> abrirDron());

        solicitarPermisos();
    }

    private void abrirAuto() {
        Intent intent = new Intent(this, CarControlActivity.class);
        startActivity(intent);
    }

    private void abrirDron() {
        Intent intent = new Intent(this, DroneControlActivity.class);
        startActivity(intent);
    }

    private void solicitarPermisos() {
        List<String> permisos = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT);
            permisos.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            permisos.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permisos.add(Manifest.permission.ACCESS_COARSE_LOCATION);
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
}

