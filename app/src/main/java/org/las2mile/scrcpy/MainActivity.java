package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.app.AlertDialog;
import android.view.LayoutInflater;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.las2mile.scrcpy.adb.AdbPairingHelper;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks, SensorEventListener {
    private static final String TAG = "MainActivity";
    private static final String PREFERENCE_KEY = "default";
    private static final String PREFERENCE_SPINNER_RESOLUTION = "spinner_resolution";
    private static final String PREFERENCE_SPINNER_BITRATE = "spinner_bitrate";
    private static final String PREFERENCE_SPINNER_CODEC = "spinner_codec";
    private static final String PREFERENCE_SPINNER_FPS = "spinner_fps";
    private static final String PREFERENCE_SWITCH_AUDIO = "switch_audio";
    private static final String PREFERENCE_SPINNER_AUDIO_CODEC = "spinner_audio_codec";
    private static final String PREFERENCE_SWITCH_STAY_AWAKE = "switch_stay_awake";
    private static final String PREFERENCE_SWITCH_SCREEN_OFF = "switch_screen_off";

    private static int screenWidth;
    private static int screenHeight;
    private static volatile boolean landscape = false;
    private static volatile boolean first_time = true;
    private static volatile boolean result_of_Rotation = false;
    private static volatile boolean serviceBound = false;
    private static boolean nav = false;
    private static boolean no_control = false;
    private static float remote_device_width;
    private static float remote_device_height;

    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private SendCommands sendCommands;
    private int videoBitrate;
    private String videoCodec = "h264";
    private int maxFps = 0;
    private boolean audioEnabled = false;
    private String audioCodec = "raw";
    private boolean stayAwake = false;
    private boolean screenOff = false;

    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private volatile Scrcpy scrcpy;
    private long timestamp = 0;
    private byte[] fileBase64;
    private LinearLayout linearLayout;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                scrcpy.start(surface, serverAdr, 7007, screenHeight, screenWidth, audioEnabled);
                new Thread(() -> {
                    int count = 60;
                    while (count > 0) {
                        Scrcpy s = scrcpy;
                        if (s == null || !serviceBound) break;
                        if (s.check_socket_connection()) break;
                        count--;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }

                    final int finalCount = count;
                    runOnUiThread(() -> {
                        if (finalCount == 0) {
                            if (serviceBound && scrcpy != null) {
                                scrcpy.StopService();
                                try {
                                    unbindService(serviceConnection);
                                } catch (Exception ignored) {}
                                serviceBound = false;
                                scrcpy = null;
                                scrcpy_main();
                            }
                            Toast.makeText(context, "Connection Timed out", Toast.LENGTH_SHORT).show();
                        } else {
                            if (scrcpy != null) {
                                int[] rem_res = scrcpy.get_remote_device_resolution();
                                if (rem_res != null && rem_res.length >= 2) {
                                    remote_device_width = rem_res[0];
                                    remote_device_height = rem_res[1];
                                }
                                first_time = false;
                                if (screenOff) {
                                    scrcpy.setDisplayPower(false);
                                }
                            }
                            set_display_nd_touch();
                        }
                    });
                }, "WaitSocketConnect").start();
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                set_display_nd_touch();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (first_time) {
            scrcpy_main();
        } else {
            this.context = this;
            start_screen_copy_magic();
        }

        sensorManager = (SensorManager) this.getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }
    }

    public void scrcpy_main() {
        setContentView(R.layout.activity_main);
        final Button startButton = findViewById(R.id.button_start);
        final Button floatButton = findViewById(R.id.button_start_float);

        AssetManager assetManager = getAssets();
        try (InputStream inputStream = assetManager.open("scrcpy-server.jar")) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            byte[] rawBytes = baos.toByteArray();
            fileBase64 = Base64.encode(rawBytes, Base64.NO_WRAP);
            Log.d(TAG, "Loaded scrcpy-server.jar: " + rawBytes.length + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Asset Manager Error: " + e.getMessage());
        }

        sendCommands = new SendCommands();

        startButton.setOnClickListener(v -> {
            getAttributes();
            if (serverAdr.isEmpty()) {
                Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
                return;
            }

            // Show progress dialog and run ADB commands on background thread to avoid ANR
            AlertDialog connectingDialog = new AlertDialog.Builder(this)
                    .setTitle("正在连接...")
                    .setMessage("正在部署 scrcpy 服务，请稍候")
                    .setCancelable(false)
                    .create();
            connectingDialog.show();
            startButton.setEnabled(false);

            final String capturedServerAdr = serverAdr;
            final int capturedBitrate = videoBitrate;
            final int capturedMaxFps = maxFps;
            final String capturedVideoCodec = videoCodec;
            final boolean capturedAudio = audioEnabled;
            final String capturedAudioCodec = audioCodec;
            final boolean capturedControl = !no_control;
            final boolean capturedStayAwake = stayAwake;

            new Thread(() -> {
                int res = sendCommands.SendAdbCommands(context, fileBase64, capturedServerAdr, 7007, capturedBitrate,
                        Math.max(screenHeight, screenWidth), capturedMaxFps, capturedVideoCodec,
                        capturedAudio, capturedAudioCodec, capturedControl, capturedStayAwake);
                runOnUiThread(() -> {
                    connectingDialog.dismiss();
                    startButton.setEnabled(true);
                    if (res == 0) {
                        start_screen_copy_magic();
                    } else {
                        String err = sendCommands.getLastError();
                        String msg = (err != null && !err.isEmpty()) ? err : "Network OR ADB connection failed. Check if port 5555 is enabled.";
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }, "AdbConnectThread").start();
        });

        floatButton.setOnClickListener(v -> {
            getAttributes();
            showDisplayWindow();
        });

        final Button pairButton = findViewById(R.id.button_adb_pair);
        if (pairButton != null) {
            pairButton.setOnClickListener(v -> showPairDialog());
        }

        get_saved_preferences();
    }

    private void showPairDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.pair_dialog_title);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pair, null);
        builder.setView(view);

        final EditText etHost = view.findViewById(R.id.et_pair_host);
        final EditText etPort = view.findViewById(R.id.et_pair_port);
        final EditText etCode = view.findViewById(R.id.et_pair_code);
        final ProgressBar progressBar = view.findViewById(R.id.pb_pair_progress);
        final TextView tvStatus = view.findViewById(R.id.tv_pair_status);

        String currentHost = ((EditText) findViewById(R.id.editText_server_host)).getText().toString().trim();
        if (currentHost.contains(":")) {
            etHost.setText(currentHost.substring(0, currentHost.indexOf(':')).trim());
        } else if (!currentHost.isEmpty()) {
            etHost.setText(currentHost);
        }

        builder.setPositiveButton(R.string.pair_dialog_btn_pair, null);
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = etHost.getText().toString().trim();
            String portStr = etPort.getText().toString().trim();
            String code = etCode.getText().toString().trim();

            if (host.isEmpty() || portStr.isEmpty() || code.isEmpty()) {
                tvStatus.setText("请完整输入配对 IP、端口和配对码");
                tvStatus.setVisibility(View.VISIBLE);
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                tvStatus.setText("端口格式无效");
                tvStatus.setVisibility(View.VISIBLE);
                return;
            }

            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

            org.las2mile.scrcpy.adb.AdbPairingHelper.pair(context, host, port, code, new org.las2mile.scrcpy.adb.AdbPairingHelper.PairingCallback() {
                @Override
                public void onSuccess() {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(context, "配对成功！请输入无线调试连接端口后点击启动。", Toast.LENGTH_LONG).show();
                    dialog.dismiss();

                    EditText editTextServerHost = findViewById(R.id.editText_server_host);
                    if (editTextServerHost != null) {
                        editTextServerHost.setText(host + ":");
                        editTextServerHost.setSelection(editTextServerHost.getText().length());
                        editTextServerHost.requestFocus();
                    }
                }

                @Override
                public void onFailure(String error) {
                    progressBar.setVisibility(View.GONE);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    tvStatus.setText(error);
                    tvStatus.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showDisplayWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                startActivity(intent);
                return;
            }
        }
        Intent it = new Intent(this, FloatService.class);
        it.putExtra("ip", serverAdr);
        it.putExtra("w", screenWidth);
        it.putExtra("h", screenHeight);
        it.putExtra("b", videoBitrate);
        startService(it);
        finish();
    }

    public void get_saved_preferences() {
        this.context = this;
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        final Switch aSwitch0 = findViewById(R.id.switch0);
        final Switch aSwitch1 = findViewById(R.id.switch1);
        final Switch switchAudio = findViewById(R.id.switch_audio);
        final Switch switchStayAwake = findViewById(R.id.switch_stay_awake);
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);

        editTextServerHost.setText(context.getSharedPreferences(PREFERENCE_KEY, 0).getString("Server Address", ""));
        aSwitch0.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("No Control", false));
        aSwitch1.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean("Nav Switch", false));
        switchAudio.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean(PREFERENCE_SWITCH_AUDIO, false));
        switchStayAwake.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean(PREFERENCE_SWITCH_STAY_AWAKE, true));
        switchScreenOff.setChecked(context.getSharedPreferences(PREFERENCE_KEY, 0).getBoolean(PREFERENCE_SWITCH_SCREEN_OFF, false));

        setSpinner(R.array.options_resolution_values, R.id.spinner_video_resolution, PREFERENCE_SPINNER_RESOLUTION);
        setSpinner(R.array.options_bitrate_keys, R.id.spinner_video_bitrate, PREFERENCE_SPINNER_BITRATE);
        setSpinner(R.array.options_codec_keys, R.id.spinner_video_codec, PREFERENCE_SPINNER_CODEC);
        setSpinner(R.array.options_fps_keys, R.id.spinner_max_fps, PREFERENCE_SPINNER_FPS);
        setSpinner(R.array.options_audio_codec_keys, R.id.spinner_audio_codec, PREFERENCE_SPINNER_AUDIO_CODEC);

        if (aSwitch0.isChecked()) {
            aSwitch1.setVisibility(View.GONE);
        }

        aSwitch0.setOnClickListener(v -> {
            if (aSwitch0.isChecked()) {
                aSwitch1.setVisibility(View.GONE);
            } else {
                aSwitch1.setVisibility(View.VISIBLE);
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (ViewConfiguration.get(context).hasPermanentMenuKey()) {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        } else {
            final Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        }

        float this_dev_height = linearLayout.getHeight();
        float this_dev_width = linearLayout.getWidth();
        if (nav && !no_control) {
            if (landscape) {
                this_dev_width = this_dev_width - 96;
            } else {
                this_dev_height = this_dev_height - 96;
            }
        }

        if (remote_device_width > 0 && remote_device_height > 0) {
            float remote_device_aspect_ratio = remote_device_height / remote_device_width;
            if (!landscape) { // Portrait
                float this_device_aspect_ratio = this_dev_height / this_dev_width;
                if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                    float wantWidth = this_dev_height / remote_device_aspect_ratio;
                    int padding = (int) (this_dev_width - wantWidth) / 2;
                    linearLayout.setPadding(padding, 0, padding, 0);
                } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                    linearLayout.setPadding(0, (int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_width)), 0, 0);
                }
            } else { // Landscape
                float this_device_aspect_ratio = this_dev_width / this_dev_height;
                if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                    float wantHeight = this_dev_width / remote_device_aspect_ratio;
                    int padding = (int) (this_dev_height - wantHeight) / 2;
                    linearLayout.setPadding(0, padding, 0, padding);
                } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                    int padding = (int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_height) / 2);
                    linearLayout.setPadding(padding, 0, padding, 0);
                }
            }
        }

        if (!no_control && surfaceView != null) {
            surfaceView.setOnTouchListener((v, event) -> scrcpy != null && scrcpy.touchevent(event, surfaceView.getWidth(), surfaceView.getHeight()));
        }

        if (nav && !no_control) {
            final Button backButton = findViewById(R.id.back_button);
            final Button homeButton = findViewById(R.id.home_button);
            final Button appswitchButton = findViewById(R.id.appswitch_button);

            if (backButton != null) backButton.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(4); });
            if (homeButton != null) homeButton.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(3); });
            if (appswitchButton != null) appswitchButton.setOnClickListener(v -> { if (scrcpy != null) scrcpy.sendKeyevent(187); });
        }

        if (sensorManager != null && proximitySensor != null) {
            sensorManager.unregisterListener(this);
            sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void setSpinner(final int textArrayOptionResId, final int textViewResId, final String preferenceId) {
        final Spinner spinner = findViewById(textViewResId);
        ArrayAdapter<CharSequence> arrayAdapter = ArrayAdapter.createFromResource(this, textArrayOptionResId, android.R.layout.simple_spinner_item);
        arrayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(arrayAdapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putInt(preferenceId, 0).apply();
            }
        });
        spinner.setSelection(context.getSharedPreferences(PREFERENCE_KEY, 0).getInt(preferenceId, 0));
    }

    private void getAttributes() {
        final EditText editTextServerHost = findViewById(R.id.editText_server_host);
        serverAdr = editTextServerHost.getText().toString();
        context.getSharedPreferences(PREFERENCE_KEY, 0).edit().putString("Server Address", serverAdr).apply();

        final Spinner videoResolutionSpinner = findViewById(R.id.spinner_video_resolution);
        final Spinner videoBitrateSpinner = findViewById(R.id.spinner_video_bitrate);
        final Spinner videoCodecSpinner = findViewById(R.id.spinner_video_codec);
        final Spinner maxFpsSpinner = findViewById(R.id.spinner_max_fps);

        final Switch a_Switch0 = findViewById(R.id.switch0);
        no_control = a_Switch0.isChecked();
        final Switch a_Switch1 = findViewById(R.id.switch1);
        nav = a_Switch1.isChecked();

        final Switch switchAudio = findViewById(R.id.switch_audio);
        audioEnabled = switchAudio.isChecked();
        final Switch switchStayAwake = findViewById(R.id.switch_stay_awake);
        stayAwake = switchStayAwake.isChecked();
        final Switch switchScreenOff = findViewById(R.id.switch_screen_off);
        screenOff = switchScreenOff.isChecked();

        context.getSharedPreferences(PREFERENCE_KEY, 0).edit()
                .putBoolean("No Control", no_control)
                .putBoolean("Nav Switch", nav)
                .putBoolean(PREFERENCE_SWITCH_AUDIO, audioEnabled)
                .putBoolean(PREFERENCE_SWITCH_STAY_AWAKE, stayAwake)
                .putBoolean(PREFERENCE_SWITCH_SCREEN_OFF, screenOff)
                .apply();

        final String[] videoResolutions = getResources().getStringArray(R.array.options_resolution_values)[videoResolutionSpinner.getSelectedItemPosition()].split("x");
        screenHeight = Integer.parseInt(videoResolutions[0]);
        screenWidth = Integer.parseInt(videoResolutions[1]);
        videoBitrate = getResources().getIntArray(R.array.options_bitrate_values)[videoBitrateSpinner.getSelectedItemPosition()];

        videoCodec = videoCodecSpinner.getSelectedItemPosition() == 1 ? "h265" : "h264";
        maxFps = getResources().getIntArray(R.array.options_fps_values)[maxFpsSpinner.getSelectedItemPosition()];

        final Spinner audioCodecSpinner = findViewById(R.id.spinner_audio_codec);
        if (audioCodecSpinner != null) {
            audioCodec = getResources().getStringArray(R.array.options_audio_codec_values)[audioCodecSpinner.getSelectedItemPosition()];
        }
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        setContentView(R.layout.surface);
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView = findViewById(R.id.decoder_surface);
        surface = surfaceView.getHolder().getSurface();
        final LinearLayout nav_bar = findViewById(R.id.nav_button_bar);
        if (nav && !no_control) {
            nav_bar.setVisibility(LinearLayout.VISIBLE);
        } else {
            nav_bar.setVisibility(LinearLayout.GONE);
        }
        linearLayout = findViewById(R.id.container1);
        start_Scrcpy_service();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void loadNewRotation() {
        if (scrcpy == null) return;
        int[] rem_res = scrcpy.get_remote_device_resolution();
        if (rem_res == null || rem_res.length < 2) return;
        remote_device_width = rem_res[0];
        remote_device_height = rem_res[1];

        result_of_Rotation = true;
        landscape = (remote_device_width > remote_device_height);
        if (landscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
        if (linearLayout != null) {
            linearLayout.post(this::set_display_nd_touch);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!first_time && linearLayout != null) {
            linearLayout.post(this::set_display_nd_touch);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (serviceBound && scrcpy != null) {
            scrcpy.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time && !result_of_Rotation) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound && scrcpy != null) {
                linearLayout = findViewById(R.id.container1);
                scrcpy.resume();
                if (sensorManager != null && proximitySensor != null) {
                    sensorManager.unregisterListener(this);
                    sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                }
            }
        }
        result_of_Rotation = false;
    }

    @Override
    public void onBackPressed() {
        if (timestamp == 0) {
            timestamp = SystemClock.uptimeMillis();
            Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show();
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (sensorManager != null) {
                    sensorManager.unregisterListener(this);
                }
                if (serviceBound && scrcpy != null) {
                    scrcpy.StopService();
                    try {
                        unbindService(serviceConnection);
                    } catch (Exception ignored) {}
                    serviceBound = false;
                    scrcpy = null;
                }
                SendCommands.closeActiveSession();
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
            timestamp = 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        if (sensorEvent.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            Scrcpy localScrcpy = scrcpy;
            if (serviceBound && localScrcpy != null) {
                try {
                    if (sensorEvent.values[0] == 0) {
                        localScrcpy.setDisplayPower(false);
                    } else {
                        localScrcpy.setDisplayPower(true);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to toggle display power: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (serviceBound && scrcpy != null) {
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {}
            serviceBound = false;
            scrcpy = null;
        }
        SendCommands.closeActiveSession();
    }
}
