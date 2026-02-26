package com.bit.coin.structure.script;

import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;


import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 地址生成器
 * 使用32字节公钥生成各种比特币地址
 */
@Slf4j
public class AddressGenerator {

    // 比特币主网版本字节
    private static final byte MAINNET_PUBKEY_HASH = 0x00;  // P2PKH地址前缀
    private static final byte MAINNET_SCRIPT_HASH = 0x05;  // P2SH地址前缀

    // Base58字符集
    private static final String BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";


    /**
     * 生成P2PKH地址 (Pay to Public Key Hash)
     * 格式: Base58Check(0x00 + RIPEMD160(SHA256(压缩公钥)))
     */
    public static String generateP2PKHAddress(byte[] rawPublicKey) {
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));

        // 添加版本字节(主网P2PKH为0x00)
        byte[] versionedHash = new byte[21];
        versionedHash[0] = MAINNET_PUBKEY_HASH;
        System.arraycopy(hash, 0, versionedHash, 1, 20);

        return base58CheckEncode(versionedHash);
    }

    /**
     * 生成P2SH地址 (Pay to Script Hash)
     * 格式: Base58Check(0x05 + RIPEMD160(SHA256(赎回脚本)))
     * 注意:这里简化处理,使用标准P2PKH赎回脚本
     */
    public static String generateP2SHAddress(byte[] rawPublicKey) {
        // 构建标准P2PKH赎回脚本: <压缩公钥> OP_CHECKSIG
        // 格式: <33字节> <AC>
        byte[] redeemScript = new byte[rawPublicKey.length + 1];
        System.arraycopy(rawPublicKey, 0, redeemScript, 0, rawPublicKey.length);
        redeemScript[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;

        // 计算赎回脚本的哈希
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(redeemScript));

        // 添加版本字节(主网P2SH为0x05)
        byte[] versionedHash = new byte[21];
        versionedHash[0] = MAINNET_SCRIPT_HASH;
        System.arraycopy(hash, 0, versionedHash, 1, 20);

        return base58CheckEncode(versionedHash);
    }

    /**
     * 生成P2WPKH地址 (Witness v0 Public Key Hash)
     * 格式: Bech32(bc1q + RIPEMD160(SHA256(压缩公钥)))
     */
    public static String generateP2WPKHAddress(byte[] rawPublicKey) {
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));

        return bech32Encode("bc", 0, hash);
    }

    /**
     * 生成P2WSH地址 (Witness v0 Script Hash)
     * 格式: Bech32(bc1q + SHA256(赎回脚本))
     */
    public static String generateP2WSHAddress(byte[] rawPublicKey) {
        // 构建赎回脚本: <压缩公钥> OP_CHECKSIG
        byte[] redeemScript = new byte[rawPublicKey.length + 1];
        System.arraycopy(rawPublicKey, 0, redeemScript, 0, rawPublicKey.length);
        redeemScript[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;

        // 计算SHA256哈希(P2WSH使用SHA256而非RIPEMD160)
        byte[] hash = Sha.applySHA256(redeemScript);

        return bech32Encode("bc", 0, hash);
    }

    /**
     * 生成P2TR地址 (Taproot)
     * 格式: Bech32(bc1p + 32字节公钥)
     * 注意:真正的Taproot需要更复杂的公钥调整,这里简化处理
     */
    public static String generateP2TRAddress(byte[] rawPublicKey) {
        if (rawPublicKey.length != 32) {
            throw new IllegalArgumentException("P2TR需要32字节公钥");
        }

        // P2TR使用版本号1,直接使用32字节公钥
        return bech32Encode("bc", 1, rawPublicKey);
    }

    /**
     * 生成P2PK脚本 (Pay to Public Key)
     * 格式: <公钥> OP_CHECKSIG
     */
    public static byte[] generateP2PKScript(byte[] rawPublicKey) {
        byte[] script = new byte[rawPublicKey.length + 1];
        System.arraycopy(rawPublicKey, 0, script, 0, rawPublicKey.length);
        script[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;
        return script;
    }

    /**
     * 生成P2PKH脚本
     * 格式: OP_DUP OP_HASH160 <20字节hash> OP_EQUALVERIFY OP_CHECKSIG
     */
    public static byte[] generateP2PKHScript(byte[] rawPublicKey) {
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));

        byte[] script = new byte[25];
        script[0] = (byte) ScriptParser.OP_DUP;
        script[1] = (byte) ScriptParser.OP_HASH160;
        script[2] = 0x14; // 长度前缀: 20字节
        System.arraycopy(hash, 0, script, 3, 20);
        script[23] = (byte) ScriptParser.OP_EQUALVERIFY;
        script[24] = (byte) ScriptParser.OP_CHECKSIG;
        return script;
    }

    /**
     * 生成P2SH脚本
     * 格式: OP_HASH160 <20字节hash> OP_EQUAL
     */
    public static byte[] generateP2SHScript(byte[] rawPublicKey) {
        // 构建标准P2PKH赎回脚本: <压缩公钥> OP_CHECKSIG
        byte[] redeemScript = new byte[rawPublicKey.length + 1];
        System.arraycopy(rawPublicKey, 0, redeemScript, 0, rawPublicKey.length);
        redeemScript[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;

        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(redeemScript));

        byte[] script = new byte[23];
        script[0] = (byte) ScriptParser.OP_HASH160;
        script[1] = 0x14; // 长度前缀: 20字节
        System.arraycopy(hash, 0, script, 2, 20);
        script[22] = (byte) ScriptParser.OP_EQUAL;
        return script;
    }

    /**
     * 生成P2WPKH脚本
     * 格式: OP_0 <20字节>
     */
    public static byte[] generateP2WPKHScript(byte[] rawPublicKey) {
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));

        byte[] script = new byte[22];
        script[0] = (byte) ScriptParser.OP_0;
        script[1] = 0x14; // 长度前缀: 20字节
        System.arraycopy(hash, 0, script, 2, 20);
        return script;
    }

    /**
     * 生成P2WSH脚本
     * 格式: OP_0 <32字节>
     */
    public static byte[] generateP2WSHScript(byte[] rawPublicKey) {
        // 构建赎回脚本: <压缩公钥> OP_CHECKSIG
        byte[] redeemScript = new byte[rawPublicKey.length + 1];
        System.arraycopy(rawPublicKey, 0, redeemScript, 0, rawPublicKey.length);
        redeemScript[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;

        byte[] hash = Sha.applySHA256(redeemScript);

        byte[] script = new byte[34];
        script[0] = (byte) ScriptParser.OP_0;
        script[1] = 0x20; // 长度前缀: 32字节
        System.arraycopy(hash, 0, script, 2, 32);
        return script;
    }

    /**
     * 生成P2TR脚本
     * 格式: OP_1 <32字节>
     */
    public static byte[] generateP2TRScript(byte[] rawPublicKey) {
        byte[] script = new byte[34];
        script[0] = (byte) ScriptParser.OP_1;
        script[1] = 0x20; // 长度前缀: 32字节
        System.arraycopy(rawPublicKey, 0, script, 2, 32);
        return script;
    }

    /**
     * Base58Check编码
     */
    private static String base58CheckEncode(byte[] input) {
        // 计算校验和: 双重SHA256的前4字节
        byte[] hash = Sha.applySHA256(Sha.applySHA256(input));
        byte[] checksum = Arrays.copyOf(hash, 4);

        // 拼接数据和校验和
        byte[] data = new byte[input.length + 4];
        System.arraycopy(input, 0, data, 0, input.length);
        System.arraycopy(checksum, 0, data, input.length, 4);

        return base58Encode(data);
    }

    /**
     * Base58编码
     */
    private static String base58Encode(byte[] input) {
        // 将输入转换为大整数
        java.math.BigInteger num = java.math.BigInteger.ZERO;
        java.math.BigInteger base58 = java.math.BigInteger.valueOf(58);

        for (byte b : input) {
            num = num.multiply(java.math.BigInteger.valueOf(256))
                    .add(java.math.BigInteger.valueOf(b & 0xFF));
        }

        // 转换为Base58字符串
        StringBuilder sb = new StringBuilder();
        while (num.compareTo(java.math.BigInteger.ZERO) > 0) {
            java.math.BigInteger[] divmod = num.divideAndRemainder(base58);
            sb.insert(0, BASE58_CHARS.charAt(divmod[1].intValue()));
            num = divmod[0];
        }

        // 处理前导零
        for (byte b : input) {
            if (b == 0) {
                sb.insert(0, '1');
            } else {
                break;
            }
        }

        return sb.toString();
    }

    /**
     * Bech32编码(用于SegWit地址)
     */
    private static String bech32Encode(String hrp, int witVer, byte[] witProg) {
        // 将witVer和witProg分别转换为5位
        byte[] verData = {(byte) witVer};
        byte[] verConverted = convertBits(verData, 8, 5, true);
        byte[] progConverted = convertBits(witProg, 8, 5, true);

        // 合并转换后的数据
        byte[] converted = new byte[verConverted.length + progConverted.length];
        System.arraycopy(verConverted, 0, converted, 0, verConverted.length);
        System.arraycopy(progConverted, 0, converted, verConverted.length, progConverted.length);

        // 创建校验和
        StringBuilder bech = new StringBuilder();
        bech.append(hrp);
        bech.append("1");

        for (byte b : converted) {
            bech.append(BECH32_CHARS.charAt(b));
        }

        // 计算并添加校验和
        bech.append(createChecksum(hrp, converted));

        return bech.toString();
    }

    private static final String BECH32_CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        java.util.List<Byte> result = new java.util.ArrayList<>();
        int maxv = (1 << toBits) - 1;
        int maxacc = (1 << (fromBits + toBits - 1)) - 1;

        for (byte b : data) {
            int value = b & 0xFF;
            if ((value >> fromBits) != 0) {
                throw new IllegalArgumentException("Invalid value");
            }

            acc = ((acc << fromBits) | value) & maxacc;
            bits += fromBits;

            while (bits >= toBits) {
                bits -= toBits;
                result.add((byte) ((acc >> bits) & maxv));
            }
        }

        if (pad) {
            if (bits > 0) {
                result.add((byte) ((acc << (toBits - bits)) & maxv));
            }
        } else {
            // 对于P2WPKH/P2WSH,检查剩余位是否为零
            if (bits > 0) {
                int mask = (1 << bits) - 1;
                if ((acc & mask) != 0) {
                    throw new IllegalArgumentException("Invalid padding - non-zero bits in remainder");
                }
            }
        }

        byte[] arr = new byte[result.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = result.get(i);
        }
        return arr;
    }

    private static String createChecksum(String hrp, byte[] data) {
        int[] values = new int[data.length + 6];
        values[0] = values[1] = values[2] = values[3] = values[4] = values[5] = 0;

        for (int i = 0; i < data.length; i++) {
            values[i + 6] = data[i] & 0xFF;
        }

        // 将hrp的值加入到values中
        int hrpExpanded = hrpExpand(hrp);
        int mod = polymod(values) ^ hrpExpanded;

        int[] checksum = new int[6];
        for (int i = 0; i < 6; i++) {
            checksum[i] = (mod >> (5 * (5 - i))) & 31;
        }

        StringBuilder sb = new StringBuilder();
        for (int c : checksum) {
            sb.append(BECH32_CHARS.charAt(c));
        }
        return sb.toString();
    }

    private static int hrpExpand(String hrp) {
        int result = 1;
        for (int i = 0; i < hrp.length(); i++) {
            char c = hrp.charAt(i);
            result ^= c & 31;
            result *= 31;
        }
        result ^= 1;
        return result;
    }

    private static int polymod(int[] values) {
        int[] GEN = new int[]{0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;

        for (int v : values) {
            byte b = (byte) (chk >> 25);
            chk = ((chk & 0x1ffffff) << 5) ^ v;

            for (int i = 0; i < 5; i++) {
                if (((b >> i) & 1) != 0) {
                    chk ^= GEN[i];
                }
            }
        }

        return chk;
    }

    /**
     * 测试方法 - 从32字节公钥生成各种地址
     */
    public static void main(String[] args) {
        System.out.println("===========================================");
        System.out.println("比特币地址生成器测试");
        System.out.println("===========================================");

        // 示例32字节公钥(这是一个测试用的公钥)
        byte[] rawPublicKey = hexToBytes(
            "dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1"
        );

        System.out.println("\n输入公钥(32字节): " + bytesToHex(rawPublicKey));
        System.out.println("\n生成的地址:");

        // P2PKH地址
        String p2pkhAddr = generateP2PKHAddress(rawPublicKey);
        System.out.println("\nP2PKH (传统地址):");
        System.out.println("  " + p2pkhAddr);

        // P2SH地址
        String p2shAddr = generateP2SHAddress(rawPublicKey);
        System.out.println("\nP2SH (多签地址):");
        System.out.println("  " + p2shAddr);

        // P2WPKH地址
        String p2wpkhAddr = generateP2WPKHAddress(rawPublicKey);
        System.out.println("\nP2WPKH (原生SegWit地址):");
        System.out.println("  " + p2wpkhAddr);

        // P2WSH地址
        String p2wshAddr = generateP2WSHAddress(rawPublicKey);
        System.out.println("\nP2WSH (SegWit脚本地址):");
        System.out.println("  " + p2wshAddr);

        // P2TR地址
        String p2trAddr = generateP2TRAddress(rawPublicKey);
        System.out.println("\nP2TR (Taproot地址):");
        System.out.println("  " + p2trAddr);

        System.out.println("\n===========================================");
        System.out.println("脚本验证");
        System.out.println("===========================================");

        // 生成并验证各种脚本
        byte[] p2pkhScript = generateP2PKHScript(rawPublicKey);
        System.out.println("\nP2PKH脚本: " + bytesToHex(p2pkhScript));

        byte[] p2shScript = generateP2SHScript(rawPublicKey);
        System.out.println("P2SH脚本: " + bytesToHex(p2shScript));

        byte[] p2wpkhScript = generateP2WPKHScript(rawPublicKey);
        System.out.println("P2WPKH脚本: " + bytesToHex(p2wpkhScript));

        byte[] p2wshScript = generateP2WSHScript(rawPublicKey);
        System.out.println("P2WSH脚本: " + bytesToHex(p2wshScript));

        byte[] p2trScript = generateP2TRScript(rawPublicKey);
        System.out.println("P2TR脚本: " + bytesToHex(p2trScript));

        System.out.println("\n===========================================");
        System.out.println("脚本类型检测验证");
        System.out.println("===========================================");

        // 验证生成的脚本能否被正确识别
        validateScript(p2pkhScript, "P2PKH");
        validateScript(p2shScript, "P2SH");
        validateScript(p2wpkhScript, "P2WPKH");
        validateScript(p2wshScript, "P2WSH");
        validateScript(p2trScript, "P2TR");

        System.out.println("\n===========================================");
        System.out.println("倒推测试 - 从地址和脚本反推验证");
        System.out.println("===========================================");

        // 倒推测试:从脚本解析地址,验证是否与原始公钥一致
        testReverseValidation(rawPublicKey, p2pkhScript, ScriptType.P2PKH);
        testReverseValidation(rawPublicKey, p2shScript, ScriptType.P2SH);
        testReverseValidation(rawPublicKey, p2wpkhScript, ScriptType.P2WPKH);
        testReverseValidation(rawPublicKey, p2wshScript, ScriptType.P2WSH);
        testReverseValidation(rawPublicKey, p2trScript, ScriptType.P2TR);

        // 测试Address类的解析功能
        testAddressParsing(rawPublicKey, p2pkhScript, ScriptType.P2PKH);
        testAddressParsing(rawPublicKey, p2shScript, ScriptType.P2SH);
        testAddressParsing(rawPublicKey, p2wpkhScript, ScriptType.P2WPKH);
        testAddressParsing(rawPublicKey, p2wshScript, ScriptType.P2WSH);
        testAddressParsing(rawPublicKey, p2trScript, ScriptType.P2TR);

        System.out.println("\n===========================================");
        System.out.println("测试完成!");
        System.out.println("===========================================");


        Address add1 = Address.parseFromString("bc1qq54l3rgwtk8g20pqmtvwrwnaugm6zlrzkydvwgm");
        log.info("地址解析类型{}",add1.getScriptType());
    }

    /**
     * 倒推测试:从脚本解析并验证与原始公钥的哈希是否匹配
     */
    private static void testReverseValidation(byte[] rawPublicKey, byte[] script, ScriptType expectedType) {
        ScriptType detectedType = ScriptParser.detectScriptType(script);
        byte[] addressBytes = ScriptParser.extractAddress(script, detectedType);

        System.out.println("\n" + expectedType.name() + " 倒推测试:");

        if (addressBytes == null) {
            System.out.println("  ✗ 失败 - 无法提取地址字节");
            return;
        }

        // 根据类型计算期望的哈希值
        byte[] expectedHash;

        switch (expectedType) {
            case P2PKH:
                expectedHash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));
                break;
            case P2SH:
                byte[] redeemScript = new byte[rawPublicKey.length + 1];
                System.arraycopy(rawPublicKey, 0, redeemScript, 0, rawPublicKey.length);
                redeemScript[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;
                expectedHash = Sha.applyRIPEMD160(Sha.applySHA256(redeemScript));
                break;
            case P2WPKH:
                expectedHash = Sha.applyRIPEMD160(Sha.applySHA256(rawPublicKey));
                break;
            case P2WSH:
                byte[] redeemScript2 = new byte[rawPublicKey.length + 1];
                System.arraycopy(rawPublicKey, 0, redeemScript2, 0, rawPublicKey.length);
                redeemScript2[rawPublicKey.length] = (byte) ScriptParser.OP_CHECKSIG;
                expectedHash = Sha.applySHA256(redeemScript2);
                break;
            case P2TR:
                expectedHash = rawPublicKey; // P2TR直接使用32字节公钥
                break;
            default:
                System.out.println("  ✗ 失败 - 不支持的脚本类型");
                return;
        }

        // 比较哈希值
        boolean hashMatch = Arrays.equals(addressBytes, expectedHash);
        boolean typeMatch = detectedType == expectedType;

        System.out.println("  提取的地址字节: " + bytesToHex(addressBytes));
        System.out.println("  期望的哈希值: " + bytesToHex(expectedHash));
        System.out.println("  脚本类型检测: " + (typeMatch ? "✓ 正确" : "✗ 错误"));

        if (hashMatch) {
            System.out.println("  哈希值匹配: ✓ 通过");
        } else {
            System.out.println("  哈希值匹配: ✗ 失败");
        }

        System.out.println("  总体结果: " + ((hashMatch && typeMatch) ? "✓ 通过" : "✗ 失败"));
    }

    /**
     * 测试Address类的解析功能
     */
    private static void testAddressParsing(byte[] rawPublicKey, byte[] script, ScriptType expectedType) {
        System.out.println("\n" + expectedType.name() + " Address解析测试:");

        Address address = Address.parseFromScriptPubKey(script);

        if (address == null) {
            System.out.println("  ✗ 失败 - Address对象为null");
            return;
        }

        boolean isValid = address.isValid();
        boolean typeMatch = address.getScriptType() == expectedType;

        System.out.println("  脚本类型: " + address.getScriptType().name() + 
                          " (期望: " + expectedType.name() + ")");
        System.out.println("  地址长度: " + address.getAddressBytes().length + " 字节");
        System.out.println("  地址字节: " + address.toHex());
        System.out.println("  完整脚本: " + bytesToHex(address.getFullScript()));
        System.out.println("  有效性: " + (isValid ? "✓ 有效" : "✗ 无效"));
        System.out.println("  类型匹配: " + (typeMatch ? "✓ 正确" : "✗ 错误"));
        System.out.println("  总体结果: " + ((isValid && typeMatch) ? "✓ 通过" : "✗ 失败"));
    }

    /**
     * 验证生成的脚本
     */
    private static void validateScript(byte[] script, String expectedType) {
        ScriptType detectedType = ScriptParser.detectScriptType(script);
        boolean isValid = expectedType.equals(detectedType.name());
        System.out.println("\n" + expectedType + " 脚本检测: " +
                          (isValid ? "✓ 通过" : "✗ 失败"));
        if (!isValid) {
            System.out.println("  期望: " + expectedType);
            System.out.println("  实际: " + detectedType.name());
        }
    }



}
