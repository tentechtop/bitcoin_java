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
        private final byte[] networkMagic;
        private final int rpcPort;

        // 区块链参数
        private final byte[] genesisHash;
        private final long genesisTime;
        private final long genesisNonce;
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
            this(name, net, defaultPort, defaultRpcPort(name), networkMagic(net), genesisHash,
                    1754287472L, -1L, powLimit, powLimitBits, powNoRetargeting, coinbaseMaturity,
                    subsidyReductionInterval, targetTimespan, targetTimePerBlock, retargetAdjustmentFactor,
                    reduceMinDifficulty, minDiffReductionTime);
        }

        public Params(String name, int net, String defaultPort, int rpcPort, byte[] networkMagic,
                byte[] genesisHash, long genesisTime, long genesisNonce, BigInteger powLimit,
                long powLimitBits, boolean powNoRetargeting, int coinbaseMaturity,
                int subsidyReductionInterval, Duration targetTimespan, Duration targetTimePerBlock,
                int retargetAdjustmentFactor, boolean reduceMinDifficulty, Duration minDiffReductionTime) {
            boolean legacy = genesisNonce == -1L;
            if (name == null || name.isBlank() || defaultPort == null || defaultPort.isBlank()
                    || (!legacy && (rpcPort < 1 || rpcPort > 65535)) || rpcPort < 0 || rpcPort > 65535
                    || networkMagic == null || networkMagic.length != 4
                    || genesisHash == null || genesisHash.length != 32 || genesisTime < 0
                    || genesisTime > 0xFFFFFFFFL || genesisNonce < -1L || genesisNonce > 0xFFFFFFFFL
                    || powLimit == null || powLimit.signum() <= 0 || coinbaseMaturity < 1
                    || subsidyReductionInterval < 1 || targetTimespan == null || targetTimePerBlock == null
                    || targetTimespan.isZero() || targetTimePerBlock.isZero() || retargetAdjustmentFactor < 1
                    || minDiffReductionTime == null || minDiffReductionTime.isNegative()) {
                throw new IllegalArgumentException("Invalid network parameters");
            }
            this.name = name;
            this.net = net;
            this.defaultPort = defaultPort;
            this.networkMagic = networkMagic.clone();
            this.rpcPort = rpcPort;
            this.genesisHash = genesisHash.clone();
            this.genesisTime = genesisTime;
            this.genesisNonce = genesisNonce;
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

        public byte[] getNetworkMagic() {
            return networkMagic.clone();
        }

        public int getRpcPort() {
            return rpcPort;
        }

        public byte[] getGenesisHash() {
            return genesisHash.clone();
        }

        public long getGenesisTime() {
            return genesisTime;
        }

        public long getGenesisNonce() {
            return genesisNonce;
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

        public int getDifficultyAdjustmentInterval() {
            long spacing = targetTimePerBlock.toSeconds();
            long span = targetTimespan.toSeconds();
            if (spacing <= 0 || span % spacing != 0 || span / spacing > Integer.MAX_VALUE) {
                throw new IllegalStateException("Invalid difficulty adjustment interval");
            }
            return (int) (span / spacing);
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
                0xF9BEB4D9,
                "8333",
                8332,
                hexStringToByteArray("f9beb4d9"),
                hexStringToByteArray("000000008293fad98d49dec153e9f38dd1ab4ad99879ca1f32ee0d1428c3ebed"),
                1231006507L,
                1566562286L,
                new BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16),
                0x1d00ffffL,
                false, // 是否禁用难度调整
                100, // coinbase 成熟所需块数
                210000, // 补贴减半间隔（块）
                Duration.ofDays(14), // 难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                false, // 是否允许出块延迟时使用最低难度
                Duration.ZERO  // 此网络未启用最低难度例外
        );

        // --- 测试网3 (TestNet3) ---
        TestNet3Params = new Params(
                "testnet3",
                0x0B110907,
                "18333",
                18332,
                hexStringToByteArray("0b110907"),
                hexStringToByteArray("000000002673f92df94c60ddd31acc4bc0151e4236432bcd616b7862600719b9"),
                1296688602L,
                2793860755L,
                new BigInteger("00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16),
                0x1d00ffffL,
                false,
                100, // coinbase 成熟所需块数
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
                0xFABFB5DA,
                "18444",
                18443,
                hexStringToByteArray("fabfb5da"),
                hexStringToByteArray("0706867e8b363aa0ec1ecedc4cc862d081a14460aeab5c3eb14a27e7199bbd70"),
                1296688602L,
                1L,
                new BigInteger("7fffff000000000000000000000000000000000000000000000000000000000000", 16),
                0x207fffffL,
                true, // 是否禁用难度调整
                100, // coinbase 成熟所需块数
                150, // 补贴减半间隔（块）
                Duration.ofDays(1), // Bitcoin Core regtest 的难度调整周期
                Duration.ofMinutes(10), // 目标出块时间
                4, // 难度调整因子
                true, // 是否允许出块延迟时使用最低难度
                Duration.ofMinutes(20) // 触发最低难度例外所需的出块间隔
        );
    }

    private static byte[] networkMagic(int net) {
        return new byte[] { (byte) (net >>> 24), (byte) (net >>> 16), (byte) (net >>> 8), (byte) net };
    }

    private static int defaultRpcPort(String name) {
        return switch (name) {
            case "mainnet" -> 8332;
            case "testnet3" -> 18332;
            case "regtest" -> 18443;
            default -> 0;
        };
    }

}
