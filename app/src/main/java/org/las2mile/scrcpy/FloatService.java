package org.las2mile.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class FloatService extends Service implements Scrcpy.ServiceCallbacks {
    private static final String TAG = "FloatService";
    private static final String CHANNEL_ID = "scrcpy_floating_service";
    private static final int NOTIFICATION_ID = 1001;

    private DisplayWindow displayWindow;
    private WindowManager windowManager;
    private WindowManager.LayoutParams lp;

    private volatile Scrcpy scrcpy;
    private volatile boolean serviceBound = false;
    private Surface surface;

    private String serverAdr;
    private int serverPort = 7007;
    private int screenWidth = 1080;
    private int screenHeight = 1920;
    private boolean audioEnabled = false;
    private boolean noControl = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            scrcpy = ((Scrcpy.MyServiceBinder) binder).getService();
            if (scrcpy == null) return;
            scrcpy.setServiceCallbacks(FloatService.this);
            serviceBound = true;

            if (surface != null && surface.isValid()) {
                scrcpy.start(surface, serverAdr, serverPort, screenHeight, screenWidth, audioEnabled);

                new Thread(() -> {
                    int count = 60;
                    while (count > 0 && serviceBound && scrcpy != null) {
                        if (scrcpy.check_socket_connection()) break;
                        count--;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    final int finalCount = count;
                    mainHandler.post(() -> {
                        if (finalCount == 0) {
                            Toast.makeText(FloatService.this, R.string.msg_connection_timeout, Toast.LENGTH_SHORT).show();
                            stopSelf();
                        } else {
                            if (displayWindow != null) {
                                displayWindow.hideHintTip();
                                if (scrcpy != null) {
                                    int[] res = scrcpy.get_remote_device_resolution();
                                    if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                                        displayWindow.setRemote(res[0], res[1]);
                                    }
                                }
                            }
                        }
                    });
                }, "FloatWaitConnect").start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            scrcpy = null;
        }
    };

    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                Log.d(TAG, "USB device detached");
                onDisconnected();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        promoteToForeground();
        setupDisplay();

        // Register USB detach listener
        IntentFilter usbFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbDetachReceiver, usbFilter);
        }
    }

    private void promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "scrcpy Floating Window",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Running screen mirroring in floating window");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.btn_start_float))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground with connectedDevice failed, retrying without type: " + e.getMessage());
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Exception fatal) {
                Log.e(TAG, "Failed to start foreground service", fatal);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return super.onStartCommand(intent, flags, startId);
        }

        serverAdr = intent.getStringExtra("ip");
        serverPort = intent.getIntExtra("port", 7007);
        screenWidth = intent.getIntExtra("width", intent.getIntExtra("w", 1080));
        screenHeight = intent.getIntExtra("height", intent.getIntExtra("h", 1920));
        audioEnabled = intent.getBooleanExtra("audio", false);
        noControl = intent.getBooleanExtra("no_control", false);

        Log.d(TAG, "onStartCommand: server=" + serverAdr + ":" + serverPort + ", size=" + screenWidth + "x" + screenHeight);
        if (displayWindow != null) {
            displayWindow.setRemote(screenWidth, screenHeight);
        }

        if (!serviceBound && surface != null && surface.isValid() && serverAdr != null && !serverAdr.isEmpty()) {
            Intent scrcpyIntent = new Intent(FloatService.this, Scrcpy.class);
            startService(scrcpyIntent);
            bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        }

        return START_NOT_STICKY;
    }

    private void setupDisplay() {
        Context themedContext = new androidx.appcompat.view.ContextThemeWrapper(this, R.style.AppTheme);
        displayWindow = new DisplayWindow(themedContext);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        lp = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            lp.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        lp.format = PixelFormat.TRANSLUCENT;
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = 40;
        lp.y = 120;

        windowManager.addView(displayWindow, lp);

        displayWindow.setCloseListener(v -> {
            Log.d(TAG, "Floating window close clicked");
            stopSelf();
        });

        displayWindow.setMoveCallback((dx, dy) -> {
            lp.x += (int) dx;
            lp.y += (int) dy;
            try {
                if (windowManager != null && displayWindow != null) {
                    windowManager.updateViewLayout(displayWindow, lp);
                }
            } catch (Exception ignored) {
            }
        });

        displayWindow.setActionCallback(actionType -> {
            if (scrcpy == null) return;
            switch (actionType) {
                case DisplayWindow.ACTION_POWER:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_POWER);
                    break;
                case DisplayWindow.ACTION_BACK:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_BACK);
                    break;
                case DisplayWindow.ACTION_HOME:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_HOME);
                    break;
                case DisplayWindow.ACTION_MENU:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_APP_SWITCH);
                    break;
                case DisplayWindow.ACTION_VOLUME_DOWN:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_DOWN);
                    break;
                case DisplayWindow.ACTION_VOLUME_UP:
                    scrcpy.sendKeyevent(KeyEvent.KEYCODE_VOLUME_UP);
                    break;
                case DisplayWindow.ACTION_ROTATE:
                    scrcpy.rotateDevice();
                    break;
            }
        });

        displayWindow.setOnDisplayTouchListener((v, event) -> {
            if (noControl || scrcpy == null) return false;
            return scrcpy.touchevent(event, displayWindow.getSurfaceWidth(), displayWindow.getSurfaceHeight());
        });

        displayWindow.setSurfaceReadyCallback(new DisplayWindow.SurfaceReadyCallback() {
            @Override
            public void onSurfaceReady(Surface readySurface) {
                surface = readySurface;
                if (!serviceBound && serverAdr != null && !serverAdr.isEmpty()) {
                    Intent scrcpyIntent = new Intent(FloatService.this, Scrcpy.class);
                    startService(scrcpyIntent);
                    bindService(scrcpyIntent, serviceConnection, Context.BIND_AUTO_CREATE);
                } else if (serviceBound && scrcpy != null) {
                    scrcpy.setSurface(readySurface);
                }
            }

            @Override
            public void onSurfaceDestroyed() {
                surface = null;
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged");
        if (displayWindow != null && scrcpy != null) {
            int[] res = scrcpy.get_remote_device_resolution();
            if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
                displayWindow.setRemote(res[0], res[1]);
            }
        }
    }

    @Override
    public void loadNewRotation() {
        if (scrcpy == null || displayWindow == null) return;
        int[] res = scrcpy.get_remote_device_resolution();
        if (res != null && res.length >= 2 && res[0] > 0 && res[1] > 0) {
            mainHandler.post(() -> {
                if (displayWindow != null) {
                    displayWindow.setRemote(res[0], res[1]);
                }
            });
        }
    }

    @Override
    public void onDisconnected() {
        mainHandler.post(() -> {
            Toast.makeText(FloatService.this, R.string.msg_device_disconnected, Toast.LENGTH_SHORT).show();
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "FloatService onDestroy");

        try {
            unregisterReceiver(usbDetachReceiver);
        } catch (Exception ignored) {
        }

        if (serviceBound && scrcpy != null) {
            scrcpy.StopService();
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {
            }
            serviceBound = false;
            scrcpy = null;
        }

        SendCommands.closeActiveSession();

        if (windowManager != null && displayWindow != null) {
            try {
                windowManager.removeView(displayWindow);
            } catch (Exception ignored) {
            }
            displayWindow = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
