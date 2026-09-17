package org.las2mile.scrcpy.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ControlMessage {

    public static final int TYPE_INJECT_KEYCODE = 0;
    public static final int TYPE_INJECT_TEXT = 1;
    public static final int TYPE_INJECT_TOUCH_EVENT = 2;
    public static final int TYPE_INJECT_SCROLL_EVENT = 3;
    public static final int TYPE_BACK_OR_SCREEN_ON = 4;
    public static final int TYPE_EXPAND_NOTIFICATION_PANEL = 5;
    public static final int TYPE_EXPAND_SETTINGS_PANEL = 6;
    public static final int TYPE_COLLAPSE_PANELS = 7;
    public static final int TYPE_GET_CLIPBOARD = 8;
    public static final int TYPE_SET_CLIPBOARD = 9;
    public static final int TYPE_SET_DISPLAY_POWER = 10;
    public static final int TYPE_ROTATE_DEVICE = 11;
    public static final int TYPE_OPEN_HARD_KEYBOARD_SETTINGS = 12;
    public static final int TYPE_RESET_VIDEO = 17;

    private ControlMessage() {
    }

    public static byte[] createResetVideo() {
        return new byte[]{(byte) TYPE_RESET_VIDEO};
    }

    public static byte[] createInjectKeycode(int action, int keycode, int repeat, int metaState) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(14);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(TYPE_INJECT_KEYCODE);
            dos.writeByte(action);
            dos.writeInt(keycode);
            dos.writeInt(repeat);
            dos.writeInt(metaState);
            dos.flush();
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }

    public static byte[] createInjectText(String text) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(5 + textBytes.length);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(TYPE_INJECT_TEXT);
            dos.writeInt(textBytes.length);
            dos.write(textBytes);
            dos.flush();
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }

    public static byte[] createInjectTouchEvent(int action, long pointerId, Position position, float pressure, int actionButton, int buttons) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(33);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(TYPE_INJECT_TOUCH_EVENT);
            dos.writeByte(action);
            dos.writeLong(pointerId);
            dos.writeInt(position.getX());
            dos.writeInt(position.getY());
            dos.writeShort(position.getScreenWidth());
            dos.writeShort(position.getScreenHeight());
            int u16Pressure = (int) (Math.max(0f, Math.min(1f, pressure)) * 0xFFFF);
            dos.writeShort(u16Pressure);
            dos.writeInt(actionButton);
            dos.writeInt(buttons);
            dos.flush();
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }

    public static byte[] createInjectScrollEvent(Position position, float hScroll, float vScroll, int buttons) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(22);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(TYPE_INJECT_SCROLL_EVENT);
            dos.writeInt(position.getX());
            dos.writeInt(position.getY());
            dos.writeShort(position.getScreenWidth());
            dos.writeShort(position.getScreenHeight());
            // range is [-16, 16], normalized to [-1, 1] then to i16
            short sHScroll = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (hScroll / 16.0f) * 0x7FFF));
            short sVScroll = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (vScroll / 16.0f) * 0x7FFF));
            dos.writeShort(sHScroll);
            dos.writeShort(sVScroll);
            dos.writeInt(buttons);
            dos.flush();
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }

    public static byte[] createBackOrScreenOn(int action) {
        return new byte[]{(byte) TYPE_BACK_OR_SCREEN_ON, (byte) action};
    }

    public static byte[] createEmpty(int type) {
        return new byte[]{(byte) type};
    }

    public static byte[] createSetDisplayPower(boolean on) {
        return new byte[]{(byte) TYPE_SET_DISPLAY_POWER, (byte) (on ? 1 : 0)};
    }

    public static byte[] createGetClipboard() {
        return new byte[]{(byte) TYPE_GET_CLIPBOARD, 0};
    }

    public static byte[] createSetClipboard(String text, boolean paste) {
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream(14 + textBytes.length);
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            dos.writeByte(TYPE_SET_CLIPBOARD);
            dos.writeLong(0); // sequence
            dos.writeByte(paste ? 1 : 0);
            dos.writeInt(textBytes.length);
            dos.write(textBytes);
            dos.flush();
        } catch (IOException ignored) {
        }
        return baos.toByteArray();
    }
}
