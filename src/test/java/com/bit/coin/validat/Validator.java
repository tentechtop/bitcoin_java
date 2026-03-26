package com.bit.coin.validat;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * 验证者实体类
 * 核心字段：32字节ID、质押金额、动态质押权重（罚款会降低）
 */
public class Validator {
    // 验证者ID：固定32字节（区块链标准地址长度）
    private final byte[] validatorId;
    // 质押金额（高精度，避免浮点误差）
    private final BigDecimal stakeAmount;
    // 质押权重（动态值：天数+金额计算，罚款会降低）
    private BigDecimal stakeWeight;

    /**
     * 构造方法：强制校验32字节ID
     */
    public Validator(byte[] validatorId, BigDecimal stakeAmount, BigDecimal stakeWeight) {
        if (validatorId == null || validatorId.length != 32) {
            throw new IllegalArgumentException("验证者ID必须为32字节！");
        }
        if (stakeAmount == null || stakeAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("质押金额不能为负数！");
        }
        this.validatorId = Arrays.copyOf(validatorId, 32);
        this.stakeAmount = stakeAmount;
        this.stakeWeight = stakeWeight;
    }

    // Getter方法
    public byte[] getValidatorId() {
        return Arrays.copyOf(validatorId, 32);
    }

    public BigDecimal getStakeAmount() {
        return stakeAmount;
    }

    public BigDecimal getStakeWeight() {
        return stakeWeight;
    }

    // 动态更新权重（罚款/奖励时调用）
    public void updateStakeWeight(BigDecimal newWeight) {
        if (newWeight.compareTo(BigDecimal.ZERO) < 0) {
            this.stakeWeight = BigDecimal.ZERO;
        } else {
            this.stakeWeight = newWeight;
        }
    }

    // 工具：将32字节ID转为十六进制字符串（方便打印/存储）
    public String getValidatorIdHex() {
        StringBuilder sb = new StringBuilder();
        for (byte b : validatorId) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Validator{" +
                "ID(十六进制)=" + getValidatorIdHex() +
                ", 质押金额=" + stakeAmount +
                ", 质押权重=" + stakeWeight +
                '}';
    }
}