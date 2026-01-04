package com.bit.coin.config;

import java.math.BigInteger;
import java.time.Duration;

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
        private final BigInteger powLimit;
        private final long powLimitBits;
        private final boolean powNoRetargeting;
        private final int bip34Height;
        private final int bip65Height;
        private final int bip66Height;

        // 奖励和成熟度
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
                      byte[] genesisHash, BigInteger powLimit, long powLimitBits, boolean powNoRetargeting,
                      int bip34Height, int bip65Height, int bip66Height,
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
            this.bip34Height = bip34Height;
            this.bip65Height = bip65Height;
            this.bip66Height = bip66Height;
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
        public int getBip34Height() { return bip34Height; }
        public int getBip65Height() { return bip65Height; }
        public int getBip66Height() { return bip66Height; }
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
                0xD9B4BEF9,
                "8333",
                hexStringToByteArray("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"),
                BigInteger.ONE.shiftLeft(224).subtract(BigInteger.ONE), // 工作量证明难度上限
                0x1d00ffffL, // 难度上限的紧凑格式
                false, // 是否禁用难度调整
                227931, // BIP34 激活高度
                388381, // BIP65 激活高度
                363725, // BIP66 激活高度
                100, // Coinbase 交易成熟度
                210000, // 区块奖励减半间隔
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                false, // 是否降低最小难度
                Duration.ZERO // 降低最小难度的时间间隔
        );

        // --- 测试网3 (TestNet3) ---
        TestNet3Params = new Params(
                "testnet3",
                0x0709110B,
                "18333",
                hexStringToByteArray("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"),
                BigInteger.ONE.shiftLeft(224).subtract(BigInteger.ONE),
                0x1d00ffffL,
                false,
                21111, // BIP34 激活高度
                581885, // BIP65 激活高度
                330776, // BIP66 激活高度
                100, // Coinbase 交易成熟度
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
                0xDAB5BFFA,
                "18444",
                hexStringToByteArray("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
                BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE),
                0x207fffffL,
                true, // 是否禁用难度调整
                100000000, // BIP34 激活高度 (实际上未激活)
                1351, // BIP65 激活高度
                1251, // BIP66 激活高度
                100, // Coinbase 交易成熟度
                150, // 区块奖励减半间隔
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否降低最小难度
                Duration.ofMinutes(20) // 降低最小难度的时间间隔
        );
    }
}