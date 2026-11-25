package com.bit.coin.blockchain;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Function;

/** 上下文校验以候选区块的祖先为依据，与当前主链顶端无关。 */
public final class ConsensusRules {

    public static final int REVISION = 3;  // 网络参数对齐 Bitcoin Core 后的链数据库共识版本
    public static final int MAX_BLOCK_BYTES = 8_000_000;  // 共识允许的单块序列化字节数上限
    public static final int MAX_BLOCK_TRANSACTIONS = 20_000;  // 单块交易数上限，含 coinbase

    private ConsensusRules() {}

    /** 解码区块头的紧凑难度目标，拒绝非规范编码。 */
    public static BigInteger target(int bits) {
        int size = bits >>> 24;
        int word = bits & 0x007fffff;
        if ((bits & 0x00800000) != 0 || word == 0 || size > 34) {
            throw new IllegalArgumentException("Invalid PoW target");
        }
        BigInteger value = BigInteger.valueOf(word);
        value = size <= 3 ? value.shiftRight(8 * (3 - size)) : value.shiftLeft(8 * (size - 3));
        if (value.signum() <= 0 || value.bitLength() > 256 || BlockHeader.convertTargetToBits(value) != bits) {
            throw new IllegalArgumentException("Noncanonical PoW target");
        }
        return value;
    }

    /** 取父块及其祖先最近最多 11 个时间戳的中位数，单位为秒。 */
    public static long medianTime(Block parent, Function<byte[], Block> blocks) {
        ArrayList<Long> times = new ArrayList<>(11);
        for (Block cursor = parent; cursor != null && times.size() < 11;) {
            times.add(cursor.getBlockHeader().getTime());
            if (cursor.getHeight() == 0) {
                break;
            }
            cursor = requiredParent(cursor, blocks);
        }
        Collections.sort(times);
        return times.isEmpty() ? 0 : times.get(times.size() / 2);
    }

    /** 按候选分支的出块时间和网络调难规则计算下一区块的 bits。 */
    public static int expectedBits(Block parent, long time, ParamsConfig.Params params,
            Function<byte[], Block> blocks) {
        int limit = (int) params.getPowLimitBits();
        if (parent == null || params.isPowNoRetargeting()) {
            return limit;
        }
        // 调整周期由目标总时长除以单块目标时长得到，边界按候选区块高度判断。
        int interval = Math
                .toIntExact(params.getTargetTimespan().toSeconds() / params.getTargetTimePerBlock().toSeconds());
        if (interval < 1) {
            throw new IllegalStateException("Invalid retarget interval");
        }
        if ((parent.getHeight() + 1) % interval != 0) {
            if (params.isReduceMinDifficulty()) {
                if (time > parent.getBlockHeader().getTime() + params.getMinDiffReductionTime().toSeconds()) {
                    return limit;
                }
                Block cursor = parent;
                while (cursor.getHeight() % interval != 0 && cursor.getBlockHeader().getBits() == limit) {
                    cursor = requiredParent(cursor, blocks);
                }
                return cursor.getBlockHeader().getBits();
            }
            return parent.getBlockHeader().getBits();
        }
        Block anchor = parent;
        for (int i = 1; i < interval; i++) {
            anchor = requiredParent(anchor, blocks);
        }
        long span = params.getTargetTimespan().toSeconds();
        long actual = parent.getBlockHeader().getTime() - anchor.getBlockHeader().getTime();
        actual = Math.max(span / params.getRetargetAdjustmentFactor(),
                Math.min(span * params.getRetargetAdjustmentFactor(), actual));
        BigInteger next = target(parent.getBlockHeader().getBits()).multiply(BigInteger.valueOf(actual))
                .divide(BigInteger.valueOf(span));
        return BlockHeader.convertTargetToBits(next.min(target(limit)));
    }

    /** 绝对锁定值小于 5 亿时按高度判断，否则按中位时间判断；所有输入均为最终序号时直接放行。 */
    public static boolean isFinal(Transaction tx, int height, long medianTime) {
        if (tx.getLockTime() == 0 || tx.getLockTime() < (tx.getLockTime() < 500_000_000L ? height : medianTime)) {
            return true;
        }
        return tx.getInputs().stream().allMatch(input -> input.getSequence() == 0xFFFFFFFFL);
    }

    /** 检查版本 101 普通交易的每个输入是否满足相对于来源区块的等待期。 */
    public static boolean relativeLocksSatisfied(Transaction tx, int height, long median,
            java.util.function.IntToLongFunction medianBeforeHeight) {
        if (tx.isCoinbase() || tx.getVersion() != 101) {
            return true;
        }
        for (var input : tx.getInputs()) {
            long sequence = input.getSequence();
            if ((sequence & 0x80000000L) != 0) {
                continue;
            }
            var source = tx.getUtxoMap()
                    .get(com.bit.coin.structure.tx.UTXO.getKeyStr(input.getTxId(), input.getIndex()));
            if (source == null) {
                return false;
            }
            // 未设置时间单位位时按区块数计；设置时按 512 秒为一个单位比较中位时间差。
            if ((sequence & 0x400000L) == 0) {
                if ((long) height - source.getHeight() < (sequence & 0xFFFFL)) {
                    return false;
                }
            } else if (median - medianBeforeHeight.applyAsLong(source.getHeight()) < ((sequence & 0xFFFFL) << 9)) {
                return false;
            }
        }
        return true;
    }

    /** 按来源高度缓存前一区块的中位时间，供输入相对时间锁复用。 */
    public static java.util.function.IntToLongFunction sourceMedianTimes(Block parent, Function<byte[], Block> blocks) {
        java.util.Map<Integer, Long> cache = new java.util.HashMap<>();
        return sourceHeight -> cache.computeIfAbsent(sourceHeight, height -> {
            int target = Math.max(0, height - 1);
            Block ancestor = parent;
            while (ancestor.getHeight() > target) {
                ancestor = requiredParent(ancestor, blocks);
            }
            return medianTime(ancestor, blocks);
        });
    }

    /** 按高度计算历次减半后的区块补贴，单位为聪，不含手续费。 */
    public static long subsidy(int height, ParamsConfig.Params params) {
        if (height < 0) {
            throw new IllegalArgumentException("Negative height");
        }
        int halvings = height / params.getSubsidyReductionInterval();
        return halvings >= 64 ? 0 : (50L * 100_000_000L) >>> halvings;
    }

    private static Block requiredParent(Block block, Function<byte[], Block> blocks) {
        Block parent = blocks.apply(block.getBlockHeader().getPreviousHash());
        if (parent == null || parent.getHeight() != block.getHeight() - 1) {
            throw new IllegalStateException("Missing ancestor");
        }
        return parent;
    }
}
