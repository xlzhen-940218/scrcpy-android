package org.las2mile.scrcpy.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import org.las2mile.scrcpy.R;

import java.io.IOException;
import java.util.HashMap;

/**
 * Manages USB host ADB device discovery, permission requests, and connection lifecycle.
 */
public class UsbAdbManager {

    private static final String TAG = "UsbAdbManager";
    public static final String ACTION_USB_PERMISSION = "org.las2mile.scrcpy.USB_PERMISSION";

    public interface UsbListener {
        void onDeviceDiscovered(UsbDevice device);
        void onDeviceConnected(UsbDevice device, int bridgePort);
        void onDeviceDisconnected(UsbDevice device);
        void onError(String message);
    }

    private static UsbAdbManager instance;

    private final Context context;
    private final UsbManager usbManager;
    private UsbListener listener;

    private UsbDevice connectedDevice;
    private UsbAdbBridge activeBridge;
    private boolean isRegistered = false;

    public static synchronized UsbAdbManager getInstance(Context context) {
        if (instance == null) {
            instance = new UsbAdbManager(context.getApplicationContext());
        }
        return instance;
    }

    private UsbAdbManager(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void setListener(UsbListener listener) {
        this.listener = listener;
    }

    public synchronized void register() {
        if (isRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(usbReceiver, filter);
        }
        isRegistered = true;
        Log.d(TAG, "UsbAdbManager registered broadcast receiver");
    }

    public synchronized void unregister() {
        if (!isRegistered) return;
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
        isRegistered = false;
        Log.d(TAG, "UsbAdbManager unregistered broadcast receiver");
    }

    public UsbDevice findAdbDevice() {
        if (usbManager == null) return null;
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList == null || deviceList.isEmpty()) return null;

        for (UsbDevice device : deviceList.values()) {
            if (findAdbInterface(device) != null) {
                return device;
            }
        }
        return null;
    }

    public UsbInterface findAdbInterface(UsbDevice device) {
        if (device == null) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            // Standard ADB interface: Class 0xFF (Vendor), Subclass 0x42 (ADB), Protocol 0x01
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                    && intf.getInterfaceSubclass() == 0x42
                    && intf.getInterfaceProtocol() == 0x01) {
                return intf;
            }
        }
        return null;
    }

    public void connect(UsbDevice device) {
        if (device == null) {
            if (listener != null) listener.onError(context.getString(R.string.usb_err_no_device));
            return;
        }

        if (!usbManager.hasPermission(device)) {
            Log.d(TAG, "Requesting USB permission for " + device.getDeviceName());
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(context.getPackageName());
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, flags);
            usbManager.requestPermission(device, pi);
            return;
        }

        openAdbDevice(device);
    }

    private synchronized void openAdbDevice(UsbDevice device) {
        disconnect();

        UsbInterface adbInterface = findAdbInterface(device);
        if (adbInterface == null) {
            if (listener != null) listener.onError(context.getString(R.string.usb_err_no_adb_mode));
            return;
        }

        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;
        for (int i = 0; i < adbInterface.getEndpointCount(); i++) {
            UsbEndpoint ep = adbInterface.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    epIn = ep;
                } else {
                    epOut = ep;
                }
            }
        }

        if (epIn == null || epOut == null) {
            if (listener != null) listener.onError(context.getString(R.string.usb_err_no_endpoints));
            return;
        }

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            if (listener != null) listener.onError(context.getString(R.string.usb_err_open_failed));
            return;
        }

        if (!conn.claimInterface(adbInterface, true)) {
            conn.close();
            if (listener != null) listener.onError(context.getString(R.string.usb_err_claim_failed));
            return;
        }

        try {
            activeBridge = new UsbAdbBridge(conn, adbInterface, epIn, epOut);
            int port = activeBridge.start();
            connectedDevice = device;
            Log.d(TAG, "USB ADB connected successfully on port " + port);
            if (listener != null) {
                listener.onDeviceConnected(device, port);
            }
        } catch (IOException e) {
            conn.releaseInterface(adbInterface);
            conn.close();
            if (listener != null) listener.onError(context.getString(R.string.usb_err_bridge_start, e.getMessage()));
        }
    }

    public synchronized void disconnect() {
        if (activeBridge != null) {
            activeBridge.close();
            activeBridge = null;
        }
        UsbDevice prev = connectedDevice;
        connectedDevice = null;
        if (prev != null && listener != null) {
            listener.onDeviceDisconnected(prev);
        }
    }

    public boolean isConnected() {
        return connectedDevice != null && activeBridge != null;
    }

    public int getBridgePort() {
        return activeBridge != null ? activeBridge.getPort() : -1;
    }

    public UsbDevice getConnectedDevice() {
        return connectedDevice;
    }

    public String getDeviceDisplayName(UsbDevice device) {
        if (device == null) return context.getString(R.string.usb_unknown_device);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String prod = device.getProductName();
            String mfg = device.getManufacturerName();
            if (prod != null && !prod.isEmpty()) {
                if (mfg != null && !mfg.isEmpty() && !prod.startsWith(mfg)) {
                    return mfg + " " + prod;
                }
                return prod;
            }
        }
        return context.getString(R.string.usb_device_format, device.getVendorId(), device.getProductId());
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.d(TAG, "USB device attached: " + device);
                if (device != null && findAdbInterface(device) != null) {
                    if (listener != null) listener.onDeviceDiscovered(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.d(TAG, "USB device detached: " + device);
                if (device != null && connectedDevice != null && device.getDeviceId() == connectedDevice.getDeviceId()) {
                    disconnect();
                }
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                Log.d(TAG, "USB permission result: " + granted + " for " + device);
                if (granted && device != null) {
                    openAdbDevice(device);
                } else {
                    if (listener != null) listener.onError(context.getString(R.string.usb_err_permission_denied));
                }
            }
        }
    };
}
