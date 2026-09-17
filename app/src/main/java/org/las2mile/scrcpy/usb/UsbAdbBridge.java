package org.las2mile.scrcpy.usb;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridges ADB TCP stream to USB bulk transfer endpoints.
 * Listens on 127.0.0.1 on an ephemeral port. When an ADB client (adblib) connects,
 * it translates between the TCP socket and the device's USB Bulk IN / Bulk OUT endpoints.
 */
public class UsbAdbBridge implements Closeable {

    private static final String TAG = "UsbAdbBridge";
    private static final int TIMEOUT_MS = 3000;

    private final UsbDeviceConnection connection;
    private final UsbInterface usbInterface;
    private final UsbEndpoint endpointIn;
    private final UsbEndpoint endpointOut;

    private ServerSocket serverSocket;
    private int port = -1;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Thread acceptThread;
    private Socket activeTcpSocket;

    public UsbAdbBridge(UsbDeviceConnection connection, UsbInterface usbInterface,
                        UsbEndpoint endpointIn, UsbEndpoint endpointOut) {
        this.connection = connection;
        this.usbInterface = usbInterface;
        this.endpointIn = endpointIn;
        this.endpointOut = endpointOut;
    }

    public synchronized int start() throws IOException {
        if (isRunning.get()) {
            return port;
        }

        serverSocket = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        serverSocket.setReceiveBufferSize(512 * 1024);
        port = serverSocket.getLocalPort();
        isRunning.set(true);

        acceptThread = new Thread(this::acceptLoop, "UsbAdbBridge-Accept");
        acceptThread.start();

        Log.d(TAG, "UsbAdbBridge started on 127.0.0.1:" + port);
        return port;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (isRunning.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientSocket.setSendBufferSize(512 * 1024);
                clientSocket.setReceiveBufferSize(512 * 1024);
                activeTcpSocket = clientSocket;
                Log.d(TAG, "Accepted TCP connection from ADB client");

                handleClient(clientSocket);
            } catch (IOException e) {
                if (!isRunning.get()) break;
                Log.e(TAG, "Error accepting client", e);
            }
        }
    }

    private void handleClient(Socket socket) {
        Thread tcpToUsb = new Thread(() -> runTcpToUsb(socket), "UsbAdb-TcpToUsb");
        Thread usbToTcp = new Thread(() -> runUsbToTcp(socket), "UsbAdb-UsbToTcp");

        tcpToUsb.start();
        usbToTcp.start();

        try {
            tcpToUsb.join();
        } catch (InterruptedException ignored) {}

        try {
            usbToTcp.join();
        } catch (InterruptedException ignored) {}

        try {
            socket.close();
        } catch (Exception ignored) {}
    }

    /**
     * Reads ADB messages from TCP and writes to USB Bulk OUT.
     * ADB packets consist of a 24-byte header followed by an optional payload.
     * On USB, the header and payload are sent as discrete transfers.
     */
    private void runTcpToUsb(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            byte[] header = new byte[24];
            byte[] payloadBuf = new byte[262144];

            while (isRunning.get() && !socket.isClosed()) {
                int read = readFully(in, header, 0, 24);
                if (read < 24) break;

                // Little-endian payload length at offset 12..15
                int payloadLength = (header[12] & 0xFF)
                        | ((header[13] & 0xFF) << 8)
                        | ((header[14] & 0xFF) << 16)
                        | ((header[15] & 0xFF) << 24);

                // Transfer 24-byte header to USB
                int sent = connection.bulkTransfer(endpointOut, header, 24, TIMEOUT_MS);
                if (sent < 0) {
                    Log.w(TAG, "Failed to send header via USB: " + sent);
                    break;
                }

                // If payload present, read from TCP and transfer to USB
                if (payloadLength > 0 && payloadLength < (16 * 1024 * 1024)) {
                    byte[] payload = (payloadLength <= payloadBuf.length) ? payloadBuf : new byte[payloadLength];
                    int payloadRead = readFully(in, payload, 0, payloadLength);
                    if (payloadRead < payloadLength) break;

                    int payloadSent = connection.bulkTransfer(endpointOut, payload, payloadLength, TIMEOUT_MS);
                    if (payloadSent < 0) {
                        Log.w(TAG, "Failed to send payload via USB: " + payloadSent);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "TcpToUsb stopped: " + e.getMessage());
        }
    }

    /**
     * Reads USB Bulk IN packets and writes them to TCP socket.
     * Buffer size is 256KB to accept full modern ADB payloads without dropping or truncating.
     */
    private void runUsbToTcp(Socket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            byte[] buf = new byte[262144];

            while (isRunning.get() && !socket.isClosed()) {
                int read = connection.bulkTransfer(endpointIn, buf, buf.length, 1000);
                if (read > 0) {
                    out.write(buf, 0, read);
                    out.flush();
                } else if (read < 0) {
                    // Check if USB connection is still valid
                    if (!isRunning.get()) break;
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "UsbToTcp stopped: " + e.getMessage());
        }
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int count = in.read(b, off + total, len - total);
            if (count < 0) return total == 0 ? -1 : total;
            total += count;
        }
        return total;
    }

    @Override
    public synchronized void close() {
        isRunning.set(false);
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        if (activeTcpSocket != null) {
            try { activeTcpSocket.close(); } catch (Exception ignored) {}
            activeTcpSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (connection != null && usbInterface != null) {
            try { connection.releaseInterface(usbInterface); } catch (Exception ignored) {}
            try { connection.close(); } catch (Exception ignored) {}
        }
        Log.d(TAG, "UsbAdbBridge closed");
    }
}
