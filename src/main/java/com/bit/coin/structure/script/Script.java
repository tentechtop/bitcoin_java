package com.bit.coin.structure.script;

import com.bit.coin.utils.Sha;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 兼容极简验证和脚本验证  即如果解锁脚本固定为20字节 且解锁脚本固定为97字节 则认为是P2PKH
 */
@Slf4j
@Data
public class Script {

    private List<ScriptElement> elements;

    public Script() {
        this.elements = new ArrayList<>();
    }

    public Script(List<ScriptElement> elements) {
        this.elements = new ArrayList<>(elements);
    }

    //添加操作码
    public void addOpCode(int opCode) {
        elements.add(new ScriptElement(opCode));
    }

    // 添加数据
    public void addData(byte[] data) {
        elements.add(new ScriptElement(data));
    }

    //序列化为二进制
    public byte[] serialize() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        for (ScriptElement element : elements) {
            if (element.isOpCode()) {
                int opCode = element.getOpCode();
                baos.write(opCode & 0xFF);
            } else {
                byte[] data = element.getData();
                if (data == null) {
                    baos.write(OP_0);
                } else {
                    writeData(baos, data);
                }
            }
        }

        return baos.toByteArray();
    }
    /**
     * 写入数据（带长度前缀）
     */
    private void writeData(ByteArrayOutputStream baos, byte[] data) throws IOException {
        int length = data.length;

        if (length <= 75) {
            // 单字节长度前缀
            baos.write(length);
        } else if (length <= 0xFF) {  // ≤ 255
            baos.write(OP_PUSHDATA1);
            baos.write(length);
        } else if (length <= 0xFFFF) {  // ≤ 65535
            baos.write(OP_PUSHDATA2);
            baos.write(length & 0xFF);
            baos.write((length >> 8) & 0xFF);
        } else {  // ≤ 2^32-1
            baos.write(OP_PUSHDATA4);
            baos.write(length & 0xFF);
            baos.write((length >> 8) & 0xFF);
            baos.write((length >> 16) & 0xFF);
            baos.write((length >> 24) & 0xFF);
        }
        baos.write(data);
    }



    //从二进制反序列化
    /**
     * 从字节数组反序列化
     */
    public static Script deserialize(byte[] bytes) throws IOException {
        return deserialize(bytes, 0, bytes.length);
    }

    /**
     * 从字节数组指定位置反序列化
     */
    public static Script deserialize(byte[] bytes, int offset, int length) throws IOException {
        //如果固定只有97长度 先尝试翻译成P2PKH解锁脚本
        //如果是创世 输入
        if (length == 97) {
            Script script = new Script();
            ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length)
                    .order(ByteOrder.LITTLE_ENDIAN);

            // 第一步：解析64字节签名（无长度前缀）
            byte[] signature = new byte[64];
            if (buffer.remaining() >= 64) {
                buffer.get(signature);
            } else {
                throw new IOException("P2PKH解锁脚本签名长度不足，期望64字节，剩余：" + buffer.remaining());
            }

            // 第二步：解析1字节签名类型
            byte sigType;
            if (buffer.remaining() >= 1) {
                sigType = buffer.get();
            } else {
                throw new IOException("P2PKH解锁脚本缺少签名类型字节");
            }

            // 合并签名+签名类型（符合脚本规范的65字节签名数据）
            byte[] signatureData = new byte[65];
            System.arraycopy(signature, 0, signatureData, 0, 64);
            signatureData[64] = sigType;
            script.addData(signatureData); // 添加到脚本元素

            // 第三步：解析32字节公钥（无长度前缀）
            byte[] pubKeyData = new byte[32];
            if (buffer.remaining() >= 32) {
                buffer.get(pubKeyData);
                script.addData(pubKeyData); // 添加公钥数据到脚本
            } else {
                throw new IOException("P2PKH解锁脚本公钥长度不足，期望32字节，剩余：" + buffer.remaining());
            }

            // 验证是否解析完所有数据
            if (buffer.remaining() != 0) {
                throw new IOException("P2PKH解锁脚本解析后仍有剩余数据，剩余字节数：" + buffer.remaining());
            }

            return script;
        }

        // 如果固定为20字节，解释为P2PKH锁定脚本（公钥哈希）
        if (length == 20) {
            // 复制20字节的公钥哈希（避免原数组被修改）
            byte[] pubKeyHash = new byte[20];
            System.arraycopy(bytes, offset, pubKeyHash, 0, 20);

            // 构建标准的P2PKH锁定脚本
            Script standardLockingScript = new Script();
            standardLockingScript.addOpCode(Script.OP_DUP);         // 0x76
            standardLockingScript.addOpCode(Script.OP_HASH160);     // 0xa9
            standardLockingScript.addData(pubKeyHash);              // 20字节公钥哈希
            standardLockingScript.addOpCode(Script.OP_EQUALVERIFY); // 0x88
            standardLockingScript.addOpCode(Script.OP_CHECKSIG);    // 0xac

            return standardLockingScript;
        }


        //如果长度是16 则是coinbase的锁定脚本
        if (length == 16) {
            Script coinbaseScript = new Script();
            // 复制16字节的coinbase数据（避免原数组引用被修改）
            byte[] coinbaseData = new byte[16];
            System.arraycopy(bytes, offset, coinbaseData, 0, 16);

            // Coinbase锁定脚本标准结构：OP_PUSHDATA1 + 0x10(16) + 16字节数据
            // 1. 添加OP_PUSHDATA1操作码（用于推送16字节数据，因为16>75不适用直接长度前缀）
            coinbaseScript.addOpCode(OP_PUSHDATA1);
            // 2. 添加数据长度标识（16字节）
            coinbaseScript.addData(new byte[]{(byte) 0x10});
            // 3. 添加16字节的coinbase核心数据
            coinbaseScript.addData(coinbaseData);
            return coinbaseScript;
        }

        if (length == 69){
            Script coinbaseScript = new Script();
            // 复制16字节的coinbase数据（避免原数组引用被修改）
            byte[] coinbaseData = new byte[16];
            System.arraycopy(bytes, offset, coinbaseData, 0, 16);

            // Coinbase锁定脚本标准结构：OP_PUSHDATA1 + 0x10(16) + 16字节数据
            // 1. 添加OP_PUSHDATA1操作码（用于推送16字节数据，因为16>75不适用直接长度前缀）
            coinbaseScript.addOpCode(OP_PUSHDATA1);
            // 2. 添加数据长度标识（16字节）
            coinbaseScript.addData(new byte[]{(byte) 0x10});
            // 3. 添加16字节的coinbase核心数据
            coinbaseScript.addData(coinbaseData);
            return coinbaseScript;
        }

        Script script = new Script();
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() > 0) {
            int opCode = buffer.get() & 0xFF;

            if (opCode > 0 && opCode <= 75) {
                // 直接长度前缀
                byte[] data = new byte[opCode];
                if (buffer.remaining() >= opCode) {
                    buffer.get(data);
                    script.addData(data);
                } else {
                    throw new IOException("数据长度不足，期望长度: " + opCode +
                            "，实际剩余: " + buffer.remaining());
                }
            } else if (opCode == OP_PUSHDATA1) {
                if (buffer.remaining() >= 1) {
                    int dataLength = buffer.get() & 0xFF;
                    byte[] data = new byte[dataLength];
                    if (buffer.remaining() >= dataLength) {
                        buffer.get(data);
                        script.addData(data);
                    } else {
                        throw new IOException("数据长度不足，期望长度: " + dataLength);
                    }
                } else {
                    throw new IOException("OP_PUSHDATA1缺少长度字节");
                }
            } else if (opCode == OP_PUSHDATA2) {
                if (buffer.remaining() >= 2) {
                    int dataLength = Short.toUnsignedInt(buffer.getShort());
                    byte[] data = new byte[dataLength];
                    if (buffer.remaining() >= dataLength) {
                        buffer.get(data);
                        script.addData(data);
                    } else {
                        throw new IOException("数据长度不足，期望长度: " + dataLength);
                    }
                } else {
                    throw new IOException("OP_PUSHDATA2缺少长度字节");
                }
            } else if (opCode == OP_PUSHDATA4) {
                if (buffer.remaining() >= 4) {
                    int dataLength = buffer.getInt();
                    if (dataLength < 0) {
                        throw new IOException("数据长度无效: " + dataLength);
                    }
                    byte[] data = new byte[dataLength];
                    if (buffer.remaining() >= dataLength) {
                        buffer.get(data);
                        script.addData(data);
                    } else {
                        throw new IOException("数据长度不足，期望长度: " + dataLength);
                    }
                } else {
                    throw new IOException("OP_PUSHDATA4缺少长度字节");
                }
            } else if (opCode == OP_1NEGATE) {
                script.addOpCode(OP_1NEGATE);
            } else if (opCode >= OP_1 && opCode <= OP_16) {
                script.addOpCode(opCode);
            } else if (opCode == OP_0) {
                script.addOpCode(OP_0);
            } else if (isValidOpCode(opCode)) {
                // 其他操作码
                script.addOpCode(opCode);
            } else {
                throw new IOException("无效的操作码: 0x" + Integer.toHexString(opCode));
            }
        }

        return script;
    }

    /**
     * 验证操作码是否有效
     */
    private static boolean isValidOpCode(int opCode) {
        return OP_CODE_NAMES.containsKey(opCode) ||
                (opCode >= 0x01 && opCode <= 0x4b) ||  // OP_PUSHBYTES1-75
                (opCode >= 0x4f && opCode <= 0x60) ||  // OP_1NEGATE到OP_16
                (opCode >= 0x61 && opCode <= 0xb2);    // 其他标准操作码
    }

    /**
     * 序列化为十六进制字符串
     */
    public String toHex() {
        try {
            byte[] bytes = serialize();
            return HexFormat.of().formatHex(bytes);
        } catch (IOException e) {
            throw new RuntimeException("序列化失败", e);
        }
    }

    /**
     * 从十六进制字符串解析
     */
    public static Script fromHex(String hex) throws IOException {
        byte[] bytes = HexFormat.of().parseHex(hex);
        return deserialize(bytes);
    }

    /**
     * 获取脚本的字符串表示
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            ScriptElement element = elements.get(i);
            if (element.isOpCode()) {
                int opCode = element.getOpCode();
                String opName = getOpCodeName(opCode);
                sb.append(opName);
            } else {
                byte[] data = element.getData();
                if (data == null) {
                    sb.append("OP_0");
                } else {
                    sb.append("0x");
                    if (data.length <= 4) {
                        // 小整数显示为十进制
                        BigInteger bi = new BigInteger(1, data);
                        sb.append(bi.toString(10));
                    } else if (data.length == 20) {
                        // 20字节可能是公钥哈希
                        sb.append(HexFormat.of().formatHex(data));
                    } else if (data.length == 33 || data.length == 65) {
                        // 可能是公钥
                        sb.append(HexFormat.of().formatHex(data));
                    } else {
                        sb.append(HexFormat.of().formatHex(data));
                    }
                }
            }
            if (i < elements.size() - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    /**
     * 获取操作码名称
     */
    public static String getOpCodeName(int opCode) {
        if (OP_CODE_NAMES.containsKey(opCode)) {
            return OP_CODE_NAMES.get(opCode);
        } else if (opCode == 0x00) {
            return "OP_0";
        } else if (opCode >= 0x01 && opCode <= 0x4b) {
            return String.format("OP_PUSHBYTES_%d", opCode);
        } else if (opCode == OP_PUSHDATA1) {
            return "OP_PUSHDATA1";
        } else if (opCode == OP_PUSHDATA2) {
            return "OP_PUSHDATA2";
        } else if (opCode == OP_PUSHDATA4) {
            return "OP_PUSHDATA4";
        } else {
            return String.format("OP_UNKNOWN(0x%02x)", opCode);
        }
    }

    /**
     * 获取脚本字节大小
     */
    public int getSize() {
        int size = 0;
        for (ScriptElement element : elements) {
            if (element.isOpCode()) {
                size += 1;  // 操作码占1字节
            } else {
                byte[] data = element.getData();
                if (data == null) {
                    size += 1;  // OP_0
                } else {
                    int dataLength = data.length;
                    if (dataLength <= 75) {
                        size += 1;  // 长度前缀
                    } else if (dataLength <= 0xFF) {
                        size += 2;  // OP_PUSHDATA1 + 1字节长度
                    } else if (dataLength <= 0xFFFF) {
                        size += 3;  // OP_PUSHDATA2 + 2字节长度
                    } else {
                        size += 5;  // OP_PUSHDATA4 + 4字节长度
                    }
                    size += dataLength;
                }
            }
        }
        return size;
    }

    /**
     * 添加小整数（0-16）
     */
    public void addSmallInteger(int n) {
        if (n == -1) {
            addOpCode(OP_1NEGATE); // 0x4f
        } else if (n == 0) {
            addOpCode(OP_0); // 0x00
        } else if (n >= 1 && n <= 16) {
            // 正确的OP_1~OP_16：0x51~0x60（OP_1=0x51, OP_2=0x52...OP_16=0x60）
            addOpCode(OP_1 + (n - 1));
        } else {
            throw new IllegalArgumentException("整数必须在-1到16之间: " + n);
        }
    }

    /**
     * 添加大整数
     */
    public void addInteger(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            addOpCode(OP_0);
        } else if (n.equals(BigInteger.ONE.negate())) {
            addOpCode(OP_1NEGATE);
        } else if (n.compareTo(BigInteger.ZERO) > 0 && n.compareTo(BigInteger.valueOf(16)) <= 0) {
            addSmallInteger(n.intValue());
        } else {
            // 将大整数转换为字节数组
            byte[] bytes = n.toByteArray();

            // 移除前导零（除非是负数）
            int start = 0;
            if (bytes.length > 1 && bytes[0] == 0 && bytes[1] >= 0) {
                start = 1;
            }

            byte[] trimmed = Arrays.copyOfRange(bytes, start, bytes.length);
            addData(trimmed);
        }
    }



    //main
    public static void main(String[] args) throws IOException {
        byte[] bytes2 = Base58.decode("J5JGY238f6TGBuscTBs5AQjDu6Jpau1mUaFYq89ty3GR");
        log.info("公钥Hash{}",bytesToHex(bytes2));
        byte[] bytes3 = Sha.applyRIPEMD160(Sha.applySHA256(bytes2));
        log.info("解锁脚本{}",bytesToHex(bytes3));

        Script p2pkhLockingScript = new Script();
        p2pkhLockingScript.addOpCode(Script.OP_DUP);         // 0x76
        p2pkhLockingScript.addOpCode(Script.OP_HASH160);     // 0xa9
        p2pkhLockingScript.addData(bytes3);             // 20字节公钥哈希
        p2pkhLockingScript.addOpCode(Script.OP_EQUALVERIFY); // 0x88
        p2pkhLockingScript.addOpCode(Script.OP_CHECKSIG);    // 0xac

        String string = p2pkhLockingScript.toString();
        log.info("打印脚本{}",string);
        log.info("获取脚本大小{}",p2pkhLockingScript.getSize());
        log.info("toHex{}",p2pkhLockingScript.toHex());
        Script script = fromHex("76a91460c275b1ff20de69f3099596fa75a0a7fd439e2c88ac");
        log.info("fromHex{}",script.toHex());
        byte[] serialize = p2pkhLockingScript.serialize();
        log.info("serialize{}",bytesToHex(serialize));
        Script deserialize = deserialize(hexToBytes("76a91460c275b1ff20de69f3099596fa75a0a7fd439e2c88ac"));
        log.info("fromHex{}",deserialize.toHex());



    }

    /**
     * 获取签名类型（仅适用于P2PKH解锁脚本）
     * 签名类型是65字节签名数据的最后1字节（哈希类型：SIGHASH_ALL/SIGHASH_NONE等）
     * @return 签名类型字节
     * @throws IllegalStateException 非P2PKH解锁脚本或签名数据异常时抛出
     */
    public byte getSignatureType() {
        // 1. 校验脚本元素结构：P2PKH解锁脚本应包含2个元素（65字节签名 + 32字节公钥）
        if (elements == null || elements.size() != 2) {
            throw new IllegalStateException("非P2PKH解锁脚本，无法提取签名类型（期望2个元素，实际：" +
                    (elements == null ? 0 : elements.size()) + "）");
        }

        // 2. 获取第一个元素（签名数据）并校验类型
        ScriptElement signatureElement = elements.get(0);
        if (signatureElement.isOpCode()) {
            throw new IllegalStateException("第一个元素应为签名数据，而非操作码");
        }

        // 3. 获取签名数据并校验长度（标准P2PKH签名数据为65字节：64字节签名 + 1字节类型）
        byte[] signatureData = signatureElement.getData();
        if (signatureData == null || signatureData.length != 65) {
            throw new IllegalStateException("签名数据长度异常（期望65字节，实际：" +
                    (signatureData == null ? 0 : signatureData.length) + "）");
        }

        // 4. 提取最后1字节作为签名类型（SIGHASH类型）
        return signatureData[64];
    }


    public static class ScriptElement {
        private int opCode;
        private byte[] data;
        private boolean isOpCode;

        // 添加无参构造函数
        public ScriptElement() {

        }

        public ScriptElement(int opCode) {
            this.opCode = opCode;
            this.data = null;
            this.isOpCode = true;
        }

        public ScriptElement(byte[] data) {
            this.opCode = 0;
            this.data = data != null ? data.clone() : null;
            this.isOpCode = false;
        }

        public boolean isOpCode() {
            return isOpCode;
        }

        public int getOpCode() {
            if (!isOpCode) throw new IllegalStateException("不是操作码");
            return opCode;
        }

        public byte[] getData() {
            if (isOpCode) throw new IllegalStateException("不是数据");
            return data != null ? data.clone() : null;
        }
    }


    public static final int OP_0 = 0x00;
    public static final int OP_FALSE = OP_0;
    public static final int OP_PUSHDATA1 = 0x4c;
    public static final int OP_PUSHDATA2 = 0x4d;
    public static final int OP_PUSHDATA4 = 0x4e;
    public static final int OP_1NEGATE = 0x4f;
    public static final int OP_1 = 0x51;
    public static final int OP_TRUE = OP_1;
    public static final int OP_2 = 0x52;
    public static final int OP_3 = 0x53;
    public static final int OP_4 = 0x54;
    public static final int OP_5 = 0x55;
    public static final int OP_6 = 0x56;
    public static final int OP_7 = 0x57;
    public static final int OP_8 = 0x58;
    public static final int OP_9 = 0x59;
    public static final int OP_10 = 0x5a;
    public static final int OP_11 = 0x5b;
    public static final int OP_12 = 0x5c;
    public static final int OP_13 = 0x5d;
    public static final int OP_14 = 0x5e;
    public static final int OP_15 = 0x5f;
    public static final int OP_16 = 0x60;

    // 控制流操作码
    public static final int OP_IF = 0x63;// 条件判断
    public static final int OP_NOTIF = 0x64;// 逻辑非
    public static final int OP_ELSE = 0x67;
    public static final int OP_ENDIF = 0x68;
    public static final int OP_RETURN = 0x6a;

    // 栈操作码
    public static final int OP_TOALTSTACK = 0x6b;
    public static final int OP_FROMALTSTACK = 0x6c;
    public static final int OP_2DROP = 0x6d;
    public static final int OP_2DUP = 0x6e;
    public static final int OP_3DUP = 0x6f;
    public static final int OP_2OVER = 0x70;
    public static final int OP_2ROT = 0x71;
    public static final int OP_2SWAP = 0x72;
    public static final int OP_IFDUP = 0x73;
    public static final int OP_DEPTH = 0x74;
    public static final int OP_DROP = 0x75;

    public static final int OP_DUP2 = 0x7e;
    public static final int OP_DUP = 0x76;
    public static final int OP_NIP = 0x77;
    public static final int OP_OVER = 0x78;
    public static final int OP_PICK = 0x79;
    public static final int OP_ROLL = 0x7a;
    public static final int OP_ROT = 0x7b;
    public static final int OP_SWAP = 0x7c;
    public static final int OP_TUCK = 0x7d;

    // 切片操作码
    public static final int OP_SIZE = 0x82;

    // 位逻辑操作码
    public static final int OP_INVERT = 0x83;
    public static final int OP_AND = 0x84;
    public static final int OP_OR = 0x85;
    public static final int OP_XOR = 0x86;
    public static final int OP_EQUAL = 0x87;
    public static final int OP_EQUALVERIFY = 0x88;

    // 数值操作码
    public static final int OP_1ADD = 0x8b;
    public static final int OP_1SUB = 0x8c;
    public static final int OP_2MUL = 0x8d;
    public static final int OP_2DIV = 0x8e;
    public static final int OP_NEGATE = 0x8f;
    public static final int OP_ABS = 0x90;
    public static final int OP_NOT = 0x91;
    public static final int OP_0NOTEQUAL = 0x92;
    public static final int OP_ADD = 0x93;
    public static final int OP_SUB = 0x94;
    public static final int OP_MUL = 0x95;
    public static final int OP_DIV = 0x96;
    public static final int OP_MOD = 0x97;
    public static final int OP_LSHIFT = 0x98;
    public static final int OP_RSHIFT = 0x99;
    public static final int OP_BOOLAND = 0x9a;
    public static final int OP_BOOLOR = 0x9b;
    public static final int OP_NUMEQUAL = 0x9c;
    public static final int OP_NUMEQUALVERIFY = 0x9d;
    public static final int OP_NUMNOTEQUAL = 0x9e;
    public static final int OP_LESSTHAN = 0x9f;
    public static final int OP_GREATERTHAN = 0xa0;
    public static final int OP_LESSTHANOREQUAL = 0xa1;
    public static final int OP_GREATERTHANOREQUAL = 0xa2;
    public static final int OP_MIN = 0xa3;
    public static final int OP_MAX = 0xa4;
    public static final int OP_WITHIN = 0xa5;

    // 密码学操作码
    public static final int OP_RIPEMD160 = 0xa6;
    public static final int OP_SHA256 = 0xa8;
    public static final int OP_HASH160 = 0xa9;
    public static final int OP_HASH256 = 0xaa;
    public static final int OP_CODESEPARATOR = 0xab;
    public static final int OP_CHECKSIG = 0xac;
    public static final int OP_CHECKSIGVERIFY = 0xad;
    public static final int OP_CHECKMULTISIG = 0xae;
    public static final int OP_CHECKMULTISIGVERIFY = 0xaf;

    // 锁定时间操作码
    public static final int OP_NOP = 0x61;
    public static final int OP_CHECKLOCKTIMEVERIFY = 0xb1;
    public static final int OP_CHECKSEQUENCEVERIFY = 0xb2;

    private static final Map<Integer, String> OP_CODE_NAMES = new HashMap<>();
    static {
        // 初始化常见操作码的名称
        OP_CODE_NAMES.put(OP_0, "OP_0");
        OP_CODE_NAMES.put(OP_1, "OP_1");
        OP_CODE_NAMES.put(OP_2, "OP_2");
        OP_CODE_NAMES.put(OP_3, "OP_3");
        OP_CODE_NAMES.put(OP_4, "OP_4");
        OP_CODE_NAMES.put(OP_5, "OP_5");
        OP_CODE_NAMES.put(OP_6, "OP_6");
        OP_CODE_NAMES.put(OP_7, "OP_7");
        OP_CODE_NAMES.put(OP_8, "OP_8");
        OP_CODE_NAMES.put(OP_9, "OP_9");
        OP_CODE_NAMES.put(OP_10, "OP_10");
        OP_CODE_NAMES.put(OP_11, "OP_11");
        OP_CODE_NAMES.put(OP_12, "OP_12");
        OP_CODE_NAMES.put(OP_13, "OP_13");
        OP_CODE_NAMES.put(OP_14, "OP_14");
        OP_CODE_NAMES.put(OP_15, "OP_15");
        OP_CODE_NAMES.put(OP_16, "OP_16");
        OP_CODE_NAMES.put(OP_1NEGATE, "OP_1NEGATE");
        OP_CODE_NAMES.put(OP_PUSHDATA1, "OP_PUSHDATA1");
        OP_CODE_NAMES.put(OP_PUSHDATA2, "OP_PUSHDATA2");
        OP_CODE_NAMES.put(OP_PUSHDATA4, "OP_PUSHDATA4");

        // 控制流操作码
        OP_CODE_NAMES.put(OP_IF, "OP_IF");
        OP_CODE_NAMES.put(OP_NOTIF, "OP_NOTIF");
        OP_CODE_NAMES.put(OP_ELSE, "OP_ELSE");
        OP_CODE_NAMES.put(OP_ENDIF, "OP_ENDIF");
        OP_CODE_NAMES.put(OP_RETURN, "OP_RETURN");

        // 栈操作码
        OP_CODE_NAMES.put(OP_DUP, "OP_DUP");
        OP_CODE_NAMES.put(OP_DROP, "OP_DROP");
        OP_CODE_NAMES.put(OP_SWAP, "OP_SWAP");
        OP_CODE_NAMES.put(OP_OVER, "OP_OVER");
        OP_CODE_NAMES.put(OP_ROT, "OP_ROT");
        OP_CODE_NAMES.put(OP_TUCK, "OP_TUCK");
        OP_CODE_NAMES.put(OP_PICK, "OP_PICK");
        OP_CODE_NAMES.put(OP_ROLL, "OP_ROLL");
        OP_CODE_NAMES.put(OP_TOALTSTACK, "OP_TOALTSTACK");
        OP_CODE_NAMES.put(OP_FROMALTSTACK, "OP_FROMALTSTACK");
        OP_CODE_NAMES.put(OP_2DROP, "OP_2DROP");
        OP_CODE_NAMES.put(OP_2DUP, "OP_2DUP");
        OP_CODE_NAMES.put(OP_3DUP, "OP_3DUP");
        OP_CODE_NAMES.put(OP_2OVER, "OP_2OVER");
        OP_CODE_NAMES.put(OP_2ROT, "OP_2ROT");
        OP_CODE_NAMES.put(OP_2SWAP, "OP_2SWAP");
        OP_CODE_NAMES.put(OP_IFDUP, "OP_IFDUP");
        OP_CODE_NAMES.put(OP_DEPTH, "OP_DEPTH");
        OP_CODE_NAMES.put(OP_NIP, "OP_NIP");
        OP_CODE_NAMES.put(OP_DUP2, "OP_DUP2");

        // 切片操作码
        OP_CODE_NAMES.put(OP_SIZE, "OP_SIZE");

        // 位逻辑操作码
        OP_CODE_NAMES.put(OP_INVERT, "OP_INVERT");
        OP_CODE_NAMES.put(OP_AND, "OP_AND");
        OP_CODE_NAMES.put(OP_OR, "OP_OR");
        OP_CODE_NAMES.put(OP_XOR, "OP_XOR");
        OP_CODE_NAMES.put(OP_EQUAL, "OP_EQUAL");
        OP_CODE_NAMES.put(OP_EQUALVERIFY, "OP_EQUALVERIFY");

        // 数值操作码
        OP_CODE_NAMES.put(OP_1ADD, "OP_1ADD");
        OP_CODE_NAMES.put(OP_1SUB, "OP_1SUB");
        OP_CODE_NAMES.put(OP_2MUL, "OP_2MUL");
        OP_CODE_NAMES.put(OP_2DIV, "OP_2DIV");
        OP_CODE_NAMES.put(OP_NEGATE, "OP_NEGATE");
        OP_CODE_NAMES.put(OP_ABS, "OP_ABS");
        OP_CODE_NAMES.put(OP_NOT, "OP_NOT");
        OP_CODE_NAMES.put(OP_0NOTEQUAL, "OP_0NOTEQUAL");
        OP_CODE_NAMES.put(OP_ADD, "OP_ADD");
        OP_CODE_NAMES.put(OP_SUB, "OP_SUB");
        OP_CODE_NAMES.put(OP_MUL, "OP_MUL");
        OP_CODE_NAMES.put(OP_DIV, "OP_DIV");
        OP_CODE_NAMES.put(OP_MOD, "OP_MOD");
        OP_CODE_NAMES.put(OP_LSHIFT, "OP_LSHIFT");
        OP_CODE_NAMES.put(OP_RSHIFT, "OP_RSHIFT");
        OP_CODE_NAMES.put(OP_BOOLAND, "OP_BOOLAND");
        OP_CODE_NAMES.put(OP_BOOLOR, "OP_BOOLOR");
        OP_CODE_NAMES.put(OP_NUMEQUAL, "OP_NUMEQUAL");
        OP_CODE_NAMES.put(OP_NUMEQUALVERIFY, "OP_NUMEQUALVERIFY");
        OP_CODE_NAMES.put(OP_NUMNOTEQUAL, "OP_NUMNOTEQUAL");
        OP_CODE_NAMES.put(OP_LESSTHAN, "OP_LESSTHAN");
        OP_CODE_NAMES.put(OP_GREATERTHAN, "OP_GREATERTHAN");
        OP_CODE_NAMES.put(OP_LESSTHANOREQUAL, "OP_LESSTHANOREQUAL");
        OP_CODE_NAMES.put(OP_GREATERTHANOREQUAL, "OP_GREATERTHANOREQUAL");
        OP_CODE_NAMES.put(OP_MIN, "OP_MIN");
        OP_CODE_NAMES.put(OP_MAX, "OP_MAX");
        OP_CODE_NAMES.put(OP_WITHIN, "OP_WITHIN");

        // 密码学操作码
        OP_CODE_NAMES.put(OP_RIPEMD160, "OP_RIPEMD160");
        OP_CODE_NAMES.put(OP_SHA256, "OP_SHA256");
        OP_CODE_NAMES.put(OP_HASH160, "OP_HASH160");
        OP_CODE_NAMES.put(OP_HASH256, "OP_HASH256");
        OP_CODE_NAMES.put(OP_CODESEPARATOR, "OP_CODESEPARATOR");
        OP_CODE_NAMES.put(OP_CHECKSIG, "OP_CHECKSIG");
        OP_CODE_NAMES.put(OP_CHECKSIGVERIFY, "OP_CHECKSIGVERIFY");
        OP_CODE_NAMES.put(OP_CHECKMULTISIG, "OP_CHECKMULTISIG");
        OP_CODE_NAMES.put(OP_CHECKMULTISIGVERIFY, "OP_CHECKMULTISIGVERIFY");

        // 锁定时间操作码
        OP_CODE_NAMES.put(OP_NOP, "OP_NOP");
        OP_CODE_NAMES.put(OP_CHECKLOCKTIMEVERIFY, "OP_CHECKLOCKTIMEVERIFY");
        OP_CODE_NAMES.put(OP_CHECKSEQUENCEVERIFY, "OP_CHECKSEQUENCEVERIFY");
    }
}
