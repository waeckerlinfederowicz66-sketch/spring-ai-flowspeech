package io.flowspeech.springai.api;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class WavFixtures {
    static final byte[] FORMAT = HexFormat.of().parseHex("01000100c05d000080bb000002001000");
    private WavFixtures() { }
    public static byte[] wav() { return riff(chunk("fmt ", FORMAT), chunk("data", new byte[] {0, 0, 1, 0})); }
    static byte[] chunk(String tag, byte[] data) {
        return ByteBuffer.allocate(8 + data.length + data.length % 2).order(ByteOrder.LITTLE_ENDIAN)
                .put(tag.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data).array();
    }
    static byte[] riff(byte[]... chunks) {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes("RIFF0000WAVE".getBytes(StandardCharsets.US_ASCII));
        for (byte[] chunk : chunks) bytes.writeBytes(chunk);
        byte[] result = bytes.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
        return result;
    }
}
