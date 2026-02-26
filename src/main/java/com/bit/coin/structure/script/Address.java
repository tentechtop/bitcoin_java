package com.bit.coin.structure.script;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

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
     * 从String解析地址
     * 支持以下格式：
     * 1. 16进制字符串的20字节公钥哈希 -> P2PKH地址
     * 2. 16进制字符串的完整scriptPubKey -> 解析为对应地址
     * 3. Base58编码的比特币地址 -> P2PKH地址
     * 4. Bech32编码的比特币地址（bc1q...） -> P2WPKH地址
     *
     * @param addressString 地址字符串
     * @return 解析出的地址对象，无法解析时返回null
     */
    public static Address parseFromString(String addressString) {
        if (addressString == null || addressString.trim().isEmpty()) {
            log.debug("地址字符串为空");
            return null;
        }

        String trimmed = addressString.trim();

        // 1. 尝试解析为16进制字符串
        if (isHexString(trimmed)) {
            byte[] bytes = hexToBytes(trimmed);

            if (bytes == null || bytes.length == 0) {
                log.debug("16进制字符串解析失败: {}", trimmed);
                return null;
            }

            // 1.1 如果是20字节，直接作为公钥哈希解析
            if (bytes.length == 20) {
                return parseFromPubKeyHash(bytes);
            }

            // 1.2 其他长度作为完整的scriptPubKey解析
            return parseFromScriptPubKey(bytes);
        }

        // 2. 尝试解析为Bech32编码的比特币地址（bc1q...）
        if (trimmed.toLowerCase().startsWith("bc1q") || trimmed.toLowerCase().startsWith("tb1q")) {
            try {
                Address bech32Address = parseFromBech32(trimmed);
                if (bech32Address != null) {
                    return bech32Address;
                }
            } catch (Exception e) {
                log.debug("Bech32解码失败: {}", trimmed, e);
            }
        }

        // 3. 尝试解析为Base58编码的比特币地址
        try {
            byte[] decoded = org.bitcoinj.core.Base58.decode(trimmed);
            if (decoded != null && decoded.length > 0) {
                // Base58解码后的格式: [版本字节1字节] [20字节哈希] [4字节校验和]
                // 提取20字节哈希(跳过版本字节)
                if (decoded.length >= 21) {
                    byte[] pubKeyHash = new byte[20];
                    System.arraycopy(decoded, 1, pubKeyHash, 0, 20);
                    return parseFromPubKeyHash(pubKeyHash);
                }
            }
        } catch (Exception e) {
            log.debug("Base58解码失败: {}", trimmed, e);
        }

        log.debug("无法解析地址字符串: {}", trimmed);
        return null;
    }

    /**
     * 判断字符串是否为有效的16进制字符串
     */
    private static boolean isHexString(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        // 检查是否全为16进制字符(可选择性包含0x前缀)
        String hexStr = str.startsWith("0x") || str.startsWith("0X") ? str.substring(2) : str;
        if (hexStr.isEmpty()) {
            return false;
        }
        // 长度必须是偶数(每2个字符代表1个字节)
        if (hexStr.length() % 2 != 0) {
            return false;
        }
        // 检查每个字符是否为16进制
        for (char c : hexStr.toCharArray()) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从Bech32编码的地址解析（支持P2WPKH和P2WSH）
     * Bech32格式: bc1[q/p]... 其中q表示P2WPKH(20字节)，p表示P2WSH(32字节)
     */
    private static Address parseFromBech32(String bech32Address) {
        if (bech32Address == null || bech32Address.isEmpty()) {
            return null;
        }

        try {
            log.info("开始解析Bech32地址: {}", bech32Address);

            // 解析Bech32地址
            Bech32Result result = bech32Decode(bech32Address.toLowerCase());
            if (result == null) {
                log.error("Bech32解码返回null");
                return null;
            }

            log.info("Bech32解码成功 - HRP: {}, 数据长度: {}", result.hrp, result.data.length);

            // 验证HRP（Human Readable Part）
            if (!result.hrp.equals("bc") && !result.hrp.equals("tb")) {
                log.error("不支持的Bech32 HRP: {}", result.hrp);
                return null;
            }

            // 转换为8位字节数组（从5位转换）
            byte[] data = convertBits(result.data, 5, 8, false);
            if (data == null || data.length == 0) {
                log.error("Bech32数据转换失败");
                return null;
            }

            log.info("位数转换后数据长度: {}", data.length);

            // 获取witness version和program
            int witnessVersion = data[0] & 0xFF;
            byte[] program = new byte[data.length - 1];
            System.arraycopy(data, 1, program, 0, program.length);

            log.info("Witness版本: {}, Program长度: {}", witnessVersion, program.length);

            // 判断地址类型
            if (witnessVersion == 0) {
                // P2WPKH: 20字节
                if (program.length == 20) {
                    log.info("识别为P2WPKH地址");
                    // 构建P2WPKH锁定脚本: OP_0 <20字节hash>
                    Script script = new Script();
                    script.addOpCode(Script.OP_0);
                    script.addData(program);

                    return new Address(ScriptType.P2WPKH, program, script.serialize());
                }
                // P2WSH: 32字节
                else if (program.length == 32) {
                    log.info("识别为P2WSH地址");
                    // 构建P2WSH锁定脚本: OP_0 <32字节hash>
                    Script script = new Script();
                    script.addOpCode(Script.OP_0);
                    script.addData(program);

                    return new Address(ScriptType.P2WSH, program, script.serialize());
                }
            }

            log.error("不支持的witness version或program length: version={}, length={}",
                    witnessVersion, program.length);
            return null;

        } catch (Exception e) {
            log.error("Bech32地址解析失败", e);
            return null;
        }
    }

    /**
     * Bech32解码结果
     */
    private static class Bech32Result {
        String hrp;
        byte[] data;
    }

    /**
     * Bech32字符集（小写）
     */
    private static final String BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    /**
     * Bech32解码
     */
    private static Bech32Result bech32Decode(String bech32) {
        if (bech32 == null || bech32.length() < 8 || bech32.length() > 90) {
            log.error("Bech32地址长度无效: {}", bech32 == null ? 0 : bech32.length());
            return null;
        }

        // 转换为小写
        bech32 = bech32.toLowerCase();

        // 分离HRP和数据部分（用最后一个'1'分隔）
        int separatorIndex = bech32.lastIndexOf('1');
        if (separatorIndex == -1 || separatorIndex == 0 || separatorIndex + 7 > bech32.length()) {
            log.error("Bech32分隔符'1'位置无效: separatorIndex={}, length={}",
                    separatorIndex, bech32.length());
            return null;
        }

        String hrp = bech32.substring(0, separatorIndex);
        String dataPart = bech32.substring(separatorIndex + 1);

        log.info("HRP: {}, DataPart: {}, DataPart长度: {}", hrp, dataPart, dataPart.length());

        // 检查字符并转换为5位值
        int[] values = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int charIndex = BECH32_CHARSET.indexOf(dataPart.charAt(i));
            if (charIndex == -1) {
                log.error("Bech32包含无效字符: {}", dataPart.charAt(i));
                return null;
            }
            values[i] = charIndex;
        }

        log.info("转换后的values数组长度: {}", values.length);

        // 验证校验和
        boolean checksumValid = bech32VerifyChecksum(hrp, values);
        log.info("校验和验证结果: {}", checksumValid);

        if (!checksumValid) {
            return null;
        }

        // 移除6字节校验和，转换为byte数组
        byte[] data = new byte[values.length - 6];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) values[i];
        }

        log.info("移除校验和后的数据长度: {}", data.length);

        Bech32Result result = new Bech32Result();
        result.hrp = hrp;
        result.data = data;
        return result;
    }

    /**
     * 验证Bech32校验和
     */
    private static boolean bech32VerifyChecksum(String hrp, int[] values) {
        int checksum = bech32Polymod(hrp, values);
        log.info("Polymod校验和结果: 0x{}", Integer.toHexString(checksum));
        log.info("校验和是否等于1: {}", checksum == 1);
        return checksum == 1;
    }

    /**
     * Bech32多项式模运算
     */
    private static int bech32Polymod(String hrp, int[] values) {
        // Bech32m（BIP 350）使用的生成器
        final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;

        // 处理HRP（每个字符高5位）
        for (int i = 0; i < hrp.length(); i++) {
            int b = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (hrp.charAt(i) >> 5);
            for (int j = 0; j < 5; j++) {
                if (((b >> j) & 1) == 1) {
                    chk ^= GEN[j];
                }
            }
        }

        // 处理分隔符
        chk ^= 1;

        // 处理数据（已转换为5位值）
        for (int i = 0; i < values.length; i++) {
            int b = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ values[i];
            for (int j = 0; j < 5; j++) {
                if (((b >> j) & 1) == 1) {
                    chk ^= GEN[j];
                }
            }
        }

        return chk;
    }

    /**
     * 转换位数（5位转8位或8位转5位）
     */
    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxVal = (1 << toBits) - 1;
        int maxAcc = (1 << (fromBits + toBits - 1)) - 1;

        java.util.List<Byte> result = new java.util.ArrayList<>();

        for (int i = 0; i < data.length; i++) {
            int value = data[i] & 0xFF;

            if ((value >> fromBits) != 0) {
                return null;
            }

            acc = ((acc << fromBits) | value) & maxAcc;
            bits += fromBits;

            while (bits >= toBits) {
                bits -= toBits;
                result.add((byte) ((acc >> bits) & maxVal));
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add((byte) ((acc << (toBits - bits)) & maxVal));
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxVal) != 0) {
            return null;
        }

        byte[] resultArray = new byte[result.size()];
        for (int i = 0; i < result.size(); i++) {
            resultArray[i] = result.get(i);
        }
        return resultArray;
    }

    /**
     * 从20字节公钥哈希解析为P2PKH地址
     * 公钥哈希为 SHA256(公钥) 后再 RIPEMD160 的结果
     * @param pubKeyHash 20字节的公钥哈希值
     * @return P2PKH地址对象,包含构建的完整锁定脚本
     */
    public static Address parseFromPubKeyHash(byte[] pubKeyHash) {
        if (pubKeyHash == null || pubKeyHash.length != 20) {
            log.debug("公钥哈希必须为20字节,实际长度: {}", pubKeyHash == null ? 0 : pubKeyHash.length);
            return null;
        }

        try {
            // 构建标准的P2PKH锁定脚本: OP_DUP OP_HASH160 <20字节hash> OP_EQUALVERIFY OP_CHECKSIG
            Script lockingScript = new Script();
            lockingScript.addOpCode(Script.OP_DUP);
            lockingScript.addOpCode(Script.OP_HASH160);
            lockingScript.addData(pubKeyHash);
            lockingScript.addOpCode(Script.OP_EQUALVERIFY);
            lockingScript.addOpCode(Script.OP_CHECKSIG);

            return new Address(ScriptType.P2PKH, pubKeyHash, lockingScript.serialize());
        } catch (Exception e) {
            log.error("从公钥哈希构建P2PKH地址失败", e);
            return null;
        }
    }

    /**
     * 从16进制字符串的公钥哈希解析为P2PKH地址
     * @param hexPubKeyHash 20字节的16进制字符串公钥哈希值
     * @return P2PKH地址对象
     */
    public static Address parseFromPubKeyHashHex(String hexPubKeyHash) {
        if (hexPubKeyHash == null || hexPubKeyHash.isEmpty()) {
            log.debug("公钥哈希16进制字符串为空");
            return null;
        }

        byte[] pubKeyHash = hexToBytes(hexPubKeyHash);
        return parseFromPubKeyHash(pubKeyHash);
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




}
