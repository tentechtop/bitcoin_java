package com.bit.coin.validat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.bit.coin.utils.SerializeUtils.hexToBytes;

public class ValidatorSelectTest {
    public static void main(String[] args) {
        // 1. 构造测试验证者列表（3个验证者，32字节ID）
        List<Validator> validatorList = new ArrayList<>();
        validatorList.add(new Validator(hexToBytes("0000000000000000000000000000000000000000000000000000000000000000"), new BigDecimal("1000"), new BigDecimal("10")));
        validatorList.add(new Validator(hexToBytes("000000000d1b165bf5cb609ffc2702e189ce3b5139d8ff0c7579b5ea4db8bc80"), new BigDecimal("2000"), new BigDecimal("20")));
        validatorList.add(new Validator(hexToBytes("0000efd923697715b753f53ebba258dcae0905ef4d3f3bddc2e60b024c91a5c6"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("0000c930d8b1ee2ebcf31e3373929e9a5339d7818781677ed12f81b38bb0ecdb"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("00005d1c2a635c88a2d184fc1902f6ddeaffac9ea42a586a6591ce27a3a1719f"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("000076771542889040a979a870cc76ed9c87490149d2754676a52db1bc15668c"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("00005e7d4c246c2a623d816194f830090b4f192b77793ced7b0fd0e176a90275"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("0000d3892499bde3679322166fc852715ae00cd10ceaeae0a5c16d546b027430"), new BigDecimal("3000"), new BigDecimal("30")));
        validatorList.add(new Validator(hexToBytes("0000e654afe1f0d0bc1cb034801285c0b8d3a5b00cff37ea64f0b1522d97f6ca"), new BigDecimal("3000"), new BigDecimal("30")));




        // 2. 固定输入参数
        long prevHeight = 1000L;
        byte[] prevHash = new byte[32]; // 模拟上一个区块Hash
        long currentSlot = 5000L;

        // 3. 多次调用：验证【参数唯一→结果唯一】
        Validator v1 = ValidatorSelector.selectUniqueValidator(validatorList, prevHeight, prevHash, currentSlot);
        Validator v2 = ValidatorSelector.selectUniqueValidator(validatorList, prevHeight, prevHash, currentSlot);
        Validator v3 = ValidatorSelector.selectUniqueValidator(validatorList, prevHeight, prevHash, currentSlot);

        // 打印结果：三次选中的验证者完全相同
        System.out.println("第一次选中：" + v1);
        System.out.println("第二次选中：" + v2);
        System.out.println("第三次选中：" + v3);
        System.out.println("是否唯一选中：" + (v1 == v2 && v2 == v3));

        // 4. 修改参数：验证【参数变化→结果变化】
        currentSlot = 5001L;
        Validator v4 = ValidatorSelector.selectUniqueValidator(validatorList, prevHeight, prevHash, currentSlot);
        System.out.println("修改槽位后选中：" + v4);
    }

    // 生成随机32字节ID（测试用）
    private static byte[] random32Bytes() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }
}