package com.bit.coin.config;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static ch.qos.logback.core.encoder.ByteArrayUtil.hexStringToByteArray;

/**
 * ParamsConfig 是一个受 btcsuite/btcd chaincfg 包启发的 Java 实现。
 * 为不同的比特币网络提供精简的配置参数。
 */
public final class ParamsConfig {


    /**
     * Params 类定义了比特币网络的核心参数。
     */
    public static final class Params {

        // 网络标识
        private final String name;
        private final int net; // 网络标识符
        private final String defaultPort;

        // 区块链参数
        private final byte[] genesisHash;
        private final BigInteger powLimit;  // 允许的最大目标哈希，对应最低难度
        private final long powLimitBits;  // 同一目标在区块头 bits 字段中的紧凑编码
        private final boolean powNoRetargeting;  // 启用时固定使用该网络的 powLimitBits

        private final int coinbaseMaturity;  // 花费 coinbase 时与来源区块的最小高度差
        private final int subsidyReductionInterval;  // 每经过多少个区块将补贴减半

        // 难度调整
        private final Duration targetTimespan;  // 一个难度调整周期的目标耗时
        private final Duration targetTimePerBlock;  // 期望相邻区块之间的时间间隔
        private final int retargetAdjustmentFactor;  // 单次调难时，实际耗时相对目标耗时可放大或缩小的最大倍数
        private final boolean reduceMinDifficulty;
        private final Duration minDiffReductionTime;  // 启用最低难度例外时，两块间隔超过此值即可使用最低难度

        public Params(String name, int net, String defaultPort,
                byte[] genesisHash, BigInteger powLimit,
                long powLimitBits, boolean powNoRetargeting,
                int coinbaseMaturity, int subsidyReductionInterval,
                Duration targetTimespan, Duration targetTimePerBlock, int retargetAdjustmentFactor,
                boolean reduceMinDifficulty, Duration minDiffReductionTime) {
            this.name = name;
            this.net = net;
            this.defaultPort = defaultPort;
            this.genesisHash = genesisHash.clone();
            this.powLimit = powLimit;
            this.powLimitBits = powLimitBits;
            this.powNoRetargeting = powNoRetargeting;

            this.coinbaseMaturity = coinbaseMaturity;
            this.subsidyReductionInterval = subsidyReductionInterval;
            this.targetTimespan = targetTimespan;
            this.targetTimePerBlock = targetTimePerBlock;
            this.retargetAdjustmentFactor = retargetAdjustmentFactor;
            this.reduceMinDifficulty = reduceMinDifficulty;
            this.minDiffReductionTime = minDiffReductionTime;
        }

        public String getName() {
            return name;
        }

        public int getNet() {
            return net;
        }

        public String getDefaultPort() {
            return defaultPort;
        }

        public byte[] getGenesisHash() {
            return genesisHash.clone();
        }

        public BigInteger getPowLimit() {
            return powLimit;
        }

        public long getPowLimitBits() {
            return powLimitBits;
        }

        public boolean isPowNoRetargeting() {
            return powNoRetargeting;
        }

        public int getCoinbaseMaturity() {
            return coinbaseMaturity;
        }

        public int getSubsidyReductionInterval() {
            return subsidyReductionInterval;
        }

        public Duration getTargetTimespan() {
            return targetTimespan;
        }

        public Duration getTargetTimePerBlock() {
            return targetTimePerBlock;
        }

        public int getRetargetAdjustmentFactor() {
            return retargetAdjustmentFactor;
        }

        public boolean isReduceMinDifficulty() {
            return reduceMinDifficulty;
        }

        public Duration getMinDiffReductionTime() {
            return minDiffReductionTime;
        }
    }

    // 预定义的网络参数
    public static final Params MainNetParams;
    public static final Params TestNet3Params;
    public static final Params RegressionNetParams;

    static {
        // --- 主网 (MainNet) ---
        MainNetParams = new Params(
                "mainnet",
                0xB14A0201,
                "8333",
                hexStringToByteArray("00009541df5d2011d46ca0d32f4fc9163369434122566f28a9ba36a607ffcf5d"),
                new BigInteger("0000ffff0000000000000000000000000000000000000000000000000000000000", 16),  // 最大目标哈希（最低难度）
                0x1f00ffffL,  // 最大目标哈希的紧凑编码
                false, // 是否禁用难度调整
                10, // coinbase 成熟所需块数
                210000, // 补贴减半间隔（块）
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(1), // 目标出块时间
                4, // 难度调整因子
                false, // 是否允许出块延迟时使用最低难度
                Duration.ZERO  // 此网络未启用最低难度例外
        );

        // --- 测试网3 (TestNet3) ---
        TestNet3Params = new Params(
                "testnet3",
                0xB14A0202,
                "18333",
                hexStringToByteArray("0000ff19c2fd0e94c4366a0a2d1dd4c9612e40c1541aa52d256e40d2029399dc"),
                new BigInteger("0000ffff0000000000000000000000000000000000000000000000000000000000", 16),
                0x1f00ffffL,
                false,
                10, // coinbase 成熟所需块数
                210000, // 补贴减半间隔（块）
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否允许出块延迟时使用最低难度
                Duration.ofMinutes(20) // 触发最低难度例外所需的出块间隔
        );

        // --- 回归测试网 (RegressionNet) ---
        RegressionNetParams = new Params(
                "regtest",
                0xB14A0203,
                "18444",
                hexStringToByteArray("21086e77a61c65eac56cef1d96d1737ca1d5866f87df1da967a041c69d65d098"),
                new BigInteger("7fffff000000000000000000000000000000000000000000000000000000000000", 16),
                0x207fffffL,
                true, // 是否禁用难度调整
                10, // coinbase 成熟所需块数
                150, // 补贴减半间隔（块）
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否允许出块延迟时使用最低难度
                Duration.ofMinutes(20) // 触发最低难度例外所需的出块间隔
        );
    }

}
