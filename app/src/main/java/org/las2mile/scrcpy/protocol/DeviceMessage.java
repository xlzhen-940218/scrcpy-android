package org.las2mile.scrcpy.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DeviceMessage {

    public static final int TYPE_CLIPBOARD = 0;
    public static final int TYPE_ACK_CLIPBOARD = 1;

    private final int type;
    private final String text;

    public DeviceMessage(int type, String text) {
        this.type = type;
        this.text = text;
    }

    public int getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public static DeviceMessage parse(DataInputStream dis) throws IOException {
        int type = dis.readUnsignedByte();
        if (type == TYPE_CLIPBOARD) {
            int len = dis.readInt();
            byte[] buf = new byte[len];
            dis.readFully(buf);
            return new DeviceMessage(type, new String(buf, StandardCharsets.UTF_8));
        } else if (type == TYPE_ACK_CLIPBOARD) {
            dis.readLong(); // sequence
            return new DeviceMessage(type, null);
        }
        return null;
    }
}
