package com.tyranor.runtime.systemnnn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * Bounded CP932 + sjis_ext.bin decoder for the Android host.
 * Tunnel numbering is derived from arcusmaximus/VNTranslationTools,
 * commit 36fbe909d02f2e9bb187443e03e2df595f56bfec (MIT, copyright 2021
 * arcusmaximus). The corresponding license is packaged in assets/licenses.
 * It accepts canonical producer pairs, not the proxy's malformed-pair aliases.
 */
public final class SjisTunnelDecoder {
    public static final int MAX_MAPPINGS = 0x3b * 0x3a;
    public static final int MAX_LEGACY_CONFIGURATION_MAPPINGS = 0x3b * 0x3b;
    public static final int MAX_INPUT_BYTES = 4 * 1024 * 1024;
    private static final int[] AVOID = {9, 10, 13, 32, 44};
    private static final int[] LEGACY_CONFIGURATION_AVOID = {9, 10, 13, 32};
    private static final Charset CP932 = Charset.forName("windows-31j");
    private static final ThreadLocal<CharsetDecoder> STANDARD = ThreadLocal.withInitial(() ->
            CP932.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
    private static final ThreadLocal<CharsetEncoder> ENCODER = ThreadLocal.withInitial(() ->
            CP932.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
    private final char[] mapping;
    private final int[] avoid;
    private final int rowWidth;

    public SjisTunnelDecoder(byte[] utf16leMapping) throws IOException {
        this(utf16leMapping,AVOID,MAX_MAPPINGS);
    }

    /** Explicit old configuration profile: 59 entries per lead, with comma available as a trail byte. */
    public static SjisTunnelDecoder legacyConfiguration(byte[] utf16leMapping) throws IOException {
        return new SjisTunnelDecoder(utf16leMapping,LEGACY_CONFIGURATION_AVOID,MAX_LEGACY_CONFIGURATION_MAPPINGS);
    }

    private SjisTunnelDecoder(byte[] utf16leMapping,int[] avoid,int maximum) throws IOException {
        this.avoid=avoid;rowWidth=0x3f-avoid.length;
        if (utf16leMapping == null || (utf16leMapping.length & 1) != 0
                || utf16leMapping.length > maximum * 2) {
            throw new IOException("Invalid sjis_ext.bin byte length");
        }
        mapping = new char[utf16leMapping.length / 2];
        for (int index = 0; index < mapping.length; index++) {
            char value = (char) ((utf16leMapping[index * 2] & 255) | ((utf16leMapping[index * 2 + 1] & 255) << 8));
            if (value == 0 || Character.isSurrogate(value)) throw new IOException("NUL/surrogate mapping is unsupported");
            mapping[index] = value;
        }
    }

    public String decode(byte[] input) throws IOException {
        if (input == null || input.length > MAX_INPUT_BYTES) throw new IOException("Text input exceeds limit or is missing");
        CharsetDecoder standard = STANDARD.get();
        StringBuilder output = new StringBuilder(input.length);
        int index = 0;
        int ordinaryStart = 0;
        while (index < input.length) {
            int high = input[index] & 255;
            // CP932 ASCII is byte-for-codepoint; ICU's windows-31j provider has
            // IBM control-code swaps (for example 0x1a -> U+001c). Keep the
            // Windows CP932 values and half-width katakana explicitly.
            if (high < 0x80 || (high >= 0xa1 && high <= 0xdf)) {
                appendOrdinary(output, standard, input, ordinaryStart, index - ordinaryStart);
                output.append((char) (high < 0x80 ? high : 0xff61 + high - 0xa1));
                ordinaryStart = ++index;
                continue;
            }
            // Windows MultiByteToWideChar(932, 0, ...) and Python CP932 retain
            // these legacy single bytes; the JDK windows-31j codec rejects them.
            // Handle them explicitly so Android/desktop providers agree.
            if (high == 0x80 || high == 0xa0 || high >= 0xfd) {
                appendOrdinary(output, standard, input, ordinaryStart, index - ordinaryStart);
                output.append((char) (high == 0x80 ? 0x80 : high == 0xa0 ? 0xf8f0 : 0xf8f1 + high - 0xfd));
                ordinaryStart = ++index;
                continue;
            }
            boolean lead = (high >= 0x81 && high < 0xa0) || (high >= 0xe0 && high < 0xfd);
            if (!lead) { index++; continue; }
            if (index + 1 >= input.length) throw new IOException("Truncated CP932 character");
            int low = input[index + 1] & 255;
            if (low < 0x40) {
                if (low == 0) throw new IOException("NUL inside tunnel pair");
                for (int excluded : avoid) if (low == excluded) throw new IOException("Reserved low byte in tunnel pair");
                appendOrdinary(output, standard, input, ordinaryStart, index - ordinaryStart);
                int highIndex = high < 0xa0 ? high - 0x81 : 0x1f + high - 0xe0;
                int lowIndex = low;
                for (int at = avoid.length - 1; at >= 0; at--) if (lowIndex > avoid[at]) lowIndex--;
                int mappingIndex = highIndex * rowWidth + lowIndex - 1;
                if (mappingIndex < 0 || mappingIndex >= mapping.length) throw new IOException("Tunnel pair has no mapping entry");
                output.append(mapping[mappingIndex]);
                index += 2;
                ordinaryStart = index;
            } else index += 2;
        }
        appendOrdinary(output, standard, input, ordinaryStart, input.length - ordinaryStart);
        return output.toString();
    }

    /** Return one exact CP932 or tunnel character, or null if no reversible representation exists. */
    byte[] encodeCharacter(int point) {
        if (point <= 0 || point > Character.MAX_CODE_POINT || point >= 0xd800 && point <= 0xdfff) return null;
        if (point <= 0x80) return new byte[]{(byte) point};
        if (point >= 0xff61 && point <= 0xff9f) return new byte[]{(byte) (0xa1 + point - 0xff61)};
        if (point == 0xf8f0) return new byte[]{(byte) 0xa0};
        if (point >= 0xf8f1 && point <= 0xf8f3) return new byte[]{(byte) (0xfd + point - 0xf8f1)};
        String character = new String(Character.toChars(point));
        try {
            ByteBuffer encoded = ENCODER.get().encode(CharBuffer.wrap(character));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            // Windows-31J encoders accept aliases such as U+00A5 for ASCII backslash.
            if (decode(bytes).equals(character)) return bytes;
        } catch (IOException ignored) {
            // The game can still supply a tunnel representation.
        }
        for (int index = 0; index < mapping.length; index++) {
            if (mapping[index] != point) continue;
            int row = index / rowWidth;
            int low = index % rowWidth + 1;
            for (int excluded : avoid) if (low >= excluded) low++;
            return new byte[]{(byte) (row < 0x1f ? 0x81 + row : 0xe0 + row - 0x1f), (byte) low};
        }
        return null;
    }

    private static void appendOrdinary(StringBuilder output, CharsetDecoder decoder, byte[] bytes, int offset, int length)
            throws CharacterCodingException {
        if (length != 0) output.append(decoder.decode(ByteBuffer.wrap(bytes, offset, length)));
    }
}
