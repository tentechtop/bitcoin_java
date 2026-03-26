package com.bit.coin.validat;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 区块链确定性验证者选择器
 * 核心保证：输入参数唯一 → 输出验证者绝对唯一
 */
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ValidatorSelector {

    /**
     * 原始：单次选举主验证者
     */
    public static Validator selectUniqueValidator(
            List<Validator> validators,
            long prevHeight,
            byte[] prevHash,
            long currentSlot
    ) {
        List<Validator> valid = filterValid(validators);
        byte[] seed = buildSeed(prevHeight, prevHash, currentSlot);
        return selectByWeight(valid, seed);
    }

    /**
     * 【核心】顺延选举：
     * 依次处理未出块验证者 -> 惩罚 -> 排除 -> 选下一个
     * 直到有人出块 或 无可用验证者
     */
    public static Validator selectInOrderUntilProduce(
            List<Validator> allValidators,
            long prevHeight,
            byte[] prevHash,
            long currentSlot
    ) {
        List<Validator> excluded = new ArrayList<>();
        Validator current;

        while (true) {
            // 1. 过滤：有效权重 + 排除已失效的验证者
            List<Validator> candidates = filterValid(allValidators).stream()
                    .filter(v -> !excluded.contains(v))
                    .collect(Collectors.toList());

            if (candidates.isEmpty()) {
                throw new RuntimeException("当前slot无可用验证者，链出块中断");
            }

            // 2. 选举当前顺位验证者
            current = selectByWeight(candidates, buildSeed(prevHeight, prevHash, currentSlot));
            System.out.println("\n当前顺位验证者：" + current);

            // ======================
            // 模拟：验证者未出块 → 惩罚并加入排除列表，继续循环
            // 实际业务中这里替换为：检查链上是否出块
            // ======================
            System.out.println("结果：该验证者未出块，顺延下一位");
            current.punishForMissSlot();
            excluded.add(current);
        }
    }

    // ==================== 内部工具 ====================
    // 过滤有效验证者（权重>0）
    private static List<Validator> filterValid(List<Validator> list) {
        return list.stream()
                .filter(v -> v.getStakeWeight().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
    }

    // 构造唯一种子
    private static byte[] buildSeed(long h, byte[] hash, long slot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(longToBytes(h));
            md.update(hash);
            md.update(longToBytes(slot));
            return md.digest();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 加权选举
    private static Validator selectByWeight(List<Validator> list, byte[] seed) {
        BigDecimal total = list.stream().map(Validator::getStakeWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigInteger idx = new BigInteger(1, seed).mod(total.toBigInteger());

        BigDecimal sum = BigDecimal.ZERO;
        for (Validator v : list) {
            sum = sum.add(v.getStakeWeight());
            if (sum.toBigInteger().compareTo(idx) >= 0) {
                return v;
            }
        }
        return list.get(0);
    }

    private static byte[] longToBytes(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xff);
            v >>= 8;
        }
        return b;
    }
}