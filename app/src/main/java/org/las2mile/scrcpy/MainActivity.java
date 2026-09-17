package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.hardware.usb.UsbDevice;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.las2mile.scrcpy.adb.AdbPairingHelper;
import org.las2mile.scrcpy.usb.UsbAdbManager;

import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final String PREF_KEY = "default";

    // Preference keys
    private static final String PREF_SERVER_ADDRESS   = "Server Address";
    private static final String PREF_NO_CONTROL       = "No Control";
    private static final String PREF_NAV              = "Nav Switch";
    private static final String PREF_AUDIO            = "switch_audio";
    private static final String PREF_STAY_AWAKE       = "switch_stay_awake";
    private static final String PREF_SCREEN_OFF       = "switch_screen_off";
    private static final String PREF_SYNC_ROTATION    = "switch_sync_rotation";
    private static final String PREF_CONNECTION_MODE  = "connection_mode";
    private static final String PREF_RES_IDX          = "spinner_resolution";
    private static final String PREF_BITRATE_IDX      = "spinner_bitrate";
    private static final String PREF_CODEC_IDX        = "spinner_codec";
    private static final String PREF_FPS_IDX          = "spinner_fps";
    private static final String PREF_AUDIO_CODEC_IDX  = "spinner_audio_codec";

    // UI references - Mode & Connection
    private MaterialButtonToggleGroup toggleGroupMode;
    private View layoutWifiConnection;
    private View layoutUsbConnection;
    private TextView tvUsbStatus;
    private TextView tvUsbHint;
    private MaterialButton btnUsbConnect;
    private TextInputEditText etServerHost;

    // UI references - Video & Audio & Advanced
    private AutoCompleteTextView acResolution;
    private AutoCompleteTextView acBitrate;
    private AutoCompleteTextView acCodec;
    private AutoCompleteTextView acFps;
    private AutoCompleteTextView acAudioCodec;
    private MaterialSwitch switchAudio;
    private MaterialSwitch switchStayAwake;
    private MaterialSwitch switchScreenOff;
    private MaterialSwitch switchSyncRotation;
    private MaterialSwitch switch0;  // no control
    private MaterialSwitch switch1;  // nav bar
    private TextInputLayout tilAudioCodec;
    private MaterialButton btnStart;

    private SendCommands sendCommands;
    private byte[] fileBase64;

    private UsbAdbManager usbManager;
    private boolean isUsbMode = false;

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadServerJar();
        sendCommands = new SendCommands();
        usbManager = UsbAdbManager.getInstance(this);

        bindViews();
        setupDropdowns();
        setupUsbListener();
        restorePreferences();
        setupListeners();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (usbManager != null) {
            usbManager.register();
            if (isUsbMode) {
                updateUsbDeviceUI();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (usbManager != null) {
            usbManager.unregister();
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────────────────

    private void loadServerJar() {
        AssetManager am = getAssets();
        try (InputStream is = am.open("scrcpy-server.jar")) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            byte[] raw = baos.toByteArray();
            fileBase64 = Base64.encode(raw, Base64.NO_WRAP);
            Log.d(TAG, "Loaded scrcpy-server.jar: " + raw.length + " bytes");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load scrcpy-server.jar: " + e.getMessage());
        }
    }

    private void bindViews() {
        toggleGroupMode      = findViewById(R.id.toggle_group_connection_mode);
        layoutWifiConnection = findViewById(R.id.layout_wifi_connection);
        layoutUsbConnection  = findViewById(R.id.layout_usb_connection);
        tvUsbStatus          = findViewById(R.id.tv_usb_status);
        tvUsbHint            = findViewById(R.id.tv_usb_hint);
        btnUsbConnect        = findViewById(R.id.button_usb_connect);
        etServerHost         = findViewById(R.id.editText_server_host);

        acResolution         = findViewById(R.id.spinner_video_resolution);
        acBitrate            = findViewById(R.id.spinner_video_bitrate);
        acCodec              = findViewById(R.id.spinner_video_codec);
        acFps                = findViewById(R.id.spinner_max_fps);
        acAudioCodec         = findViewById(R.id.spinner_audio_codec);
        switchAudio          = findViewById(R.id.switch_audio);
        switchStayAwake      = findViewById(R.id.switch_stay_awake);
        switchScreenOff      = findViewById(R.id.switch_screen_off);
        switchSyncRotation   = findViewById(R.id.switch_sync_rotation);
        switch0              = findViewById(R.id.switch0);
        switch1              = findViewById(R.id.switch1);
        tilAudioCodec        = findViewById(R.id.til_audio_codec);
        btnStart             = findViewById(R.id.button_start);
    }

    private void setupDropdowns() {
        setupDropdown(acResolution, R.array.options_resolution_keys,  PREF_RES_IDX,        1);
        setupDropdown(acBitrate,    R.array.options_bitrate_keys,      PREF_BITRATE_IDX,    1);
        setupDropdown(acCodec,      R.array.options_codec_keys,        PREF_CODEC_IDX,      0);
        setupDropdown(acFps,        R.array.options_fps_keys,          PREF_FPS_IDX,        0);
        setupDropdown(acAudioCodec, R.array.options_audio_codec_keys,  PREF_AUDIO_CODEC_IDX, 0);
    }

    private void setupDropdown(AutoCompleteTextView view, int arrayRes, String prefKey, int defaultIdx) {
        String[] items = getResources().getStringArray(arrayRes);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, items);
        view.setAdapter(adapter);
        int savedIdx = prefs().getInt(prefKey, defaultIdx);
        if (savedIdx >= 0 && savedIdx < items.length) {
            view.setText(items[savedIdx], false);
        }
        view.setOnItemClickListener((parent, v, pos, id) ->
                prefs().edit().putInt(prefKey, pos).apply());
    }

    private void restorePreferences() {
        SharedPreferences p = prefs();
        etServerHost.setText(p.getString(PREF_SERVER_ADDRESS, ""));
        switchAudio.setChecked(p.getBoolean(PREF_AUDIO, true)); // Default TRUE as requested
        switchStayAwake.setChecked(p.getBoolean(PREF_STAY_AWAKE, true));
        switchScreenOff.setChecked(p.getBoolean(PREF_SCREEN_OFF, false));
        switchSyncRotation.setChecked(p.getBoolean(PREF_SYNC_ROTATION, true)); // Default TRUE as requested
        switch0.setChecked(p.getBoolean(PREF_NO_CONTROL, false));
        switch1.setChecked(p.getBoolean(PREF_NAV, false));

        // Sync audio codec visibility
        if (tilAudioCodec != null) {
            tilAudioCodec.setVisibility(switchAudio.isChecked() ? View.VISIBLE : View.GONE);
        }
        // Sync nav switch visibility
        if (switch1 != null) {
            switch1.setVisibility(switch0.isChecked() ? View.GONE : View.VISIBLE);
        }

        int savedMode = p.getInt(PREF_CONNECTION_MODE, 0);
        if (savedMode == 1) {
            if (toggleGroupMode != null) toggleGroupMode.check(R.id.btn_mode_usb);
            setConnectionMode(true);
        } else {
            if (toggleGroupMode != null) toggleGroupMode.check(R.id.btn_mode_wifi);
            setConnectionMode(false);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
        // Mode toggle listener (Wi-Fi vs USB)
        if (toggleGroupMode != null) {
            toggleGroupMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (isChecked) {
                    boolean usb = (checkedId == R.id.btn_mode_usb);
                    setConnectionMode(usb);
                    prefs().edit().putInt(PREF_CONNECTION_MODE, usb ? 1 : 0).apply();
                }
            });
        }

        // USB connect button
        if (btnUsbConnect != null) {
            btnUsbConnect.setOnClickListener(v -> {
                if (usbManager.isConnected()) {
                    usbManager.disconnect();
                } else {
                    UsbDevice dev = usbManager.findAdbDevice();
                    if (dev != null) {
                        tvUsbStatus.setText(getString(R.string.usb_status_connecting));
                        btnUsbConnect.setEnabled(false);
                        usbManager.connect(dev);
                    } else {
                        Toast.makeText(this, getString(R.string.usb_status_no_device), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // Audio switch toggles codec dropdown visibility
        switchAudio.setOnCheckedChangeListener((btn, checked) -> {
            if (tilAudioCodec != null) {
                tilAudioCodec.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });

        // No-control switch hides nav bar option
        switch0.setOnCheckedChangeListener((btn, checked) -> {
            if (switch1 != null) {
                switch1.setVisibility(checked ? View.GONE : View.VISIBLE);
            }
        });

        // Pair button
        View pairBtn = findViewById(R.id.button_adb_pair);
        if (pairBtn != null) pairBtn.setOnClickListener(v -> showPairDialog());

        // Fullscreen start
        if (btnStart != null) btnStart.setOnClickListener(v -> onStartFullscreen());

        // Float start
        View floatBtn = findViewById(R.id.button_start_float);
        if (floatBtn != null) floatBtn.setOnClickListener(v -> onStartFloat());
    }

    private void setupUsbListener() {
        usbManager.setListener(new UsbAdbManager.UsbListener() {
            @Override
            public void onDeviceDiscovered(UsbDevice device) {
                runOnUiThread(() -> {
                    if (tvUsbStatus != null) {
                        tvUsbStatus.setText(getString(R.string.usb_status_detected, usbManager.getDeviceDisplayName(device)));
                    }
                    if (btnUsbConnect != null) {
                        btnUsbConnect.setText(getString(R.string.usb_btn_scan_connect));
                        btnUsbConnect.setEnabled(true);
                    }
                    // If currently on USB mode, automatically connect
                    if (isUsbMode && !usbManager.isConnected()) {
                        usbManager.connect(device);
                    }
                });
            }

            @Override
            public void onDeviceConnected(UsbDevice device, int bridgePort) {
                runOnUiThread(() -> {
                    if (tvUsbStatus != null) {
                        tvUsbStatus.setText(getString(R.string.usb_status_connected, usbManager.getDeviceDisplayName(device)));
                    }
                    if (btnUsbConnect != null) {
                        btnUsbConnect.setText(getString(R.string.usb_btn_disconnect));
                        btnUsbConnect.setEnabled(true);
                    }
                    if (isUsbMode && btnStart != null) {
                        btnStart.setEnabled(true);
                    }
                    Toast.makeText(MainActivity.this, getString(R.string.usb_status_connected, usbManager.getDeviceDisplayName(device)), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDeviceDisconnected(UsbDevice device) {
                runOnUiThread(() -> {
                    if (tvUsbStatus != null) {
                        tvUsbStatus.setText(getString(R.string.usb_status_no_device));
                    }
                    if (btnUsbConnect != null) {
                        btnUsbConnect.setText(getString(R.string.usb_btn_scan_connect));
                        btnUsbConnect.setEnabled(true);
                    }
                    if (isUsbMode && btnStart != null) {
                        btnStart.setEnabled(false);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                    if (btnUsbConnect != null) {
                        btnUsbConnect.setEnabled(true);
                    }
                });
            }
        });
    }

    private void setConnectionMode(boolean usb) {
        isUsbMode = usb;
        if (usb) {
            if (layoutWifiConnection != null) layoutWifiConnection.setVisibility(View.GONE);
            if (layoutUsbConnection != null)  layoutUsbConnection.setVisibility(View.VISIBLE);
            if (btnStart != null) {
                btnStart.setEnabled(usbManager.isConnected());
            }
            updateUsbDeviceUI();
        } else {
            if (layoutWifiConnection != null) layoutWifiConnection.setVisibility(View.VISIBLE);
            if (layoutUsbConnection != null)  layoutUsbConnection.setVisibility(View.GONE);
            if (btnStart != null) {
                btnStart.setEnabled(true);
            }
        }
    }

    private void updateUsbDeviceUI() {
        if (usbManager.isConnected()) {
            if (tvUsbStatus != null) {
                tvUsbStatus.setText(getString(R.string.usb_status_connected, usbManager.getDeviceDisplayName(usbManager.getConnectedDevice())));
            }
            if (btnUsbConnect != null) {
                btnUsbConnect.setText(getString(R.string.usb_btn_disconnect));
                btnUsbConnect.setEnabled(true);
            }
            if (btnStart != null) {
                btnStart.setEnabled(true);
            }
        } else {
            UsbDevice dev = usbManager.findAdbDevice();
            if (dev != null) {
                if (tvUsbStatus != null) {
                    tvUsbStatus.setText(getString(R.string.usb_status_detected, usbManager.getDeviceDisplayName(dev)));
                }
                if (btnUsbConnect != null) {
                    btnUsbConnect.setText(getString(R.string.usb_btn_scan_connect));
                    btnUsbConnect.setEnabled(true);
                }
                // Auto connect if device found
                usbManager.connect(dev);
            } else {
                if (tvUsbStatus != null) {
                    tvUsbStatus.setText(getString(R.string.usb_status_no_device));
                }
                if (btnUsbConnect != null) {
                    btnUsbConnect.setText(getString(R.string.usb_btn_scan_connect));
                    btnUsbConnect.setEnabled(true);
                }
                if (btnStart != null) {
                    btnStart.setEnabled(false);
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Actions
    // ────────────────────────────────────────────────────────────────────────

    private void onStartFullscreen() {
        String addr = collectAndSaveParams();
        if (addr == null) return;

        // Show connecting dialog; run ADB on background thread
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.msg_connecting))
                .setMessage(getString(R.string.msg_deploying_server))
                .setCancelable(false)
                .create();
        dialog.show();

        if (btnStart != null) btnStart.setEnabled(false);

        // Snapshot all params for background thread
        final String   fAddr        = addr;
        final int      fBitrate     = getSelectedBitrate();
        final int      fMaxFps      = getSelectedFps();
        final String   fVideoCodec  = getSelectedVideoCodec();
        final boolean  fAudio       = switchAudio.isChecked();
        final String   fAudioCodec  = getSelectedAudioCodec();
        final boolean  fControl     = !switch0.isChecked();
        final boolean  fStayAwake   = switchStayAwake.isChecked();
        final boolean  fSyncRotation= switchSyncRotation.isChecked();
        final int[]    fResolution  = getSelectedResolution();
        final int      fMaxSize     = Math.max(fResolution[0], fResolution[1]);

        new Thread(() -> {
            int res = sendCommands.SendAdbCommands(this, fileBase64, fAddr, 7007,
                    fBitrate, fMaxSize, fMaxFps, fVideoCodec, fAudio, fAudioCodec, fControl, fStayAwake);
            runOnUiThread(() -> {
                dialog.dismiss();
                if (btnStart != null) {
                    btnStart.setEnabled(!isUsbMode || usbManager.isConnected());
                }
                if (res == 0) {
                    // Launch dedicated mirror activity
                    Intent i = new Intent(this, ScreenMirrorActivity.class);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SERVER_ADR,    isUsbMode ? "127.0.0.1" : fAddr);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SERVER_PORT,   7007);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_WIDTH,  fResolution[0]);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_HEIGHT, fResolution[1]);
                    i.putExtra(ScreenMirrorActivity.EXTRA_AUDIO_ENABLED, fAudio);
                    i.putExtra(ScreenMirrorActivity.EXTRA_NAV,           switch1.isChecked());
                    i.putExtra(ScreenMirrorActivity.EXTRA_NO_CONTROL,    !fControl);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_OFF,    switchScreenOff.isChecked());
                    i.putExtra(ScreenMirrorActivity.EXTRA_SYNC_ROTATION, fSyncRotation);
                    startActivity(i);
                } else {
                    String err = sendCommands.getLastError();
                    String msg = (err != null && !err.isEmpty()) ? err
                            : getString(R.string.msg_connection_failed);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }, "AdbConnectThread").start();
    }

    private void onStartFloat() {
        String addr = collectAndSaveParams();
        if (addr == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        }

        final String  fAddr       = addr;
        final int     fBitrate    = getSelectedBitrate();
        final int     fMaxFps     = getSelectedFps();
        final String  fVideoCodec = getSelectedVideoCodec();
        final boolean fAudio      = switchAudio.isChecked();
        final String  fAudioCodec = getSelectedAudioCodec();
        final boolean fControl    = !switch0.isChecked();
        final boolean fStayAwake  = switchStayAwake.isChecked();
        final int[]   fResolution = getSelectedResolution();
        final int     fMaxSize    = Math.max(fResolution[0], fResolution[1]);

        new Thread(() -> {
            int res = sendCommands.SendAdbCommands(this, fileBase64, fAddr, 7007,
                    fBitrate, fMaxSize, fMaxFps, fVideoCodec, fAudio, fAudioCodec, fControl, fStayAwake);
            runOnUiThread(() -> {
                if (res == 0) {
                    Intent serviceIntent = new Intent(this, FloatService.class);
                    serviceIntent.putExtra("ip", isUsbMode ? "127.0.0.1" : fAddr);
                    serviceIntent.putExtra("port", 7007);
                    serviceIntent.putExtra("width", fResolution[0]);
                    serviceIntent.putExtra("height", fResolution[1]);
                    serviceIntent.putExtra("audio", fAudio);
                    serviceIntent.putExtra("no_control", !fControl);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                    finish();
                } else {
                    String err = sendCommands.getLastError();
                    String msg = (err != null && !err.isEmpty()) ? err
                            : getString(R.string.msg_connection_failed);
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        }, "AdbFloatThread").start();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Wireless Pairing dialog
    // ────────────────────────────────────────────────────────────────────────

    private void showPairDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_adb_pair, null);

        android.widget.EditText etHost = dialogView.findViewById(R.id.et_pair_host);
        android.widget.EditText etPort = dialogView.findViewById(R.id.et_pair_port);
        android.widget.EditText etCode = dialogView.findViewById(R.id.et_pair_code);
        ProgressBar progressBar  = dialogView.findViewById(R.id.pb_pair_progress);
        TextView tvStatus        = dialogView.findViewById(R.id.tv_pair_status);

        String currentHost = etServerHost != null && etServerHost.getText() != null
                ? etServerHost.getText().toString().trim() : "";
        if (!currentHost.isEmpty()) {
            int colon = currentHost.indexOf(':');
            etHost.setText(colon != -1 ? currentHost.substring(0, colon) : currentHost);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.pair_dialog_title))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.pair_dialog_btn_pair), null)
                .setNegativeButton(getString(R.string.pair_dialog_btn_cancel), (d, w) -> d.dismiss())
                .create();

        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = etHost.getText() != null ? etHost.getText().toString().trim() : "";
            String portStr = etPort.getText() != null ? etPort.getText().toString().trim() : "";
            String code = etCode.getText() != null ? etCode.getText().toString().trim() : "";

            if (host.isEmpty() || portStr.isEmpty() || code.isEmpty()) {
                tvStatus.setText("请填写完整信息");
                tvStatus.setVisibility(View.VISIBLE);
                return;
            }

            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                tvStatus.setText("端口格式错误");
                tvStatus.setVisibility(View.VISIBLE);
                return;
            }

            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);

            AdbPairingHelper.pair(this, host, port, code, new AdbPairingHelper.PairingCallback() {
                @Override
                public void onSuccess() {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, getString(R.string.pair_success), Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                    if (etServerHost != null) {
                        etServerHost.setText(host + ":");
                        etServerHost.setSelection(etServerHost.getText().length());
                        etServerHost.requestFocus();
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

    // ────────────────────────────────────────────────────────────────────────
    // Param helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Validates and saves all params, returns server address or null if invalid. */
    private String collectAndSaveParams() {
        prefs().edit()
                .putBoolean(PREF_NO_CONTROL, switch0.isChecked())
                .putBoolean(PREF_NAV, switch1 != null && switch1.isChecked())
                .putBoolean(PREF_AUDIO, switchAudio.isChecked())
                .putBoolean(PREF_STAY_AWAKE, switchStayAwake.isChecked())
                .putBoolean(PREF_SCREEN_OFF, switchScreenOff.isChecked())
                .putBoolean(PREF_SYNC_ROTATION, switchSyncRotation.isChecked())
                .apply();

        if (isUsbMode) {
            if (!usbManager.isConnected()) {
                Toast.makeText(this, getString(R.string.usb_status_no_device), Toast.LENGTH_SHORT).show();
                return null;
            }
            return "127.0.0.1:" + usbManager.getBridgePort();
        } else {
            if (etServerHost == null) return null;
            String addr = etServerHost.getText() != null ? etServerHost.getText().toString().trim() : "";
            if (addr.isEmpty()) {
                Toast.makeText(this, getString(R.string.msg_address_empty), Toast.LENGTH_SHORT).show();
                return null;
            }
            prefs().edit().putString(PREF_SERVER_ADDRESS, addr).apply();
            return addr;
        }
    }

    /** Returns [width, height] based on spinner; [0,0] means native/unlimited. */
    private int[] getSelectedResolution() {
        String[] values = getResources().getStringArray(R.array.options_resolution_values);
        int idx = prefs().getInt(PREF_RES_IDX, 1);
        if (idx < 0 || idx >= values.length) idx = 1;
        String val = values[idx]; // e.g. "1920x1080" or "0x0"
        try {
            String[] parts = val.split("x");
            int w = Integer.parseInt(parts[0]);
            int h = Integer.parseInt(parts[1]);
            return new int[]{w, h};
        } catch (Exception e) {
            return new int[]{1280, 720};
        }
    }

    private int getSelectedBitrate() {
        int[] values = getResources().getIntArray(R.array.options_bitrate_values);
        int idx = prefs().getInt(PREF_BITRATE_IDX, 1);
        if (idx < 0 || idx >= values.length) idx = 1;
        return values[idx];
    }

    private int getSelectedFps() {
        int[] values = getResources().getIntArray(R.array.options_fps_values);
        int idx = prefs().getInt(PREF_FPS_IDX, 0);
        if (idx < 0 || idx >= values.length) idx = 0;
        return values[idx];
    }

    private String getSelectedVideoCodec() {
        String[] values = getResources().getStringArray(R.array.options_codec_values);
        int idx = prefs().getInt(PREF_CODEC_IDX, 0);
        if (idx < 0 || idx >= values.length) idx = 0;
        return values[idx];
    }

    private String getSelectedAudioCodec() {
        String[] values = getResources().getStringArray(R.array.options_audio_codec_values);
        int idx = prefs().getInt(PREF_AUDIO_CODEC_IDX, 0);
        if (idx < 0 || idx >= values.length) idx = 0;
        return values[idx];
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF_KEY, MODE_PRIVATE);
    }
}
