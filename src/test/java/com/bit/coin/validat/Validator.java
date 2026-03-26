package com.bit.coin.validat;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 验证者实体类
 * 核心字段：32字节ID、质押金额、动态质押权重（罚款会降低）
 */
public class Validator {
    private final byte[] validatorId;
    private final BigDecimal stakeAmount;
    private BigDecimal stakeWeight;

    // 未出块惩罚：每次扣减10%权重，可自行调整
    private static final BigDecimal PUNISH_RATIO = new BigDecimal("0.1");
    private static final BigDecimal MIN_WEIGHT = BigDecimal.ZERO;

    public Validator(byte[] validatorId, BigDecimal stakeAmount, BigDecimal stakeWeight) {
        if (validatorId == null || validatorId.length != 32) {
            throw new IllegalArgumentException("验证者ID必须为32字节");
        }
        this.validatorId = Arrays.copyOf(validatorId, 32);
        this.stakeAmount = stakeAmount;
        this.stakeWeight = stakeWeight;
    }

    /**
     * 未出块惩罚：降低权重
     */
    public void punishForMissSlot() {
        BigDecimal newWeight = this.stakeWeight.multiply(BigDecimal.ONE.subtract(PUNISH_RATIO));
        this.stakeWeight = newWeight.compareTo(MIN_WEIGHT) < 0 ? MIN_WEIGHT : newWeight;
        System.out.println("【惩罚】验证者 " + getValidatorIdShort() + " 权重降低，新权重：" + this.stakeWeight);
    }

    // 简化打印ID
    public String getValidatorIdShort() {
        return getValidatorIdHex();
    }

    // 32字节ID转16进制
    public String getValidatorIdHex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : validatorId) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // getter
    public byte[] getValidatorId() { return Arrays.copyOf(validatorId, 32); }
    public BigDecimal getStakeWeight() { return stakeWeight; }

    @Override
    public String toString() {
        return "验证者[" + getValidatorIdShort() + "], 权重=" + stakeWeight;
    }
}