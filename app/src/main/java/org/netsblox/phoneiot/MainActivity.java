package org.netsblox.phoneiot;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.ExifInterface;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;
import com.google.android.material.navigation.NavigationView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static class PermissionRequest {
        public String permission;
        public String name;

        public PermissionRequest(String p, String n) {
            permission = p;
            name = n;
        }
    }
    private static PermissionRequest[] _requestedPermissions = null;
    private static PermissionRequest[] getRequestedPermissions() {
        if (_requestedPermissions != null) return _requestedPermissions;
        List<PermissionRequest> res = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            res.add(new PermissionRequest(Manifest.permission.BODY_SENSORS, "Body Sensors"));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            res.add(new PermissionRequest(Manifest.permission.ACTIVITY_RECOGNITION, "Activity Recognition"));
        }

        res.add(new PermissionRequest(Manifest.permission.INTERNET, "Internet"));
        res.add(new PermissionRequest(Manifest.permission.ACCESS_WIFI_STATE, "WIFI State"));

        res.add(new PermissionRequest(Manifest.permission.ACCESS_COARSE_LOCATION, "Coarse Location"));
        res.add(new PermissionRequest(Manifest.permission.ACCESS_FINE_LOCATION, "Fine Location"));

        res.add(new PermissionRequest(Manifest.permission.CAMERA, "Camera"));
        res.add(new PermissionRequest(Manifest.permission.RECORD_AUDIO, "Record Audio"));

        res.add(new PermissionRequest(Manifest.permission.READ_EXTERNAL_STORAGE, "Read External Storage"));
        res.add(new PermissionRequest(Manifest.permission.WRITE_EXTERNAL_STORAGE, "Write External Storage"));

        _requestedPermissions = new PermissionRequest[res.size()];
        for (int i = 0; i < _requestedPermissions.length; ++i) _requestedPermissions[i] = res.get(i);
        return _requestedPermissions;
    }

    // ------------------------------------

    private static final int PERMISSIONS_REQUEST_CODE = 0x2301;
    private static final int CAMERA_REQUEST_CODE = 0x9901;

    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    // ------------------------------------

    private interface BasicSensor {
        boolean isSupported();
        double[] getData();
    }

    // ------------------------------------

    private class SensorInfo implements SensorEventListener, BasicSensor {
        public final Sensor sensor;
        public final double[] data;
        public boolean supported;
        private final double scale;

        public SensorInfo(Sensor s, int dims, double scale) {
            sensor = s;
            data = new double[dims];
            supported = false;
            this.scale = scale;
        }
        public SensorInfo(Sensor s, int dims) {
            this(s, dims, 1.0);
        }

        public void start() {
            if (sensor != null) supported = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        public void stop() {
            if (sensor != null) sensorManager.unregisterListener(this, sensor);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            float[] newData = event.values;

            // copy over what we were given - anything we didn't get we set to zero (some sensors have optional values)
            int m = Math.min(data.length, newData.length);
            for (int i = 0; i < m; ++i) data[i] = scale * newData[i];
            for (int i = m; i < data.length; ++i) data[i] = 0;
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public double[] getData() { return data; }
    }

    // ----------------------------------------------

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

    // ----------------------------------------------

    // the hardware orientation sensor has been deprecated for a while, so we simulate it with the accelerometer and magnetometer
    private static class OrientationCalculator implements BasicSensor {
        public final double[] data = new double[3];

        private final float[] R = new float[9];
        private final float[] I = new float[9];
        private final float[] accelBuffer = new float[3];
        private final float[] magnetBuffer = new float[3];

        private final SensorInfo accel;
        private final SensorInfo magnet;

        public OrientationCalculator(SensorInfo accelerometerSource, SensorInfo magnetometerSource) {
            accel = accelerometerSource;
            magnet = magnetometerSource;
        }

        @Override
        public boolean isSupported() { return accel.isSupported() && magnet.isSupported(); }
        @Override
        public double[] getData() {
            for (int i = 0; i < 3; ++i) accelBuffer[i] = (float)accel.data[i];
            for (int i = 0; i < 3; ++i) magnetBuffer[i] = (float)magnet.data[i];

            SensorManager.getRotationMatrix(R, I, accelBuffer, magnetBuffer);
            SensorManager.getOrientation(R, accelBuffer); // store into this buffer temporarily

            data[0] = RAD_TO_DEG * accelBuffer[0]; // an extract into real data array
            data[1] = RAD_TO_DEG * -accelBuffer[1];
            data[2] = RAD_TO_DEG * accelBuffer[2];

            return data;
        }
    }

    OrientationCalculator orientationCalculator;

    // ----------------------------------------------

    private static class LocationSensor implements BasicSensor {
        public final double[] data = new double[4];
        public int updateCount = 0;
        public boolean supported = false;

        private final FusedLocationProviderClient fusedLocationProviderClient;
        private final LocationRequest locationRequest;
        private final LocationCallback locationCallback;

        public LocationSensor(Context context) {
            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context);
            locationRequest = LocationRequest.create();
            locationRequest.setInterval(4000); // we request an update every 4 seconds
            locationRequest.setFastestInterval(1000); // we also accept updates from other sources, but at most once per second (could be very fast)
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

            LocationSettingsRequest request = new LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build();
            SettingsClient client = LocationServices.getSettingsClient(context);
            locationCallback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    if (locationResult != null) {
                        Location loc = locationResult.getLastLocation();
                        data[0] = loc.getLatitude();
                        data[1] = loc.getLongitude();
                        data[2] = loc.getBearing();
                        data[3] = loc.getAltitude();

                        updateCount += 1;
                    }
                }
            };
            Task<LocationSettingsResponse> locationSettingsResponseTask = client.checkLocationSettings(request);
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

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public double[] getData() { return data; }
    }
    private LocationSensor location;

    // ----------------------------------------------

    private static class SoundSensor implements BasicSensor {
        private final double[] data = new double[1];
        private boolean supported = false;

        private static final long SAMPLE_RATE = 250; // ms
        private static final float NORMALIZATION_FACTOR = 32768.0f;

        private final MediaRecorder recorder = new MediaRecorder();
        private final Handler handler = new Handler();

        private Context cachedContext = null;

        public SoundSensor() {
            new Runnable() {
                @Override
                public void run() {
                    if (supported) {
                        try { data[0] = (float)recorder.getMaxAmplitude() / NORMALIZATION_FACTOR; }
                        catch (IllegalStateException ex) {
                            System.err.printf("sound sensor illegal state: %s\nattempting sound sensor restart\n", ex);
                            supported = false; // mark as not supported until we successfully restart
                            start(cachedContext);
                        }
                        catch (Exception ignore) { }
                    }
                    handler.postDelayed(this, SAMPLE_RATE);
                }
            }.run();
        }
        public void start(Context context) {
            cachedContext = context;
            try {
                recorder.reset();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

                // time for a little rant...
                // Android 11 added more restrictions on file access among apps, relegating them to their own little sandboxes
                // that's all well and good, but now /dev/null is inaccessible, and they didn't think to add any alternative...
                // so we actually have to SAVE all this garbage to a temporary file and just delete it on exit...

                File res = File.createTempFile("_media_recorder_dump_", null, context.getFilesDir());
                res.deleteOnExit(); // for some reason createTempFile doesn't do this automatically... what's even the point? who knows...
                System.err.printf("generated media file: %s\n", res);

                recorder.setOutputFile(res.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                supported = true;
                System.err.println("microphone started");
            }
            catch (Exception ex) {
                supported = false;
                ex.printStackTrace();
            }
        }
        public void stop() {
            if (supported) {
                try { recorder.stop(); }
                catch (Exception ignored) { }
            }
        }

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public double[] getData() { return data; }
    }

    private SoundSensor soundSensor;

    // ----------------------------------------------

    private View getNavigationView(int id) {
        NavigationView nav = findViewById(R.id.navigationView);
        View view = nav.getHeaderView(0);
        return view.findViewById(id);
    }

    private static final long PASSWORD_EXPIRY_INTERVAL = 1000 * 60 * 60 * 24; // expire after one day

    private long _rawPassword = 0;
    private long _passwordExpiry = 0;

    private long getPassword() {
        long nowtime = System.currentTimeMillis();
        if (nowtime < _passwordExpiry) return _rawPassword;

        long t = 0;
        for (;;) {
            t = rand.nextLong() & 0xffffffffL; // password is a 32-bit (positive) value
            String str = String.format("%08x", t);
            if (isValidHexStr(str)) break;
        }

        setPassword(t);
        return _rawPassword;
    }
    private void setPassword(long pass) {
        _rawPassword = pass;
        _passwordExpiry = System.currentTimeMillis() + PASSWORD_EXPIRY_INTERVAL;
        TextView text = (TextView)getNavigationView(R.id.authText);
        text.setText(String.format("Password: %08x", _rawPassword));
    }
    private void invalidatePassword() {
        _passwordExpiry = 0;
        getPassword();
    }

    // ----------------------------------------------

    private static long fromBEBytes(byte[] v, int start, int length) {
        long res = 0;
        for (int i = 0; i < length; ++i) res = (res << 8) | ((long)v[start + i] & 0xff);
        return res;
    }
    private static int intFromBEBytes(byte[] v, int start) {
        return (int)fromBEBytes(v, start, 4);
    }
    private static float floatFromBEBytes(byte[] v, int start) { return Float.intBitsToFloat(intFromBEBytes(v, start)); }

    // ----------------------------------------------

    private static boolean ellipseContains(RectF ellipse, float x, float y) {
        float rx = ellipse.width() / 2f;
        float ry = ellipse.height() / 2f;
        float offx = x - (ellipse.left + rx);
        float offy = y - (ellipse.top + ry);
        return (offx * offx) / (rx * rx) + (offy * offy) / (ry * ry) <= 1;
    }

    private static Paint.Align parseTextAlign(byte val) {
        switch (val) {
            default: return Paint.Align.LEFT;
            case 1: return Paint.Align.CENTER;
            case 2: return Paint.Align.RIGHT;
        }
    }

    private static RectF rotate(RectF rect) {
        return new RectF(rect.left - rect.height(), rect.top, (rect.left - rect.height()) + rect.height(), rect.top + rect.width());
    }
    private static RectF inflate(RectF rect, float padding) {
        return new RectF(rect.left - padding, rect.top - padding, rect.right + padding, rect.bottom + padding);
    }

    private static int applyAlpha(int color, float alpha) {
        return (color & 0x00ffffff) | (Math.round(((color >> 24) & 0xff) * alpha) << 24);
    }

    private static PointF localPos(PointF global, RectF rect, boolean landscape) {
        PointF base = new PointF(global.x - rect.left, global.y - rect.top);
        PointF corrected = landscape ? new PointF(base.y, -base.x) : base;
        return new PointF( corrected.x / rect.width(), corrected.y / rect.height());
    }

    private static boolean isValidHexStr(String str) {
        return !str.matches("^[0-9]*e[0-9]*$");
    }

    // ----------------------------------------------

    private interface ICustomControl {
        byte[] getID();
        void draw(Canvas canvas, Paint paint, float baseFontSize);
        boolean containsPoint(int x, int y);
        void handleMouseDown(View view, MainActivity context, int x, int y);
        void handleMouseMove(View view, MainActivity context, int x, int y);
        void handleMouseUp(View view, MainActivity context);
    }
    private interface IToggleable extends ICustomControl {
        boolean getToggleState();
        void setToggleState(boolean val);
    }
    private interface IPushable extends ICustomControl {
        boolean isPushed();
    }
    private interface IPositionLike extends ICustomControl {
        float[] getPos();
    }
    private interface ILevelLike extends ICustomControl {
        float getLevel();
        void setLevel(float value);
    }
    private interface IImageLike extends ICustomControl {
        Bitmap getImage();
        void setImage(Bitmap newimg, boolean recycleOld);
    }
    private interface ITextLike extends ICustomControl {
        String getText();
        void setText(String newtext);
    }

    private enum ButtonStyle {
        Rect, Ellipse,
    }
    private class CustomButton implements ICustomControl, ITextLike, IPushable {
        private int posx, posy, width, height;
        private int color, textColor;
        private final byte[] id;
        private String text;
        private float fontSize;
        private ButtonStyle style;
        private boolean landscape;

        private boolean pressed = false;

        public CustomButton(int posx, int posy, int width, int height, int color, int textColor, byte[] id, String text, float fontSize, ButtonStyle style, boolean landscape) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.color = color;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
            this.fontSize = fontSize;
            this.style = style;
            this.landscape = landscape;
        }

        @Override
        public byte[] getID() { return id; }
        private void drawRegion(Canvas canvas, Paint paint, RectF rect) {
            if (style == ButtonStyle.Rect) canvas.drawRect(rect, paint);
            else canvas.drawArc(rect, 0, 360, false, paint);
        }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(baseFontSize * fontSize);
            RectF rect = new RectF(0, 0, width, height);
            drawRegion(canvas, paint, rect);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setColor(textColor);
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, width / 2f, (height + textBounds.height() - 4) / 2f, paint);

            if (pressed) {
                paint.setColor(Color.argb(100, 255, 255, 255));
                drawRegion(canvas, paint, rect);
            }

            canvas.restore();
        }
        
        @Override
        public boolean containsPoint(int x, int y) {
            RectF rect = landscape ? new RectF(posx - height, posy, posx, posy + width) : new RectF(posx, posy, posx + width, posy + height);
            if (style == ButtonStyle.Rect) return rect.contains(x, y);
            else return ellipseContains(rect, x, y);
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte)'b').put(id).array(), netsbloxAddress); }
            catch (Exception ignored) {}

            view.playSoundEffect(SoundEffectConstants.CLICK);

            pressed = true;
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) {
            pressed = false;
        }

        @Override
        public boolean isPushed() { return pressed; }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }
    private class CustomJoystick implements ICustomControl, IPositionLike, IPushable {
        private int posx, posy, width;
        private int color;
        private final byte[] id;
        private boolean landscape;

        private boolean pressed = false;

        private int timeIndex = 0; // time index for sending update events (used to ensure they don't receive them out of order)
        private float stickX = 0, stickY = 0; // these are [0, 1] values, which we scale up for the display
        private static final float STICK_SIZE = 0.3333f;

        private static final long JOY_UPDATE_INTERVAL = 100; // so we don't spam the server with update messages, set a grace period where we won't send multiple messages
        private long nextUpdateTimestamp = 0;

        public CustomJoystick(int posx, int posy, int width, int color, byte[] id, boolean landscape) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.color = color;
            this.id = id;
            this.landscape = landscape;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(4f, 0.035f * width));
            canvas.drawArc(new RectF(posx, posy, posx + width, posy + width), 0, 360, false, paint);

            float stick1 = posx + (stickX + 1f - STICK_SIZE) * (width / 2f);
            float stick2 = posy + (stickY + 1f - STICK_SIZE) * (width / 2f);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawArc(new RectF(stick1, stick2, stick1 + width * STICK_SIZE, stick2 + width * STICK_SIZE), 0, 360, false, paint);
        }

        @Override
        public boolean containsPoint(int x, int y) {
            return ellipseContains(new RectF(posx, posy, posx + width, posy + width), x, y);
        }
        private void updateStick(double x, double y, byte tag) {
            double radius = width / 2.0;
            x -= posx + radius;
            y -= posy + radius;
            double dist = Math.sqrt(x * x + y * y);
            if (dist > radius) { x *= radius / dist; y *= radius / dist; } // if it's too far away, point in the right direction but put it in bounds
            stickX = (float)(x / radius);
            stickY = (float)(y / radius);

            long now = System.currentTimeMillis();
            if (tag == 0 || now >= nextUpdateTimestamp) {
                nextUpdateTimestamp = now + JOY_UPDATE_INTERVAL;
                sendEvent(tag);
            }
        }
        private void sendEvent(byte tag) {
            try {
                float[] vec = getPos();
                netsbloxSend(ByteBuffer.allocate(14 + id.length).put((byte)'n').putInt(timeIndex++).put(tag).putFloat(vec[0]).putFloat(vec[1]).put(id).array(), netsbloxAddress);
            }
            catch (Exception ignored) {}
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            pressed = true;
            updateStick(x, y, (byte)0);
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) {
            updateStick(x, y, (byte)1);
        }
        @Override
        public void handleMouseUp(View view, MainActivity context) {
            stickX = 0;
            stickY = 0;
            pressed = false;
            sendEvent((byte)2);
        }

        @Override
        public float[] getPos() {
            float x = landscape ? stickY : stickX;
            float y = landscape ? stickX : -stickY;
            return new float[]{x, y};
        }
        @Override
        public boolean isPushed() {
            return pressed;
        }
    }
    private class CustomTouchpad implements ICustomControl, IPositionLike, IPushable {
        private int posx, posy, width, height;
        private int color, fillColor;
        private final byte[] id;
        private boolean landscape;

        private static final float BACKGROUND_ALPHA = 0.4f;
        private static final float STROKE_WIDTH = 4;
        private static final float CURSOR_SIZE = 40;

        private int timeIndex = 0; // time index for sending update events (used to ensure they don't receive them out of order)
        private float cursorX = 0, cursorY = 0; // these are [0, 1] values, which we scale up for the display
        private boolean cursorDown = false;

        private static final long TOUCHPAD_UPDATE_INTERVAL = 100; // so we don't spam the server with update messages, set a grace period where we won't send multiple messages
        private long nextUpdateTimestamp = 0;

        public CustomTouchpad(int posx, int posy, int width, int height, int color, byte[] id, boolean landscape) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.color = color;
            this.fillColor = applyAlpha(color, BACKGROUND_ALPHA); // just compute this once
            this.id = id;
            this.landscape = landscape;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            RectF mainRect = new RectF(0, 0, width, height);
            paint.setColor(fillColor);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(mainRect, paint);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE_WIDTH);
            canvas.drawRect(mainRect, paint);

            if (cursorDown) {
                float x = (cursorX + 1) * width / 2 - CURSOR_SIZE / 2;
                float y = (cursorY + 1) * height / 2 - CURSOR_SIZE / 2;
                paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(new RectF(x, y, x + CURSOR_SIZE, y + CURSOR_SIZE), 0, 360, false, paint);
            }

            canvas.restore();
        }

        @Override
        public boolean containsPoint(int x, int y) {
            RectF raw = new RectF(posx, posy, posx + width, posy + height);
            RectF r = landscape ? rotate(raw) : raw;
            return r.contains(x, y);
        }
        private void updateCursor(float x, float y, byte tag) {
            PointF local = localPos(new PointF(x, y), new RectF(posx, posy, posx + width, posy + height), landscape);
            if (local.x < 0 || local.x > 1 || local.y < 0 || local.y > 1) return;
            cursorX = 2 * local.x - 1;
            cursorY = 2 * local.y - 1;

            long now = System.currentTimeMillis();
            if (tag == 0 || now >= nextUpdateTimestamp) {
                nextUpdateTimestamp = now + TOUCHPAD_UPDATE_INTERVAL;
                sendEvent(tag);
            }
        }
        private void sendEvent(byte tag) {
            try {
                float[] vec = getPosRaw();
                netsbloxSend(ByteBuffer.allocate(14 + id.length).put((byte)'n').putInt(timeIndex++).put(tag).putFloat(vec[0]).putFloat(vec[1]).put(id).array(), netsbloxAddress);
            }
            catch (Exception ignored) {}
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            cursorDown = true;
            updateCursor(x, y, (byte)0);
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) {
            updateCursor(x, y, (byte)1);
        }
        @Override
        public void handleMouseUp(View view, MainActivity context) {
            cursorDown = false;
            sendEvent((byte)2);
        }

        private float[] getPosRaw() {
            return new float[] { cursorX, -cursorY };
        }
        @Override
        public float[] getPos() {
            return cursorDown ? getPosRaw() : null;
        }
        @Override
        public boolean isPushed() {
            return cursorDown;
        }
    }

    private enum SliderStyle {
        Slider, Progress,
    }
    private class CustomSlider implements ICustomControl, ILevelLike, IPushable {
        private float posx, posy, width;
        private int color, alphaColor;
        private float level;
        private byte[] id;
        private SliderStyle style;
        private boolean landscape, readonly;

        private int timeIndex = 0; // time index for sending update events (used to ensure they don't receive them out of order)
        private boolean cursorDown = false;

        private static final float CLICK_PADDING = 25;
        private static final float BAR_HEIGHT = 20;
        private static final float SLIDER_RADIUS = 20;
        private static final float STROKE_WIDTH = 3;
        private static final float FILL_ALPHA = 0.4f;

        private static final long SLIDER_UPDATE_INTERVAL = 100; // so we don't spam the server with update messages, set a grace period where we won't send multiple messages
        private long nextUpdateTimestamp = 0;

        private CustomSlider(float x, float y, float width, int color, float level, byte[] id, SliderStyle style, boolean landscape, boolean readonly) {
            this.posx = x;
            this.posy = y;
            this.width = width;
            this.color = color;
            this.alphaColor = applyAlpha(color, FILL_ALPHA);
            this.level = Math.min(1, Math.max(0, level));
            this.id = id;
            this.style = style;
            this.landscape = landscape;
            this.readonly = readonly;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            RectF leftCap = new RectF(-BAR_HEIGHT / 2, 0, BAR_HEIGHT / 2, BAR_HEIGHT);
            RectF rightCap = new RectF(width - BAR_HEIGHT / 2, 0, width + BAR_HEIGHT / 2, BAR_HEIGHT);

            if (style == SliderStyle.Progress && level > 0) {
                paint.setColor(alphaColor);
                paint.setStyle(Paint.Style.FILL);

                canvas.drawArc(leftCap, 90, 180, false, paint);
                canvas.drawRect(0, 0, width * level, BAR_HEIGHT, paint);
                if (level == 1) canvas.drawArc(rightCap, 270, 180, false, paint);
            }

            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE_WIDTH);
            canvas.drawLine(0, 0, width, 0, paint);
            canvas.drawLine(0, BAR_HEIGHT, width, BAR_HEIGHT, paint);
            canvas.drawArc(leftCap, 90, 180, false, paint);
            canvas.drawArc(rightCap, 270, 180, false, paint);

            if (style == SliderStyle.Slider) {
                PointF sliderPos = new PointF(width * level, BAR_HEIGHT / 2);
                RectF r = inflate(new RectF(sliderPos.x, sliderPos.y, sliderPos.x, sliderPos.y), SLIDER_RADIUS);

                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(r, 0, 360, false, paint);
                paint.setColor(alphaColor);
                canvas.drawArc(r, 0, 360, false, paint);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawArc(r, 0, 360, false, paint);
            }

            canvas.restore();
        }

        @Override
        public boolean containsPoint(int x, int y) {
            if (readonly) return false;

            RectF raw = new RectF(posx, posy, posx + width, posy + BAR_HEIGHT);
            RectF r = landscape ? rotate(raw) : raw;
            return inflate(r, CLICK_PADDING).contains(x, y);
        }
        private void updateCursor(float x, float y, byte tag) {
            PointF local = localPos(new PointF(x, y), new RectF(posx, posy, posx + width, posy + BAR_HEIGHT), landscape);
            float newLevel = Math.min(1, Math.max(0, local.x));
            if (level == newLevel) return;
            level = newLevel;

            long now = System.currentTimeMillis();
            if (tag == 0 || now >= nextUpdateTimestamp) {
                nextUpdateTimestamp = now + SLIDER_UPDATE_INTERVAL;
                sendEvent(tag);
            }
        }
        private void sendEvent(byte tag) {
            try {
                netsbloxSend(ByteBuffer.allocate(10 + id.length).put((byte)'d').putInt(timeIndex++).put(tag).putFloat(level).put(id).array(), netsbloxAddress);
            }
            catch (Exception ignored) {}
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            view.playSoundEffect(SoundEffectConstants.CLICK);
            cursorDown = true;
            updateCursor(x, y, (byte)0);
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) {
            updateCursor(x, y, (byte)1);
        }
        @Override
        public void handleMouseUp(View view, MainActivity context) {
            cursorDown = false;
            sendEvent((byte)2);
        }

        @Override
        public float getLevel() {
            return level;
        }
        @Override
        public void setLevel(float level) {
            this.level = level;
            redrawCustomControls(false);
        }
        @Override
        public boolean isPushed() {
            return cursorDown;
        }
    }

    private enum FitType {
        Stretch, Fit, Zoom,
    }
    private FitType parseFitType(byte val) {
        switch (val) {
            case 1: return FitType.Zoom;
            case 2: return FitType.Stretch;
            default: return FitType.Fit;
        }
    }

    private static RectF center(Bitmap img, RectF rect, float scale) {
        float srcw = (float)img.getWidth(), srch = (float)img.getHeight();
        float destw = srcw * scale, desth = srch * scale;
        float destx = rect.left + (rect.width() - destw) / 2, desty = rect.top + (rect.height() - desth) / 2;
        return new RectF(destx, desty, destx + destw, desty + desth);
    }
    private static RectF fitRect(Bitmap img, RectF rect, FitType fit) {
        switch (fit) {
            case Stretch: return rect;
            case Fit: return center(img, rect, Math.min(rect.width() / img.getWidth(), rect.height() / img.getHeight()));
            case Zoom: return center(img, rect, Math.max(rect.width() / img.getWidth(), rect.height() / img.getHeight()));
        }
        throw new RuntimeException("unreachable");
    }

    private class CustomImageBox implements ICustomControl, IImageLike {
        private int posx, posy, width, height;
        private final byte[] id;
        private Bitmap img;
        private boolean readonly;
        private boolean landscape;
        private FitType fit;

        public CustomImageBox(int posx, int posy, int width, int height, byte[] id, Bitmap img, boolean readonly, boolean landscape, FitType fit) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.id = id;
            this.img = img;
            this.readonly = readonly;
            this.landscape = landscape;
            this.fit = fit;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            RectF mainRect = new RectF(0, 0, width, height);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(mainRect, paint);

            {
                canvas.save();
                canvas.clipRect(mainRect);

                Rect src = new Rect(0, 0, img.getWidth(), img.getHeight());
                RectF dest = fitRect(img, mainRect, fit);
                canvas.drawBitmap(img, src, dest, paint);

                canvas.restore();
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas.drawRect(mainRect, paint);

            canvas.restore();
        }

        @Override
        public boolean containsPoint(int x, int y) {
            RectF rect = landscape ? new RectF(posx - height, posy, posx, posy + width) : new RectF(posx, posy, posx + width, posy + height);
            return rect.contains(x, y);
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            if (readonly) return;

            view.playSoundEffect(SoundEffectConstants.CLICK);
            requestImageFor(this);
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) { }

        @Override
        public Bitmap getImage() { return img; }
        @Override
        public void setImage(Bitmap newimg, boolean recycleOld) {
            if (newimg == img) return;
            if (recycleOld) img.recycle();
            img = newimg;
            redrawCustomControls(false);
        }
    }
    private class CustomTextField implements ICustomControl, ITextLike {
        private int posx, posy, width, height;
        private int color, textColor;
        private final byte[] id;
        private String text;
        private boolean readonly;
        private float fontSize;
        private Paint.Align align;
        private boolean landscape;

        private static final int PADDING = 10;

        public CustomTextField(int posx, int posy, int width, int height, int color, int textColor, byte[] id, String text, boolean readonly, float fontSize, Paint.Align align, boolean landscape) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.color = color;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
            this.readonly = readonly;
            this.fontSize = fontSize;
            this.align = align;
            this.landscape = landscape;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setTextSize(baseFontSize * fontSize);
            canvas.drawRect(0, 0, width, height, paint);
            paint.setColor(textColor);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setTextAlign(align);
            paint.setStyle(Paint.Style.FILL);

            if (Build.VERSION.SDK_INT >= 23) {
                TextPaint textPaint = new TextPaint(paint);
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width - 2 * PADDING).build();

                canvas.save();
                float correction = (align == Paint.Align.CENTER ? 0.5f : align == Paint.Align.RIGHT ? 1 : 0) * layout.getWidth();
                canvas.translate(PADDING + correction, 0);
                canvas.clipRect(new RectF(-correction, 0, width, height));
                layout.draw(canvas);
                canvas.restore();
            }
            else canvas.drawText(text, posx, posy, paint);

            canvas.restore();
        }

        @Override
        public boolean containsPoint(int x, int y) {
            return x >= posx && y >= posy && x <= posx + width && y <= posy + height;
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            if (readonly) return;

            try {
                view.playSoundEffect(SoundEffectConstants.CLICK);

                AlertDialog.Builder prompt = new AlertDialog.Builder(context);
                prompt.setMessage("Message");
                prompt.setTitle("Title");

                EditText field = new EditText(context);
                field.setText(text);
                prompt.setView(field);

                prompt.setPositiveButton("Ok", (d,w) -> {
                    text = field.getText().toString();
                    redrawCustomControls(false);

                    // send update notification to server
                    try {
                        byte[] t = text.getBytes("UTF-8");
                        if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(2 + id.length + t.length).put((byte)'t').put((byte)id.length).put(id).put(t).array(), netsbloxAddress);
                    }
                    catch (Exception ignored) {}
                });
                prompt.setNegativeButton("Cancel", (d,w) -> {});

                prompt.show();
            }
            catch (Exception ignored) {}
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) { }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }
    private class CustomLabel implements ICustomControl, ITextLike {
        private int posx, posy;
        private int textColor;
        private final byte[] id;
        private String text;
        private float fontSize;
        private Paint.Align align;
        private boolean landscape;

        public CustomLabel(int posx, int posy, int textColor, byte[] id, String text, float fontSize, Paint.Align align, boolean landscape) {
            this.posx = posx;
            this.posy = posy;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
            this.fontSize = fontSize;
            this.align = align;
            this.landscape = landscape;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(align);
            paint.setTextSize(baseFontSize * fontSize);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            canvas.drawText(text, 0, textBounds.height() - 6, paint);

            canvas.restore();
        }
        
        @Override
        public boolean containsPoint(int x, int y) { return false; }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) { }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }

    private enum CheckboxStyle {
        CheckBox, ToggleSwitch,
    }
    private class CustomCheckbox implements ICustomControl, IToggleable, ITextLike {
        private int posx, posy;
        private int checkColor, textColor;
        private boolean checked;
        private final byte[] id;
        private String text;
        private CheckboxStyle style;
        private float fontSize;
        private boolean landscape;
        private boolean readonly;

        private float boxW = 0;
        private float boxH = 0;

        private final int UNCHECKED_COLOR = Color.argb(255, 217, 217, 217);

        private static final float STROKE_WIDTH = 4f;

        private static final float CHECKBOX_SIZE = 1f;

        private static final float TOGGLESWITCH_WIDTH = 2.5f;
        private static final float TOGGLESWITCH_HEIGHT = 1.5f;

        private static final float TEXT_PADDING = 25f;
        private static final float CLICK_PADDING = 20;

        public CustomCheckbox(int posx, int posy, int checkColor, int textColor, boolean checked, byte[] id, String text, CheckboxStyle style, float fontSize, boolean landscape, boolean readonly) {
            this.posx = posx;
            this.posy = posy;
            this.checkColor = checkColor;
            this.textColor = textColor;
            this.checked = checked;
            this.id = id;
            this.text = text;
            this.style = style;
            this.fontSize = fontSize;
            this.landscape = landscape;
            this.readonly = readonly;
        }

        private void drawToggleswitch(Canvas canvas, Paint paint, float size) {
            float w = size * TOGGLESWITCH_WIDTH;
            float h = size * TOGGLESWITCH_HEIGHT;
            boxW = w; boxH = h;

            paint.setColor(checked ? checkColor : UNCHECKED_COLOR);

            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(STROKE_WIDTH);
            canvas.drawRect(h / 2, 0, w - h / 2, h, paint);
            canvas.drawArc(new RectF(0, 0, h, h), 0, 360, false, paint);
            canvas.drawArc(new RectF(w - h, 0, w, h), 0, 360, false, paint);

            float circleLeft = (checked ? w - h : 0) + STROKE_WIDTH;
            RectF circle = new RectF(circleLeft, STROKE_WIDTH, circleLeft + h - 2 * STROKE_WIDTH, h - STROKE_WIDTH);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                BlendMode m = paint.getBlendMode();
                paint.setBlendMode(BlendMode.XOR);
                canvas.drawArc(circle, 0, 360, false, paint);
                paint.setBlendMode(m);
            }
            else {
                paint.setColor(Color.WHITE);
                canvas.drawArc(circle, 0, 260, false, paint);
            }
        }
        private void drawCheckbox(Canvas canvas, Paint paint, float size) {
            float w = size * CHECKBOX_SIZE;
            boxW = boxH = w;
            RectF rect = new RectF(0, 0, w, w);

            paint.setColor(checked ? checkColor : UNCHECKED_COLOR);

            paint.setStrokeWidth(STROKE_WIDTH);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(rect, paint);
            if (checked) {
                float x1 = w / 4, y1 = w / 2;
                float x2 = w / 2, y2 = 3 * w / 4;
                float x3 = w, y3 = -w / 2;
                canvas.drawLines(new float[] {
                        x1, y1, x2, y2,
                        x2, y2, x3, y3,
                }, paint);
            }
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            float size = baseFontSize * fontSize;
            switch (style) {
                case CheckBox: drawCheckbox(canvas, paint, size); break;
                case ToggleSwitch: drawToggleswitch(canvas, paint, size); break;
            }

            paint.setTextSize(size);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(text, boxW + TEXT_PADDING, (boxH + size) / 2.375f, paint);

            canvas.restore();
        }
        
        @Override
        public boolean containsPoint(int x, int y) {
            if (boxW == 0 && boxH == 0) return false;
            RectF base = new RectF(posx, posy, posx + boxW, posy + boxH);
            return inflate(landscape ? rotate(base) : base, CLICK_PADDING).contains(x, y);
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            if (!readonly) {
                view.playSoundEffect(SoundEffectConstants.CLICK);
                checked = !checked;

                try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(2 + id.length).put((byte) 'z').put((byte) (checked ? 1 : 0)).put(id).array(), netsbloxAddress); }
                catch (Exception ignored) { }
            }
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) { }

        @Override
        public boolean getToggleState() { return checked; }
        @Override
        public void setToggleState(boolean val) {
            checked = val;
            redrawCustomControls(false);
        }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }
    private class CustomRadioButton implements ICustomControl, IToggleable, ITextLike {
        private int posx, posy;
        private int checkColor, textColor;
        private boolean checked;
        private final byte[] id;
        private byte[] group;
        private String text;
        private float fontSize;
        boolean landscape;
        boolean readonly;

        private float boxW = 0;
        private float boxH = 0;

        private final int UNCHECKED_COLOR = Color.argb(255, 217, 217, 217);

        private static final float STROKE_WIDTH = 4f;
        private static final float RADIO_SIZE = 1f;
        private static final float CIRCLE_SIZE = 0.25f;

        private static final float TEXT_PADDING = 25f;
        private static final float CLICK_PADDING = 20;

        public CustomRadioButton(int posx, int posy, int checkColor, int textColor, boolean checked, byte[] id, byte[] group, String text, float fontSize, boolean landscape, boolean readonly) {
            this.posx = posx;
            this.posy = posy;
            this.checkColor = checkColor;
            this.textColor = textColor;
            this.checked = checked;
            this.id = id;
            this.group = group;
            this.text = text;
            this.fontSize = fontSize;
            this.landscape = landscape;
            this.readonly = readonly;
        }

        @Override
        public byte[] getID() { return id; }
        @Override
        public void draw(Canvas canvas, Paint paint, float baseFontSize) {
            canvas.save();
            canvas.translate(posx, posy);
            if (landscape) canvas.rotate(90);

            float size = baseFontSize * fontSize;
            float w = size * RADIO_SIZE;
            boxW = boxH = w;
            RectF base = new RectF(0, 0, w, w);

            paint.setColor(checked ? checkColor : UNCHECKED_COLOR);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(STROKE_WIDTH);
            canvas.drawArc(base, 0, 360, false, paint);
            if (checked) {
                RectF r = inflate(new RectF(w / 2, w / 2, w / 2, w / 2), size * CIRCLE_SIZE);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(r, 0, 360, false, paint);
            }

            paint.setTextSize(size);
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawText(text, boxW + TEXT_PADDING, (boxH + size) / 2.375f, paint);

            canvas.restore();
        }

        @Override
        public boolean containsPoint(int x, int y) {
            if (boxW == 0 && boxH == 0) return false;
            RectF base = new RectF(posx, posy, posx + boxW, posy + boxH);
            return inflate(landscape ? rotate(base) : base, CLICK_PADDING).contains(x, y);
        }
        @Override
        public void handleMouseDown(View view, MainActivity context, int x, int y) {
            if (!readonly) {
                // set state to true and uncheck every other radiobutton in the same group
                checked = true;
                for (ICustomControl other : context.customControls) {
                    if (other != this && other instanceof CustomRadioButton) {
                        CustomRadioButton b = (CustomRadioButton) other;
                        if (Arrays.equals(b.group, this.group)) b.checked = false;
                    }
                }

                try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte) 'b').put(id).array(), netsbloxAddress); }
                catch (Exception ignored) { }

                view.playSoundEffect(SoundEffectConstants.CLICK);
            }
        }
        @Override
        public void handleMouseMove(View view, MainActivity context, int x, int y) { }
        @Override
        public void handleMouseUp(View view, MainActivity context) { }

        @Override
        public boolean getToggleState() { return checked; }
        @Override
        public void setToggleState(boolean val) {
            checked = val;
            redrawCustomControls(false);
        }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }

    private static final int MAX_CUSTOM_CONTROLS = 128;

    private boolean controlPanelInitialized = false;
    private final List<ICustomControl> customControls = new ArrayList<>();
    private boolean redrawCustomControls(boolean optional) {
        if (optional && controlPanelInitialized) return true;
        ImageView view = findViewById(R.id.controlPanel);
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return false;

        Bitmap img = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(img);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        float baseFontSize = 30 * ((float)height / 1200);
        paint.setTextSize(baseFontSize);

        if (customControls.isEmpty()) { // if there aren't any controls, put a message to let them know it's intentionally empty
            String msg = "Add controls through NetsBlox!";
            float len = paint.measureText(msg);
            canvas.drawText(msg, (width - len) / 2f, height / 2f, paint);
        }
        for (ICustomControl control : customControls) {
            control.draw(canvas, paint, baseFontSize);
        }

        view.setImageBitmap(img);
        controlPanelInitialized = true;
        return true;
    }
    private ICustomControl getCustomControlWithIDWhere(byte[] id, Predicate<ICustomControl> predicate) {
        for (ICustomControl control : customControls) {
            if (predicate.test(control) && Arrays.equals(control.getID(), id)) return control;
        }
        return null;
    }
    private byte tryAddCustomControl(ICustomControl control) {
        if (customControls.size() >= MAX_CUSTOM_CONTROLS) return 1;
        byte[] id = control.getID();
        for (ICustomControl other : customControls) {
            if (Arrays.equals(id, other.getID())) return 2;
        }
        customControls.add(control);
        redrawCustomControls(false);
        return 0;
    }

    private static class PointerInfo {
        @NonNull public ICustomControl target;
        public int lastX, lastY;

        public PointerInfo(@NonNull ICustomControl target, int x, int y) {
            this.target = target;
            this.lastX = x;
            this.lastY = y;
        }
        public boolean isNewPoint(int x, int y) {
            return x != lastX || y != lastY;
        }
    }
    private final HashMap<Integer, PointerInfo> activePointers = new HashMap<>();
    private boolean handleCustomControlOnTouch(View view, MotionEvent e) {
        HashSet<Integer> nowPointers = new HashSet<>();
        List<Integer> purgeList = new ArrayList<>(4);
        boolean didSomething = false;

        synchronized (activePointers) {
            // look at all the pointers that are represented in this event (still alive)
            for (int i = 0; i < e.getPointerCount(); ++i) {
                Integer id = e.getPointerId(i);
                int x = (int)e.getX(i);
                int y = (int)e.getY(i);

                PointerInfo continuedControl = activePointers.get(id);
                nowPointers.add(id);

                // if it's not in the active pointers map, it's a new touch down event
                if (continuedControl == null) {
                    for (int j = customControls.size() - 1; j >= 0; --j) { // find the first thing we clicked (iterate backwards because we draw forwards, so back is on top layer)
                        ICustomControl target = customControls.get(j);
                        if (target.containsPoint(x, y)) {
                            boolean good = true;
                            for (PointerInfo info : activePointers.values()) { // check if there's already a pointer active on the given target
                                if (info.target != target) continue;
                                good = false;
                                break;
                            }
                            if (good) { // only do something if there's not another pointer for the same target
                                try { target.handleMouseDown(view, this, x, y); }
                                catch (Exception ignored) {}

                                activePointers.put(id, new PointerInfo(target, x, y));
                                didSomething = true;
                            }
                            break;
                        }
                    }
                }
                // otherwise we are continuing an ongoing touch event
                else if (continuedControl.isNewPoint(x, y)) {
                    try { continuedControl.target.handleMouseMove(view, this, x, y); }
                    catch (Exception ignored) {}

                    didSomething = true;
                }
            }

            // if we had an up event (up or pointer up), remove that cursor from nowPointers so it will be properly purged
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                nowPointers.remove(e.getPointerId(e.getActionIndex()));
            }

            // now look at all the pointers we were keeping track of and remove any that have ended (and raise a mouse up event)
            for (Integer key : activePointers.keySet()) {
                if (!nowPointers.contains(key)) purgeList.add(key);
            }
            // we have to remove them after loop to avoid modification during iteration
            for (Integer key : purgeList) {
                PointerInfo info = activePointers.remove(key);
                didSomething = true;

                try { info.target.handleMouseUp(view, this); }
                catch (Exception ignored) {}
            }
        }

        if (didSomething) redrawCustomControls(false);

        return true;
    }

    private Bitmap getDefaultImage() {
        Bitmap img = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(img);
        canvas.drawRect(0, 0, 100, 100, new Paint(Color.BLACK));
        return img;
    }

    // ----------------------------------------------

    private static final String MAC_ADDR_PREF_NAME = "MAC_ADDR"; // name to use for mac addr in stored app preferences
    private static final String RUN_IN_BACKGROUND_PREF_NAME = "RUN_IN_BACKGROUND";

    private final Random rand = new Random();

    private static final int DEFAULT_SERVER_PORT = 1976;
    private static final int UDP_PORT = 8888;
    private SocketAddress netsbloxAddress = null; // target for heartbeat comms - can be changed at will
    private DatagramSocket udpSocket = null;      // our socket for udp comms - do not close or change it

    private final byte[] macAddress = new byte[6];

    private Thread udpServerThread = null;
    private Thread pipeThread = null;
    private Thread sensorStreamThread = null;

    private long next_heartbeat = 0;

    private final Object sensorUpdateMutex = new Object();
    private long sensorUpdatePeriod = Long.MAX_VALUE; // guarded by sensorUpdateMutex
    private long nextSensorUpdate = Long.MAX_VALUE; // guarded by sensorUpdateMutex

    private String reconnectRequest = null;
    private final List<DatagramPacket> pipeQueue = new ArrayList<>();

    private final Handler handler = new Handler();

    private static final String[] KNOWN_SERVERS = new String[] {
            "editor.netsblox.org", "dev.netsblox.org",
            "24.11.247.254", "10.0.0.24", // temporary dev addresses for convenience
    };

    private static Pattern IP_PATTERN = Pattern.compile("^(\\d+\\.){3}\\d+$");
    boolean isIP(String addr) {
        return IP_PATTERN.matcher(addr).matches();
    }
    String httpGet(String address) {
        try {
            System.err.printf("http get %s\n", address);

            URL url = new URL(address);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setRequestMethod("GET");

            StringBuilder b = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                for (String line; (line = reader.readLine()) != null; ) b.append(line);
            }
            return b.toString();
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }
    private int getServerPort(String serverAddress) {
        String target = isIP(serverAddress) ? serverAddress + ":8080" : serverAddress.startsWith("https://") ? serverAddress : "https://" + serverAddress;
        String content = httpGet(target + "/services/routes/phone-iot/port");
        try {
            return Integer.parseInt(content);
        }
        catch (Exception ignored) {
            System.err.println("getServerPort() failed - falling back to default port");
            return DEFAULT_SERVER_PORT;
        }
    }

    // schedules a new toast to be shown for the given duration - this works from any calling thread
    private void scheduleToast(String msg, int duration) {
        this.runOnUiThread(() -> Toast.makeText(this, msg, duration).show());
    }

    @FunctionalInterface
    private interface SensorConsumer {
        void apply(BasicSensor sensor) throws Exception;
    }
    @FunctionalInterface
    private interface Predicate<T> { // for reasons that baffle even sheogorath, java.util.function.Predicate is only API level 24+
        boolean test(T t);
    }

    private void setSensorUpdatePeriods(long[] periods) {
        // we need to take the fastest speed, which is the shortest period
        long oldmin = sensorUpdatePeriod;
        long min = Long.MAX_VALUE;
        for (long p : periods) if (p < min) min = p;

        synchronized (sensorUpdateMutex) {
            // fix the next update time and period for current iteration
            nextSensorUpdate = min == Long.MAX_VALUE ? min : nextSensorUpdate - sensorUpdatePeriod + min;
            sensorUpdatePeriod = min;

            // if we moved to a faster speed we need to wake up the current iteration so it doesn't wait for potentially a long time due to oldmin
            if (min < oldmin) sensorUpdateMutex.notifyAll();
        }
    }

    private void requestConnReset() {
        try { if (netsbloxAddress != null) netsbloxSend(new byte[] { (byte)'I', 86 }, netsbloxAddress); }
        catch (Exception ignored) {}
    }
    private void connectToServer() {
        if (udpSocket == null) {
            System.err.printf("opening port %d\n", UDP_PORT);

            try { udpSocket = new DatagramSocket(UDP_PORT); }
            catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to open udp port %d: %s", UDP_PORT, ex.toString()), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        reconnectRequest = ((EditText)getNavigationView(R.id.serverHostText)).getText().toString();

        if (udpServerThread == null) {
            udpServerThread = new Thread(() -> {
                byte[] buf = new byte[64 * 1024]; // must be big enough to hold any UDP datagram
                DatagramPacket packet = new DatagramPacket(buf, 0, buf.length);
                while (true) {
                    try {
                        // wait until we're in the foreground (sensors are running)
                        synchronized (sensorsRunning) {
                            while (!sensorsRunning[0]) {
                                next_heartbeat = 0;    // set it so we trigger a heartbeat the moment we wake up
                                sensorsRunning.wait(); // wait for sensors to be enabled - could take a very long time
                            }
                        }

                        // handle pending reconnect requests from the gui thread
                        String recon = reconnectRequest;
                        reconnectRequest = null;
                        if (recon != null) {
                            try {
                                int port = getServerPort(recon);
                                SocketAddress temp = new InetSocketAddress(recon, port);
                                byte[] msg = netsbloxFormat(new byte[] { 'I', 0 }); // send a hearbeat with an ack request flag

                                // check to make sure the address is good (send empty packet, which will trigger the server to send us an 'I' conn ack message)
                                synchronized (pipeQueue) { // lock pipeQueue so we can send from the socket
                                    udpSocket.send(new DatagramPacket(msg, msg.length, temp)); // netsbloxSend would hide the exception, which is what we want to test for
                                }

                                netsbloxAddress = temp; // if we get here it was a valid address
                                System.err.printf("reconnected to target: %s\n", netsbloxAddress);
                            }
                            catch (Exception ex) { scheduleToast("endpoint does not exist", Toast.LENGTH_SHORT); }
                        }

                        // send a heartbeat if we're over the next timestamp
                        long now_time = System.currentTimeMillis();
                        if (now_time >= next_heartbeat && netsbloxAddress != null) {
                            netsbloxSend(new byte[] { 'I' }, netsbloxAddress); // send heartbeat so server knows we're still there
                            next_heartbeat = now_time + 30 * 1000; // next heartbeat in 30 seconds
                        }

                        // wait for a message - short duration is so we can see reconnections quickly
                        // IMPORTANT: short timeout is also important for the sleep no-communications mode (otherwise we might leak one instruction through after arbitrary time).
                        udpSocket.setSoTimeout(1000);
                        udpSocket.receive(packet);
                        if (packet.getLength() == 0) continue;

                        // check for things that don't need auth
                        if (packet.getLength() <= 2 && buf[0] == 'I') {
                            if (packet.getLength() == 1 || (packet.getLength() == 2 && buf[1] == 1)) { // len 1 is back compat with server - eventually not needed
                                scheduleToast("Connected to NetsBlox", Toast.LENGTH_SHORT);
                                continue;
                            }
                            else if (packet.getLength() == 2 && buf[1] == 87) {
                                scheduleToast("Connection Reset", Toast.LENGTH_SHORT);
                                new Timer().schedule(new TimerTask() {
                                    @Override
                                    public void run() {
                                        connectToServer();
                                    }
                                }, 3000);
                                continue;
                            }
                        }

                        // ignore anything that's invalid or fails to auth
                        if (packet.getLength() < 9 || fromBEBytes(buf, 1, 8) != getPassword()) continue;

                        final SensorConsumer handleSensor = src -> {
                            if (packet.getLength() != 9) return; // ignore invalid format
                            if (src.isSupported()) { // if the sensor is supported, send back all the content
                                double[] v = src.getData();
                                ByteBuffer b = ByteBuffer.allocate(1 + v.length * 8).put(buf[0]);
                                for (double val : v) b.putDouble(val);
                                netsbloxSend(b.array(), packet.getSocketAddress());
                            }
                            // otherwise send back the acknowledgement, but no data
                            else netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                        };

                        // otherwise do the actual request
                        switch (buf[0]) {
                            case 'A': handleSensor.apply(accelerometer); break;
                            case 'G': handleSensor.apply(gravity); break;
                            case 'L': handleSensor.apply(linearAcceleration); break;
                            case 'Y': handleSensor.apply(gyroscope); break;
                            case 'R': handleSensor.apply(rotationVector); break;
                            case 'r': handleSensor.apply(gameRotationVector); break;
                            case 'M': handleSensor.apply(magneticField); break;
                            case 'm': handleSensor.apply(soundSensor); break;
                            case 'P': handleSensor.apply(proximity); break;
                            case 'S': handleSensor.apply(stepCounter); break;
                            case 'l': handleSensor.apply(light); break;
                            case 'X': handleSensor.apply(location); break;
                            case 'O': handleSensor.apply(orientationCalculator); break;

                            case 'a': { // authenticate (no-op)
                                netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                break;
                            }
                            case 'p': { // set sensor update periods
                                if (packet.getLength() < 9 || (packet.getLength() - 9) % 4 != 0) continue;
                                long[] vals = new long[(packet.getLength() - 9) / 4];
                                for (int i = 0; i < vals.length; ++i) vals[i] = intFromBEBytes(buf, 9 + i * 4);
                                setSensorUpdatePeriods(vals);
                                netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                break;
                            }
                            case 'u': { // get image
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                IImageLike target = (IImageLike)getCustomControlWithIDWhere(id, c -> c instanceof IImageLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                else netsbloxSend(new byte[] { buf[0] }, target.getImage(), packet.getSocketAddress());
                                break;
                            }
                            case 'i': { // set image
                                if (packet.getLength() < 10) continue;
                                int idlen = (int)buf[9] & 0xff;
                                if (packet.getLength() < 10 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 10, 10 + idlen); // image content is everything after this block
                                IImageLike target = (IImageLike)getCustomControlWithIDWhere(id, c -> c instanceof IImageLike);
                                if (target == null) netsbloxSend(new byte[]{ buf[0], 3 }, packet.getSocketAddress());
                                else {
                                    Bitmap img = BitmapFactory.decodeByteArray(buf, 10 + idlen, packet.getLength() - (10 + idlen));
                                    System.err.printf("decoded image: %dx%d\n", img.getWidth(), img.getHeight());
                                    target.setImage(img, true);
                                    netsbloxSend(new byte[] { buf[0], 0 }, packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'H': { // set text
                                if (packet.getLength() < 10) continue;
                                int idlen = (int)buf[9] & 0xff;
                                if (packet.getLength() < 10 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 10, 10 + idlen); // text content is everything after this block
                                ITextLike target = (ITextLike)getCustomControlWithIDWhere(id, c -> c instanceof ITextLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0], 3 }, packet.getSocketAddress());
                                else {
                                    String text = new String(buf, 10 + idlen, packet.getLength() - (10 + idlen), "UTF-8");
                                    target.setText(text);
                                    netsbloxSend(new byte[] { buf[0], 0 }, packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'h': { // get text
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                ITextLike target = (ITextLike)getCustomControlWithIDWhere(id, c -> c instanceof ITextLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                else {
                                    byte[] content = target.getText().getBytes("UTF-8");
                                    netsbloxSend(ByteBuffer.allocate(2 + content.length).put(buf[0]).put((byte)0).put(content).array(), packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'J': { // get position
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                IPositionLike target = (IPositionLike)getCustomControlWithIDWhere(id, c -> c instanceof IPositionLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                else {
                                    float[] vec = target.getPos();
                                    if (vec == null) netsbloxSend(new byte[] { buf[0], 0 }, packet.getSocketAddress());
                                    else netsbloxSend(ByteBuffer.allocate(10).put(buf[0]).put((byte)1).putFloat(vec[0]).putFloat(vec[1]).array(), packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'E': { // get level
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                ILevelLike target = (ILevelLike)getCustomControlWithIDWhere(id, c -> c instanceof ILevelLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                else netsbloxSend(ByteBuffer.allocate(5).put(buf[0]).putFloat(target.getLevel()).array(), packet.getSocketAddress());
                                break;
                            }
                            case 'e': { // set level
                                if (packet.getLength() < 13) continue;
                                float level = floatFromBEBytes(buf, 9);
                                byte[] id = Arrays.copyOfRange(buf, 13, packet.getLength());
                                ILevelLike target = (ILevelLike)getCustomControlWithIDWhere(id, c -> c instanceof ILevelLike);
                                if (target == null) netsbloxSend(new byte[] { buf[0], 3 }, packet.getSocketAddress());
                                else {
                                    target.setLevel(level);
                                    netsbloxSend(new byte[] { buf[0], 0 }, packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'V': { // is pushed
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                IPushable target = (IPushable)getCustomControlWithIDWhere(id, c -> c instanceof IPushable);
                                netsbloxSend(new byte[] { buf[0], (byte)(target == null ? 2 : target.isPushed() ? 1 : 0) }, packet.getSocketAddress());
                                break;
                            }
                            case 'W': { // get toggle state
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                IToggleable target = (IToggleable)getCustomControlWithIDWhere(id, c -> c instanceof IToggleable);
                                netsbloxSend(new byte[] { buf[0], (byte)(target == null ? 2 : target.getToggleState() ? 1 : 0) }, packet.getSocketAddress());
                                break;
                            }
                            case 'w': { // set toggle state
                                if (packet.getLength() < 10) continue;
                                boolean state = buf[9] != 0;
                                byte[] id = Arrays.copyOfRange(buf, 10, packet.getLength());
                                IToggleable target = (IToggleable)getCustomControlWithIDWhere(id, c -> c instanceof IToggleable);
                                if (target == null) netsbloxSend(new byte[] { buf[0], 3 }, packet.getSocketAddress());
                                else {
                                    target.setToggleState(state);
                                    netsbloxSend(new byte[] { buf[0], 0 }, packet.getSocketAddress());
                                }
                                break;
                            }
                            case 'C': { // clear custom controls
                                if (packet.getLength() != 9) continue;
                                customControls.clear();
                                redrawCustomControls(false);
                                netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                break;
                            }
                            case 'c': { // remove specific custom control
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                for (int i = 0; i < customControls.size(); ++i) {
                                    ICustomControl control = customControls.get(i);
                                    if (Arrays.equals(control.getID(), id)) {
                                        customControls.remove(i);
                                        redrawCustomControls(false);
                                        break;
                                    }
                                }
                                netsbloxSend(new byte[] { buf[0] }, packet.getSocketAddress());
                                break;
                            }
                            case 'B': { // add custom button control
                                if (packet.getLength() < 40) continue;

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();

                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int width = (int)(floatFromBEBytes(buf, 17) / 100 * viewWidth);
                                int height = (int)(floatFromBEBytes(buf, 21) / 100 * viewHeight);
                                int color = intFromBEBytes(buf, 25);
                                int textColor = intFromBEBytes(buf, 29);
                                float fontSize = floatFromBEBytes(buf, 33);
                                ButtonStyle style;
                                switch (buf[37]) {
                                    case 0: default: style = ButtonStyle.Rect; break;
                                    case 1: style = ButtonStyle.Ellipse; break;
                                    case 2: height = width; style = ButtonStyle.Rect; break; // these are just like previous, but make perfect squares/circles based on width only
                                    case 3: height = width; style = ButtonStyle.Ellipse; break;
                                }
                                boolean landscape = buf[38] != 0;
                                int idlen = (int)buf[39] & 0xff;
                                if (packet.getLength() < 40 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 40, 40 + idlen);
                                String text = new String(buf, 40 + idlen, packet.getLength() - (40 + idlen), "UTF-8");

                                ICustomControl control = new CustomButton(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        width, height,
                                        color, textColor, id, text, fontSize, style, landscape);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'j': { // add custom joystick control
                                if (packet.getLength() < 26) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                int color = intFromBEBytes(buf, 21);
                                boolean landscape = buf[25] != 0;
                                byte[] id = Arrays.copyOfRange(buf, 26, packet.getLength());

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomJoystick(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth),
                                        color, id, landscape);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'N': { // add custom touchpad
                                if (packet.getLength() < 31) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                int color = intFromBEBytes(buf, 25);
                                if (buf[29] == 1) {
                                    height = width;
                                }
                                boolean landscape = buf[30] != 0;
                                byte[] id = Arrays.copyOfRange(buf, 31, packet.getLength());

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomTouchpad(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        color, id, landscape);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'D': { // add custom slider
                                if (packet.getLength() < 32) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                int color = intFromBEBytes(buf, 21);
                                float level = floatFromBEBytes(buf, 25);
                                SliderStyle style;
                                switch (buf[29]) {
                                    default: case 0: style = SliderStyle.Slider; break;
                                    case 1: style = SliderStyle.Progress; break;
                                }
                                boolean landscape = buf[30] != 0;
                                boolean readonly = buf[31] != 0;
                                byte[] id = Arrays.copyOfRange(buf, 32, packet.getLength());

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomSlider(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth),
                                        color, level, id, style, landscape, readonly);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'U': { // add custom image display
                                if (packet.getLength() < 28) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                boolean readonly = buf[25] != 0;
                                boolean landscape = buf[26] != 0;
                                FitType fit = parseFitType(buf[27]);
                                byte[] id = Arrays.copyOfRange(buf, 28, packet.getLength());

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomImageBox(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        id, getDefaultImage(), readonly, landscape, fit);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'T': { // add custom text field control
                                if (packet.getLength() < 41) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                int color = intFromBEBytes(buf, 25);
                                int textColor = intFromBEBytes(buf, 29);
                                float fontSize = floatFromBEBytes(buf, 33);
                                Paint.Align align = parseTextAlign(buf[37]);
                                boolean readonly = buf[38] != 0;
                                boolean landscape = buf[39] != 0;
                                int idlen = (int)buf[40] & 0xff;
                                if (packet.getLength() < 41 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 41, 41 + idlen);
                                String text = new String(buf, 41 + idlen, packet.getLength() - (41 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomTextField(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        color, textColor, id, text, readonly, fontSize, align, landscape);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'g': { // add custom label control
                                if (packet.getLength() < 28) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int textColor = intFromBEBytes(buf, 17);
                                float fontSize = floatFromBEBytes(buf, 21);
                                Paint.Align align = parseTextAlign(buf[25]);
                                boolean landscape = buf[26] != 0;
                                int idlen = (int)buf[27] & 0xff;
                                if (packet.getLength() < 28 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 28, 28 + idlen);
                                String text = new String(buf, 28 + idlen, packet.getLength() - (28 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomLabel(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        textColor, id, text, fontSize, align, landscape);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'Z': { // add custom checkbox control
                                if (packet.getLength() < 34) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int checkColor = intFromBEBytes(buf, 17);
                                int textColor = intFromBEBytes(buf, 21);
                                float fontSize = floatFromBEBytes(buf, 25);
                                boolean checked = buf[29] != 0;
                                CheckboxStyle style;
                                switch (buf[30]) {
                                    case 0: default: style = CheckboxStyle.ToggleSwitch; break;
                                    case 1: style = CheckboxStyle.CheckBox; break;
                                }
                                boolean landscape = buf[31] != 0;
                                boolean readonly = buf[32] != 0;
                                int idlen = (int)buf[33] & 0xff;
                                if (packet.getLength() < 34 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 34, 34 + idlen);
                                String text = new String(buf, 34 + idlen, packet.getLength() - (34 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomCheckbox(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        checkColor, textColor, checked, id, text, style, fontSize, landscape, readonly);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'y': { // add custom radiobutton control
                                if (packet.getLength() < 33) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int checkColor = intFromBEBytes(buf, 17);
                                int textColor = intFromBEBytes(buf, 21);
                                float fontSize = floatFromBEBytes(buf, 25);
                                boolean state = buf[29] != 0;
                                boolean landscape = buf[30] != 0;
                                boolean readonly = buf[31] != 0;
                                int idlen = (int)buf[32] & 0xff;
                                if (packet.getLength() < 33 + idlen + 1) continue;
                                byte[] id = Arrays.copyOfRange(buf, 33, 33 + idlen);
                                int grouplen = (int)buf[33 + idlen] & 0xff;
                                if (packet.getLength() < 33 + idlen + 1 + grouplen) continue;
                                byte[] group = Arrays.copyOfRange(buf, 33 + idlen + 1, 33 + idlen + 1 + grouplen);
                                String text = new String(buf, 33 + idlen + 1 + grouplen, packet.getLength() - (33 + idlen + 1 + grouplen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomRadioButton(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        checkColor, textColor, state, id, group, text, fontSize, landscape, readonly);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                        }
                    }
                    catch (SocketTimeoutException ignored) {} // this is fine - just means we hit the timeout we requested
                    catch (Exception ex) {
                        System.err.printf("udp network thread exception: (addr %s): %s\n", netsbloxAddress, ex);
                        try { Thread.sleep(100); } catch (Exception ignored) {} // do this so a loop of failures doesn't burn up network resources and power
                    }
                }
            });
            udpServerThread.start();
        }
        if (pipeThread == null) {
            pipeThread = new Thread(() -> {
                while (true) {
                    try {
                        synchronized (pipeQueue) {
                            for (DatagramPacket packet : pipeQueue) {
                                try { udpSocket.send(packet); } // ignore errors here so we can clear out the pipe even on failure
                                catch (Exception ignored) {}
                            }
                            pipeQueue.clear();
                            pipeQueue.wait();
                        }
                    }
                    catch (Exception ignored) {}
                }
            });
            pipeThread.start();
        }
        if (sensorStreamThread == null) {
            sensorStreamThread = new Thread(() -> {
                int timestamp = 0;
                while (true) {
                    try {
                        // ensure we have proper timing constraints
                        synchronized (sensorUpdateMutex) {
                            long now;
                            while ((now = System.currentTimeMillis()) < nextSensorUpdate) {
                                if (nextSensorUpdate == Long.MAX_VALUE) sensorUpdateMutex.wait(); // if it's the 'infinity' value, just wait forever
                                else sensorUpdateMutex.wait(nextSensorUpdate - now); // otherwise wait long enough to reach the target
                            }
                            nextSensorUpdate = sensorUpdatePeriod == Long.MAX_VALUE ? Long.MAX_VALUE : now + sensorUpdatePeriod; // if period is inf, next update is inf as well
                        }

                        // we need to check the sensor shutdown flag before sending the message
                        synchronized (sensorsRunning) {
                            while (!sensorsRunning[0]) {
                                try { sensorsRunning.wait(); }
                                catch (Exception ignored) { } // ignore exceptions from waiting so we don't skip a time step
                            }
                        }

                        // finally, pack up the current data and send it
                        netsbloxSend(getSensorPacket(new byte[] { 'Q' }, timestamp++), netsbloxAddress);
                    }
                    catch (Exception ignored) { }
                }
            });
            sensorStreamThread.start();
        }
    }
    private byte[] getSensorPacket(byte[] prefix, int timestamp) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try (DataOutputStream writer = new DataOutputStream(raw)) {
                BasicSensor[] sensors = {
                        accelerometer, gravity, linearAcceleration, gyroscope, rotationVector, gameRotationVector,
                        magneticField, soundSensor, proximity, stepCounter, light, location, orientationCalculator,
                };
                writer.write(prefix);
                writer.writeInt(timestamp);
                for (BasicSensor sensor : sensors) {
                    if (sensor.isSupported()) {
                        double[] vals = sensor.getData();
                        writer.write((byte)vals.length);
                        for (double v : vals) writer.writeDouble(v);
                    }
                    else writer.write((byte)0);
                }
            }
            return raw.toByteArray();
        }
        catch (Exception ignored) { return new byte[0]; }
    }
    private byte[] netsbloxFormat(byte[] content) {
        byte[] expanded = new byte[content.length + 10];
        for (int i = 0; i < 6; ++i) expanded[i] = macAddress[i];
        for (int i = 0; i < 4; ++i) expanded[6 + i] = 0; // we can set the time field to zero (pretty sure it isn't actually used by the server)
        for (int i = 0; i < content.length; ++i) expanded[10 + i] = content[i];
        return expanded;
    }
    private void netsbloxSend(byte[] content, SocketAddress dest) {
        if (udpSocket != null && dest != null) {
            byte[] expanded = netsbloxFormat(content);
            DatagramPacket packet = new DatagramPacket(expanded, expanded.length, dest);
            synchronized (pipeQueue) {
                pipeQueue.add(packet);
                pipeQueue.notify();
            }
        }
    }
    private void netsbloxSend(byte[] prefix, Bitmap img, SocketAddress dest) throws IOException {
        Bitmap scaled = ScaleImageForUDP(img);
        ByteArrayOutputStream temp = new ByteArrayOutputStream();
        temp.write(prefix);
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, temp);

        byte[] content = temp.toByteArray();
        System.err.printf("image content size: %d\n", content.length);
        netsbloxSend(content, dest);

        if (scaled != img) scaled.recycle(); // if it was a new object, recycle it (no longer needed)
    }

    // given a large bitmap, scales it down (maintaining aspect ratio) so that it is small enough to send over UDP
    private Bitmap ScaleImageForUDP(Bitmap src) {
        final int MAX_BYTES = 8 * 64 * 1024; // due to using jpeg compression, we can actually afford to be over the maximum packet size
        int w = src.getWidth(), h = src.getHeight();
        int rawBytes = 4 * w * h;
        if (rawBytes <= MAX_BYTES) return src;
        float mult = (float)Math.sqrt((double)MAX_BYTES / (double)rawBytes);
        int w2 = (int)(w * mult), h2 = (int)(h * mult);

        System.err.printf("resized image: %dx%d -> %dx%d (%d argb8 bytes)\n", w, h, w2, h2, 4 * w2 * h2);

        Bitmap res = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(res);
        canvas.drawBitmap(src, new Rect(0, 0, w, h), new Rect(0, 0, w2, h2), new Paint());
        return res;
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
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); // night mode can make custom controls have black on black and be unreadable

        openDrawerButton(null); // start with menu open

        List<String> failedPermissions = new ArrayList<>();
        for (PermissionRequest r : getRequestedPermissions()) {
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
    @Override
    protected void onDestroy() {
        super.onDestroy();
        System.err.println("destroying");

        DatagramSocket sock = udpSocket;
        if (sock != null) sock.close(); // close this so we can reuse the port
        udpSocket = null;
    }

    private boolean canRunInBackground() {
        Switch toggle = (Switch)getNavigationView(R.id.runInBackgroundSwitch);
        return toggle.isChecked();
    }

    private final boolean[] sensorsRunning = new boolean[] { false };
    private void startSensors() {
        synchronized(sensorsRunning) {
            if (sensorsRunning[0]) return;
            sensorsRunning[0] = true;
            sensorsRunning.notifyAll(); // do this to wake up any threads that are suspended in the background
        }

        // motion sensors
        accelerometer.start();
        gravity.start();
        gyroscope.start();
        linearAcceleration.start();
        rotationVector.start();
        stepCounter.start();

        // position sensors
        gameRotationVector.start();
        geomagneticRotationVector.start();
        magneticField.start();
        proximity.start();

        // environment sensors
        ambientTemperature.start();
        light.start();
        pressure.start();
        relativeHumidity.start();

        // misc sensors
        soundSensor.start(this);
        location.start();
    }
    private void stopSensors() {
        synchronized (sensorsRunning) {
            if (!sensorsRunning[0]) return;
            sensorsRunning[0] = false;
        }

        // motion sensors
        accelerometer.stop();
        gravity.stop();
        gyroscope.stop();
        linearAcceleration.stop();
        rotationVector.stop();
        stepCounter.stop();

        // position sensors
        gameRotationVector.stop();
        geomagneticRotationVector.stop();
        magneticField.stop();
        proximity.stop();

        // environment sensors
        ambientTemperature.stop();
        light.stop();
        pressure.stop();
        relativeHumidity.stop();

        // misc sensors
        soundSensor.stop();
        location.stop();
    }

    private SharedPreferences _prefs;
    private SharedPreferences getPrefs() {
        if (_prefs != null) return _prefs;
        _prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return _prefs;
    }
    private void loadPrefId() {
        long macInt = -1;
        try { macInt = getPrefs().getLong(MAC_ADDR_PREF_NAME, -1); }
        catch (Exception ignored) { }
        if (macInt < 0) {
            // generate a random fake mac addr (new versions of android no longer support getting the real one)
            for (StringBuilder b = new StringBuilder(32);;) {
                rand.nextBytes(macAddress);
                b.setLength(0);
                appendBytes(b, macAddress);
                if (isValidHexStr(b.toString())) break;
            }

            // cache the generated value in preferences (so multiple application starts have the same id)
            macInt = 0;
            for (byte b : macAddress) macInt = (macInt << 8) | ((long)b & 0xff); // convert array to int
            getPrefs().edit().putLong(MAC_ADDR_PREF_NAME, macInt).apply();
        }
        else {
            // convert int to array
            for (int i = 5; i >= 0; --i, macInt >>= 8) macAddress[i] = (byte)macInt;
        }

        TextView title = (TextView)getNavigationView(R.id.idText);
        StringBuilder b = new StringBuilder(32);
        b.append("Device ID: ");
        appendBytes(b, macAddress);
        title.setText(b.toString());
    }
    void loadPrefRunInBackground() {
        boolean res = false;
        try { res = getPrefs().getBoolean(RUN_IN_BACKGROUND_PREF_NAME, false); }
        catch (Exception ignored) { }

        Switch toggle = (Switch)getNavigationView(R.id.runInBackgroundSwitch);
        toggle.setChecked(res);
    }

    private boolean postInitializationComplete = false; // marks that we've finished everything and are ready for user interaction
    private void finishInitialization() {
        loadPrefId();
        loadPrefRunInBackground();

        //invalidatePassword();
        setPassword(0); // for development purposes

        ArrayAdapter<String> completionAdapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_item, KNOWN_SERVERS);
        AutoCompleteTextView serverBox = (AutoCompleteTextView)getNavigationView(R.id.serverHostText);
        serverBox.setThreshold(1);
        serverBox.setAdapter(completionAdapter);
        serverBox.setText(KNOWN_SERVERS[0]); // auto fill in the first option

        // --------------------------------------------------

        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        // motion sensors
        accelerometer = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), 3);
        gravity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY), 3);
        gyroscope = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), 3, RAD_TO_DEG);
        linearAcceleration = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION), 3);
        rotationVector = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR), 4, RAD_TO_DEG);
        stepCounter = new SensorInfo(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) : null, 1);

        // position sensors
        gameRotationVector = new SensorInfo(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 ? sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null, 3);
        geomagneticRotationVector = new SensorInfo(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) : null, 3);
        magneticField = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), 3);
        proximity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY), 1);

        // environment sensors
        ambientTemperature = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE), 1);
        light = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT), 1);
        pressure = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE), 1);
        relativeHumidity = new SensorInfo(sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY), 1);

        // misc sensors
        orientationCalculator = new OrientationCalculator(accelerometer, magneticField);
        location = new LocationSensor(this);
        soundSensor = new SoundSensor();

        // --------------------------------------------------

        startSensors();

        // --------------------------------------------------

        ImageView controlsView = findViewById(R.id.controlPanel);
        controlsView.setOnTouchListener((v, e) -> handleCustomControlOnTouch(v, e));

        // repeat canvas redraw until first success (we need to wait for control constraints to resolve to get size)
        new Runnable() {
            @Override
            public void run() {
                try {
                    if (redrawCustomControls(true)) return;
                }
                catch (Exception ignore) {}
                handler.postDelayed(this, 100);
            }
        }.run();

        // --------------------------------------------------

        postInitializationComplete = true;
        System.err.println("post init complete");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (postInitializationComplete) startSensors();
        System.err.println("resuming");
    }
    @Override
    protected void onPause() {
        super.onPause();
        if (postInitializationComplete && !canRunInBackground()) stopSensors();
        System.err.println("pausing");
    }

    private static void appendBytes(StringBuilder b, byte[] bytes) {
        for (byte v : bytes) {
            b.append(String.format("%02x",v));
        }
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
        ExifInterface exif = new ExifInterface(imageActivityCorrectedPath);
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        Bitmap raw = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageActivityUri);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: return rotateImage(raw, 90);
            case ExifInterface.ORIENTATION_ROTATE_180: return rotateImage(raw, 180);
            case ExifInterface.ORIENTATION_ROTATE_270: return rotateImage(raw, 270);
            default: return raw;
        }
    }

    private IImageLike cameraImageDest = null;
    private void requestImageFor(IImageLike target) {
        // only do this if we have a camera and we have the necessary permissions
        if (getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        {
            cameraImageDest = target;
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) != null) {
                try {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                    File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                    File file = File.createTempFile("IMAGE_" + timeStamp + "_", ".jpg", storageDir);
                    Uri fileUri = FileProvider.getUriForFile(this, "com.example.android.fileprovider", file);
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);

                    // delete the last image if it isn't already (incomplete load cycle)
                    if (imageActivityCorrectedPath != null) {
                        try { new File(imageActivityCorrectedPath).delete(); }
                        catch (Exception ignored) {}
                    }
                    imageActivityCorrectedPath = file.getAbsolutePath();
                    imageActivityUri = fileUri;

                    startActivityForResult(intent, CAMERA_REQUEST_CODE);
                }
                catch (Exception ex) {
                    Toast.makeText(this, String.format("Failed to take picture: %s", ex), Toast.LENGTH_LONG).show();
                }
            }
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;

        if (requestCode == CAMERA_REQUEST_CODE) {
            try {
                Bitmap img = grabResultImage();
                if (cameraImageDest != null) {
                    cameraImageDest.setImage(img, true);
                    byte[] id = cameraImageDest.getID();
                    if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte)'b').put(id).array(), netsbloxAddress);
                }
            } catch (Exception ex) {
                Toast.makeText(this, String.format("failed to load image: %s", ex), Toast.LENGTH_LONG).show();
            }
            finally {
                try {
                    if (new File(imageActivityCorrectedPath).delete()) imageActivityCorrectedPath = null; // we read the file, so we can delete it and mark as gone
                }
                catch (Exception ignored) {}
            }
        }
    }

    // ------------------------------------------------------------------------------

    public void serverConnectButtonPress(View view) {
        getNavigationView(R.id.serverHostText).clearFocus(); // do this so we don't have a blinking cursor for all of eternity
        connectToServer();
    }
    public void connResetButtonPress(View view) {
        getNavigationView(R.id.serverHostText).clearFocus(); // do this so we don't have a blinking cursor for all of eternity
        requestConnReset();
    }
    public void openDrawerButton(View view) {
        DrawerLayout nav = findViewById(R.id.drawerLayout);
        nav.openDrawer(GravityCompat.START);
    }
    public void newPasswordButtonClick(View view) {
        new AlertDialog.Builder(this)
                .setTitle("Confirmation")
                .setMessage("Are you sure you want to regenerate the password? This may break active connections.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(android.R.string.yes, (d, w) -> invalidatePassword())
                .setNegativeButton(android.R.string.no, null)
                .show();
    }
    public void runInBackgroundSwitchClick(View view) {
        Switch toggle = (Switch)view;
        boolean on = toggle.isChecked();
        getPrefs().edit().putBoolean(RUN_IN_BACKGROUND_PREF_NAME, on).apply();

        if (on) {
            new AlertDialog.Builder(this)
                    .setTitle("Info")
                    .setMessage("Note: this is an experimental feature and may not function correctly on all devices.\n\n" +
                            "Due to accessing sensor data, running in the background can consume a lot of power if you forget to close the app." +
                            " Additionally, if location is enabled, it may still be tracked while running in the background.")
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }
}