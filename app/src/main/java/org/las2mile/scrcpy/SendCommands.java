package org.las2mile.scrcpy;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

public class SendCommands {
    private static final String TAG = "SendCommands";

    private Thread thread = null;
    private Context context;
    private int status;
    private String lastError = null;

    private static AdbSession activeSession = null;

    public String getLastError() {
        return lastError;
    }

    public SendCommands() {
    }

    public static synchronized void closeActiveSession() {
        if (activeSession != null) {
            try {
                activeSession.close();
            } catch (Exception ignored) {
            }
            activeSession = null;
        }
    }

    public static AdbBase64 getBase64Impl() {
        return new AdbBase64() {
            @Override
            public String encodeToString(byte[] arg0) {
                return Base64.encodeToString(arg0, Base64.NO_WRAP);
            }
        };
    }

    private AdbCrypto setupCrypto() throws NoSuchAlgorithmException, IOException {
        AdbCrypto c = null;
        try {
            c = AdbCrypto.loadAdbKeyPair(getBase64Impl(), context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        } catch (IOException | InvalidKeySpecException | NoSuchAlgorithmException | NullPointerException e) {
            c = null;
        }

        if (c == null) {
            c = AdbCrypto.generateAdbKeyPair(getBase64Impl());
            c.saveAdbKeyPair(context.getFileStreamPath("priv.key"), context.getFileStreamPath("pub.key"));
        }

        return c;
    }

    public int SendAdbCommands(Context context, final byte[] fileBytes, final String ip, int port, int bitrate, int size,
                               int maxFps, String videoCodec, boolean audio, String audioCodec, boolean control, boolean stayAwake) {
        this.context = context;
        status = 1;
        lastError = null;

        final StringBuilder command = new StringBuilder();
        command.append("CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / com.genymobile.scrcpy.Server 4.1");
        command.append(" port=").append(port);
        command.append(" tunnel_forward=true");
        command.append(" video_bit_rate=").append(bitrate);
        if (size > 0) {
            command.append(" max_size=").append(size);
        }
        if (maxFps > 0) {
            command.append(" max_fps=").append(maxFps);
        }
        command.append(" video_codec=").append(videoCodec != null ? videoCodec : "h264");
        command.append(" audio=").append(audio);
        if (audio) {
            command.append(" audio_codec=").append(audioCodec != null ? audioCodec : "opus");
        }
        command.append(" control=").append(control);
        command.append(" stay_awake=").append(stayAwake);
        command.append(" cleanup=false");
        command.append(" send_device_meta=true");
        command.append(" send_frame_meta=true");
        command.append(" send_stream_meta=true");
        command.append(" send_dummy_byte=true");

        thread = new Thread(() -> {
            try {
                adbWrite(ip, fileBytes, command.toString());
            } catch (Exception e) {
                Log.e(TAG, "adbWrite unexpected exception", e);
                status = 2;
                if (lastError == null) {
                    lastError = "执行异常: " + e.getMessage();
                }
            }
        });
        thread.start();

        int count = 0;
        while (status == 1 && count < 300) {
            try {
                Thread.sleep(100);
                count++;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if (count == 300) {
            status = 2;
            if (lastError == null) {
                lastError = "连接或部署服务超时 (30秒)";
            }
        }
        return status;
    }

    public int SendAdbCommands(Context context, final byte[] fileBytes, final String ip, String localip, int bitrate, int size) {
        return SendAdbCommands(context, fileBytes, ip, 7007, bitrate, size, 0, "h264", false, "raw", true, false);
    }

    public interface ServerOutputCallback {
        void onServerLine(String line);
    }

    private interface AdbSession extends AutoCloseable {
        String execute(String cmd) throws Exception;
        void pushFile(byte[] data, String remotePath) throws Exception;
        void startServerProcess(String cmd, ServerOutputCallback callback) throws Exception;
    }

    private static class ModernSession implements AdbSession {
        private final io.github.muntashirakon.adb.AdbConnection connection;
        private io.github.muntashirakon.adb.AdbStream serverStream;

        ModernSession(io.github.muntashirakon.adb.AdbConnection connection) {
            this.connection = connection;
        }

        @Override
        public String execute(String cmd) throws Exception {
            try (io.github.muntashirakon.adb.AdbStream stream = connection.open("exec:" + cmd)) {
                try (io.github.muntashirakon.adb.AdbInputStream in = stream.openInputStream()) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int r;
                    try {
                        while ((r = in.read(buf)) != -1) {
                            baos.write(buf, 0, r);
                        }
                    } catch (IOException e) {
                        if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("stream closed")) {
                            throw e;
                        }
                    }
                    return new String(baos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }

        @Override
        public void pushFile(byte[] data, String remotePath) throws Exception {
            try (io.github.muntashirakon.adb.AdbStream stream = connection.open("exec:cat > " + remotePath)) {
                try (io.github.muntashirakon.adb.AdbOutputStream out = stream.openOutputStream()) {
                    out.write(data);
                    out.flush();
                }
            }
        }

        @Override
        public void startServerProcess(String cmd, ServerOutputCallback callback) throws Exception {
            serverStream = connection.open("exec:" + cmd);
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverStream.openInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (true) {
                        try {
                            line = reader.readLine();
                        } catch (IOException e) {
                            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("stream closed")) {
                                break;
                            }
                            throw e;
                        }
                        if (line == null) break;
                        Log.i(TAG, "[server] " + line);
                        if (callback != null) {
                            callback.onServerLine(line);
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Server stream closed: " + e.getMessage());
                }
            }, "ModernServerStream").start();
        }

        @Override
        public void close() {
            if (serverStream != null) {
                try { serverStream.close(); } catch (Exception ignored) {}
                serverStream = null;
            }
            try { connection.close(); } catch (Exception ignored) {}
        }
    }

    private static class LegacySession implements AdbSession {
        private final Socket socket;
        private final com.tananaev.adblib.AdbConnection connection;
        private com.tananaev.adblib.AdbStream serverStream;

        LegacySession(Socket socket, com.tananaev.adblib.AdbConnection connection) {
            this.socket = socket;
            this.connection = connection;
        }

        @Override
        public String execute(String cmd) throws Exception {
            try (com.tananaev.adblib.AdbStream stream = connection.open("exec:" + cmd)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                while (!stream.isClosed()) {
                    try {
                        byte[] r = stream.read();
                        if (r != null && r.length > 0) {
                            baos.write(r);
                        }
                    } catch (IOException e) {
                        if (e.getMessage() == null || !e.getMessage().toLowerCase().contains("stream closed")) {
                            throw e;
                        }
                        break;
                    } catch (Exception e) {
                        break;
                    }
                }
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        @Override
        public void pushFile(byte[] data, String remotePath) throws Exception {
            try (com.tananaev.adblib.AdbStream stream = connection.open("exec:cat > " + remotePath)) {
                int offset = 0;
                int chunkSize = 4096;
                while (offset < data.length) {
                    int len = Math.min(chunkSize, data.length - offset);
                    byte[] chunk = new byte[len];
                    System.arraycopy(data, offset, chunk, 0, len);
                    stream.write(chunk);
                    offset += len;
                }
            }
        }

        @Override
        public void startServerProcess(String cmd, ServerOutputCallback callback) throws Exception {
            serverStream = connection.open("exec:" + cmd);
            new Thread(() -> {
                try {
                    while (!serverStream.isClosed()) {
                        byte[] r = null;
                        try {
                            r = serverStream.read();
                        } catch (IOException e) {
                            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("stream closed")) {
                                break;
                            }
                            throw e;
                        }
                        if (r != null && r.length > 0) {
                            String s = new String(r, StandardCharsets.UTF_8);
                            Log.i(TAG, "[server] " + s.trim());
                            if (callback != null) {
                                callback.onServerLine(s);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Legacy server stream closed: " + e.getMessage());
                }
            }, "LegacyServerStream").start();
        }

        @Override
        public void close() {
            if (serverStream != null) {
                try { serverStream.close(); } catch (Exception ignored) {}
                serverStream = null;
            }
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void adbWrite(String ip, byte[] fileData, String command) throws IOException {
        String targetHost = ip;
        int targetAdbPort = 5555;
        if (ip != null) {
            int colonIndex = ip.indexOf(':');
            if (colonIndex != -1) {
                targetHost = ip.substring(0, colonIndex).trim();
                try {
                    targetAdbPort = Integer.parseInt(ip.substring(colonIndex + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }

        closeActiveSession();
        AdbSession session = null;

        // 1. If target port is not 5555, try Modern TLS first (Android 11+ Wireless Debugging)
        if (targetAdbPort != 5555) {
            try {
                Log.d(TAG, "Attempting TLS connection to " + targetHost + ":" + targetAdbPort);
                org.las2mile.scrcpy.adb.AdbKeyManager.AdbKeyPair kp = org.las2mile.scrcpy.adb.AdbKeyManager.getKeyPair(context);
                io.github.muntashirakon.adb.AdbConnection modernConn = io.github.muntashirakon.adb.AdbConnection.create(
                        targetHost, targetAdbPort, kp.getPrivateKey(), kp.getCertificate()
                );
                modernConn.connect();
                session = new ModernSession(modernConn);
                Log.d(TAG, "TLS ADB session established with " + targetHost + ":" + targetAdbPort);
            } catch (io.github.muntashirakon.adb.AdbPairingRequiredException e) {
                status = 2;
                lastError = "设备未配对！请点击【无线配对 (Android 11+)】输入被控端 6 位配对码完成配对。";
                return;
            } catch (Exception e) {
                Log.w(TAG, "TLS connection failed (" + e.getMessage() + "), trying legacy ADB...", e);
            }
        }

        // 2. If TLS was not used or failed (or if target port is 5555), try legacy unencrypted connection
        if (session == null && status == 1) {
            try {
                Log.d(TAG, "Attempting Legacy ADB connection to " + targetHost + ":" + targetAdbPort);
                AdbCrypto crypto = setupCrypto();
                Socket sock = new Socket(targetHost, targetAdbPort);
                com.tananaev.adblib.AdbConnection adb = com.tananaev.adblib.AdbConnection.create(sock, crypto);
                adb.connect();
                session = new LegacySession(sock, adb);
                Log.d(TAG, "Legacy ADB session established with " + targetHost + ":" + targetAdbPort);
            } catch (ConnectException e) {
                status = 2;
                lastError = "目标端口 " + targetHost + ":" + targetAdbPort + " 连接被拒绝。若为 Android 11+ 无线调试请先点击【无线配对】并核对连接端口；若为传统模式请执行 'adb tcpip " + targetAdbPort + "'";
                return;
            } catch (Exception e) {
                // If legacy also failed and target was 5555, try TLS as last resort
                if (targetAdbPort == 5555) {
                    try {
                        Log.d(TAG, "Trying TLS on 5555 as fallback");
                        org.las2mile.scrcpy.adb.AdbKeyManager.AdbKeyPair kp = org.las2mile.scrcpy.adb.AdbKeyManager.getKeyPair(context);
                        io.github.muntashirakon.adb.AdbConnection modernConn = io.github.muntashirakon.adb.AdbConnection.create(
                                targetHost, targetAdbPort, kp.getPrivateKey(), kp.getCertificate()
                        );
                        modernConn.connect();
                        session = new ModernSession(modernConn);
                    } catch (io.github.muntashirakon.adb.AdbPairingRequiredException pe) {
                        status = 2;
                        lastError = "设备未配对！请点击【无线配对 (Android 11+)】输入被控端 6 位配对码完成配对。";
                        return;
                    } catch (Exception te) {
                        status = 2;
                        lastError = "无法连接至 " + targetHost + ":" + targetAdbPort + ": " + e.getMessage();
                        return;
                    }
                } else {
                    status = 2;
                    lastError = "无法连接至 " + targetHost + ":" + targetAdbPort + ": " + e.getMessage();
                    return;
                }
            }
        }

        if (session != null && status == 1) {
            try {
                deployServer(session, fileData, command);
                activeSession = session;
                status = 0;
            } catch (Exception e) {
                Log.e(TAG, "Deploying server failed", e);
                status = 2;
                lastError = "部署服务失败: " + e.getMessage();
                try {
                    session.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private void deployServer(AdbSession session, byte[] fileData, String command) throws Exception {
        byte[] rawBytes = fileData;
        if (fileData != null && fileData.length > 4) {
            // Check if it's base64 (ZIP magic is PK\03\04 -> 0x50, 0x4b, 0x03, 0x04)
            if (fileData[0] != 0x50 || fileData[1] != 0x4b) {
                try {
                    rawBytes = Base64.decode(fileData, Base64.DEFAULT);
                } catch (Exception ignored) {
                }
            }
        }

        if (rawBytes == null || rawBytes.length == 0) {
            throw new IOException("Server jar data is empty!");
        }

        String remotePath = "/data/local/tmp/scrcpy-server.jar";

        // 1. Check if server already exists on the target device with matching size
        boolean needPush = true;
        String lsOutput = session.execute("ls -l " + remotePath + " 2>/dev/null");
        if (lsOutput.contains(" " + rawBytes.length + " ")) {
            Log.d(TAG, "scrcpy-server.jar is already up-to-date (" + rawBytes.length + " bytes), skipping upload!");
            needPush = false;
        }

        // 2. Direct binary push via exec:cat stream if needed
        if (needPush) {
            Log.d(TAG, "Pushing scrcpy-server.jar (" + rawBytes.length + " bytes) via binary exec:cat stream...");
            session.pushFile(rawBytes, remotePath);
            session.execute("chmod 755 " + remotePath);

            String verifyLs = session.execute("ls -l " + remotePath + " 2>/dev/null");
            Log.d(TAG, "Verify upload: " + verifyLs.trim());
            if (!verifyLs.contains(" " + rawBytes.length + " ")) {
                throw new IOException("Pushed file size verification failed: " + verifyLs.trim());
            }
        }

        // 3. Terminate any previous lingering server instance
        try {
            session.execute("pkill -f com.genymobile.scrcpy.Server 2>/dev/null");
        } catch (Exception ignored) {}

        // 4. Start scrcpy server and observe startup
        Log.d(TAG, "Starting scrcpy server process: " + command);
        final boolean[] serverStarted = new boolean[]{false};
        final String[] startupError = new String[]{null};

        session.startServerProcess(command, line -> {
            if (line.contains("[server] INFO:") || line.contains("Device:")) {
                serverStarted[0] = true;
            }
            if (line.contains("Exception") || line.contains("Error") || line.contains("ERROR:")) {
                startupError[0] = line;
            }
        });

        // Wait up to 3 seconds for server banner
        for (int i = 0; i < 30; i++) {
            if (serverStarted[0]) {
                Log.d(TAG, "scrcpy server confirmed running!");
                break;
            }
            if (startupError[0] != null) {
                throw new IOException("Server failed on startup: " + startupError[0]);
            }
            Thread.sleep(100);
        }
    }
}

