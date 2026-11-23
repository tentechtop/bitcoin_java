package com.bit.coin.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

public class HexByteArraySerializer extends StdSerializer<byte[]> {

    // 十六进制字符表
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final char[] UPPER_HEX_CHARS = "0123456789ABCDEF".toCharArray();

    // 是否使用大写字母
    private final boolean useUpperCase;

    // 是否添加分隔符（如空格）
    private final boolean withDelimiter;

    // 分隔符
    private final String delimiter;

    // 是否添加0x前缀
    private final boolean withPrefix;

    public HexByteArraySerializer() {
        this(false, false, null, false);
    }

    public HexByteArraySerializer(boolean useUpperCase) {
        this(useUpperCase, false, null, false);
    }

    public HexByteArraySerializer(boolean useUpperCase, boolean withDelimiter, String delimiter, boolean withPrefix) {
        super(byte[].class);
        this.useUpperCase = useUpperCase;
        this.withDelimiter = withDelimiter;
        this.delimiter = delimiter != null ? delimiter : " ";
        this.withPrefix = withPrefix;
    }

    @Override
    public void serialize(byte[] value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {

        if (value == null) {
            gen.writeNull();
            return;
        }

        if (value.length == 0) {
            gen.writeString("");
            return;
        }

        char[] digits = useUpperCase ? UPPER_HEX_CHARS : HEX_CHARS;
        int delimiterLength = withDelimiter ? delimiter.length() : 0;
        int capacity = (withPrefix ? 2 : 0) + value.length * 2
                + Math.max(0, value.length - 1) * delimiterLength;
        StringBuilder hexString = new StringBuilder(capacity);

        // 如果需要前缀
        if (withPrefix) {
            hexString.append("0x");
        }

        for (int i = 0; i < value.length; i++) {
            if (i > 0 && withDelimiter) {
                hexString.append(delimiter);
            }

            int unsigned = value[i] & 0xFF;
            hexString.append(digits[unsigned >>> 4]);
            hexString.append(digits[unsigned & 0x0F]);
        }

        gen.writeString(hexString.toString());
    }

    // 快速转换单个字节为十六进制字符串
    public static String byteToHex(byte b) {
        return byteToHex(b, false);
    }

    public static String byteToHex(byte b, boolean useUpperCase) {
        int high = (b >> 4) & 0x0F;
        int low = b & 0x0F;
        char[] digits = useUpperCase ? UPPER_HEX_CHARS : HEX_CHARS;
        return new String(new char[] {digits[high], digits[low]});
    }

    // 静态方法：将字节数组转换为十六进制字符串
    public static String bytesToHex(byte[] bytes) {
        return bytesToHex(bytes, false, false, " ", false);
    }

    public static String bytesToHex(byte[] bytes, boolean useUpperCase,
            boolean withDelimiter, String delimiter,
            boolean withPrefix) {
        if (bytes == null) {
            return null;
        }

        if (bytes.length == 0) {
            return "";
        }

        char[] digits = useUpperCase ? UPPER_HEX_CHARS : HEX_CHARS;
        String delim = delimiter != null ? delimiter : " ";
        int capacity = (withPrefix ? 2 : 0) + bytes.length * 2
                + Math.max(0, bytes.length - 1) * (withDelimiter ? delim.length() : 0);
        StringBuilder hexString = new StringBuilder(capacity);

        if (withPrefix) {
            hexString.append("0x");
        }

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && withDelimiter) {
                hexString.append(delim);
            }

            int unsigned = bytes[i] & 0xFF;
            hexString.append(digits[unsigned >>> 4]);
            hexString.append(digits[unsigned & 0x0F]);
        }

        return hexString.toString();
    }

    // 可选：添加一个便捷的方法用于反转操作（十六进制字符串转字节数组）
    public static byte[] hexToBytes(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            return new byte[0];
        }

        // 移除可能的0x前缀
        if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
            hexString = hexString.substring(2);
        }

        int hexDigits = 0;
        for (int i = 0; i < hexString.length(); i++) {
            if (!Character.isWhitespace(hexString.charAt(i))) {
                hexDigits++;
            }
        }
        if ((hexDigits & 1) != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }

        byte[] bytes = new byte[hexDigits / 2];
        int high = -1;
        int outputIndex = 0;
        for (int i = 0; i < hexString.length(); i++) {
            char ch = hexString.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            int value = Character.digit(ch, 16);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid hex character: " + ch);
            }
            if (high < 0) {
                high = value;
            } else {
                bytes[outputIndex++] = (byte) ((high << 4) | value);
                high = -1;
            }
        }

        return bytes;
    }
}
