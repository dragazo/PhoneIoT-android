package com.health.salio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.navigation.NavigationView;

import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String VALUE_FORMAT = "%.3f";
    private static final String NOT_SUPPORTED_STRING = "Not supported on this device\n";

    private AppBarConfiguration mAppBarConfiguration;

    private SensorManager sensorManager;
    private Sensor gravitySensor;
    private Sensor accelerometer;
    private Sensor accelerometerLinear;
    private Sensor gyroscope;
    private Sensor rotationSensor;
    private Sensor gameRotationSensor;
    private Sensor stepCountSensor;
    private Sensor magneticSensor;
    private Sensor proximitySensor;
    private Sensor ambientTempSensor;
    private Sensor lightSensor;
    private Sensor pressureSensor;
    private Sensor relativeHumiditySensor;

    private float[] gravityData;
    private float[] accelerometerData;
    private float[] accelerometerLinearData;
    private float[] gyroscopeData;
    private float[] rotationData;
    private float[] gameRotationData;
    private float[] magneticData;
    private float stepCount = Float.NaN;
    private float proximity = Float.NaN;
    private float ambientTemp = Float.NaN;
    private float lightLevel = Float.NaN;
    private float pressure = Float.NaN;
    private float relativeHumidity = Float.NaN;

    private TextView sensorDisplay;

    private void grabSensors() {
        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerLinear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        gameRotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        stepCountSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        ambientTempSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        relativeHumiditySensor = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(view -> Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG).setAction("Action", null).show());
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        NavigationView navigationView = findViewById(R.id.nav_view);
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow)
                .setDrawerLayout(drawer)
                .build();
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navigationView, navController);

        grabSensors();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration) || super.onSupportNavigateUp();
    }

    private static void appendVector(StringBuilder b, float[] vec) {
        float s = 0;
        for (int i = 0; i < vec.length; ) {
            s += vec[i] * vec[i];
            b.append(String.format(VALUE_FORMAT, vec[i]));
            if (++i < vec.length) b.append(", ");
        }
        b.append(" (");
        b.append(String.format(VALUE_FORMAT, Math.sqrt(s)));
        b.append(')');
    }
    private void updateSensorDisplay() {
        if (sensorDisplay == null || !sensorDisplay.isShown()) sensorDisplay = findViewById(R.id.sensorDisplay);
        if (sensorDisplay == null || !sensorDisplay.isShown()) return;

        StringBuilder b = new StringBuilder();

        b.append("Gravity: ");
        if (gravityData != null) {
            appendVector(b, gravityData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Acceleration: ");
        if (accelerometerData != null) {
            appendVector(b, accelerometerData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Linear Accel.: ");
        if (accelerometerLinearData != null) {
            appendVector(b, accelerometerLinearData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Gyroscope: ");
        if (gyroscopeData != null) {
            appendVector(b, gyroscopeData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Rotation: ");
        if (rotationData != null) {
            appendVector(b, rotationData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Game Rot.: ");
        if (gameRotationData != null) {
            appendVector(b, gameRotationData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Steps: ");
        if (!Float.isNaN(stepCount)) {
            b.append(stepCount);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Magnetic Field: ");
        if (magneticData != null) {
            appendVector(b, magneticData);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Proximity: ");
        if (!Float.isNaN(proximity)) {
            b.append(proximity);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        // --------------

        b.append("Temp.: ");
        if (!Float.isNaN(ambientTemp)) {
            b.append(ambientTemp);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Light: ");
        if (!Float.isNaN(lightLevel)) {
            b.append(lightLevel);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Pressure: ");
        if (!Float.isNaN(pressure)) {
            b.append(pressure);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        b.append("Rel. Humidity: ");
        if (!Float.isNaN(relativeHumidity)) {
            b.append(relativeHumidity);
            b.append('\n');
        }
        else b.append(NOT_SUPPORTED_STRING);

        sensorDisplay.setText(b.toString());
    }

    @Override
    public final void onSensorChanged(SensorEvent e) {
        if (e.sensor == gravitySensor) gravityData = e.values;
        else if (e.sensor == accelerometer) accelerometerData = e.values;
        else if (e.sensor == accelerometerLinear) accelerometerLinearData = e.values;
        else if (e.sensor == gyroscope) gyroscopeData = e.values;
        else if (e.sensor == rotationSensor) rotationData = e.values;
        else if (e.sensor == gameRotationSensor) gameRotationData = e.values;
        else if (e.sensor == stepCountSensor) stepCount = e.values[0];
        else if (e.sensor == magneticSensor) magneticData = e.values;
        else if (e.sensor == proximitySensor) proximity = e.values[0];
        else if (e.sensor == ambientTempSensor) ambientTemp = e.values[0];
        else if (e.sensor == lightSensor) lightLevel = e.values[0];
        else if (e.sensor == pressureSensor) pressure = e.values[0];
        else if (e.sensor == relativeHumiditySensor) relativeHumidity = e.values[0];

        updateSensorDisplay();
    }
    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    void resubscribeListeners() {
        sensorManager.unregisterListener(this);
        grabSensors();
        if (gravitySensor != null) sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (accelerometer != null) sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        if (accelerometerLinear != null) sensorManager.registerListener(this, accelerometerLinear, SensorManager.SENSOR_DELAY_NORMAL);
        if (gyroscope != null) sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        if (rotationSensor != null) sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (gameRotationSensor != null) sensorManager.registerListener(this, gameRotationSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (stepCountSensor != null) sensorManager.registerListener(this, stepCountSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (magneticSensor != null) sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (proximitySensor != null) sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (ambientTempSensor != null) sensorManager.registerListener(this, ambientTempSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (pressureSensor != null) sensorManager.registerListener(this, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (relativeHumiditySensor != null) sensorManager.registerListener(this, relativeHumiditySensor, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resubscribeListeners();
    }
    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    public void updateSensorsButtonPress(View view) {
        Toast.makeText(this, "updating sensors", Toast.LENGTH_SHORT).show();
        resubscribeListeners();
    }
}