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

    // 网络参数映射



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
        private final BigInteger powLimit;
        private final long powLimitBits;
        private final boolean powNoRetargeting;


        private final int coinbaseMaturity;
        private final int subsidyReductionInterval;

        // 难度调整
        private final Duration targetTimespan;
        private final Duration targetTimePerBlock;
        private final int retargetAdjustmentFactor;
        private final boolean reduceMinDifficulty;
        private final Duration minDiffReductionTime;

        // 构造函数
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

        // --- Getter 方法 ---
        public String getName() { return name; }
        public int getNet() { return net; }
        public String getDefaultPort() { return defaultPort; }
        public byte[] getGenesisHash() { return genesisHash.clone(); }
        public BigInteger getPowLimit() { return powLimit; }
        public long getPowLimitBits() { return powLimitBits; }
        public boolean isPowNoRetargeting() { return powNoRetargeting; }


        public int getCoinbaseMaturity() { return coinbaseMaturity; }
        public int getSubsidyReductionInterval() { return subsidyReductionInterval; }
        public Duration getTargetTimespan() { return targetTimespan; }
        public Duration getTargetTimePerBlock() { return targetTimePerBlock; }
        public int getRetargetAdjustmentFactor() { return retargetAdjustmentFactor; }
        public boolean isReduceMinDifficulty() { return reduceMinDifficulty; }
        public Duration getMinDiffReductionTime() { return minDiffReductionTime; }
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
                new BigInteger("0000ffff0000000000000000000000000000000000000000000000000000000000", 16), // 工作量证明难度上限
                0x1f00ffffL, // 难度上限的紧凑格式
                false, // 是否禁用难度调整
                10, // Coinbase 交易成熟度
                210000, // 区块奖励减半间隔
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(1), // 目标出块时间
                4, // 难度调整因子
                false, // 是否降低最小难度
                Duration.ZERO // 降低最小难度的时间间隔
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
                10, // Coinbase 交易成熟度
                210000, // 区块奖励减半间隔
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否降低最小难度
                Duration.ofMinutes(20) // 降低最小难度的时间间隔
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
                10, // Coinbase 交易成熟度
                150, // 区块奖励减半间隔
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否降低最小难度
                Duration.ofMinutes(20) // 降低最小难度的时间间隔
        );
    }




}