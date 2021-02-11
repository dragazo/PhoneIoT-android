package com.health.salio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
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
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;

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
            new PermissionRequest(Manifest.permission.RECORD_AUDIO, "Record Audio"),
    };

    // ------------------------------------

    private static final int PERMISSIONS_REQUEST_CODE = 0x2301;
    private static final int CAMERA_REQUEST_CODE = 0x9901;

    // ------------------------------------

    private interface BasicSensor {
        boolean isSupported();
        double[] getData();
        void calculate();
    }

    // ------------------------------------

    private class SensorInfo implements SensorEventListener, BasicSensor {
        public Sensor sensor;
        public double[] data;
        public boolean supported;

        public SensorInfo(Sensor s, int dims) {
            sensor = s;
            data = new double[dims];
            supported = false;
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
            for (int i = 0; i < m; ++i) data[i] = newData[i];
            for (int i = m; i < data.length; ++i) data[i] = 0;
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public double[] getData() { return data; }
        @Override
        public void calculate() { }
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

        private final float[] matrixBuffer = new float[9];
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
        public double[] getData() { return data; }
        @Override
        public void calculate() {
            for (int i = 0; i < 3; ++i) accelBuffer[i] = (float)accel.data[i];
            for (int i = 0; i < 3; ++i) magnetBuffer[i] = (float)magnet.data[i];

            SensorManager.getRotationMatrix(matrixBuffer, null, accelBuffer, magnetBuffer);
            SensorManager.getOrientation(matrixBuffer, accelBuffer); // store into this buffer temporarily

            for (int i = 0; i < 3; ++i) data[i] = accelBuffer[i]; // an extract into real data array
        }
    }

    OrientationCalculator orientationCalculator;

    // ----------------------------------------------

    private class LocationSensor implements BasicSensor {
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
        @Override
        public void calculate() { }
    }
    private LocationSensor location;

    // ----------------------------------------------

    private static class SoundSensor implements BasicSensor {
        private final double[] data = new double[1];
        private boolean supported = false;

        private static final long SAMPLE_RATE = 250; // ms
        private static final float NORMALIZATION_FACTOR = 32768.0f;

        private MediaRecorder recorder = new MediaRecorder();
        private final Handler handler = new Handler();

        public SoundSensor() {
            new Runnable() {
                @Override
                public void run() {
                    try { data[0] = (float)recorder.getMaxAmplitude() / NORMALIZATION_FACTOR; }
                    catch (Exception ignore) { }
                    handler.postDelayed(this, SAMPLE_RATE);
                }
            }.run();
        }
        public void start() {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                recorder.setOutputFile("/dev/null"); // important: we don't want to save the recording (esp. since we never stop recording)
                recorder.prepare();
                recorder.start();
                supported = true;
            }
            catch (Exception ignored) {
                supported = false;
            }
        }
        public void stop() {
            recorder.stop();
        }

        @Override
        public boolean isSupported() { return supported; }
        @Override
        public double[] getData() { return data; }
        @Override
        public void calculate() { }
    }

    private SoundSensor soundSensor;

    // ----------------------------------------------

    private static final long PASSWORD_EXPIRY_INTERVAL = 1 * 1000 * 60 * 60 * 24; // expire after one day

    private long _rawPassword = 0;
    private long _passwordExpiry = 0;

    private long getPassword() {
        long nowtime = System.currentTimeMillis();
        if (nowtime < _passwordExpiry) return _rawPassword;
        setPassword(rand.nextLong() & Long.MAX_VALUE); // password is a 63-bit (positive) value
        return _rawPassword;
    }
    private void setPassword(long pass) {
        _rawPassword = pass;
        _passwordExpiry = System.currentTimeMillis() + PASSWORD_EXPIRY_INTERVAL;
        TextView text = findViewById(R.id.authText);
        text.setText(String.format("password: %016x", _rawPassword));
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

    private interface ICustomControl {
        void draw(Canvas canvas, Paint paint);
        boolean containsPoint(int x, int y);
        void handleClick(View view, MainActivity context);
        byte[] getID();
    }
    private interface IToggleable extends ICustomControl {
        boolean getToggleState();
    }
    private interface IImageLike extends ICustomControl {
        Bitmap getImage();
        void setImage(Bitmap newimg, boolean recycleOld);
    }
    private interface ITextLike extends ICustomControl {
        String getText();
        void setText(String newtext);
    }

    private class CustomButton implements ICustomControl, ITextLike {
        private int posx, posy, width, height;
        private int color, textColor;
        private final byte[] id;
        private String text;

        public CustomButton(int posx, int posy, int width, int height, int color, int textColor, byte[] id, String text) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.color = color;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(posx, posy, posx + width, posy + height, paint);
            paint.setColor(textColor);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(text, posx + (float)width / 2, posy + ((float)height + textBounds.height() - 4) / 2, paint);
        }
        @Override
        public boolean containsPoint(int x, int y) {
            return x >= posx && y >= posy && x <= posx + width && y <= posy + height;
        }
        @Override
        public void handleClick(View view, MainActivity context) {
            try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte)'b').put(id).array(), netsbloxAddress); }
            catch (Exception ignored) {}

            view.playSoundEffect(SoundEffectConstants.CLICK);
        }
        @Override
        public byte[] getID() { return id; }

        @Override
        public String getText() { return text; }
        @Override
        public void setText(String text) {
            this.text = text;
            redrawCustomControls(false);
        }
    }
    private class CustomImageBox implements ICustomControl, IImageLike {
        private int posx, posy, width, height;
        private final byte[] id;
        private Bitmap img;

        public CustomImageBox(int posx, int posy, int width, int height, byte[] id, Bitmap img) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.id = id;
            this.img = img;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(posx, posy, posx + width, posy + height, paint);

            float srcw = (float)img.getWidth(), srch = (float)img.getHeight();
            float mult = Math.min((float)width / srcw, (float)height / srch);
            float destw = srcw * mult, desth = srch * mult;
            float destx = posx + ((float)width - destw) / 2, desty = posy + ((float)height - desth) / 2;
            Rect src = new Rect(0, 0, img.getWidth(), img.getHeight());
            Rect dest = new Rect((int)destx, (int)desty, (int)(destx + destw), (int)(desty + desth));
            canvas.drawBitmap(img, src, dest, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas.drawRect(posx, posy, posx + width, posy + height, paint);
        }

        @Override
        public boolean containsPoint(int x, int y) {
            return x >= posx && y >= posy && x <= posx + width && y <= posy + height;
        }
        @Override
        public void handleClick(View view, MainActivity context) {
            view.playSoundEffect(SoundEffectConstants.CLICK);

            requestImageFor(this);
        }
        @Override
        public byte[] getID() { return id; }

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

        private static final int PADDING = 10;

        public CustomTextField(int posx, int posy, int width, int height, int color, int textColor, byte[] id, String text) {
            this.posx = posx;
            this.posy = posy;
            this.width = width;
            this.height = height;
            this.color = color;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas.drawRect(posx, posy, posx + width, posy + height, paint);
            paint.setColor(textColor);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setStyle(Paint.Style.FILL);

            if (Build.VERSION.SDK_INT >= 23) {
                TextPaint textPaint = new TextPaint(paint);
                StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, width - 2 * PADDING).build();

                canvas.save();
                canvas.translate(posx + PADDING, posy);
                layout.draw(canvas);
                canvas.restore();
            }
            else canvas.drawText(text, posx + (float)width / 2, posy + ((float)height + textBounds.height() - 4) / 2, paint);
        }

        @Override
        public boolean containsPoint(int x, int y) {
            return x >= posx && y >= posy && x <= posx + width && y <= posy + height;
        }
        @Override
        public void handleClick(View view, MainActivity context) {
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
        public byte[] getID() { return id; }

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

        public CustomLabel(int posx, int posy, int textColor, byte[] id, String text) {
            this.posx = posx;
            this.posy = posy;
            this.textColor = textColor;
            this.id = id;
            this.text = text;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            canvas.drawText(text, posx, posy + textBounds.height() - 6, paint);
        }
        @Override
        public boolean containsPoint(int x, int y) { return false; }
        @Override
        public void handleClick(View view, MainActivity context) { }
        @Override
        public byte[] getID() { return id; }

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
        private boolean state;
        private final byte[] id;
        private String text;
        private CheckboxStyle style;

        private static final int CHECKBOX_WIDTH = 35;
        private static final int CHECKBOX_PADDING = 20;

        public CustomCheckbox(int posx, int posy, int checkColor, int textColor, boolean state, byte[] id, String text, CheckboxStyle style) {
            this.posx = posx;
            this.posy = posy;
            this.checkColor = checkColor;
            this.textColor = textColor;
            this.state = state;
            this.id = id;
            this.text = text;
            this.style = style;
        }

        private void drawCheckbox(Canvas canvas, Paint paint) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(checkColor);
            canvas.drawRect(posx, posy, posx + CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH, paint);

            if (state) {
                paint.setStrokeWidth(3f);
                float x1 = posx + 5f, y1 = posy + (float)CHECKBOX_WIDTH * 0.5f;
                float x2 = posx + (float)CHECKBOX_WIDTH * 0.5f, y2 = posy + CHECKBOX_WIDTH * 0.75f;
                float x3 = posx + CHECKBOX_WIDTH, y3 = posy - (float)CHECKBOX_WIDTH * 0.5f;
                canvas.drawLines(new float[] {
                        x1, y1, x2, y2,
                        x2, y2, x3, y3,
                }, paint);
            }

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, posx + CHECKBOX_WIDTH + 17, posy + ((float)CHECKBOX_WIDTH + textBounds.height() - 4) / 2, paint);
        }
        private void drawToggleswitch(Canvas canvas, Paint paint) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(checkColor);
            canvas.drawArc(new RectF(posx, posy, posx + CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH), 90, 180, false, paint);
            canvas.drawArc(new RectF(posx + CHECKBOX_WIDTH, posy, posx + 2 * CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH), 270, 180, false, paint);
            canvas.drawLine(posx + 0.5f * CHECKBOX_WIDTH, posy, posx + 1.5f * CHECKBOX_WIDTH, posy, paint);
            canvas.drawLine(posx + 0.5f * CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH, posx + 1.5f * CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH, paint);

            float checkPosX;
            if (state) {
                paint.setStyle(Paint.Style.FILL);
                checkPosX = posx + CHECKBOX_WIDTH;
            }
            else {
                checkPosX = posx;
            }
            canvas.drawArc(new RectF(checkPosX + 5, posy + 5, checkPosX + CHECKBOX_WIDTH - 5, posy + CHECKBOX_WIDTH - 5), 0, 360, false, paint);

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, posx + 2f * CHECKBOX_WIDTH + 17, posy + ((float)CHECKBOX_WIDTH + textBounds.height() - 4) / 2, paint);
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            switch (style) {
                case CheckBox: drawCheckbox(canvas, paint); break;
                case ToggleSwitch: drawToggleswitch(canvas, paint); break;
            }
        }
        @Override
        public boolean containsPoint(int x, int y) {
            RectF rect;
            switch (style) {
                case CheckBox: rect = new RectF(posx, posy, posx + CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH); break;
                case ToggleSwitch: rect = new RectF(posx, posy, posx + 2 * CHECKBOX_WIDTH, posy + CHECKBOX_WIDTH); break;
                default: rect = new RectF(0, 0, 0, 0); break;
            }
            return x >= rect.left - CHECKBOX_PADDING && y >= rect.top - CHECKBOX_PADDING &&
                    x <= rect.right + CHECKBOX_PADDING && y <= rect.bottom + CHECKBOX_PADDING;
        }
        @Override
        public void handleClick(View view, MainActivity context) {
            state = !state;
            context.redrawCustomControls(false);

            try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(2 + id.length).put((byte)'z').put((byte)(state ? 1 : 0)).put(id).array(), netsbloxAddress); }
            catch (Exception ignored) {}

            view.playSoundEffect(SoundEffectConstants.CLICK);
        }
        @Override
        public byte[] getID() { return id; }

        @Override
        public boolean getToggleState() { return state; }

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
        private boolean state;
        private final byte[] id;
        private byte[] group;
        private String text;

        private static final int RADIO_WIDTH = 35;
        private static final int RADIO_PADDING = 20;
        private static final int RADIO_INSET = 5;

        public CustomRadioButton(int posx, int posy, int checkColor, int textColor, boolean state, byte[] id, byte[] group, String text) {
            this.posx = posx;
            this.posy = posy;
            this.checkColor = checkColor;
            this.textColor = textColor;
            this.state = state;
            this.id = id;
            this.group = group;
            this.text = text;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(checkColor);
            canvas.drawArc(new RectF(posx, posy, posx + RADIO_WIDTH, posy + RADIO_WIDTH),
                    0, 360, false, paint);

            if (state) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawArc(new RectF(posx + RADIO_INSET, posy + RADIO_INSET, posx + RADIO_WIDTH - RADIO_INSET, posy + RADIO_WIDTH - RADIO_INSET),
                        0, 360, false, paint);
            }

            Rect textBounds = new Rect();
            paint.getTextBounds(text, 0, text.length(), textBounds);

            paint.setColor(textColor);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(text, posx + RADIO_WIDTH + 17, posy + ((float)RADIO_WIDTH + textBounds.height() - 10) / 2, paint);
        }
        @Override
        public boolean containsPoint(int x, int y) {
            return x >= posx - RADIO_PADDING && y >= posy - RADIO_PADDING &&
                    x <= posx + RADIO_WIDTH + RADIO_PADDING && y <= posy + RADIO_WIDTH + RADIO_PADDING;
        }
        @Override
        public void handleClick(View view, MainActivity context) {
            // set state to true and uncheck every other radiobutton in the same group
            state = true;
            for (ICustomControl other : context.customControls) {
                if (other != this && other instanceof CustomRadioButton) {
                    CustomRadioButton b = (CustomRadioButton)other;
                    if (Arrays.equals(b.group, this.group)) b.state = false;
                }
            }
            context.redrawCustomControls(false);

            try { if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte)'b').put(id).array(), netsbloxAddress); }
            catch (Exception ignored) {}

            view.playSoundEffect(SoundEffectConstants.CLICK);
        }

        @Override
        public byte[] getID() { return id; }

        @Override
        public boolean getToggleState() { return state; }

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
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(10);
        paint.setTextSize(50 * ((float)height / 1200));
        canvas.drawRect(0, 0, img.getWidth(), img.getHeight(), paint);

        for (ICustomControl control : customControls) {
            control.draw(canvas, paint);
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
    private boolean handleCustomControlOnTouch(View view, MotionEvent e) {
        List<ICustomControl> controls = customControls;
        int x = (int)e.getX();
        int y = (int)e.getY();

        // find the first thing we clicked (iterate backwards because we draw forwards, so back is on top layer)
        for (int i = controls.size() - 1; i >= 0; --i) {
            ICustomControl control = controls.get(i);
            if (control.containsPoint(x, y)) {
                control.handleClick(view, this);
                break;
            }
        }

        return false;
    }

    private Bitmap getDefaultImage() {
        Bitmap img = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(img);
        canvas.drawRect(0, 0, 100, 100, new Paint(Color.BLACK));
        return img;
    }

    // ----------------------------------------------

    private final Random rand = new Random();

    private static final int SERVER_PORT = 1976;
    private static final int UDP_PORT = 8888;
    private SocketAddress netsbloxAddress = null; // target for heartbeat comms - can be changed at will
    private DatagramSocket udpSocket = null;      // our socket for udp comms - do not close or change it

    private static final String MAC_ADDR_PREF_NAME = "MAC_ADDR"; // name to use for mac addr in stored app preferences
    private byte[] macAddress = null;

    private Thread udpServerThread = null;
    private Thread pipeThread = null;
    private long next_heartbeat = 0;

    private String reconnectRequest = null;
    private final List<DatagramPacket> pipeQueue = new ArrayList<>();

    @FunctionalInterface
    private interface SensorConsumer {
        void apply(BasicSensor sensor) throws Exception;
    }
    @FunctionalInterface
    private interface Predicate<T> {
        boolean test(T t);
    }

    private void connectToServer() {
        if (udpSocket == null) {
            try { udpSocket = new DatagramSocket(UDP_PORT); }
            catch (Exception ex) {
                Toast.makeText(this, String.format("Failed to open udp port %d: %s", UDP_PORT, ex.toString()), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        reconnectRequest = ((EditText)findViewById(R.id.serverHostText)).getText().toString();

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
                                SocketAddress temp = new InetSocketAddress(recon, SERVER_PORT);
                                // check to make sure the address is good (send empty packet, which will be ignored)
                                synchronized (pipeQueue) { // lock pipeQueue so we can send from the socket
                                    udpSocket.send(new DatagramPacket(new byte[] {}, 0, temp));
                                }

                                netsbloxAddress = temp; // if we get here it was a valid address
                                next_heartbeat = 0;
                                System.err.printf("reconnected to target: %s\n", netsbloxAddress);
                            }
                            catch (Exception ex) { System.err.printf("reconnect failure: %s\n", ex); }
                        }

                        // send a heartbeat if we're over the next timestamp
                        long now_time = System.currentTimeMillis();
                        if (now_time >= next_heartbeat && netsbloxAddress != null) {
                            netsbloxSend(new byte[] { 'I' }, netsbloxAddress); // send heartbeat so server knows we're still there
                            next_heartbeat = now_time + 30 * 1000; // next heartbeat in 30 seconds
                            System.err.println("sent heartbeat");
                        }

                        // wait for a message - short duration is so we can see reconnections quickly
                        udpSocket.setSoTimeout(1000);
                        udpSocket.receive(packet);

                        // ignore anything that's invalid or fails to auth
                        if (packet.getLength() < 9 || fromBEBytes(buf, 1, 8) != getPassword()) continue;

                        final SensorConsumer handleSensor = src -> {
                            if (packet.getLength() != 9) return; // ignore invalid format
                            if (src.isSupported()) { // if the sensor is supported, send back all the content
                                src.calculate(); // compute software-emulated logic (if any)
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
                                if (target == null) netsbloxSend(new byte[]{ buf[0], 1 }, packet.getSocketAddress());
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
                                if (target == null) netsbloxSend(new byte[] { buf[0], 1 }, packet.getSocketAddress());
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
                            case 'W': { // get toggle state
                                if (packet.getLength() < 9) continue;
                                byte[] id = Arrays.copyOfRange(buf, 9, packet.getLength());
                                IToggleable target = (IToggleable)getCustomControlWithIDWhere(id, c -> c instanceof IToggleable);
                                netsbloxSend(new byte[] { buf[0], (byte)(target == null ? 2 : target.getToggleState() ? 1 : 0) }, packet.getSocketAddress());
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
                                if (packet.getLength() < 34) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                int color = intFromBEBytes(buf, 25);
                                int textColor = intFromBEBytes(buf, 29);
                                int idlen = (int)buf[33] & 0xff;
                                if (packet.getLength() < 34 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 34, 34 + idlen);
                                String text = new String(buf, 34 + idlen, packet.getLength() - (34 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomButton(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        color, textColor, id, text);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'U': { // add custom image display
                                if (packet.getLength() < 26) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                byte[] id = Arrays.copyOfRange(buf, 25, packet.getLength());

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomImageBox(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        id, getDefaultImage());
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'T': { // add custom button control
                                if (packet.getLength() < 34) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                float width = floatFromBEBytes(buf, 17);
                                float height = floatFromBEBytes(buf, 21);
                                int color = intFromBEBytes(buf, 25);
                                int textColor = intFromBEBytes(buf, 29);
                                int idlen = (int)buf[33] & 0xff;
                                if (packet.getLength() < 34 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 34, 34 + idlen);
                                String text = new String(buf, 34 + idlen, packet.getLength() - (34 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomTextField(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        (int)(width / 100 * viewWidth), (int)(height / 100 * viewHeight),
                                        color, textColor, id, text);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'g': { // add custom label control
                                if (packet.getLength() < 22) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int textColor = intFromBEBytes(buf, 17);
                                int idlen = (int)buf[21] & 0xff;
                                if (packet.getLength() < 22 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 22, 22 + idlen);
                                String text = new String(buf, 22 + idlen, packet.getLength() - (22 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomLabel(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        textColor, id, text);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'Z': { // add custom checkbox control
                                if (packet.getLength() < 28) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int checkColor = intFromBEBytes(buf, 17);
                                int textColor = intFromBEBytes(buf, 21);
                                boolean state = buf[25] != 0;
                                CheckboxStyle style;
                                switch (buf[26]) {
                                    case 0: default: style = CheckboxStyle.CheckBox; break;
                                    case 1: style = CheckboxStyle.ToggleSwitch; break;
                                }
                                int idlen = (int)buf[27] & 0xff;
                                if (packet.getLength() < 28 + idlen) continue;
                                byte[] id = Arrays.copyOfRange(buf, 28, 28 + idlen);
                                String text = new String(buf, 28 + idlen, packet.getLength() - (28 + idlen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomCheckbox(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        checkColor, textColor, state, id, text, style);
                                netsbloxSend(new byte[] { buf[0], tryAddCustomControl(control) }, packet.getSocketAddress());
                                break;
                            }
                            case 'y': { // add custom radiobutton control
                                if (packet.getLength() < 27) continue;
                                float x = floatFromBEBytes(buf, 9);
                                float y = floatFromBEBytes(buf, 13);
                                int checkColor = intFromBEBytes(buf, 17);
                                int textColor = intFromBEBytes(buf, 21);
                                boolean state = buf[25] != 0;
                                int idlen = (int)buf[26] & 0xff;
                                if (packet.getLength() < 27 + idlen + 1) continue;
                                byte[] id = Arrays.copyOfRange(buf, 27, 27 + idlen);
                                int grouplen = (int)buf[27 + idlen] & 0xff;
                                if (packet.getLength() < 27 + idlen + 1 + grouplen) continue;
                                byte[] group = Arrays.copyOfRange(buf, 27 + idlen + 1, 27 + idlen + 1 + grouplen);
                                String text = new String(buf, 27 + idlen + 1 + grouplen, packet.getLength() - (27 + idlen + 1 + grouplen), "UTF-8");

                                ImageView view = findViewById(R.id.controlPanel);
                                int viewWidth = view.getWidth(), viewHeight = view.getHeight();
                                ICustomControl control = new CustomRadioButton(
                                        (int)(x / 100 * viewWidth), (int)(y / 100 * viewHeight),
                                        checkColor, textColor, state, id, group, text);
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
    }
    private void netsbloxSend(byte[] content, SocketAddress dest) {
        if (udpSocket != null) {
            byte[] expanded = new byte[content.length + 10];
            for (int i = 0; i < 6; ++i) expanded[i] = macAddress[i];
            for (int i = 0; i < 4; ++i) expanded[6 + i] = 0; // we can set the time field to zero (pretty sure it isn't actually used by the server)
            for (int i = 0; i < content.length; ++i) expanded[10 + i] = content[i];
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
        soundSensor.start();
        location.start();
    }
    private void stopSensors() {
        synchronized(sensorsRunning) {
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

    private boolean postInitializationComplete = false; // marks that we've finished everything and are ready for user interaction
    private void finishInitialization() {
        macAddress = new byte[6];
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        long macInt = -1;
        try { macInt = prefs.getLong(MAC_ADDR_PREF_NAME, -1); }
        catch (Exception ex) { Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show(); }
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
        b.append("PhoneIoT - ");
        appendBytes(b, macAddress);
        title.setText(b.toString());

        //invalidatePassword();
        setPassword(0); // for development purposes

        // --------------------------------------------------

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
        Handler handler = new Handler();
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
        if (postInitializationComplete) stopSensors();
        System.err.println("pausing");
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
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK) return;
        switch (requestCode) {
            case CAMERA_REQUEST_CODE: {
                try {
                    Bitmap img = grabResultImage();
                    if (cameraImageDest != null) {
                        cameraImageDest.setImage(img, true);
                        byte[] id = cameraImageDest.getID();
                        if (netsbloxAddress != null) netsbloxSend(ByteBuffer.allocate(1 + id.length).put((byte)'b').put(id).array(), netsbloxAddress);
                    }
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
        findViewById(R.id.serverHostText).clearFocus(); // do this so we don't have a blinking cursor for all of eternity
        connectToServer();
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
}