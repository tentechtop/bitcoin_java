package com.bit.coin.utils;

import java.math.BigInteger;
import java.util.Objects;

/**
 * 无符号256位整数实现（32字节），基于BigInteger封装，支持与BigInteger互操作、数学运算和格式转换
 */
public class UInt256 implements Comparable<UInt256> {
    // 常量定义：2^256（用于校验最大值）
    private static final BigInteger TWO_POW_256 = BigInteger.ONE.shiftLeft(256);
    // UInt256最大值：2^256 - 1
    public static final BigInteger MAX_VALUE = TWO_POW_256.subtract(BigInteger.ONE);
    // UInt256最小值：0
    public static final BigInteger MIN_VALUE = BigInteger.ZERO;

    // 底层存储无符号256位值（确保在0 ~ 2^256-1范围内）
    private final BigInteger value;

    // 私有构造方法：校验值范围，确保合法性
    private UInt256(BigInteger value) {
        if (value == null) {
            throw new IllegalArgumentException("UInt256 value cannot be null");
        }
        if (value.compareTo(MIN_VALUE) < 0 || value.compareTo(MAX_VALUE) > 0) {
            throw new IllegalArgumentException(
                    String.format("UInt256 value must be between 0 and %s (2^256 - 1)", MAX_VALUE)
            );
        }
        this.value = value;
    }

    // -------------------------- 静态工厂方法（构造UInt256） --------------------------
    /**
     * 从BigInteger构造UInt256（自动校验范围）
     */
    public static UInt256 fromBigInteger(BigInteger value) {
        return new UInt256(value);
    }

