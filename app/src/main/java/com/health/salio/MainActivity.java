package com.health.salio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private static class PermissionRequest {
        public String permission;
        public String name;

        public PermissionRequest(String p, String n) {
            permission = p;
            name = n;
        }
    }
    private static final PermissionRequest[] REQUESTED_PERMISSIONS = new PermissionRequest[] {
            new PermissionRequest(Manifest.permission.BODY_SENSORS, "Body Sensors"),
            new PermissionRequest(Manifest.permission.ACTIVITY_RECOGNITION, "Activity Recognition"),
            new PermissionRequest(Manifest.permission.INTERNET, "Internet"),
            new PermissionRequest(Manifest.permission.ACCESS_WIFI_STATE, "WIFI State"),
            new PermissionRequest(Manifest.permission.ACCESS_COARSE_LOCATION, "Coarse Location"),
            new PermissionRequest(Manifest.permission.ACCESS_FINE_LOCATION, "Fine Location"),
            new PermissionRequest(Manifest.permission.CAMERA, "Camera"),
            new PermissionRequest(Manifest.permission.READ_EXTERNAL_STORAGE, "Read External Storage"),
            new PermissionRequest(Manifest.permission.WRITE_EXTERNAL_STORAGE, "Write External Storage"),
    };

    private static final String NOT_SUPPORTED_STRING = "Not Supported or Disabled";

    // ------------------------------------

    private static final int PERMISSIONS_REQUEST_CODE = 0x2301;
    private static final int CAMERA_REQUEST_CODE = 0x9901;

    // ------------------------------------

    private interface BasicSensor {
        boolean isSupported();
        float[] getData();
        void calculate();
    }

    // ------------------------------------

    private class SensorInfo implements SensorEventListener, BasicSensor {
        public Sensor sensor;
        public float[] data;
        public boolean supported;

        public SensorInfo(Sensor s, int dims) {
            sensor = s;
            data = new float[dims];
            supported = false;
        }
        public void connect(SensorManager manager) {
            if (sensor != null) {
                supported = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }
        public void updateData(float[] newData) {
            // copy over what we were given - anything we didn't get we set to zero (some sensors have optional values)
            int m = Math.min(data.length, newData.length);
            for (int i = 0; i < m; ++i) data[i] = newData[i];
            for (int i = m; i < data.length; ++i) data[i] = 0;
        }

        @Override
        public void onSensorChanged(SensorEvent event) { updateData(event.values); }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public float[] getData() { return data; }
        @Override
        public void calculate() { }
    }

    // ----------------------------------------------

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

    // ----------------------------------------------

    // the hardware orientation sensor has been deprecated for a while, so we simulate it with the accelerometer and magnetometer
    private class OrientationCalculator implements BasicSensor {
        public float[] data = new float[3];
        public boolean supported = false;

        private float[] matrixBuffer = new float[9];

        private SensorInfo accel;
        private SensorInfo magnet;

        public OrientationCalculator(SensorInfo accelerometerSource, SensorInfo magnetometerSource) {
            accel = accelerometerSource;
            magnet = magnetometerSource;
            supported = accel.supported && magnet.supported;
        }

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public float[] getData() { return data; }
        @Override
        public void calculate() {
            SensorManager.getRotationMatrix(matrixBuffer, null, accel.data, magnet.data);
            SensorManager.getOrientation(matrixBuffer, data);
        }
    }

    OrientationCalculator orientationCalculator;

    // ----------------------------------------------

    private class LocationSensor implements LocationListener, BasicSensor {
        public float[] data = new float[2];
        public int updateCount = 0;
        public boolean supported = false;

        private FusedLocationProviderClient fusedLocationProviderClient;
        private LocationRequest locationRequest;

        private LocationSettingsRequest request;
        private SettingsClient client;
        private LocationCallback locationCallback;
        private Task<LocationSettingsResponse> locationSettingsResponseTask;

        public LocationSensor(Context context) {
            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
            locationRequest = LocationRequest.create();
            locationRequest.setInterval(4000); // we request an update every 4 seconds
            locationRequest.setFastestInterval(1000); // we also accept updates from other sources, but at most once per second (could be very fast)
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            request = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build();
            client = LocationServices.getSettingsClient(context);
            locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult != null) {
                        Location loc = locationResult.getLastLocation();
                        location.data[0] = (float)loc.getLatitude();
                        location.data[1] = (float)loc.getLongitude();
                        updateCount += 1;
                    }
                }
            };
            locationSettingsResponseTask = client.checkLocationSettings(request);
            locationSettingsResponseTask.addOnSuccessListener(resp -> {
                boolean coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                supported = coarse || fine;
            });
        }
        public void start() {
            try { fusedLocationProviderClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper()); }
            catch (SecurityException ignored) {}
        }
        public void stop() {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback);
        }
        public void appendData(StringBuilder b) {
            if (supported) {
                appendVector(b, data);
                b.append(String.format(" [%d]", updateCount));
            }
            else b.append(NOT_SUPPORTED_STRING);
        }

        @Override
        public void onLocationChanged(Location loc) {
            System.err.printf("updating to %s %s", loc.getLatitude(), loc.getLongitude());
            data[0] = (float)loc.getLatitude();
            data[1] = (float)loc.getLongitude();
        }
        @Override
        public void onProviderDisabled(@NonNull String provider) { } // if we don't override these, app crashes when location is turned off
        @Override
        public void onProviderEnabled(@NonNull String provider) { } // if we don't override these, app crashes when location is turned off

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public float[] getData() { return data; }
        @Override
        public void calculate() { }
    }
    private LocationSensor location;

    // ----------------------------------------------

    private Bitmap imgSnapshot;

    // ----------------------------------------------

    private static final long PASSWORD_EXPIRY_INTERVAL = 1 * 1000 * 60 * 60 * 24; // expire after one day

    private long _rawPassword = 0;
    private long _passwordExpiry = 0;

    public long getPassword() {
        long nowtime = System.currentTimeMillis();
        if (nowtime < _passwordExpiry) return _rawPassword;
        _rawPassword = rand.nextLong() & Long.MAX_VALUE; // password is a 63-bit (positive) value
        _passwordExpiry = nowtime + PASSWORD_EXPIRY_INTERVAL;

        TextView text = (TextView)findViewById(R.id.authText);
        text.setText(String.format("password: %016x", _rawPassword));

        return _rawPassword;
    }
    public long getNewPassword() {
        _passwordExpiry = 0;
        return getPassword();
    }

    // ----------------------------------------------

    private static long fromBEBytes(byte[] v, int start, int length) {
        long res = 0;
        for (int i = 0; i < length; ++i) res = (res << 8) | ((long)v[start + i] & 0xff);
        return res;
    }

    // ----------------------------------------------

    private Random rand = new Random();

    private static final int SENSOR_DISPLAY_UPDATE_FREQUENCY = 1000;

    private static final int SERVER_PORT = 1975;
    private static final int UDP_PORT = 8888;
    private static final int TCP_PORT = 8889;
    private SocketAddress netsbloxAddress = null; // target for heartbeat comms - can be changed at will
    private DatagramSocket udpSocket = null; // our socket for udp comms - do not close or change it
    private ServerSocket tcpSocket = null;   // our socket for tcp comms - do not close or change it

    private final String MAC_ADDR_PREF_NAME = "MAC_ADDR"; // name to use for mac addr in stored app preferences
    private byte[] macAddress = null;

    private Thread udpServerThread = null;
    private Thread tcpServerThread = null;
    private long next_heartbeat = 0;

    private void connectToServer() {
        if (udpSocket == null) {
            try { udpSocket = new DatagramSocket(UDP_PORT); }
            catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to open udp port %d: %s", UDP_PORT, ex.toString()), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (tcpSocket == null) {
            try { tcpSocket = new ServerSocket(TCP_PORT); }
            catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to open tcp port %d: %s", UDP_PORT, ex.toString()), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        EditText hostText = findViewById(R.id.serverHostText);
        netsbloxAddress = new InetSocketAddress(hostText.getText().toString(), SERVER_PORT);
        next_heartbeat = 0; // trigger a heartbeat on the next network thread wakeup

        if (udpServerThread == null) {
            udpServerThread = new Thread(() -> {
                byte[] buf = new byte[64];
                DatagramPacket packet = new DatagramPacket(buf, 0, buf.length);
                while (true) {
                    try {
                        long now_time = System.currentTimeMillis();
                        if (now_time >= next_heartbeat) {
                            netsbloxSend(new byte[] { 'I' }, netsbloxAddress); // send heartbeat so server knows we're still there
                            next_heartbeat = now_time + 60 * 1000; // next heartbeat in 1 minute
                        }

                        // wait for a message - short duration is so we can see reconnections quickly
                        udpSocket.setSoTimeout(1 * 1000);
                        udpSocket.receive(packet);

                        // ignore anything that's invalid or fails to auth
                        if (packet.getLength() != 9 || fromBEBytes(buf, 1, 8) != getPassword()) {
                            continue;
                        }

                        // otherwise do the actual request
                        BasicSensor src;
                        switch (buf[0]) {
                            case 'A': src = accelerometer; break;
                            case 'G': src = gravity; break;
                            case 'L': src = linearAcceleration; break;
                            case 'Y': src = gyroscope; break;
                            case 'R': src = rotationVector; break;
                            case 'r': src = gameRotationVector; break;
                            case 'M': src = magneticField; break;
                            case 'P': src = proximity; break;
                            case 'S': src = stepCounter; break;
                            case 'l': src = light; break;
                            case 'X': src = location; break;
                            case 'O': src = orientationCalculator; break;
                            default: continue; // ignore anything we don't recognize
                        }
                        if (src.isSupported()) { // if the sensor is supported, send back all the content
                            src.calculate(); // compute software-emulated logic (if any)
                            float[] v = src.getData();
                            ByteBuffer b = ByteBuffer.allocate(1 + v.length * 4).put(buf[0]);
                            for (float val : v) b.putFloat(val);
                            netsbloxSend(b.array(), packet.getSocketAddress());
                        }
                        else netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress()); // otherwise send back the acknowledgement, but no data
                    }
                    catch (SocketTimeoutException ignored) {} // this is fine - just means we hit the timeout we requested
                    catch (Exception ex) {
                        System.err.printf("udp network thread exception: %s", ex);
                    }
                }
            });
            udpServerThread.start();
        }
        if (tcpServerThread == null) {
            tcpServerThread = new Thread(() -> {
                byte[] buf = new byte[64];
                while (true) {
                    try (Socket client = tcpSocket.accept()) {
                        int buflen = client.getInputStream().read(buf);

                        // ignore anything that's invalid or fails to auth
                        if (buflen != 9 || fromBEBytes(buf, 1, 8) != getPassword()) {
                            continue;
                        }

                        switch (buf[0]) {
                            case 'D':
                                imgSnapshot.compress(Bitmap.CompressFormat.JPEG, 90, client.getOutputStream()); // netsblox has a max resolution anyway, so no need for 100% quality compression
                                break;
                        }
                    }
                    catch (SocketTimeoutException ignored) {} // this is fine - just means we hit the timeout we requested
                    catch (Exception ex) {
                        System.err.printf("tcp network thread exception: %s", ex);
                    }
                }
            });
            tcpServerThread.start();
        }
    }
    private void netsbloxSend(byte[] content, SocketAddress dest) throws Exception {
        if (udpSocket != null) {
            byte[] expanded = new byte[content.length + 10];
            for (int i = 0; i < 6; ++i) expanded[i] = macAddress[i];
            for (int i = 0; i < 4; ++i) expanded[6 + i] = 0; // we can set the time field to zero (pretty sure it isn't actually used by the server)
            for (int i = 0; i < content.length; ++i) expanded[10 + i] = content[i];
            DatagramPacket packet = new DatagramPacket(expanded, expanded.length, dest);
            udpSocket.send(packet);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) finishInitialization();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --------------------------------------------------

        List<String> failedPermissions = new ArrayList<>();
        for (PermissionRequest r : REQUESTED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, r.permission) != PackageManager.PERMISSION_GRANTED) {
                failedPermissions.add(r.permission);
            }
        }
        if (!failedPermissions.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                String[] p = new String[failedPermissions.size()];
                for (int i = 0; i < p.length; ++i) p[i] = failedPermissions.get(i);
                requestPermissions(p, PERMISSIONS_REQUEST_CODE);
                return; // return so we don't complete initialization until after the permissions request has completed
            } else {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Missing Permissions");
                builder.setMessage("Without full permissions, some features of the app may not function.");
                builder.show();
            }
        }

        finishInitialization();
    }

    private void finishInitialization() {
        macAddress = new byte[6];
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        long macInt = -1;
        try { macInt = prefs.getLong(MAC_ADDR_PREF_NAME, -1); }
        catch (Exception ignored) { Toast.makeText(this, ignored.toString(), Toast.LENGTH_LONG).show(); }
        if (macInt < 0) {
            rand.nextBytes(macAddress); // generate a random fake mac addr (new versions of android no longer support getting the real one)

            // cache the generated value in preferences (so multiple application starts have the same id)
            macInt = 0;
            for (byte b : macAddress) macInt = (macInt << 8) | ((long)b & 0xff); // convert array to int
            prefs.edit().putLong(MAC_ADDR_PREF_NAME, macInt).commit();
        }
        else {
            // convert int to array
            for (int i = 5; i >= 0; --i, macInt >>= 8) macAddress[i] = (byte)macInt;
        }

        TextView title = findViewById(R.id.titleText);
        StringBuilder b = new StringBuilder(32);
        b.append("SalIO - ");
        appendBytes(b, macAddress);
        title.setText(b.toString());

        // --------------------------------------------------

        getNewPassword();

        // --------------------------------------------------

        SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

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

        // --------------------------------------------------

        // motion sensors
        accelerometer.connect(sensorManager);
        gravity.connect(sensorManager);
        gyroscope.connect(sensorManager);
        linearAcceleration.connect(sensorManager);
        rotationVector.connect(sensorManager);
        stepCounter.connect(sensorManager);

        // position sensors
        gameRotationVector.connect(sensorManager);
        geomagneticRotationVector.connect(sensorManager);
        magneticField.connect(sensorManager);
        proximity.connect(sensorManager);

        // environment sensors
        ambientTemperature.connect(sensorManager);
        light.connect(sensorManager);
        pressure.connect(sensorManager);
        relativeHumidity.connect(sensorManager);

        // --------------------------------------------------

        orientationCalculator = new OrientationCalculator(accelerometer, magneticField);

        // --------------------------------------------------

        location = new LocationSensor(this);
        location.start();

        // --------------------------------------------------

        // generate a default image for the networking interface and display (if enabled)
        ImageView imgDisplay = (ImageView)findViewById(R.id.snapshotDisplay);
        Bitmap defaultImg = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(defaultImg);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        canvas.drawRect(0, 0, defaultImg.getWidth(), defaultImg.getHeight(), paint);
        imgSnapshot = defaultImg;

        // if we have a camera (and permissions), set the snapshot as its display content, otherwise hide it entirely (useless on this device)
        if (getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        {
            imgDisplay.setImageBitmap(imgSnapshot);
        }
        else imgDisplay.setVisibility(View.INVISIBLE);

        // --------------------------------------------------

        Handler handler = new Handler();
        new Runnable() {
            @Override
            public void run() {
                try { updateSensorDisplay(); } // try block just in case (should never throw)
                catch (Exception ignored) {}
                finally { handler.postDelayed(this, SENSOR_DISPLAY_UPDATE_FREQUENCY); }
            }
        }.run();
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
        TextView sensorDisplay = (TextView)findViewById(R.id.sensorDisplay);
        if (!sensorDisplay.isShown()) return;
        StringBuilder b = new StringBuilder();

        // motion sensors
        if (accelerometer.supported) { b.append("Accelerometer: "); appendVector(b, accelerometer.data); b.append('\n'); }
        if (gravity.supported) { b.append("Gravity: "); appendVector(b, gravity.data); b.append('\n'); }
        if (gyroscope.supported) { b.append("Gyroscope: "); appendVector(b, gyroscope.data); b.append('\n'); }
        if (linearAcceleration.supported) { b.append("Linear Accel.: "); appendVector(b, linearAcceleration.data); b.append('\n'); }
        if (rotationVector.supported) { b.append("Rot. Vector: "); appendVector(b, rotationVector.data); b.append('\n'); }
        if (stepCounter.supported) { b.append("Step Count: "); appendVector(b, stepCounter.data); b.append('\n'); }

        // position sensors
        if (gameRotationVector.supported) { b.append("Game Rot.: "); appendVector(b, gameRotationVector.data); b.append('\n'); }
        if (geomagneticRotationVector.supported) { b.append("Geomag. Rot.: "); appendVector(b, geomagneticRotationVector.data); b.append('\n'); }
        if (magneticField.supported) { b.append("Mag. Field: "); appendVector(b, magneticField.data); b.append('\n'); }
        if (proximity.supported) { b.append("Proximity: "); appendVector(b, proximity.data); b.append('\n'); }

        // environment sensors
        if (ambientTemperature.supported) { b.append("Ambient Temp.: "); appendVector(b, ambientTemperature.data); b.append('\n'); }
        if (light.supported) { b.append("Light Level: "); appendVector(b, light.data); b.append('\n'); }
        if (pressure.supported) { b.append("Pressure: "); appendVector(b, pressure.data); b.append('\n'); }
        if (relativeHumidity.supported) { b.append("Rel. Humidity: "); appendVector(b, relativeHumidity.data); b.append('\n'); }

        // misc sensors
        if (location.supported) { b.append("Location: "); appendVector(b, location.data); b.append('\n'); }

        // orientation calculator (logical)
        if (orientationCalculator.supported) {
            orientationCalculator.calculate();
            b.append("Orientation: ");
            appendVector(b, orientationCalculator.data);
            b.append('\n');
        }

        sensorDisplay.setText(b.toString());
    }

    private File createTempImageFile(String extension) throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return File.createTempFile("IMAGE_" + timeStamp + "_", extension, storageDir);
    }

    private Bitmap rotateImage(Bitmap raw, float degrees) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap res = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, true);
        raw.recycle();
        return res;
    }
    private Uri imageActivityUri = null;
    private String imageActivityCorrectedPath = null;
    private Bitmap grabResultImage() throws Exception {
        System.err.println("here 1");
        ExifInterface exif = new ExifInterface(imageActivityCorrectedPath);
        System.err.println("here 2");
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        System.err.println("here 3");
        Bitmap raw = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageActivityUri);
        System.err.println("here 4");
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: return rotateImage(raw, 90);
            case ExifInterface.ORIENTATION_ROTATE_180: return rotateImage(raw, 180);
            case ExifInterface.ORIENTATION_ROTATE_270: return rotateImage(raw, 270);
            default: return raw;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;
        switch (requestCode) {
            case CAMERA_REQUEST_CODE: {
                try {
                    imgSnapshot = grabResultImage();
                    ImageView view = (ImageView)findViewById(R.id.snapshotDisplay);
                    view.setImageBitmap(imgSnapshot);
                }
                catch (Exception ex) {
                    Toast.makeText(this, String.format("failed to load image: %s", ex), Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------------------------

    public void serverConnectButtonPress(View view) {
        connectToServer();
    }

    public void cameraButtonClick(View view) {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                File file = createTempImageFile(".jpg");
                Uri fileUri = FileProvider.getUriForFile(this, "com.example.android.fileprovider", file);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);
                imageActivityUri = fileUri;
                imageActivityCorrectedPath = file.getAbsolutePath();
                startActivityForResult(intent, CAMERA_REQUEST_CODE);
            }
            catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to take picture: %s", ex), Toast.LENGTH_LONG).show();
            }
        }
    }
    public void newPasswordButtonClick(View view) { getNewPassword(); }
}