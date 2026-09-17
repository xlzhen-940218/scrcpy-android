package org.las2mile.scrcpy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.AssetManager;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

public class ScrcpyHost implements Scrcpy.ServiceCallbacks {
    private static final String TAG = "ScrcpyHost";

    private Context context;
    private volatile Scrcpy scrcpy;
    private static volatile boolean serviceBound = false;
    private static boolean first_time = true;

    private static int screenWidth;
    private static int screenHeight;
    private int videoBitrate;

    private String serverAdr = null;
    private Surface surface;
    private static float remote_device_width;
    private static float remote_device_height;

    private byte[] fileBase64;
    private SendCommands sendCommands;

    private ConnectCallBack connectCallBack;

    public void setConnectCallBack(ConnectCallBack connectCallBack) {
        this.connectCallBack = connectCallBack;
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(ScrcpyHost.this);
            serviceBound = true;

            if (first_time) {
                scrcpy.start(surface, serverAdr, 7007, screenHeight, screenWidth, false);
                int count = 100;
                while (count != 0) {
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
                if (count == 0) {
                    if (serviceBound && scrcpy != null) {
                        scrcpy.StopService();
                        try {
                            context.unbindService(serviceConnection);
                        } catch (Exception ignored) {}
                        serviceBound = false;
                        scrcpy = null;
                    }
                    Toast.makeText(context, "Connection Timed out", Toast.LENGTH_SHORT).show();
                } else {
                    Scrcpy s = scrcpy;
                    if (s != null) {
                        int[] rem_res = s.get_remote_device_resolution();
                        if (rem_res != null && rem_res.length >= 2) {
                            remote_device_width = rem_res[0];
                            remote_device_height = rem_res[1];
                        }
                    }
                    first_time = false;
                    Log.d(TAG, "onServiceConnected: " + remote_device_width + "x" + remote_device_height);
                    if (connectCallBack != null) {
                        connectCallBack.onConnect(Math.min(remote_device_width, remote_device_height),
                                Math.max(remote_device_width, remote_device_height));
                    }
                }
            } else {
                if (scrcpy != null) {
                    scrcpy.setParms(surface, screenWidth, screenHeight);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    private void exectJar() {
        AssetManager assetManager = context.getAssets();
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
            Log.e(TAG, "Asset Manager error: " + e.getMessage());
        }
    }

    public void connect(Context context, String clientIp, int width, int height, int bitrate, Surface display) {
        this.context = context;
        screenWidth = width;
        screenHeight = height;
        videoBitrate = bitrate;
        surface = display;
        serverAdr = clientIp;

        exectJar();
        sendCommands = new SendCommands();

        if (!serverAdr.isEmpty()) {
            if (sendCommands.SendAdbCommands(context, fileBase64, serverAdr, 7007, videoBitrate,
                    Math.max(screenHeight, screenWidth), 0, "h264", false, "opus", true, false) == 0) {
                start_screen_copy_magic();
            } else {
                String err = sendCommands.getLastError();
                String msg = (err != null && !err.isEmpty()) ? err : "Network OR ADB connection failed";
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
        }
    }

    private void start_screen_copy_magic() {
        Intent intent = new Intent(context, Scrcpy.class);
        context.startService(intent);
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public boolean touch(MotionEvent motionEvent, int surfaceW, int surfaceH) {
        if (scrcpy != null) {
            return scrcpy.touchevent(motionEvent, surfaceW, surfaceH);
        }
        return false;
    }

    public void keyEvent(int keyCode) {
        if (scrcpy != null) {
            scrcpy.sendKeyevent(keyCode);
        }
    }

    @Override
    public void loadNewRotation() {
        if (scrcpy != null) {
            int[] rem_res = scrcpy.get_remote_device_resolution();
            remote_device_width = rem_res[0];
            remote_device_height = rem_res[1];
            first_time = false;
            if (connectCallBack != null) {
                connectCallBack.onConnect(Math.min(remote_device_width, remote_device_height),
                        Math.max(remote_device_width, remote_device_height));
            }
        }
    }

    public void destroy() {
        if (serviceBound) {
            if (scrcpy != null) {
                scrcpy.StopService();
            }
            context.unbindService(serviceConnection);
            Intent intent = new Intent(context, Scrcpy.class);
            context.stopService(intent);
            serviceBound = false;
        }
    }

    public interface ConnectCallBack {
        void onConnect(float w, float h);
    }
}
