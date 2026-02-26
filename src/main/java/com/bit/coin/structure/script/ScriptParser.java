package com.bit.coin.structure.script;

import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static com.bit.coin.structure.script.Script.*;

/**
 * 脚本解析器
 * 用于解析比特币脚本,提取地址信息
 */
@Slf4j
public class ScriptParser {



    /**
     * 检测脚本类型
     * 基于操作码和脚本结构进行精确识别
     */
    public static ScriptType detectScriptType(byte[] scriptPubKey) {
        if (scriptPubKey == null || scriptPubKey.length == 0) {
            return ScriptType.UNKNOWN;
        }

        try {
            // 兼容：如果直接是20字节的公钥哈希，解析为P2PKH
            if (scriptPubKey.length == 20) {
                return ScriptType.P2PKH;
            }

            // P2PKH: OP_DUP OP_HASH160 <20字节hash> OP_EQUALVERIFY OP_CHECKSIG
            // 格式: 76 A9 14 <20字节> 88 AC (总共25字节)
            if (scriptPubKey.length == 25
                    && (scriptPubKey[0] & 0xFF) == OP_DUP
                    && (scriptPubKey[1] & 0xFF) == OP_HASH160
                    && scriptPubKey[2] == 0x14 // 长度前缀: 20字节
                    && (scriptPubKey[23] & 0xFF) == OP_EQUALVERIFY
                    && (scriptPubKey[24] & 0xFF) == OP_CHECKSIG) {
                return ScriptType.P2PKH;
            }

            // P2SH: OP_HASH160 <20字节hash> OP_EQUAL
            // 格式: A9 14 <20字节> 87 (总共23字节)
            if (scriptPubKey.length == 23
                    && (scriptPubKey[0] & 0xFF) == OP_HASH160
                    && scriptPubKey[1] == 0x14 // 长度前缀: 20字节
                    && (scriptPubKey[22] & 0xFF) == OP_EQUAL) {
                return ScriptType.P2SH;
            }

            // P2WPKH (Witness v0): OP_0 <20字节>
            // 格式: 00 14 <20字节> (总共22字节)
            if (scriptPubKey.length == 22
                    && scriptPubKey[0] == OP_0
                    && scriptPubKey[1] == 0x14) {
                return ScriptType.P2WPKH;
            }

            // P2WSH (Witness v0 Script Hash): OP_0 <32字节>
            // 格式: 00 20 <32字节> (总共34字节)
            if (scriptPubKey.length == 34
                    && scriptPubKey[0] == OP_0
                    && scriptPubKey[1] == 0x20) {
                return ScriptType.P2WSH;
            }

            // P2TR (Taproot): OP_1 <32字节>
            // 格式: 51 20 <32字节> (总共34字节)
            if (scriptPubKey.length == 34
                    && scriptPubKey[0] == OP_1
                    && scriptPubKey[1] == 0x20) {
                return ScriptType.P2TR;
            }

            // P2PK (Pay to Public Key): <公钥> OP_CHECKSIG
            // 格式: <33或65字节> AC
            if (scriptPubKey.length >= 34
                    && (scriptPubKey[scriptPubKey.length - 1] & 0xFF) == OP_CHECKSIG) {
                int pubKeyLen = scriptPubKey.length - 1;
                if (pubKeyLen == 33 || pubKeyLen == 65) {
                    // 验证是否为有效公钥
                    if (isValidPublicKeyLength(pubKeyLen)) {
                        return ScriptType.P2PK;
                    }
                }
            }

        } catch (Exception e) {
            log.warn("检测脚本类型时发生异常", e);
        }

        return ScriptType.UNKNOWN;
    }

