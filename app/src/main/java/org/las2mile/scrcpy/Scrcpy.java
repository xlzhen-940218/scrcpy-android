package org.las2mile.scrcpy;

import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import org.las2mile.scrcpy.audio.AudioPlayer;
import org.las2mile.scrcpy.decoder.VideoDecoder;
import org.las2mile.scrcpy.protocol.ControlMessage;
import org.las2mile.scrcpy.protocol.DeviceMessage;
import org.las2mile.scrcpy.protocol.Position;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class Scrcpy extends Service {
    private static final String TAG = "Scrcpy";

    private String serverAdr;
    private int serverPort = 7007;
    private boolean audioEnabled = false;
    private Surface surface;
    private int screenWidth;
    private int screenHeight;

    private VideoDecoder videoDecoder;
    private AudioPlayer audioPlayer;

    private final IBinder mBinder = new MyServiceBinder();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ServiceCallbacks serviceCallbacks;
    private final int[] remote_dev_resolution = new int[2];
    private volatile boolean socket_status = false;
    private String deviceName = "";

    private Socket videoSocket;
    private Socket audioSocket;
    private Socket controlSocket;

    private final BlockingQueue<byte[]> controlQueue = new LinkedBlockingQueue<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void setServiceCallbacks(ServiceCallbacks callbacks) {
        this.serviceCallbacks = callbacks;
    }

    public void setParms(Surface newSurface, int newWidth, int newHeight) {
        this.screenWidth = newWidth;
        this.screenHeight = newHeight;
        this.surface = newSurface;
        if (videoDecoder != null && surface != null && surface.isValid()) {
            videoDecoder.configure(surface, screenWidth, screenHeight);
        }
    }

    public void setSurface(Surface newSurface) {
        this.surface = newSurface;
        if (videoDecoder != null) {
            int w = remote_dev_resolution[0] > 0 ? remote_dev_resolution[0] : screenWidth;
            int h = remote_dev_resolution[1] > 0 ? remote_dev_resolution[1] : screenHeight;
            if (newSurface != null && newSurface.isValid()) {
                videoDecoder.setSurface(newSurface, w, h);
                resetVideo();
            }
        }
    }

    public void resetVideo() {
        sendControlMessage(ControlMessage.createResetVideo());
    }

    public void start(Surface surface, String serverAdr, int screenHeight, int screenWidth) {
        start(surface, serverAdr, 7007, screenHeight, screenWidth, false);
    }

    public void start(Surface surface, String serverAdr, int serverPort, int screenHeight, int screenWidth, boolean audio) {
        this.surface = surface;
        if (serverAdr != null && serverAdr.contains(":")) {
            this.serverAdr = serverAdr.substring(0, serverAdr.indexOf(':')).trim();
        } else {
            this.serverAdr = serverAdr;
        }
        this.serverPort = serverPort;
        this.screenHeight = screenHeight;
        this.screenWidth = screenWidth;
        this.audioEnabled = audio;

        this.videoDecoder = new VideoDecoder();
        this.videoDecoder.start();

        isRunning.set(true);
        new Thread(this::connectionThread, "Scrcpy-Connect").start();
    }

    public void pause() {
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
    }

    public void resume() {
        if (videoDecoder != null) {
            videoDecoder.start();
            if (surface != null && screenWidth > 0 && screenHeight > 0) {
                videoDecoder.configure(surface, screenWidth, screenHeight);
            }
        }
    }

    public void StopService() {
        isRunning.set(false);
        closeSockets();
        if (videoDecoder != null) {
            videoDecoder.stop();
        }
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        stopSelf();
    }

    public int[] get_remote_device_resolution() {
        return remote_dev_resolution;
    }

    public boolean check_socket_connection() {
        return socket_status;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void sendControlMessage(byte[] msg) {
        if (msg != null && isRunning.get()) {
            controlQueue.offer(msg);
        }
    }

    public void sendKeyevent(int keycode) {
        sendControlMessage(ControlMessage.createInjectKeycode(0, keycode, 0, 0));
        sendControlMessage(ControlMessage.createInjectKeycode(1, keycode, 0, 0));
    }

    public void setDisplayPower(boolean on) {
        sendControlMessage(ControlMessage.createSetDisplayPower(on));
    }

    public void setClipboard(String text, boolean paste) {
        sendControlMessage(ControlMessage.createSetClipboard(text, paste));
    }

    public void rotateDevice() {
        sendControlMessage(ControlMessage.createEmpty(ControlMessage.TYPE_ROTATE_DEVICE));
    }

    public boolean touchevent(MotionEvent touch_event, int displayW, int displayH) {
        if (remote_dev_resolution[0] == 0 || remote_dev_resolution[1] == 0 || displayW <= 0 || displayH <= 0) {
            return false;
        }

        int actionMasked = touch_event.getActionMasked();
        int actionIndex = touch_event.getActionIndex();
        int action;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                action = 0; // ACTION_DOWN
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                action = 1; // ACTION_UP
                break;
            case MotionEvent.ACTION_MOVE:
                action = 2; // ACTION_MOVE
                break;
            case MotionEvent.ACTION_CANCEL:
                action = 3; // ACTION_CANCEL
                break;
            default:
                return false;
        }

        int remoteW = remote_dev_resolution[0];
        int remoteH = remote_dev_resolution[1];

        if (action == 2) { // ACTION_MOVE sends for all pointers
            for (int i = 0; i < touch_event.getPointerCount(); i++) {
                int pointerId = touch_event.getPointerId(i);
                float x = touch_event.getX(i);
                float y = touch_event.getY(i);
                int posX = (int) Math.max(0, Math.min(remoteW, (x * remoteW) / displayW));
                int posY = (int) Math.max(0, Math.min(remoteH, (y * remoteH) / displayH));
                float pressure = touch_event.getPressure(i);
                byte[] msg = ControlMessage.createInjectTouchEvent(action, pointerId,
                        new Position(posX, posY, remoteW, remoteH), pressure, 0, 1);
                sendControlMessage(msg);
            }
        } else {
            int pointerId = touch_event.getPointerId(actionIndex);
            float x = touch_event.getX(actionIndex);
            float y = touch_event.getY(actionIndex);
            int posX = (int) Math.max(0, Math.min(remoteW, (x * remoteW) / displayW));
            int posY = (int) Math.max(0, Math.min(remoteH, (y * remoteH) / displayH));
            float pressure = touch_event.getPressure(actionIndex);
            byte[] msg = ControlMessage.createInjectTouchEvent(action, pointerId,
                    new Position(posX, posY, remoteW, remoteH), pressure, 0, 1);
            sendControlMessage(msg);
        }
        return true;
    }

    private void connectionThread() {
        int attempts = 50;
        while (attempts > 0 && isRunning.get()) {
            try {
                Log.d(TAG, "Connecting to server at " + serverAdr + ":" + serverPort);

                // 1. Connect video socket
                videoSocket = new Socket();
                videoSocket.setReceiveBufferSize(1024 * 1024);
                videoSocket.setSendBufferSize(256 * 1024);
                videoSocket.connect(new java.net.InetSocketAddress(serverAdr, serverPort), 3000);
                videoSocket.setTcpNoDelay(true);

                // 2. Connect audio socket if enabled
                if (audioEnabled) {
                    audioSocket = new Socket();
                    audioSocket.setReceiveBufferSize(256 * 1024);
                    audioSocket.connect(new java.net.InetSocketAddress(serverAdr, serverPort), 3000);
                    audioSocket.setTcpNoDelay(true);
                }

                // 3. Connect control socket
                controlSocket = new Socket();
                controlSocket.setSendBufferSize(64 * 1024);
                controlSocket.connect(new java.net.InetSocketAddress(serverAdr, serverPort), 3000);
                controlSocket.setTcpNoDelay(true);

                Log.d(TAG, "All sockets connected successfully");
                socket_status = true;
                break;
            } catch (IOException e) {
                attempts--;
                closeSockets();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (!socket_status || !isRunning.get()) {
            socket_status = false;
            return;
        }

        // Start control channel threads
        new Thread(this::controlSendThread, "Scrcpy-Control-Send").start();
        new Thread(this::controlRecvThread, "Scrcpy-Control-Recv").start();

        // Start audio thread if audio enabled
        if (audioEnabled && audioSocket != null) {
            new Thread(this::audioThread, "Scrcpy-Audio").start();
        }

        // Run video on its own dedicated thread so audio + video run truly in parallel
        // (previously videoThread() blocked connectionThread, starving audio startup)
        new Thread(this::videoThread, "Scrcpy-Video").start();
    }


    private void videoThread() {
        try {
            DataInputStream dis = new DataInputStream(new BufferedInputStream(videoSocket.getInputStream(), 1 << 18));

            // Read dummy byte
            int dummy = dis.readByte();
            Log.d(TAG, "Dummy byte read: " + dummy);

            // Read 64 bytes device metadata
            byte[] devMeta = new byte[64];
            dis.readFully(devMeta);
            deviceName = new String(devMeta, StandardCharsets.UTF_8).trim();
            Log.d(TAG, "Device metadata: " + deviceName);

            // Read 4-byte video codec ID
            int codecId = dis.readInt();
            Log.d(TAG, String.format("Video Codec ID: 0x%08X", codecId));
            videoDecoder.setCodec(codecId);

            while (isRunning.get()) {
                int firstInt = dis.readInt();
                if ((firstInt & 0x80000000) != 0) {
                    // Session packet (12 bytes: flags (firstInt), width, height)
                    int width = dis.readInt();
                    int height = dis.readInt();
                    Log.d(TAG, "Session packet received: " + width + "x" + height);

                    boolean rotationChanged = (remote_dev_resolution[0] != width || remote_dev_resolution[1] != height);
                    remote_dev_resolution[0] = width;
                    remote_dev_resolution[1] = height;

                    if (surface != null && surface.isValid()) {
                        videoDecoder.configure(surface, width, height);
                    }

                    if (serviceCallbacks != null) {
                        mainHandler.post(() -> {
                            if (serviceCallbacks != null) {
                                serviceCallbacks.loadNewRotation();
                            }
                        });
                    }
                } else {
                    // Media packet
                    boolean isConfig = (firstInt & 0x40000000) != 0;
                    boolean isKeyFrame = (firstInt & 0x20000000) != 0;
                    int secondInt = dis.readInt();
                    long pts = (((long) (firstInt & 0x1FFFFFFF)) << 32) | (secondInt & 0xFFFFFFFFL);

                    int packetSize = dis.readInt();
                    if (packetSize > 0 && packetSize < (1 << 22)) { // max 4MB guard
                        byte[] payload = new byte[packetSize];
                        dis.readFully(payload);

                        int flags = 0;
                        if (isConfig) {
                            flags |= 2; // MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        }
                        if (isKeyFrame) {
                            flags |= 1; // MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }

                        videoDecoder.decodeSample(payload, 0, packetSize, pts, flags);
                    }
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Video stream ended: " + e.getMessage());
        } finally {
            StopService();
        }
    }

    private void audioThread() {
        try {
            DataInputStream dis = new DataInputStream(new BufferedInputStream(audioSocket.getInputStream(), 1 << 16));
            int codecId = dis.readInt();
            Log.d(TAG, String.format("Audio Codec ID: 0x%08X", codecId));

            if (codecId == 0) {
                Log.w(TAG, "Audio capture disabled by device");
                return;
            }

            audioPlayer = new AudioPlayer(codecId);
            audioPlayer.start();

            while (isRunning.get()) {
                int firstInt = dis.readInt();
                boolean isConfig = (firstInt & 0x40000000) != 0;
                int secondInt = dis.readInt();
                long pts = (((long) (firstInt & 0x1FFFFFFF)) << 32) | (secondInt & 0xFFFFFFFFL);

                int packetSize = dis.readInt();
                if (packetSize > 0 && packetSize < (1 << 18)) {
                    byte[] payload = new byte[packetSize];
                    dis.readFully(payload);
                    audioPlayer.playSample(payload, pts, isConfig);
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Audio stream ended: " + e.getMessage());
        } finally {
            if (audioPlayer != null) {
                audioPlayer.stop();
            }
        }
    }

    private void controlSendThread() {
        try {
            DataOutputStream dos = new DataOutputStream(controlSocket.getOutputStream());
            while (isRunning.get()) {
                byte[] msg = controlQueue.take();
                dos.write(msg);
                dos.flush();
            }
        } catch (InterruptedException | IOException ignored) {
        }
    }

    private void controlRecvThread() {
        try {
            DataInputStream dis = new DataInputStream(controlSocket.getInputStream());
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

            while (isRunning.get()) {
                DeviceMessage msg = DeviceMessage.parse(dis);
                if (msg != null && msg.getType() == DeviceMessage.TYPE_CLIPBOARD && msg.getText() != null) {
                    mainHandler.post(() -> {
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("scrcpy", msg.getText()));
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void closeSockets() {
        socket_status = false;
        if (videoSocket != null) {
            try {
                videoSocket.close();
            } catch (IOException ignored) {
            }
            videoSocket = null;
        }
        if (audioSocket != null) {
            try {
                audioSocket.close();
            } catch (IOException ignored) {
            }
            audioSocket = null;
        }
        if (controlSocket != null) {
            try {
                controlSocket.close();
            } catch (IOException ignored) {
            }
            controlSocket = null;
        }
    }

    public interface ServiceCallbacks {
        void loadNewRotation();
    }

    public class MyServiceBinder extends Binder {
        public Scrcpy getService() {
            return Scrcpy.this;
        }
    }
}
