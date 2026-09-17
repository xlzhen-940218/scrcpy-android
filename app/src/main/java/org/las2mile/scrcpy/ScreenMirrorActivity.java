package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.google.android.material.button.MaterialButton;

/**
 * Dedicated full-screen activity for screen mirroring.
 * Receives connection parameters via Intent extras and manages the Scrcpy service lifecycle.
 */
public class ScreenMirrorActivity extends Activity
        implements Scrcpy.ServiceCallbacks, SensorEventListener, SurfaceHolder.Callback {

    private static final String TAG = "ScreenMirrorActivity";

    public static final String EXTRA_SERVER_ADR = "server_adr";
    public static final String EXTRA_SERVER_PORT = "server_port";
    public static final String EXTRA_SCREEN_WIDTH = "screen_width";
    public static final String EXTRA_SCREEN_HEIGHT = "screen_height";
    public static final String EXTRA_AUDIO_ENABLED = "audio_enabled";
    public static final String EXTRA_NAV = "nav";
    public static final String EXTRA_NO_CONTROL = "no_control";
    public static final String EXTRA_SCREEN_OFF = "screen_off";
    public static final String EXTRA_SYNC_ROTATION = "sync_rotation";

    private String serverAdr;
    private int serverPort = 7007;
    private int screenWidth;
    private int screenHeight;
    private boolean audioEnabled;
    private boolean nav;
    private boolean noControl;
    private boolean screenOff;
    private boolean syncRotation = true;

    private SurfaceView surfaceView;
    private Surface surface;
    private ConstraintLayout containerLayout;
    private volatile Scrcpy scrcpy;
    private volatile boolean serviceBound = false;
    private boolean isFirstBind = true;
    private long backTimestamp = 0;

    private float remoteDeviceWidth;
    private float remoteDeviceHeight;
    private volatile boolean landscape = false;

    private SensorManager sensorManager;
    private Sensor proximitySensor;

    // ────────────────────────────────────────────────────────────────────────
    // Service connection
    // ────────────────────────────────────────────────────────────────────────

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            scrcpy = ((Scrcpy.MyServiceBinder) binder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(ScreenMirrorActivity.this);
            serviceBound = true;

            if (isFirstBind) {
                // Kick off connection
                scrcpy.start(surface, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled);

                // Background thread waits for socket, then handles result on UI thread
                new Thread(() -> {
                    int count = 60;
                    while (count > 0) {
                        Scrcpy s = scrcpy;
                        if (s == null || !serviceBound) break;
                        if (s.check_socket_connection()) break;
                        count--;
                        try { Thread.sleep(100); } catch (InterruptedException e) { break; }
                    }
                    final int finalCount = count;
                    runOnUiThread(() -> {
                        if (finalCount == 0) {
                            // Timed out
                            stopScrcpyService();
                            Toast.makeText(ScreenMirrorActivity.this,
                                    getString(R.string.msg_connection_timeout), Toast.LENGTH_SHORT).show();
                            finish();
                        } else {
                            if (scrcpy != null) {
                                int[] res = scrcpy.get_remote_device_resolution();
                                if (res != null && res.length >= 2) {
                                    remoteDeviceWidth = res[0];
                                    remoteDeviceHeight = res[1];
                                }
                                isFirstBind = false;
                                if (screenOff) {
                                    scrcpy.setDisplayPower(false);
                                }
                            }
                            adjustSurfaceLayout();
                        }
                    });
                }, "WaitSocketConnect").start();
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                adjustSurfaceLayout();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Read params from Intent
        Intent i = getIntent();
        serverAdr      = i.getStringExtra(EXTRA_SERVER_ADR);
        serverPort     = i.getIntExtra(EXTRA_SERVER_PORT, 7007);
        screenWidth    = i.getIntExtra(EXTRA_SCREEN_WIDTH, 1280);
        screenHeight   = i.getIntExtra(EXTRA_SCREEN_HEIGHT, 720);
        audioEnabled   = i.getBooleanExtra(EXTRA_AUDIO_ENABLED, false);
        nav            = i.getBooleanExtra(EXTRA_NAV, false);
        noControl      = i.getBooleanExtra(EXTRA_NO_CONTROL, false);
        screenOff      = i.getBooleanExtra(EXTRA_SCREEN_OFF, false);
        syncRotation   = i.getBooleanExtra(EXTRA_SYNC_ROTATION, true);

        setContentView(R.layout.surface);
        applyFullscreen();

        surfaceView    = findViewById(R.id.decoder_surface);
        containerLayout = findViewById(R.id.container1);

        // Nav bar visibility
        LinearLayout navBar = findViewById(R.id.nav_button_bar);
        if (nav && !noControl) {
            navBar.setVisibility(LinearLayout.VISIBLE);
        } else {
            navBar.setVisibility(LinearLayout.GONE);
        }

        // Use SurfaceHolder callback so we get surface ready event
        surfaceView.getHolder().addCallback(this);

        // Proximity sensor
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyFullscreen();
        if (!isFirstBind && serviceBound && scrcpy != null) {
            scrcpy.resume();
            registerProximity();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (serviceBound && scrcpy != null) scrcpy.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        stopScrcpyService();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (syncRotation && scrcpy != null && remoteDeviceWidth > 0 && remoteDeviceHeight > 0) {
            boolean currentLandscape = (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
            boolean remoteLandscape = (remoteDeviceWidth > remoteDeviceHeight);
            if (currentLandscape != remoteLandscape) {
                android.util.Log.d(TAG, "Controlling device orientation changed: currentLandscape="
                        + currentLandscape + ", remoteLandscape=" + remoteLandscape + ". Syncing remote rotation.");
                scrcpy.rotateDevice();
            }
        }
        if (containerLayout != null) {
            containerLayout.post(this::adjustSurfaceLayout);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // SurfaceHolder.Callback
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        // Bind service only once surface is ready
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        if (scrcpy != null && surface != null && surface.isValid()) {
            scrcpy.setSurface(surface);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surface = null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Scrcpy.ServiceCallbacks
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void loadNewRotation() {
        if (scrcpy == null) return;
        int[] res = scrcpy.get_remote_device_resolution();
        if (res == null || res.length < 2) return;
        remoteDeviceWidth  = res[0];
        remoteDeviceHeight = res[1];
        landscape = (remoteDeviceWidth > remoteDeviceHeight);

        // Aspect ratio is dynamically applied to SurfaceView by ConstraintSet in adjustSurfaceLayout()
        if (containerLayout != null) {
            containerLayout.post(this::adjustSurfaceLayout);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Touch & Key events
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private void setupTouchAndNav() {
        if (!noControl && surfaceView != null) {
            surfaceView.setOnTouchListener((v, event) ->
                    scrcpy != null && scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (nav && !noControl) {
            MaterialButton backBtn = findViewById(R.id.back_button);
            MaterialButton homeBtn = findViewById(R.id.home_button);
            MaterialButton switchBtn = findViewById(R.id.appswitch_button);
            if (backBtn   != null) backBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(4); });
            if (homeBtn   != null) homeBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(3); });
            if (switchBtn != null) switchBtn.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(187); });
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Forward D-pad / media keys to the remote device
        if (scrcpy != null && serviceBound) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    scrcpy.sendKeyevent(keyCode);
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        long now = SystemClock.uptimeMillis();
        if (backTimestamp == 0 || now > backTimestamp + 2000) {
            backTimestamp = now;
            Toast.makeText(this, getString(R.string.msg_press_again_exit), Toast.LENGTH_SHORT).show();
        } else {
            backTimestamp = 0;
            stopScrcpyService();
            finish();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Sensor
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            Scrcpy s = scrcpy;
            if (serviceBound && s != null) {
                try {
                    s.setDisplayPower(event.values[0] != 0);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private void applyFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /**
     * Updates the SurfaceView's dimension ratio via ConstraintSet so ConstraintLayout
     * automatically letterboxes/pillarboxes the video — no manual padding needed.
     * Works correctly in both portrait and landscape.
     */
    private void adjustSurfaceLayout() {
        setupTouchAndNav();

        if (!(containerLayout instanceof ConstraintLayout)) return;
        if (remoteDeviceWidth <= 0 || remoteDeviceHeight <= 0) return;

        // Format: "W:H" — ConstraintLayout will fit this ratio inside the available space
        String ratio = (int) remoteDeviceWidth + ":" + (int) remoteDeviceHeight;

        ConstraintLayout cl = (ConstraintLayout) containerLayout;
        androidx.constraintlayout.widget.ConstraintSet cs = new androidx.constraintlayout.widget.ConstraintSet();
        cs.clone(cl);
        cs.setDimensionRatio(R.id.decoder_surface, ratio);
        cs.applyTo(cl);
    }

    private void registerProximity() {
        if (sensorManager != null && proximitySensor != null) {
            sensorManager.unregisterListener(this);
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void stopScrcpyService() {
        if (serviceBound && scrcpy != null) {
            scrcpy.StopService();
            try { unbindService(serviceConnection); } catch (Exception ignored) {}
            serviceBound = false;
            scrcpy = null;
        }
        SendCommands.closeActiveSession();
    }
}
