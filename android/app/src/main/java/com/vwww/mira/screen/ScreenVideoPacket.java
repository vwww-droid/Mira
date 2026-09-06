package com.vwww.mira.screen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encodes the MHS1 screen-video wire format consumed by the relay console. */
final class ScreenVideoPacket {
    private static final int HEADER_BYTES = 20;

    private ScreenVideoPacket() {
    }

    /** Sequence and presentation time belong to the caller's stream lifecycle. */
    static byte[] encode(byte[] payload, boolean keyFrame, long sequence, long presentationTimeUs) {
        ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        packet.put((byte) 'M');
        packet.put((byte) 'H');
        packet.put((byte) 'S');
        packet.put((byte) '1');
        packet.put(keyFrame ? (byte) 1 : (byte) 0);
        packet.put((byte) 0);
        packet.putShort((short) 0);
        packet.putInt((int) Math.min(Integer.MAX_VALUE, sequence));
        packet.putLong(presentationTimeUs);
        packet.put(payload);
        return packet.array();
    }
}
