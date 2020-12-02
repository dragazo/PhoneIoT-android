package com.health.salio;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
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

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String VALUE_FORMAT = "%.3f";
    private static final String NOT_SUPPORTED_STRING = "Not supported on this device\n";

    private AppBarConfiguration mAppBarConfiguration;

    private class SensorInfo {
        public Sensor sensor;
        public float[] data;
        public boolean supported;

        public SensorInfo(Sensor s, int dims) {
            sensor = s;
            data = new float[dims];
            supported = false;
        }
        public void connect(SensorEventListener listener, SensorManager manager) {
            if (sensor != null) {
                supported = sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        public void updateData(float[] newData) {
            // copy over what we were given - anything we didn't get we set to zero (some sensors have optional values)
            int m = Math.min(data.length, newData.length);
            for (int i = 0; i < m; ++i) data[i] = newData[i];
            for (int i = m; i < data.length; ++i) data[i] = 0;
        }
        public void appendData(StringBuilder b) {
            if (supported) appendVector(b, data);
            else b.append("Not Supported on this Device");
        }
    }

    private SensorManager sensorManager;

    // motion sensors
    private SensorInfo accelerometer;
    private SensorInfo gravity;
    private SensorInfo gyroscope;
    private SensorInfo linearAcceleration;
    private SensorInfo rotationVector; // 4th value is optional (we will default it to zero at a user level)
    private SensorInfo stepCounter;

    // position sensors
    private SensorInfo gameRotationVector; // docs say this is 3d, but appears to be 4d in simulator
    private SensorInfo geomagneticRotationVector;
    private SensorInfo magneticField;
    private SensorInfo proximity; // some proximity sensors are binary (close/far)

    // environment sensors
    private SensorInfo ambientTemperature;
    private SensorInfo light;
    private SensorInfo pressure;
    private SensorInfo relativeHumidity;

    private TextView sensorDisplay;

    private static final int SERVER_PORT = 1975;
    private SocketAddress serverAddress = null;
    private DatagramSocket serverSocket = null;
    private byte[] macAddress = null;

    private Thread serverThread = null;

    private byte[] getMacAddress() {
        if (macAddress != null) return macAddress;

        // as of Marshmallow and above, Android no longer allows access to the MAC address.
        // but we just need a unique identifier, so we can just generate a random value for it instead.
        Random r = new Random();
        macAddress = new byte[6];
        r.nextBytes(macAddress);

        Toast.makeText(this, String.format("generated mac %s", macAddress.toString()), Toast.LENGTH_LONG).show();

        return macAddress;
    }
    private void connectToServer() {
        disconnectFromServer();

        EditText portText = findViewById(R.id.serverPortText);
        int port = Integer.parseInt(portText.getText().toString());
        try { serverSocket = new DatagramSocket(port); }
        catch (Exception ex) { Toast.makeText(this, String.format("Failed to open port %d: %s", port, ex.toString()), Toast.LENGTH_SHORT).show(); }

        EditText hostText = findViewById(R.id.serverHostText);
        serverAddress = new InetSocketAddress(hostText.getText().toString(), SERVER_PORT);

        if (serverThread == null) {
            serverThread = new Thread(() -> {
                byte[] buf = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buf, 0, buf.length);
                while (true) {
                    try {
                        if (serverSocket == null) {
                            Thread.sleep(500);
                            continue;
                        }
                        serverSocket.receive(packet);
                        if (packet.getLength() == 0) continue;

                        if (buf[0] == 'A') {
                            float[] vals = accelerometer.data;
                            byte[] resp = ByteBuffer.allocate(13).put((byte)'A').putFloat(vals[0]).putFloat(vals[1]).putFloat(vals[2]).array();
                            netsbloxSend(resp);
                        }
                    }
                    catch (Exception ex) {}
                }
            });
            serverThread.start();
        }

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
    }
    private void disconnectFromServer() {
        if (serverSocket != null) {
            serverSocket.close();
            serverSocket = null;
        }
    }
    private void netsbloxSend(byte[] content) {
        if (serverSocket != null && serverAddress != null) {
            byte[] expanded = new byte[content.length + 10];
            byte[] macAddr = getMacAddress();
            for (int i = 0; i < 6; ++i) expanded[i] = macAddr[i];
            for (int i = 0; i < 4; ++i) expanded[6 + i] = 0; // we can set the time field to zero (pretty sure it isn't actually used by the server)
            for (int i = 0; i < content.length; ++i) expanded[10 + i] = content[i];
            DatagramPacket packet = new DatagramPacket(expanded, expanded.length, serverAddress);
            try { serverSocket.send(packet); }
            catch (Exception ex) { Toast.makeText(this, String.format("failed to send packet: %s", ex.toString()), Toast.LENGTH_SHORT).show(); }
        }
    }

    private void grabSensors() {
        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        // motion sensors
        accelerometer = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 3);
        gravity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), 3);
        gyroscope = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), 3);
        linearAcceleration = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), 3);
        rotationVector = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), 4);
        stepCounter = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER), 1);

        // position sensors
        gameRotationVector = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR), 3);
        geomagneticRotationVector = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR), 3);
        magneticField = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), 3);
        proximity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), 1);

        // environment sensors
        ambientTemperature = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE), 1);
        light = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), 1);
        pressure = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE), 1);
        relativeHumidity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY), 1);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        Toolbar toolbar = findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);
