package org.las2mile.scrcpy.adb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;

import io.github.muntashirakon.adb.PairingConnectionCtx;

public class AdbPairingHelper {
    private static final String TAG = "AdbPairingHelper";

    public interface PairingCallback {
        void onSuccess();
        void onFailure(String error);
    }

    public static void pair(Context context, String host, int port, String pairingCode, PairingCallback callback) {
        new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            try {
                if (host == null || host.trim().isEmpty()) {
                    throw new IllegalArgumentException("Host IP address cannot be empty");
                }
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("Invalid pairing port: " + port);
                }
                if (pairingCode == null || pairingCode.trim().isEmpty()) {
                    throw new IllegalArgumentException("Pairing code cannot be empty");
                }

                String cleanCode = pairingCode.trim();
                String cleanHost = host.trim();
                if (cleanHost.contains(":")) {
                    cleanHost = cleanHost.substring(0, cleanHost.indexOf(':')).trim();
                }

                Log.d(TAG, "Starting pairing with " + cleanHost + ":" + port);
                AdbKeyManager.AdbKeyPair keyPair = AdbKeyManager.getKeyPair(context);

                try (PairingConnectionCtx ctx = new PairingConnectionCtx(
                        cleanHost,
                        port,
                        cleanCode.getBytes(StandardCharsets.UTF_8),
                        keyPair.getPrivateKey(),
                        keyPair.getCertificate(),
                        "scrcpy-android"
                )) {
                    ctx.start();
                    Log.d(TAG, "Pairing successful with " + cleanHost + ":" + port);
                    mainHandler.post(callback::onSuccess);
                }
            } catch (Exception e) {
                Log.e(TAG, "Pairing failed", e);
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) {
                    msg = e.toString();
                }
                if (msg.contains("Connection refused") || msg.contains("ECONNREFUSED")) {
                    msg = "Connection refused on port " + port + ". Ensure 'Pair device with pairing code' dialog is open on target phone.";
                } else if (msg.contains("closed with errors") || msg.contains("bad_record_mac") || msg.contains("decryption failed")) {
                    msg = "Pairing failed: Incorrect pairing code or pairing expired. Check code on target phone.";
                }
                final String finalMsg = msg;
                mainHandler.post(() -> callback.onFailure(finalMsg));
            }
        }, "AdbPairingThread").start();
    }
}
