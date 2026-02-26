package com.bit.coin.structure.script;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * 地址抽象层
 * 统一表示不同脚本类型的地址
 */
@Slf4j
@Data
public class Address {
    private ScriptType scriptType;
    private byte[] addressBytes;  // 解析出的统一地址(20或32字节)
    private byte[] fullScript;    // 完整的scriptPubKey(用于验证)

    public Address() {
    }

    public Address(ScriptType scriptType, byte[] addressBytes, byte[] fullScript) {
        this.scriptType = scriptType;
        this.addressBytes = addressBytes;
        this.fullScript = fullScript;
    }

    /**
     * 从任意scriptPubKey解析出地址
     */
    public static Address parseFromScriptPubKey(byte[] scriptPubKey) {
        if (scriptPubKey == null || scriptPubKey.length == 0) {
            log.debug("scriptPubKey为空,无法解析地址");
            return null;
        }

        ScriptType type = ScriptParser.detectScriptType(scriptPubKey);
        byte[] addressBytes = ScriptParser.extractAddress(scriptPubKey, type);

        if (addressBytes == null) {
            log.debug("无法从scriptPubKey提取地址,脚本类型: {}", type);
            return null;
        }

        return new Address(type, addressBytes, scriptPubKey);
    }

    /**
     * 从scriptSig解析发送方地址(仅适用于P2PKH等简单脚本)
     */
    public static Address parseFromScriptSig(byte[] scriptSig) {
        if (scriptSig == null || scriptSig.length == 0) {
            return null;
        }

        byte[] senderAddress = ScriptParser.extractSenderAddress(scriptSig);
        if (senderAddress == null) {
            return null;
        }

        return new Address(ScriptType.P2PKH, senderAddress, scriptSig);
    }

    /**
     * 获取地址的十六进制字符串表示
     */
    public String toHex() {
        if (addressBytes == null) {
            return null;
        }
        return bytesToHex(addressBytes);
    }

    /**
     * 判断地址是否有效
     */
    public boolean isValid() {
        return scriptType != null
            && scriptType != ScriptType.UNKNOWN
            && addressBytes != null
            && addressBytes.length > 0
            && scriptType.isValidAddressLength(addressBytes.length);
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return new byte[0];
        }
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