//        FloatingActionButton fab = findViewById(R.id.fab);
//        fab.setOnClickListener(view -> Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG).setAction("Action", null).show());
//        DrawerLayout drawer = findViewById(R.id.drawer_layout);
//        NavigationView navigationView = findViewById(R.id.nav_view);
//        // Passing each menu ID as a set of Ids because each
//        // menu should be considered as top level destinations.
//        mAppBarConfiguration = new AppBarConfiguration.Builder(
//                R.id.nav_home, R.id.nav_gallery, R.id.nav_slideshow)
//                .setDrawerLayout(drawer)
//                .build();
//        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
//        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
//        NavigationUI.setupWithNavController(navigationView, navController);

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

        // motion sensors
        b.append("Accelerometer: ");
        if (accelerometer != null) accelerometer.appendData(b);
        b.append("\nGravity: ");
        if (gravity != null) gravity.appendData(b);
        b.append("\nGyroscope: ");
        if (gyroscope != null) gyroscope.appendData(b);
        b.append("\nLinear Accel: ");
        if (linearAcceleration != null) linearAcceleration.appendData(b);
        b.append("\nRot. Vector: ");
        if (rotationVector != null) rotationVector.appendData(b);
        b.append("\nStep Count: ");
        if (stepCounter != null) stepCounter.appendData(b);

        // position sensors
        b.append("\nGame Rot.: ");
        if (gameRotationVector != null) gameRotationVector.appendData(b);
        b.append("\nGeomag. Rot.: ");
        if (geomagneticRotationVector != null) geomagneticRotationVector.appendData(b);
        b.append("\nMag. Field: ");
        if (magneticField != null) magneticField.appendData(b);
        b.append("\nProximity: ");
        if (proximity != null) proximity.appendData(b);

        // environment sensors
        b.append("\nAmbient Temp.: ");
        if (ambientTemperature != null) ambientTemperature.appendData(b);
        b.append("\nLight Level: ");
        if (light != null) light.appendData(b);
        b.append("\nPressure: ");
        if (pressure != null) pressure.appendData(b);
        b.append("\nRel. Humidity: ");
        if (relativeHumidity != null) relativeHumidity.appendData(b);

        sensorDisplay.setText(b.toString());
    }

    @Override
    public final void onSensorChanged(SensorEvent e) {
        // motion sensors
        if (e.sensor == accelerometer.sensor) accelerometer.updateData(e.values);
        else if (e.sensor == gravity.sensor) gravity.updateData(e.values);
        else if (e.sensor == gyroscope.sensor) gyroscope.updateData(e.values);
        else if (e.sensor == linearAcceleration.sensor) linearAcceleration.updateData(e.values);
        else if (e.sensor == rotationVector.sensor) rotationVector.updateData(e.values);
        else if (e.sensor == stepCounter.sensor) stepCounter.updateData(e.values);
        // position sensors
        else if (e.sensor == gameRotationVector.sensor) gameRotationVector.updateData(e.values);
        else if (e.sensor == geomagneticRotationVector.sensor) geomagneticRotationVector.updateData(e.values);
        else if (e.sensor == magneticField.sensor) magneticField.updateData(e.values);
        else if (e.sensor == proximity.sensor) proximity.updateData(e.values);
        // environment sensors
        else if (e.sensor == ambientTemperature.sensor) ambientTemperature.updateData(e.values);
        else if (e.sensor == light.sensor) light.updateData(e.values);
        else if (e.sensor == pressure.sensor) pressure.updateData(e.values);
        else if (e.sensor == relativeHumidity.sensor) relativeHumidity.updateData(e.values);

        updateSensorDisplay();
    }
    @Override
    public final void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    void resubscribeListeners() {
        sensorManager.unregisterListener(this);
        grabSensors();

        // motion sensors
        accelerometer.connect(this, sensorManager);
        gravity.connect(this, sensorManager);
        gyroscope.connect(this, sensorManager);
        linearAcceleration.connect(this, sensorManager);
        rotationVector.connect(this, sensorManager);
        stepCounter.connect(this, sensorManager);

        // position sensors
        gameRotationVector.connect(this, sensorManager);
        geomagneticRotationVector.connect(this, sensorManager);
        magneticField.connect(this, sensorManager);
        proximity.connect(this, sensorManager);

        // environment sensors
        ambientTemperature.connect(this, sensorManager);
        light.connect(this, sensorManager);
        pressure.connect(this, sensorManager);
        relativeHumidity.connect(this, sensorManager);
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
    public void serverConnectButtonPress(View view) {
        connectToServer();
    }
    public void serverDisconnectButtonPress(View view) {
        //disconnectFromServer();
        netsbloxSend(new byte[] { 'I' });
    }
}