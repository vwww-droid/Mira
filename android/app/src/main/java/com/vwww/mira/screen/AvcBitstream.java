package com.vwww.mira.screen;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/** AVC byte transformations independent of the Android encoder and transport. */
final class AvcBitstream {
    private AvcBitstream() {
    }

    /** Adds codec configuration to a key frame only when its payload has no SPS. */
    static byte[] withCodecConfig(byte[] payload, byte[] codecConfig, boolean keyFrame) {
        if (keyFrame && codecConfig != null && codecConfig.length > 0 && findNalUnit(payload, 7) == null) {
            return concat(codecConfig, payload);
        }
        return payload;
    }

    /** Accepts Annex B, four/two-byte length prefixes, or a single raw NAL unit.
     * Unrecognized prefixes retain the existing raw-NAL fallback.
     */
    static byte[] toAnnexB(byte[] data) {
        if (data == null || data.length == 0) return data;
        if (startsWithStartCode(data)) return data;
        byte[] converted = lengthPrefixedToAnnexB(data, 4);
        if (converted != null) return converted;
        converted = lengthPrefixedToAnnexB(data, 2);
        if (converted != null) return converted;
        return concat(new byte[] {0, 0, 0, 1}, data);
    }

    private static boolean startsWithStartCode(byte[] data) {
        return data.length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1
            || data.length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1;
    }

    private static byte[] lengthPrefixedToAnnexB(byte[] data, int lengthSize) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(data.length);
        int offset = 0;
        int units = 0;
        while (data.length - offset >= lengthSize) {
            long length = 0;
            for (int i = 0; i < lengthSize; i++) length = (length << 8) | (data[offset + i] & 0xFF);
            offset += lengthSize;
            if (length <= 0 || length > data.length - offset) return null;
            output.write(new byte[] {0, 0, 0, 1}, 0, 4);
            output.write(data, offset, (int) length);
            offset += (int) length;
            units++;
        }
        if (offset != data.length || units == 0) return null;
        return output.toByteArray();
    }

    static String codecStringFromSps(byte[] annexB, String fallback) {
        byte[] sps = findNalUnit(annexB, 7);
        if (sps == null || sps.length < 4) return fallback;
        return String.format(Locale.US, "avc1.%02X%02X%02X", sps[1] & 0xFF, sps[2] & 0xFF, sps[3] & 0xFF);
    }

    private static byte[] findNalUnit(byte[] annexB, int nalType) {
        if (annexB == null) return null;
        int offset = 0;
        while (offset < annexB.length) {
            int start = findStartCode(annexB, offset);
            if (start < 0) return null;
            int nalStart = annexB[start + 2] == 1 ? start + 3 : start + 4;
            int next = findStartCode(annexB, nalStart);
            int nalEnd = next < 0 ? annexB.length : next;
            if (nalStart < nalEnd && (annexB[nalStart] & 0x1F) == nalType) {
                byte[] unit = new byte[nalEnd - nalStart];
                System.arraycopy(annexB, nalStart, unit, 0, unit.length);
                return unit;
            }
            offset = nalEnd;
        }
        return null;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = Math.max(0, from); i + 3 <= data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) return i;
            if (i + 4 <= data.length && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) return i;
        }
        return -1;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

}
