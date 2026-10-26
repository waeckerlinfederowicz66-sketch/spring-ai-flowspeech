package io.flowspeech.springai.api;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static io.flowspeech.springai.api.WavFixtures.*;

class WavAudioTests {
    static Stream<byte[]> valid() {
        return Stream.of(wav(), riff(chunk("JUNK", new byte[] {1}), chunk("fmt ", FORMAT), chunk("data", new byte[2])),
                riff(chunk("fmt ", Arrays.copyOf(FORMAT, 18)), chunk("data", new byte[2])));
    }
    static byte[] changed(int offset, int value, boolean shortValue) {
        byte[] bytes = wav();
        var view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (shortValue) view.putShort(offset, (short) value); else view.putInt(offset, value);
        return bytes;
    }
    static Stream<byte[]> invalid() {
        return Stream.of(new byte[0], Arrays.copyOf(wav(), 12), changed(4, 1, false), changed(4, -1, false),
                changed(16, -1, false), changed(16, 14, false), changed(40, -1, false),
                changed(20, 3, true), changed(22, 2, true), changed(24, 44100, false),
                changed(28, 24000, false), changed(32, 4, true), changed(34, 8, true),
                riff(chunk("fmt ", FORMAT)), riff(chunk("data", new byte[2])),
                riff(chunk("fmt ", FORMAT), chunk("data", new byte[0])),
                riff(chunk("fmt ", FORMAT), chunk("data", new byte[1])),
                riff(chunk("fmt ", FORMAT), chunk("fmt ", FORMAT), chunk("data", new byte[2])),
                riff(chunk("fmt ", FORMAT), chunk("data", new byte[2]), chunk("data", new byte[2])),
                riff(chunk("fmt ", FORMAT), chunk("data", new byte[2]), new byte[1]),
                riff(chunk("fmt ", FORMAT), chunk("data", new byte[2]),
                        Arrays.copyOf(chunk("JUNK", new byte[1]), 9)));
    }
    @ParameterizedTest @MethodSource("valid")
    void acceptsCompletePcmWithOptionalPaddedChunks(byte[] bytes) { assertThat(WavAudio.isValid(bytes)).isTrue(); }
    @ParameterizedTest @MethodSource("invalid")
    void rejectsInvalidRiffChunkBoundsFormatAndData(byte[] bytes) { assertThat(WavAudio.isValid(bytes)).isFalse(); }
}
