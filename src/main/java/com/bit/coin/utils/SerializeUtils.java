package com.bit.coin.utils;


import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;


public class SerializeUtils {
    /**
     * 将 int 以小端字节序写入 DataOutputStream。
     *
     * @param dos 输出流
     * @param value 要写入的 int 值
     * @throws IOException 如果发生I/O错误
     */
    public static void writeLittleEndianInt(DataOutputStream dos, int value) throws IOException {
        dos.write(ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array());
    }

    public static void writeLittleEndianLong(DataOutputStream dos, long value) throws IOException {
        dos.writeLong(Long.reverseBytes(value));
    }

    public static long readLittleEndianLong(DataInputStream dis) throws IOException {
        byte[] bytes = new byte[8];
        dis.readFully(bytes);
        return littleEndianToLong(bytes);
    }

    /**
     * 将一个整数作为可变长度整数 (VarInt) 写入流中。
     *
     * @param dos 输出流
     * @param value 要写入的整数值
     * @throws IOException 如果发生I/O错误
     */
    public static void writeVarInt(DataOutputStream dos, int value) throws IOException {
        if (value < 0xFD) {
            dos.write(value);
        } else if (value <= 0xFFFF) {
            dos.write(0xFD);
            dos.writeShort(Short.reverseBytes((short) value));
        } else {
            dos.write(0xFE);
            dos.writeInt(Integer.reverseBytes(value));
        }
    }

    /**
     * 从 DataInputStream 读取可变长度整数 (VarInt)
     *
     * @param dis 输入流
     * @return 读取的整数值
     * @throws IOException 如果发生I/O错误
     */
    public static int readVarInt(DataInputStream dis) throws IOException {
        int first = dis.readUnsignedByte();
        if (first < 0xFD) {
            return first;
        } else if (first == 0xFD) {
            return Short.reverseBytes(dis.readShort()) & 0xFFFF;
        } else if (first == 0xFE) {
            return Integer.reverseBytes(dis.readInt());
        } else {
            throw new IOException("不支持的 VarInt 标记: 0xFF");
        }
    }



    /**
     * 小端字节序字节数组转int（4字节）
     */
    public static int littleEndianToInt(byte[] bytes) {
        if (bytes.length != 4) {
            throw new IllegalArgumentException("字节数组长度必须为4");
        }
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

    // 辅助方法: long转小端字节序
    public static byte[] longToLittleEndian(long value) {
        return ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value)
                .array();
    }

    public static byte[] intToLittleEndian(int value) {
        return new byte[] {
                (byte) (value),         // 最低位字节（LSB）
                (byte) (value >> 8),    // 次低位字节
                (byte) (value >> 16),   // 次高位字节
                (byte) (value >> 24)    // 最高位字节（MSB）
        };
    }

    public static int readLittleEndianBytesToInt(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("字节数组长度必须至少为4");
        }
        return (bytes[0] & 0xFF) |           // 最低位字节
                ((bytes[1] & 0xFF) << 8) |    // 次低位字节
                ((bytes[2] & 0xFF) << 16) |   // 次高位字节
                ((bytes[3] & 0xFF) << 24);    // 最高位字节
    }



    // 辅助方法: 小端字节序转long
    public static long littleEndianToLong(byte[] bytes) {
        if (bytes.length != 8) {
            throw new IllegalArgumentException("字节数组长度必须为8");
        }
        return ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
    }



    /**
     * 写入字节数组到输出流（带长度前缀）
     */
    public static void writeBytesWithLength(DataOutputStream dos, byte[] bytes) throws IOException {
        if (bytes == null) {
            writeVarInt(dos, 0);
            return;
        }
        writeVarInt(dos, bytes.length);
        dos.write(bytes);
    }

    /**
     * 从输入流读取字节数组（带长度前缀）
     */
    public static byte[] readBytesWithLength(DataInputStream dis) throws IOException {
        int length = readVarInt(dis);
        if (length == 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return bytes;
    }

    /**
     * 写入固定长度字节数组
     */
    public static void writeFixedBytes(DataOutputStream dos, byte[] bytes, int fixedLength) throws IOException {
        if (bytes == null || bytes.length != fixedLength) {
            throw new IllegalArgumentException("字节数组长度必须为" + fixedLength+"当前是"+bytes.length);
        }
        dos.write(bytes);
    }

    /**
     * 读取固定长度字节数组
     */
    public static byte[] readFixedBytes(DataInputStream dis, int fixedLength) throws IOException {
        byte[] bytes = new byte[fixedLength];
        dis.readFully(bytes);
        return bytes;
    }

    /**
     * 写入布尔值
     */
    public static void writeBoolean(DataOutputStream dos, boolean value) throws IOException {
        dos.writeByte(value ? 1 : 0);
    }

    /**
     * 读取布尔值
     */
    public static boolean readBoolean(DataInputStream dis) throws IOException {
        return dis.readByte() != 0;
    }

    // 辅助方法: 字节数组转十六进制字符串
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 将两个字节数组合并成一个新的字节数组
     * @param currentHash 第一个字节数组（放在前面）
     * @param pathHash 第二个字节数组（放在后面）
     * @return 合并后的新字节数组
     */
    public static byte[] concat(byte[] currentHash, byte[] pathHash) {
        // 处理空数组情况
        if (currentHash == null && pathHash == null) {
            return new byte[0];
        }
        if (currentHash == null) {
            return pathHash.clone();
        }
        if (pathHash == null) {
            return currentHash.clone();
        }

        // 创建新数组，长度为两个输入数组长度之和
        byte[] result = new byte[currentHash.length + pathHash.length];

        // 复制第一个数组
        System.arraycopy(currentHash, 0, result, 0, currentHash.length);

        // 复制第二个数组到第一个数组后面
        System.arraycopy(pathHash, 0, result, currentHash.length, pathHash.length);

        return result;
    }

    /**
     * 读取一个小端字节序的整数
     * @param dis 输入流
     * @return 整数
     * @throws IOException
     */
    public static int readLittleEndianInt(DataInputStream dis) throws IOException {
        byte[] bytes = new byte[4];
        dis.readFully(bytes);
        return littleEndianToInt(bytes);
    }

    public static int readLittleEndianInt(byte[] intValueBytes) {
        assert intValueBytes!=null;
        // 小端序：最低有效字节在前
        return ((intValueBytes[0] & 0xFF))        |  // 字节0 -> 最低8位
                ((intValueBytes[1] & 0xFF) << 8)   |  // 字节1 -> 8-15位
                ((intValueBytes[2] & 0xFF) << 16) |  // 字节2 -> 16-23位
                ((intValueBytes[3] & 0xFF) << 24);   // 字节3 -> 最高8位
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        if (hex.isEmpty()){
            return null;
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }


}
