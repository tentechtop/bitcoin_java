package com.bit.coin.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

public class HexByteArraySerializer extends StdSerializer<byte[]> {

    // 十六进制字符表
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

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

        // 构建十六进制字符串
        StringBuilder hexString = new StringBuilder();

        // 如果需要前缀
        if (withPrefix) {
            hexString.append("0x");
        }

        for (int i = 0; i < value.length; i++) {
            if (i > 0 && withDelimiter) {
                hexString.append(delimiter);
            }

            // 获取字节的高4位和低4位
            int high = (value[i] >> 4) & 0x0F;
            int low = value[i] & 0x0F;

            // 转换为十六进制字符
            if (useUpperCase) {
                hexString.append(Character.toUpperCase(HEX_CHARS[high]));
                hexString.append(Character.toUpperCase(HEX_CHARS[low]));
            } else {
                hexString.append(HEX_CHARS[high]);
                hexString.append(HEX_CHARS[low]);
            }
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

        if (useUpperCase) {
            return String.valueOf(Character.toUpperCase(HEX_CHARS[high])) +
                    Character.toUpperCase(HEX_CHARS[low]);
        } else {
            return String.valueOf(HEX_CHARS[high]) + HEX_CHARS[low];
        }
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

        StringBuilder hexString = new StringBuilder();
        String delim = delimiter != null ? delimiter : " ";

        if (withPrefix) {
            hexString.append("0x");
        }

        for (int i = 0; i < bytes.length; i++) {
            if (i > 0 && withDelimiter) {
                hexString.append(delim);
            }

            int high = (bytes[i] >> 4) & 0x0F;
            int low = bytes[i] & 0x0F;

            if (useUpperCase) {
                hexString.append(Character.toUpperCase(HEX_CHARS[high]));
                hexString.append(Character.toUpperCase(HEX_CHARS[low]));
            } else {
                hexString.append(HEX_CHARS[high]);
                hexString.append(HEX_CHARS[low]);
            }
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

        // 移除所有分隔符
        hexString = hexString.replaceAll("\\s+", "");

        // 确保字符串长度为偶数
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex string length");
        }

        byte[] bytes = new byte[hexString.length() / 2];

        for (int i = 0; i < hexString.length(); i += 2) {
            String hexByte = hexString.substring(i, i + 2);
            bytes[i / 2] = (byte) Integer.parseInt(hexByte, 16);
        }

        return bytes;
    }
}