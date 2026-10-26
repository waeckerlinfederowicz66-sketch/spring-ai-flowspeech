package io.flowspeech.springai.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Validates the fixed public API PCM contract, including every RIFF chunk boundary. */
final class WavAudio {
    private WavAudio() { }

    static boolean isValid(byte[] bytes) {
        if (bytes.length < 44) return false;
        ByteBuffer view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (!tag(bytes, 0).equals("RIFF") || !tag(bytes, 8).equals("WAVE")
                || Integer.toUnsignedLong(view.getInt(4)) != bytes.length - 8L) return false;
        boolean format = false;
        boolean data = false;
        int offset = 12;
        while (offset < bytes.length) {
            if (bytes.length - offset < 8) return false;
            long size = Integer.toUnsignedLong(view.getInt(offset + 4));
            int start = offset + 8;
            long paddedEnd = start + size + (size % 2);
            if (paddedEnd > bytes.length) return false;
            String kind = tag(bytes, offset);
            if (kind.equals("fmt ")) {
                if (format || data || (size != 16 && size != 18)
                        || (size == 18 && view.getShort(start + 16) != 0)
                        || view.getShort(start) != 1 || view.getShort(start + 2) != 1
                        || view.getInt(start + 4) != 24000 || view.getInt(start + 8) != 48000
                        || view.getShort(start + 12) != 2 || view.getShort(start + 14) != 16) return false;
                format = true;
            }
            else if (kind.equals("data")) {
                if (!format || data || size == 0 || size % 2 != 0) return false;
                data = true;
            }
            offset = (int) paddedEnd;
        }
        return format && data;
    }

    private static String tag(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }
}
