package com.health.salio;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

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
    private static final int LOCAL_PORT = 8888;
    private DatagramSocket socket = null; // our socket for udp comms - do not close or change it
    private SocketAddress netsbloxAddress = null; // target for heartbeat comms - can be changed at will
    private byte[] macAddress = null;

    private Thread serverThread = null;
    private long next_heartbeat = 0;

    private void connectToServer() {
        if (socket == null) { // open the socket if it's not already
            try {
                socket = new DatagramSocket(LOCAL_PORT);
            } catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to open port %d: %s", LOCAL_PORT, ex.toString()), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        EditText hostText = findViewById(R.id.serverHostText);
        netsbloxAddress = new InetSocketAddress(hostText.getText().toString(), SERVER_PORT);
        next_heartbeat = 0; // trigger a heartbeat on the next network thread wakeup

        if (serverThread == null) {
            serverThread = new Thread(() -> {
                byte[] buf = new byte[1024];
                DatagramPacket packet = new DatagramPacket(buf, 0, buf.length);
                while (true) {
                    try {
                        long now_time = System.currentTimeMillis();
                        if (now_time >= next_heartbeat) {
                            netsbloxSend(new byte[] { 'I' }, netsbloxAddress); // send heartbeat so server knows we're still there
                            next_heartbeat = now_time + 60 * 1000; // next heartbeat in 1 minute
                        }

                        // wait for a message - short duration is so we can see reconnections quickly
                        socket.setSoTimeout(1 * 1000);
                        socket.receive(packet);
                        if (packet.getLength() == 0) continue;
                        switch (buf[0]) {
                            case 'A': {
                                float[] vals = accelerometer.data;
                                byte[] resp = ByteBuffer.allocate(13).put(buf[0]).putFloat(vals[0]).putFloat(vals[1]).putFloat(vals[2]).array();
                                netsbloxSend(resp, packet.getSocketAddress());
                                break;
                            }
                            case 'P': {
                                byte[] resp = ByteBuffer.allocate(5).put(buf[0]).putFloat(proximity.data[0]).array();
                                netsbloxSend(resp, packet.getSocketAddress());
                                break;
                            }
                        }
                    }
                    catch (SocketTimeoutException ignored) {} // this is fine - just means we hit the timeout we requested
                    catch (Exception ex) {
                        System.err.printf("network thread exception: %s", ex);
                    }
                }
            });
            serverThread.start();
        }
    }
    private void netsbloxSend(byte[] content, SocketAddress dest) throws Exception {
        if (socket != null) {
            byte[] expanded = new byte[content.length + 10];
            for (int i = 0; i < 6; ++i) expanded[i] = macAddress[i];
            for (int i = 0; i < 4; ++i) expanded[6 + i] = 0; // we can set the time field to zero (pretty sure it isn't actually used by the server)
            for (int i = 0; i < content.length; ++i) expanded[10 + i] = content[i];
            DatagramPacket packet = new DatagramPacket(expanded, expanded.length, dest);
            socket.send(packet);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // as of Marshmallow and above, Android no longer allows access to the MAC address.
        // but we just need a unique identifier, so we can just generate a random value for it instead.
        Random r = new Random();
        macAddress = new byte[6];
        r.nextBytes(macAddress);

        TextView title = findViewById(R.id.titleText);
        StringBuilder b = new StringBuilder(32);
        b.append("SalIO - ");
        appendBytes(b, macAddress);
        title.setText(b.toString());

        resubscribeListeners();
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
    void resubscribeListeners() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
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

    private static void appendVector(StringBuilder b, float[] vec) {
        float s = 0;
        for (int i = 0; i < vec.length; ) {
            s += vec[i] * vec[i];
            b.append(String.format("%.3f", vec[i]));
            if (++i < vec.length) b.append(", ");
        }
        b.append(" (");
        b.append(String.format("%.3f", Math.sqrt(s)));
        b.append(')');
    }
    private static void appendBytes(StringBuilder b, byte[] bytes) {
        for (byte v : bytes) {
            b.append(String.format("%02x",v));
        }
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
    public final void onAccuracyChanged(Sensor sensor, int accuracy) { }

    public void serverConnectButtonPress(View view) {
        connectToServer();
    }
}