    /**
     * 从long构造UInt256（仅支持非负long）
     */
    public static UInt256 fromLong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Long value must be non-negative for UInt256");
        }
        return new UInt256(BigInteger.valueOf(value));
    }

    /**
     * 从32字节数组构造UInt256（大端序，无符号）
     */
    public static UInt256 fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 32) {
            //返回零
            return ZERO;
        }
        // 1表示无符号（避免BigInteger解析为负数）
        BigInteger bigInt = new BigInteger(1, bytes);
        return new UInt256(bigInt);
    }

    /**
     * 从十六进制字符串构造UInt256（支持带/不带0x前缀）
     */
    public static UInt256 fromHexString(String hexStr) {
        if (hexStr == null) {
            throw new IllegalArgumentException("Hex string cannot be null");
        }
        // 移除0x/0X前缀
        String cleanHex = hexStr.trim().startsWith("0x") || hexStr.trim().startsWith("0X")
                ? hexStr.trim().substring(2)
                : hexStr.trim();
        // 解析为16进制BigInteger
        BigInteger bigInt = new BigInteger(cleanHex, 16);
        return new UInt256(bigInt);
    }

    /**
     * 从十进制字符串构造UInt256
     */
    public static UInt256 fromDecimalString(String decimalStr) {
        if (decimalStr == null) {
            throw new IllegalArgumentException("Decimal string cannot be null");
        }
        BigInteger bigInt = new BigInteger(decimalStr.trim());
        return new UInt256(bigInt);
    }

    // -------------------------- 转换方法 --------------------------
    /**
     * 转换为BigInteger（方便与BigInteger做数学运算）
     */
    public BigInteger toBigInteger() {
        return this.value;
    }

    /**
     * 转换为32字节数组（大端序，无符号）
     */
    public byte[] toBytes() {
        byte[] rawBytes = this.value.toByteArray();
        byte[] result = new byte[32];

        // 处理BigInteger字节数组长度不足/超过的情况（理论上不会超过，构造时已校验）
        if (rawBytes.length <= 32) {
            // 大端序：将有效字节复制到结果数组的末尾，前面补0
            System.arraycopy(rawBytes, 0, result, 32 - rawBytes.length, rawBytes.length);
        } else {
            throw new IllegalStateException("UInt256 value exceeds 32 bytes (should not happen)");
        }
        return result;
    }

    /**
     * 转换为十六进制字符串（64位，大写，无0x前缀）
     */
    public String toHexString() {
        String hex = this.value.toString(16);
        // 补0到64位（32字节=64个十六进制字符），大写
        return String.format("%64s", hex).replace(' ', '0').toUpperCase();
    }

    /**
     * 转换为带0x前缀的十六进制字符串（64位，大写）
     */
    public String toHexStringWithPrefix() {
        return "0x" + toHexString();
    }

    /**
     * 转换为十进制字符串
     */
    public String toDecimalString() {
        return this.value.toString(10);
    }

    // -------------------------- 数学运算方法（与BigInteger兼容） --------------------------
    /**
     * 加法运算
     */
    public UInt256 add(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        return new UInt256(this.value.add(other.value));
    }

    /**
     * 减法运算（无符号，结果为负则抛异常）
     */
    public UInt256 subtract(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        return new UInt256(this.value.subtract(other.value));
    }

    /**
     * 乘法运算
     */
    public UInt256 multiply(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        return new UInt256(this.value.multiply(other.value));
    }

    /**
     * 除法运算（无符号，除数为0抛异常）
     */
    public UInt256 divide(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        if (other.value.equals(MIN_VALUE)) {
            throw new ArithmeticException("Division by zero");
        }
        return new UInt256(this.value.divide(other.value));
    }

    /**
     * 取模运算（除数为0抛异常）
     */
    public UInt256 mod(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        if (other.value.equals(MIN_VALUE)) {
            throw new ArithmeticException("Mod by zero");
        }
        return new UInt256(this.value.mod(other.value));
    }

    // -------------------------- 基础方法重写 --------------------------

    /**
     * 0 a 和 b 相等
     * 1 a 大于 b
     * -1 a 小于 b
     * @param other
     * @return
     */
    @Override
    public int compareTo(UInt256 other) {
        Objects.requireNonNull(other, "Other UInt256 cannot be null");
        return this.value.compareTo(other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UInt256 uint256 = (UInt256) o;
        return value.equals(uint256.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    // 默认toString返回十进制字符串
    @Override
    public String toString() {
        return toDecimalString();
    }

    // -------------------------- 常用常量 --------------------------
    public static final UInt256 ZERO = fromBigInteger(MIN_VALUE);
    public static final UInt256 ONE = fromBigInteger(BigInteger.ONE);
    public static final UInt256 MAX = fromBigInteger(MAX_VALUE);


    public static void main(String[] args) {
        // 1. 基础构造与格式输出
        UInt256 uint1 = UInt256.fromLong(123456789L);
        System.out.println("=== 基础格式输出 ===");
        System.out.println("十进制: " + uint1.toDecimalString());
        System.out.println("十六进制（无前缀）: " + uint1.toHexString());
        System.out.println("十六进制（带前缀）: " + uint1.toHexStringWithPrefix());

        // 2. 从十六进制字符串构造（最大值）
        UInt256 maxUInt = UInt256.fromHexString("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF");
        System.out.println("\n=== 最大值测试 ===");
        System.out.println("最大值十进制: " + maxUInt.toDecimalString());
        System.out.println("最大值十六进制: " + maxUInt.toHexString());

        // 3. 与BigInteger互操作
        System.out.println("\n=== 与BigInteger互操作 ===");
        BigInteger bigInt = new BigInteger("9876543210987654321098765432109876543210987654321098765432109876");
        UInt256 uint2 = UInt256.fromBigInteger(bigInt);
        System.out.println("从BigInteger构造的UInt256: " + uint2.toDecimalString());
        BigInteger convertedBack = uint2.toBigInteger();
        System.out.println("转回BigInteger是否相等: " + bigInt.equals(convertedBack));

        // 4. 数学运算
        System.out.println("\n=== 数学运算 ===");
        UInt256 a = UInt256.fromLong(1000);
        UInt256 b = UInt256.fromLong(200);
        System.out.println("a + b = " + a.add(b).toDecimalString());
        System.out.println("a * b = " + a.multiply(b).toDecimalString());
        System.out.println("a / b = " + a.divide(b).toDecimalString());




        // 5. 字节数组转换
        System.out.println("\n=== 字节数组转换 ===");
        byte[] bytes = uint1.toBytes();
        System.out.println("字节数组长度: " + bytes.length); // 输出32
        UInt256 uint3 = UInt256.fromBytes(bytes);
        System.out.println("从字节数组恢复后的值: " + uint3.toDecimalString()); // 与uint1一致
    }
}