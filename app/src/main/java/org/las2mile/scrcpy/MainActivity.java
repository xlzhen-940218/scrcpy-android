package org.las2mile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
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

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.las2mile.scrcpy.adb.AdbPairingHelper;

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
    private static final String PREF_RES_IDX          = "spinner_resolution";
    private static final String PREF_BITRATE_IDX      = "spinner_bitrate";
    private static final String PREF_CODEC_IDX        = "spinner_codec";
    private static final String PREF_FPS_IDX          = "spinner_fps";
    private static final String PREF_AUDIO_CODEC_IDX  = "spinner_audio_codec";

    // UI references
    private TextInputEditText etServerHost;
    private AutoCompleteTextView acResolution;
    private AutoCompleteTextView acBitrate;
    private AutoCompleteTextView acCodec;
    private AutoCompleteTextView acFps;
    private AutoCompleteTextView acAudioCodec;
    private MaterialSwitch switchAudio;
    private MaterialSwitch switchStayAwake;
    private MaterialSwitch switchScreenOff;
    private MaterialSwitch switch0;  // no control
    private MaterialSwitch switch1;  // nav bar
    private TextInputLayout tilAudioCodec;

    private SendCommands sendCommands;
    private byte[] fileBase64;

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        loadServerJar();
        sendCommands = new SendCommands();

        bindViews();
        setupDropdowns();
        restorePreferences();
        setupListeners();
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
        etServerHost   = findViewById(R.id.editText_server_host);
        acResolution   = findViewById(R.id.spinner_video_resolution);
        acBitrate      = findViewById(R.id.spinner_video_bitrate);
        acCodec        = findViewById(R.id.spinner_video_codec);
        acFps          = findViewById(R.id.spinner_max_fps);
        acAudioCodec   = findViewById(R.id.spinner_audio_codec);
        switchAudio    = findViewById(R.id.switch_audio);
        switchStayAwake= findViewById(R.id.switch_stay_awake);
        switchScreenOff= findViewById(R.id.switch_screen_off);
        switch0        = findViewById(R.id.switch0);
        switch1        = findViewById(R.id.switch1);
        tilAudioCodec  = findViewById(R.id.til_audio_codec);
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
        switchAudio.setChecked(p.getBoolean(PREF_AUDIO, false));
        switchStayAwake.setChecked(p.getBoolean(PREF_STAY_AWAKE, true));
        switchScreenOff.setChecked(p.getBoolean(PREF_SCREEN_OFF, false));
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupListeners() {
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
        View startBtn = findViewById(R.id.button_start);
        if (startBtn != null) startBtn.setOnClickListener(v -> onStartFullscreen());

        // Float start
        View floatBtn = findViewById(R.id.button_start_float);
        if (floatBtn != null) floatBtn.setOnClickListener(v -> onStartFloat());
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

        View startBtn = findViewById(R.id.button_start);
        if (startBtn != null) startBtn.setEnabled(false);

        // Snapshot all params for background thread
        final String   fAddr        = addr;
        final int      fBitrate     = getSelectedBitrate();
        final int      fMaxFps      = getSelectedFps();
        final String   fVideoCodec  = getSelectedVideoCodec();
        final boolean  fAudio       = switchAudio.isChecked();
        final String   fAudioCodec  = getSelectedAudioCodec();
        final boolean  fControl     = !switch0.isChecked();
        final boolean  fStayAwake   = switchStayAwake.isChecked();
        final int[]    fResolution  = getSelectedResolution();
        final int      fMaxSize     = Math.max(fResolution[0], fResolution[1]);

        new Thread(() -> {
            int res = sendCommands.SendAdbCommands(this, fileBase64, fAddr, 7007,
                    fBitrate, fMaxSize, fMaxFps, fVideoCodec, fAudio, fAudioCodec, fControl, fStayAwake);
            runOnUiThread(() -> {
                dialog.dismiss();
                if (startBtn != null) startBtn.setEnabled(true);
                if (res == 0) {
                    // Launch dedicated mirror activity
                    Intent i = new Intent(this, ScreenMirrorActivity.class);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SERVER_ADR,    fAddr);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SERVER_PORT,   7007);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_WIDTH,  fResolution[0]);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_HEIGHT, fResolution[1]);
                    i.putExtra(ScreenMirrorActivity.EXTRA_AUDIO_ENABLED, fAudio);
                    i.putExtra(ScreenMirrorActivity.EXTRA_NAV,           switch1.isChecked());
                    i.putExtra(ScreenMirrorActivity.EXTRA_NO_CONTROL,    !fControl);
                    i.putExtra(ScreenMirrorActivity.EXTRA_SCREEN_OFF,    switchScreenOff.isChecked());
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
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            return;
        }
        int[] res = getSelectedResolution();
        Intent it = new Intent(this, FloatService.class);
        it.putExtra("ip", addr);
        it.putExtra("w", res[0]);
        it.putExtra("h", res[1]);
        it.putExtra("b", getSelectedBitrate());
        startService(it);
        finish();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Pairing dialog
    // ────────────────────────────────────────────────────────────────────────

    private void showPairDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.pair_dialog_title);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_adb_pair, null);
        builder.setView(view);

        android.widget.EditText etHost = view.findViewById(R.id.et_pair_host);
        android.widget.EditText etPort = view.findViewById(R.id.et_pair_port);
        android.widget.EditText etCode = view.findViewById(R.id.et_pair_code);
        ProgressBar progressBar = view.findViewById(R.id.pb_pair_progress);
        TextView tvStatus = view.findViewById(R.id.tv_pair_status);

        // Pre-fill IP from current input
        String currentHost = etServerHost != null ? etServerHost.getText().toString().trim() : "";
        if (currentHost.contains(":")) {
            etHost.setText(currentHost.substring(0, currentHost.indexOf(':')).trim());
        } else if (!currentHost.isEmpty()) {
            etHost.setText(currentHost);
        }

        builder.setPositiveButton(R.string.pair_dialog_btn_pair, null);
        builder.setNegativeButton(android.R.string.cancel, (d, w) -> d.dismiss());

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
            try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {
                tvStatus.setText("端口格式无效");
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
        if (etServerHost == null) return null;
        String addr = etServerHost.getText() != null ? etServerHost.getText().toString().trim() : "";
        if (addr.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_address_empty), Toast.LENGTH_SHORT).show();
            return null;
        }
        prefs().edit()
                .putString(PREF_SERVER_ADDRESS, addr)
                .putBoolean(PREF_NO_CONTROL, switch0.isChecked())
                .putBoolean(PREF_NAV, switch1 != null && switch1.isChecked())
                .putBoolean(PREF_AUDIO, switchAudio.isChecked())
                .putBoolean(PREF_STAY_AWAKE, switchStayAwake.isChecked())
                .putBoolean(PREF_SCREEN_OFF, switchScreenOff.isChecked())
                .apply();
        return addr;
    }

    /** Returns [width, height] based on spinner; [0,0] means native/unlimited. */
    private int[] getSelectedResolution() {
        String[] values = getResources().getStringArray(R.array.options_resolution_values);
        String[] keys   = getResources().getStringArray(R.array.options_resolution_keys);
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
        return getSharedPreferences(PREF_KEY, 0);
    }
}
