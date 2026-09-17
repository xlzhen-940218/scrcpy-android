package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private interface ChannelSocket extends Closeable {
        FileDescriptor getFileDescriptor();
        OutputStream getOutputStream() throws IOException;
        InputStream getInputStream() throws IOException;
        void shutdownInput() throws IOException;
        void shutdownOutput() throws IOException;
        void close() throws IOException;
    }

    private static class LocalChannelSocket implements ChannelSocket {
        private final LocalSocket socket;

        LocalChannelSocket(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return socket.getFileDescriptor();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public void shutdownInput() throws IOException {
            socket.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            socket.shutdownOutput();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static class NetChannelSocket implements ChannelSocket {
        private final Socket socket;
        private final ParcelFileDescriptor pfd;

        NetChannelSocket(Socket socket) throws IOException {
            this.socket = socket;
            this.pfd = ParcelFileDescriptor.fromSocket(socket);
        }

        @Override
        public FileDescriptor getFileDescriptor() {
            return pfd.getFileDescriptor();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            return socket.getOutputStream();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return socket.getInputStream();
        }

        @Override
        public void shutdownInput() throws IOException {
            socket.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            socket.shutdownOutput();
        }

        @Override
        public void close() throws IOException {
            try {
                pfd.close();
            } finally {
                socket.close();
            }
        }
    }

    private final ChannelSocket videoSocket;
    private final FileDescriptor videoFd;

    private final ChannelSocket audioSocket;
    private final FileDescriptor audioFd;

    private final ChannelSocket controlSocket;
    private final ControlChannel controlChannel;

    private DesktopConnection(ChannelSocket videoSocket, ChannelSocket audioSocket, ChannelSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket.getInputStream(), controlSocket.getOutputStream()) : null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte,
            int port) throws IOException {
        String socketName = getSocketName(scid);

        ChannelSocket videoSocket = null;
        ChannelSocket audioSocket = null;
        ChannelSocket controlSocket = null;
        try {
            if (port > 0) {
                try (ServerSocket serverSocket = new ServerSocket(port)) {
                    if (video) {
                        videoSocket = new NetChannelSocket(serverSocket.accept());
                        if (sendDummyByte) {
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = new NetChannelSocket(serverSocket.accept());
                        if (sendDummyByte) {
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = new NetChannelSocket(serverSocket.accept());
                        if (sendDummyByte) {
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoSocket = new LocalChannelSocket(localServerSocket.accept());
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = new LocalChannelSocket(localServerSocket.accept());
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = new LocalChannelSocket(localServerSocket.accept());
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                if (video) {
                    videoSocket = new LocalChannelSocket(connect(socketName));
                }
                if (audio) {
                    audioSocket = new LocalChannelSocket(connect(socketName));
                }
                if (control) {
                    controlSocket = new LocalChannelSocket(connect(socketName));
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        return open(scid, tunnelForward, video, audio, control, sendDummyByte, -1);
    }

    private ChannelSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        FileDescriptor fd = getFirstSocket().getFileDescriptor();
        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
