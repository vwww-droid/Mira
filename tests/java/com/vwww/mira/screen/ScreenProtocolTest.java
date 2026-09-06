package com.vwww.mira.screen;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ScreenProtocolTest {
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }

    private static void equal(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Expected " + Arrays.toString(expected) + ", got " + Arrays.toString(actual));
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void conversion() {
        byte[] annexB = bytes(0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1e, 0, 0, 0, 1, 0x68, 0xce);
        equal(annexB, AvcBitstream.toAnnexB(bytes(0, 0, 0, 4, 0x67, 0x42, 0xe0, 0x1e, 0, 0, 0, 2, 0x68, 0xce)));
        equal(annexB, AvcBitstream.toAnnexB(bytes(0, 4, 0x67, 0x42, 0xe0, 0x1e, 0, 2, 0x68, 0xce)));
        equal(annexB, AvcBitstream.toAnnexB(annexB));
        byte[] threeByteStart = bytes(0, 0, 1, 0x65, 0x88);
        equal(threeByteStart, AvcBitstream.toAnnexB(threeByteStart));
        equal(bytes(0, 0, 0, 1, 0x65, 0x88), AvcBitstream.toAnnexB(bytes(0x65, 0x88)));
        equal(bytes(), AvcBitstream.toAnnexB(bytes()));
        check(AvcBitstream.toAnnexB(null) == null, "null input must remain null");

        // Preserve the raw-NAL fallback for malformed or incomplete length prefixes.
        byte[][] malformed = {
            bytes(0, 0, 0, 8, 0x65), bytes(0, 0, 0, 0),
            bytes(0xff, 0xff, 0xff, 0xff, 0x65),
            bytes(0x7f, 0xff, 0xff, 0xff, 0x65),
            bytes(0, 0, 0, 2, 0x65, 0x88, 0x77)
        };
        for (byte[] input : malformed) {
            byte[] expected = new byte[input.length + 4];
            expected[3] = 1;
            System.arraycopy(input, 0, expected, 4, input.length);
            equal(expected, AvcBitstream.toAnnexB(input));
        }
    }

    private static void codecConfiguration() {
        byte[] config = bytes(0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1e, 0, 0, 1, 0x68, 0xce);
        byte[] key = bytes(0, 0, 0, 1, 0x65, 0x88);
        byte[] configured = AvcBitstream.withCodecConfig(key, config, true);
        equal(bytes(0, 0, 0, 1, 0x67, 0x42, 0xe0, 0x1e, 0, 0, 1, 0x68, 0xce, 0, 0, 0, 1, 0x65, 0x88), configured);
        equal(configured, AvcBitstream.withCodecConfig(configured, config, true));
        equal(key, AvcBitstream.withCodecConfig(key, config, false));
        equal(key, AvcBitstream.withCodecConfig(key, null, true));
        equal(key, AvcBitstream.withCodecConfig(key, bytes(), true));
        check("avc1.42E01E".equals(AvcBitstream.codecStringFromSps(configured, "fallback")), "SPS codec string");
        check("avc1.640028".equals(AvcBitstream.codecStringFromSps(bytes(0, 0, 1, 0x68, 5, 0, 0, 1, 0x67, 0x64, 0, 0x28), "fallback")), "SPS after another NAL");
        for (byte[] invalid : new byte[][] {null, bytes(), key, bytes(0, 0, 1), bytes(0, 0, 0, 1, 0x67, 0x42)}) {
            check("fallback".equals(AvcBitstream.codecStringFromSps(invalid, "fallback")), "missing/truncated SPS fallback");
        }
    }

    private static void packetFormat() {
        byte[] payload = bytes(0, 0, 0, 1, 0x65, 0x88);
        // Fixed wire vector: magic, flag, reserved bytes, sequence, timestamp, payload.
        equal(bytes(0x4d, 0x48, 0x53, 0x31, 1, 0, 0, 0, 0, 0, 0, 1,
            0, 0, 0, 0, 0, 1, 0x86, 0xa0, 0, 0, 0, 1, 0x65, 0x88),
            ScreenVideoPacket.encode(payload, true, 1, 100_000));

        // Decode using the offsets and big-endian fields used by LiveScreenViewer.
        byte[] packet = ScreenVideoPacket.encode(payload, false, 0x01020304L, 0x0102030405060708L);
        ByteBuffer view = ByteBuffer.wrap(packet);
        check(packet.length == 20 + payload.length && packet[4] == 0, "delta frame header");
        check(view.getInt(8) == 0x01020304, "sequence byte order");
        check(view.getLong(12) == 0x0102030405060708L, "timestamp byte order");
        equal(payload, Arrays.copyOfRange(packet, 20, packet.length));
        byte[] overflow = ScreenVideoPacket.encode(payload, true, Integer.MAX_VALUE + 1L, 123L);
        check(ByteBuffer.wrap(overflow).getInt(8) == Integer.MAX_VALUE, "existing sequence saturation");
        check(ByteBuffer.wrap(overflow).getLong(12) == 123L, "timestamp independent of sequence saturation");
    }

    public static void main(String[] args) {
        conversion();
        codecConfiguration();
        packetFormat();
        System.out.println("PASS: AVC conversion, codec configuration, MHS1 wire compatibility");
    }
}