    /**
     * 从scriptPubKey提取地址字节
     */
    public static byte[] extractAddress(byte[] scriptPubKey, ScriptType type) {
        if (type == null || scriptPubKey == null || type == ScriptType.UNKNOWN) {
            return null;
        }

        try {
            switch (type) {
                case P2PKH:
                    // 兼容：如果直接是20字节的公钥哈希，直接返回
                    if (scriptPubKey.length == 20) {
                        return scriptPubKey.clone();
                    }
                    // P2PKH标准脚本: 返回第3-22字节的20字节hash
                    if (scriptPubKey.length >= 23) {
                        return Arrays.copyOfRange(scriptPubKey, 3, 23);
                    }
                    break;

                case P2SH:
                    // P2SH: 返回第2-21字节的20字节hash
                    if (scriptPubKey.length >= 22) {
                        return Arrays.copyOfRange(scriptPubKey, 2, 22);
                    }
                    break;

                case P2WPKH:
                    // P2WPKH: 返回第2-21字节的20字节hash
                    if (scriptPubKey.length >= 22) {
                        return Arrays.copyOfRange(scriptPubKey, 2, 22);
                    }
                    break;

                case P2WSH:
                    // P2WSH: 返回第2-33字节的32字节hash
                    if (scriptPubKey.length >= 34) {
                        return Arrays.copyOfRange(scriptPubKey, 2, 34);
                    }
                    break;

                case P2TR:
                    // P2TR: 返回第2-33字节的32字节公钥
                    if (scriptPubKey.length >= 34) {
                        return Arrays.copyOfRange(scriptPubKey, 2, 34);
                    }
                    break;

                case P2PK:
                    // P2PK: 返回完整的公钥(压缩33字节或非压缩65字节)
                    int pubKeyLen = scriptPubKey.length - 1;
                    if (pubKeyLen > 0 && isValidPublicKeyLength(pubKeyLen)) {
                        return Arrays.copyOfRange(scriptPubKey, 0, pubKeyLen);
                    }
                    break;

                default:
                    log.debug("未知的脚本类型: {}", type);
                    return null;
            }
        } catch (Exception e) {
            log.error("提取地址时发生异常,脚本类型: {}", type, e);
        }

        return null;
    }

    /**
     * 从scriptSig提取发送方地址(仅适用于P2PKH等简单脚本)
     * 注意:这只能推断P2PKH,复杂脚本(P2SH/P2WSH等)无法从scriptSig推断
     */
    public static byte[] extractSenderAddress(byte[] scriptSig) {
        if (scriptSig == null || scriptSig.length < 33) {
            return null;
        }

        try {
            // 尝试解析P2PKH签名格式: <签名> <公钥>
            // DER编码的签名通常70-73字节,压缩公钥33字节,非压缩公钥65字节

            // 1. 尝试提取压缩公钥(以0x02或0x03开头)
            int possiblePubKeyOffset = scriptSig.length - 33;
            if (possiblePubKeyOffset > 0 && scriptSig.length > 33) {
                // 检查可能的公钥起始位置
                for (int i = possiblePubKeyOffset; i >= 1 && i <= scriptSig.length - 33; i++) {
                    byte firstByte = scriptSig[i];
                    if (firstByte == 0x02 || firstByte == 0x03) {
                        byte[] pubKey = Arrays.copyOfRange(scriptSig, i, scriptSig.length);
                        if (isValidPublicKeyLength(pubKey.length)) {
                            return Sha.applyRIPEMD160(Sha.applySHA256(pubKey));
                        }
                    }
                }
            }

            // 2. 尝试提取非压缩公钥(以0x04开头)
            if (scriptSig.length >= 66) {
                possiblePubKeyOffset = scriptSig.length - 65;
                for (int i = possiblePubKeyOffset; i >= 1 && i <= scriptSig.length - 65; i++) {
                    byte firstByte = scriptSig[i];
                    if (firstByte == 0x04) {
                        byte[] pubKey = Arrays.copyOfRange(scriptSig, i, scriptSig.length);
                        if (isValidPublicKeyLength(pubKey.length)) {
                            return Sha.applyRIPEMD160(Sha.applySHA256(pubKey));
                        }
                    }
                }
            }

            // 3. 兜底方案:如果最后33字节看起来像压缩公钥
            if (scriptSig.length >= 33) {
                int last33Start = scriptSig.length - 33;
                byte firstByte = scriptSig[last33Start];
                if (firstByte == 0x02 || firstByte == 0x03) {
                    byte[] pubKey = Arrays.copyOfRange(scriptSig, last33Start, scriptSig.length);
                    return Sha.applyRIPEMD160(Sha.applySHA256(pubKey));
                }
            }

        } catch (Exception e) {
            log.error("从scriptSig提取发送方地址时发生异常", e);
        }

        return null;
    }

    /**
     * 验证公钥长度是否有效
     */
    private static boolean isValidPublicKeyLength(int length) {
        return length == 33 || length == 65;
    }

    /**
     * 检查scriptPubKey是否为标准脚本
     */
    public static boolean isStandardScript(byte[] scriptPubKey) {
        ScriptType type = detectScriptType(scriptPubKey);
        return type != ScriptType.UNKNOWN;
    }

    /**
     * 获取脚本的描述信息
     */
    public static String getScriptDescription(byte[] scriptPubKey) {
        ScriptType type = detectScriptType(scriptPubKey);
        if (type != ScriptType.UNKNOWN) {
            return type.getDescription() + " (" + scriptPubKey.length + " bytes)";
        }
        return "Unknown script (" + scriptPubKey.length + " bytes)";
    }
}
