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
public class ValidatorSelector {

    /**
     * 计算唯一选中的验证者
     * @param validatorList 验证者列表
     * @param prevBlockHeight 上一个区块高度
     * @param prevBlockHash 上一个区块Hash
     * @param currentSlot 当前槽位
     * @return 唯一选中的验证者
     */
    public static Validator selectUniqueValidator(
            List<Validator> validatorList,
            long prevBlockHeight,
            byte[] prevBlockHash,
            long currentSlot
    ) {
        // 1. 基础参数校验
        if (validatorList == null || validatorList.isEmpty()) {
            throw new IllegalArgumentException("验证者列表不能为空！");
        }
        if (prevBlockHash == null || prevBlockHash.length == 0) {
            throw new IllegalArgumentException("上一个区块Hash不能为空！");
        }

        // 2. 过滤无效验证者（权重≤0无出块资格）
        List<Validator> validValidators = validatorList.stream()
                .filter(v -> v.getStakeWeight().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());

        if (validValidators.isEmpty()) {
            throw new IllegalStateException("所有验证者权重为0，无有效出块节点！");
        }

        // 3. 生成【唯一确定性种子】（输入唯一→种子唯一）
        byte[] seed = generateDeterministicSeed(prevBlockHeight, prevBlockHash, currentSlot);

        // 4. 计算总质押权重
        BigDecimal totalWeight = calculateTotalWeight(validValidators);

        // 5. 计算确定性偏移量（种子转大整数 → 对总权重取模）
        BigInteger seedBigInt = new BigInteger(1, seed); // 正数
        BigInteger offset = seedBigInt.mod(totalWeight.toBigInteger());

        // 6. 加权轮询：找到唯一选中的验证者
        return selectByWeight(validValidators, offset);
    }

    /**
     * 生成确定性种子：SHA-256哈希（无随机数，纯输入拼接）
     * 拼接规则：区块高度 + 区块Hash + 当前槽位
     */
    private static byte[] generateDeterministicSeed(long prevHeight, byte[] prevHash, long slot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 拼接所有关键参数（保证输入唯一则哈希唯一）
            digest.update(longToBytes(prevHeight));
            digest.update(prevHash);
            digest.update(longToBytes(slot));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不可用！", e);
        }
    }

    /**
     * 计算有效验证者的总权重
     */
    private static BigDecimal calculateTotalWeight(List<Validator> validators) {
        BigDecimal total = BigDecimal.ZERO;
        for (Validator v : validators) {
            total = total.add(v.getStakeWeight());
        }
        return total;
    }

    /**
     * 加权轮询选择：累加权重，首次超过偏移量即为选中者
     */
    private static Validator selectByWeight(List<Validator> validators, BigInteger offset) {
        BigDecimal accumulated = BigDecimal.ZERO;
        BigInteger offsetBD = new BigDecimal(offset).toBigInteger();

        for (Validator v : validators) {
            accumulated = accumulated.add(v.getStakeWeight());
            // 累加权重 ≥ 偏移量 → 选中该验证者
            if (accumulated.toBigInteger().compareTo(offsetBD) >= 0) {
                return v;
            }
        }
        // 理论上不会执行到这里（容错）
        return validators.get(0);
    }

    /**
     * long转byte数组（种子拼接用）
     */
    private static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }
}