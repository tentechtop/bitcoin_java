package com.bit.coin.structure.script;

import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * 地址索引Key构建和解析工具
 * 索引Key结构: [脚本类型1字节] [地址长度1字节] [地址20/32字节] [TxId 32字节]
 */
@Slf4j
public class AddressIndexKey {

    public static final int TYPE_OFFSET = 0;
    public static final int LENGTH_OFFSET = 1;
    public static final int ADDRESS_OFFSET = 2;

    /**
     * 构建完整的索引Key
     *
     * @param type    脚本类型
     * @param address 地址字节(20或32字节)
     * @param txId    交易ID(32字节)
     * @return 索引Key
     */
    public static byte[] buildKey(ScriptType type, byte[] address, byte[] txId) {
        if (type == null || address == null || txId == null) {
            throw new IllegalArgumentException("参数不能为null");
        }
        if (txId.length != 32) {
            throw new IllegalArgumentException("txId长度必须为32字节");
        }
        if (!type.isValidAddressLength(address.length)) {
            throw new IllegalArgumentException("地址长度与脚本类型不匹配: " + type + ", 长度: " + address.length);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(type.getCode());              // 1字节: 脚本类型
            baos.write(address.length);               // 1字节: 地址长度
            baos.write(address);                      // 20或32字节
            baos.write(txId);                         // 32字节
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("构建索引Key失败", e);
        }
    }

    /**
     * 从索引Key中解析脚本类型
     */
    public static ScriptType parseType(byte[] key) {
        if (key == null || key.length < 2) {
            return ScriptType.UNKNOWN;
        }
        return ScriptType.fromCode(key[TYPE_OFFSET] & 0xFF);
    }

    /**
     * 从索引Key中解析地址字节
     */
    public static byte[] parseAddress(byte[] key) {
        if (key == null || key.length < 3) {
            return null;
        }
        int length = key[LENGTH_OFFSET] & 0xFF;
        if (ADDRESS_OFFSET + length > key.length) {
            log.error("索引Key格式错误,地址长度超出范围");
            return null;
        }
        return Arrays.copyOfRange(key, ADDRESS_OFFSET, ADDRESS_OFFSET + length);
    }

    /**
     * 从索引Key中解析TxId
     */
    public static byte[] parseTxId(byte[] key) {
        if (key == null || key.length < 3) {
            return null;
        }
        int addressLength = key[LENGTH_OFFSET] & 0xFF;
        int txIdOffset = ADDRESS_OFFSET + addressLength;
        if (txIdOffset + 32 > key.length) {
            log.error("索引Key格式错误,TxId超出范围");
            return null;
        }
        return Arrays.copyOfRange(key, txIdOffset, txIdOffset + 32);
    }

    /**
     * 构建前缀查询key: [脚本类型1字节] [地址长度1字节] [地址20/32字节]
     * 用于查询某个地址的所有交易
     */
    public static byte[] buildPrefix(ScriptType type, byte[] address) {
        if (type == null || address == null) {
            throw new IllegalArgumentException("参数不能为null");
        }
        if (!type.isValidAddressLength(address.length)) {
            throw new IllegalArgumentException("地址长度与脚本类型不匹配: " + type + ", 长度: " + address.length);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(type.getCode());              // 1字节: 脚本类型
            baos.write(address.length);               // 1字节: 地址长度
            baos.write(address);                      // 20或32字节
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("构建前缀Key失败", e);
        }
    }

    /**
     * 解码Base58地址为地址字节
     * 支持P2PKH(以1开头)、P2SH(以3开头)等传统地址
     *
     * @param address Base58编码的地址
     * @return 地址字节(20字节)
     */
    public static byte[] decodeAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }

        try {
            // Base58解码
            byte[] decoded = Base58.decode(address);

            // Base58Check格式: [版本1字节] [数据20字节] [校验和4字节]
            // 总长度至少26字节(1+20+4)
            if (decoded.length < 26) {
                log.warn("地址格式错误,长度不足: {}", address);
                return null;
            }

            // 提取20字节的数据部分
            byte[] addressBytes = Arrays.copyOfRange(decoded, 1, 21);

            // 验证校验和
            if (verifyChecksum(decoded)) {
                return addressBytes;
            } else {
                log.warn("地址校验和验证失败: {}", address);
                return null;
            }

        } catch (Exception e) {
            log.error("解码地址时发生异常: {}", address, e);
            return null;
        }
    }

    /**
     * 从公钥推导P2PKH地址(20字节)
     */
    public static byte[] deriveP2PKHAddress(byte[] publicKey) {
        if (publicKey == null) {
            return null;
        }
        return Sha.applyRIPEMD160(Sha.applySHA256(publicKey));
    }

    /**
     * 验证Base58Check校验和
     */
    private static boolean verifyChecksum(byte[] data) {
        if (data.length < 4) {
            return false;
        }

        // 分离数据和校验和
        byte[] payload = Arrays.copyOfRange(data, 0, data.length - 4);
        byte[] checksum = Arrays.copyOfRange(data, data.length - 4, data.length);

        // 计算双SHA256哈希
        byte[] calculated = Sha.applySHA256(Sha.applySHA256(payload));

        // 取前4字节作为校验和
        byte[] calculatedChecksum = Arrays.copyOfRange(calculated, 0, 4);

        // 比较校验和
        return Arrays.equals(checksum, calculatedChecksum);
    }

    /**
     * 获取索引Key的信息字符串
     */
    public static String getKeyInfo(byte[] key) {
        if (key == null) {
            return "null";
        }

        ScriptType type = parseType(key);
        byte[] address = parseAddress(key);
        byte[] txId = parseTxId(key);

        StringBuilder sb = new StringBuilder();
        sb.append("Type: ").append(type);
        if (address != null) {
            sb.append(", Address: ").append(bytesToHex(address));
        }
        if (txId != null) {
            sb.append(", TxId: ").append(bytesToHex(txId));
        }
        sb.append(", KeyLength: ").append(key.length);
        return sb.toString();
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
     * 比较两个地址索引Key是否属于同一个地址
     */
    public static boolean isSameAddress(byte[] key1, byte[] key2) {
        if (key1 == null || key2 == null) {
            return false;
        }

        ScriptType type1 = parseType(key1);
        ScriptType type2 = parseType(key2);
        if (type1 != type2) {
            return false;
        }

        byte[] addr1 = parseAddress(key1);
        byte[] addr2 = parseAddress(key2);
        if (addr1 == null || addr2 == null) {
            return false;
        }

        return Arrays.equals(addr1, addr2);
    }
}
