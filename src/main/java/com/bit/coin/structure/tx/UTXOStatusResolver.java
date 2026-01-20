package com.bit.coin.structure.tx;

import java.util.ArrayList;
import java.util.List;
/**
 * UTXO状态解析器（基于位运算实现多状态共存）
 * 低32位：生命周期状态
 * 高32位：附加状态标志
 */
public class UTXOStatusResolver {
    // ======================== 生命周期状态（互斥，只能有一个）========================
    public static final long UNCONFIRMED_OUTPUT  = 1L << 0;  // 新生成未确认(一笔已经通过交易池验证的交易产生的UTXO)
    public static final long CONFIRMED_UNSPENT   = 1L << 1;  // 已确认未花费（已上链）
    public static final long PENDING_SPENT       = 1L << 2;  // 花费中（被未确认交易引用 一笔已经通过交易池验证的交易正在使用该UTXO 但等待确认）
    public static final long SPENT               = 1L << 3;  // 已花费（输入交易已确认）
    public static final long ORPHANED            = 1L << 4;  // 被孤立/被回滚



    // 主要状态掩码（用于清除操作）
    public static final long LIFECYCLE_MASK =
            UNCONFIRMED_OUTPUT | CONFIRMED_UNSPENT | PENDING_SPENT | SPENT | ORPHANED  ;

    // ======================== 附加状态标志（可组合）========================
    // 高32位：附加状态标志
    private static final int FLAG_OFFSET = 32;

    // 交易相关标志
    public static final long HIGH_VALUE           = 1L << (FLAG_OFFSET + 0);  // 高价值UTXO
    public static final long DUST_OUTPUT          = 1L << (FLAG_OFFSET + 1);  // 粉尘输出
    public static final long RBF_ENABLED          = 1L << (FLAG_OFFSET + 2);  // 支持RBF替换
    public static final long CPFP_ENABLED         = 1L << (FLAG_OFFSET + 3);  // 支持CPFP追加手续费

    // 验证相关标志
    public static final long VERIFIED             = 1L << (FLAG_OFFSET + 4);  // 已完全验证
    public static final long SIGNATURE_CHECKED    = 1L << (FLAG_OFFSET + 5);  // 签名已验证
    public static final long SCRIPT_CHECKED       = 1L << (FLAG_OFFSET + 6);  // 脚本已验证

    // 内存状态标志
    public static final long MEMPOOL_LOCKED       = 1L << (FLAG_OFFSET + 7);  // 内存池锁定
    public static final long DOUBLE_SPEND_FLAG    = 1L << (FLAG_OFFSET + 8);  // 双花嫌疑标记
    public static final long CONFIRMED_WEAK       = 1L << (FLAG_OFFSET + 9);  // 弱确认（小于6个确认）
    public static final long CONFIRMED_SAFE       = 1L << (FLAG_OFFSET + 10); // 安全确认（6+确认）

    // 特殊场景标志
    public static final long COINBASE_MATURING    = 1L << (FLAG_OFFSET + 11); // Coinbase成熟中
    public static final long TIME_LOCKED          = 1L << (FLAG_OFFSET + 12); // 时间锁定
    public static final long MULTISIG_OUTPUT      = 1L << (FLAG_OFFSET + 13); // 多重签名输出
    public static final long CONTRACT_BOUND       = 1L << (FLAG_OFFSET + 14); // 合约绑定输出
    public static final long COINBASE_MATURED    = 1L << (FLAG_OFFSET + 15); // 已经成熟



    // 常用组合
    public static final long ACTIVE_UNSPENT =
            CONFIRMED_UNSPENT | VERIFIED | SIGNATURE_CHECKED | SCRIPT_CHECKED;

    public static final long SAFE_SPENDABLE =
            CONFIRMED_UNSPENT | CONFIRMED_SAFE | VERIFIED;

    public static final long RISKY_OUTPUT =
            UNCONFIRMED_OUTPUT | HIGH_VALUE | DOUBLE_SPEND_FLAG;

    /**
     * 获取当前生命周期状态
     */
    public static long getLifecycleStatus(long statusMask) {
        return statusMask & LIFECYCLE_MASK;
    }

    /**
     * 设置生命周期状态（自动清除旧的生命周期状态）
     */
    public static long setLifecycleStatus(long statusMask, long newLifecycle) {
        validateLifecycle(newLifecycle);
        // 清除所有生命周期状态位
        statusMask = statusMask & (~LIFECYCLE_MASK);
        // 设置新的生命周期状态
        return statusMask | newLifecycle;
    }

    /**
     * 添加附加状态标志
     */
    public static long addFlag(long statusMask, long flag) {
        validateFlag(flag);
        return statusMask | flag;
    }

    /**
     * 移除附加状态标志
     */
    public static long removeFlag(long statusMask, long flag) {
        validateFlag(flag);
        return statusMask & (~flag);
    }

    /**
     * 检查是否包含指定生命周期状态
     */
    public static boolean hasLifecycle(long statusMask, long lifecycle) {
        return (getLifecycleStatus(statusMask) & lifecycle) != 0;
    }

    /**
     * 检查是否包含指定附加状态标志
     */
    public static boolean hasFlag(long statusMask, long flag) {
        return (statusMask & flag) != 0;
    }

    /**
     * 检查UTXO是否可用（可被花费）
     */
    public static boolean isSpendable(long statusMask) {
        long lifecycle = getLifecycleStatus(statusMask);

        // 只有已确认未花费的UTXO才可被花费
        if (lifecycle != CONFIRMED_UNSPENT) {
            return false;
        }

        // 检查是否有锁定标志
        if (hasFlag(statusMask, MEMPOOL_LOCKED) ||
                hasFlag(statusMask, TIME_LOCKED) ||
                hasFlag(statusMask, COINBASE_MATURING)) {
            return false;
        }

        // 检查是否被标记为双花嫌疑
        if (hasFlag(statusMask, DOUBLE_SPEND_FLAG)) {
            return false;
        }

        return true;
    }

    /**
     * 检查UTXO是否已确认
     */
    public static boolean isConfirmed(long statusMask) {
        return hasLifecycle(statusMask, CONFIRMED_UNSPENT) ||
                hasLifecycle(statusMask, SPENT);
    }

    /**
     * 检查UTXO是否在内存池中
     */
    public static boolean isInMempool(long statusMask) {
        return hasLifecycle(statusMask, UNCONFIRMED_OUTPUT) ||
                hasLifecycle(statusMask, PENDING_SPENT);
    }

    /**
     * 验证状态是否合法
     */
    public static boolean isValidCombination(long statusMask) {
        long lifecycle = getLifecycleStatus(statusMask);

        // 必须有一个且只有一个生命周期状态
        if (Long.bitCount(lifecycle) != 1) {
            return false;
        }

        // 已花费的UTXO不能有活跃状态标志
        if (hasLifecycle(statusMask, SPENT) || hasLifecycle(statusMask, ORPHANED)) {
            if (hasFlag(statusMask, MEMPOOL_LOCKED) ||
                    hasFlag(statusMask, RBF_ENABLED) ||
                    hasFlag(statusMask, CPFP_ENABLED)) {
                return false;
            }
        }

        // Coinbase特殊规则
        if (hasFlag(statusMask, COINBASE_MATURING)) {
            // Coinbase成熟中必须是已确认状态
            if (!hasLifecycle(statusMask, CONFIRMED_UNSPENT)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 转换为可读字符串
     */
    public static String toString(long statusMask) {
        if (statusMask == 0) {
            return "[状态未设置]";
        }

        List<String> parts = new ArrayList<>();

        // 生命周期状态
        if (hasLifecycle(statusMask, UNCONFIRMED_OUTPUT)) parts.add("未确认输出");
        if (hasLifecycle(statusMask, CONFIRMED_UNSPENT)) parts.add("已确认未花费");
        if (hasLifecycle(statusMask, PENDING_SPENT)) parts.add("花费中");
        if (hasLifecycle(statusMask, SPENT)) parts.add("已花费");
        if (hasLifecycle(statusMask, ORPHANED)) parts.add("被孤立");

        // 附加标志
        if (hasFlag(statusMask, HIGH_VALUE)) parts.add("高价值");
        if (hasFlag(statusMask, DUST_OUTPUT)) parts.add("粉尘");
        if (hasFlag(statusMask, RBF_ENABLED)) parts.add("RBF");
        if (hasFlag(statusMask, CPFP_ENABLED)) parts.add("CPFP");
        if (hasFlag(statusMask, VERIFIED)) parts.add("已验证");
        if (hasFlag(statusMask, MEMPOOL_LOCKED)) parts.add("内存池锁定");
        if (hasFlag(statusMask, DOUBLE_SPEND_FLAG)) parts.add("双花嫌疑");
        if (hasFlag(statusMask, CONFIRMED_WEAK)) parts.add("弱确认");
        if (hasFlag(statusMask, CONFIRMED_SAFE)) parts.add("安全确认");
        if (hasFlag(statusMask, COINBASE_MATURING)) parts.add("成熟中");
        if (hasFlag(statusMask, COINBASE_MATURED)) parts.add("已经成熟");
        if (hasFlag(statusMask, TIME_LOCKED)) parts.add("时间锁定");
        if (hasFlag(statusMask, MULTISIG_OUTPUT)) parts.add("多重签名");
        if (hasFlag(statusMask, CONTRACT_BOUND)) parts.add("合约绑定");

        return String.join(" | ", parts);
    }

    /**
     * 获取状态摘要
     */
    public static String getStatusSummary(long statusMask) {
        StringBuilder sb = new StringBuilder();

        if (isSpendable(statusMask)) {
            sb.append("[可花费] ");
        } else if (isInMempool(statusMask)) {
            sb.append("[内存池] ");
        } else if (hasLifecycle(statusMask, SPENT)) {
            sb.append("[已花费] ");
        } else if (hasLifecycle(statusMask, ORPHANED)) {
            sb.append("[无效] ");
        }

        sb.append(toString(statusMask));
        return sb.toString();
    }

    private static void validateLifecycle(long lifecycle) {
        if ((lifecycle & LIFECYCLE_MASK) == 0) {
            throw new IllegalArgumentException("无效的生命周期状态: " + lifecycle);
        }
    }

    private static void validateFlag(long flag) {
        if (flag == 0 || (flag & LIFECYCLE_MASK) != 0) {
            throw new IllegalArgumentException("无效的附加标志: " + flag);
        }
    }


    /**
     * 设置Coinbase为成熟中状态
     * 会将UTXO标记为成熟中，并自动清除已成熟状态
     * 要求：当前必须处于已确认未花费状态
     */
    public static long setCoinbaseMaturing(long statusMask) {
        // 验证当前状态是否允许设置为成熟中
        if (!hasLifecycle(statusMask, CONFIRMED_UNSPENT)) {
            throw new IllegalStateException("只有已确认未花费的UTXO才能设置为成熟中状态");
        }

        // 清除已成熟状态
        statusMask = removeFlag(statusMask, COINBASE_MATURED);

        // 设置为成熟中状态
        statusMask = addFlag(statusMask, COINBASE_MATURING);

        return statusMask;
    }

    /**
     * 设置Coinbase为已成熟状态
     * 会将UTXO标记为已成熟，并自动清除成熟中状态
     * 要求：当前必须处于已确认未花费状态
     */
    public static long setCoinbaseMatured(long statusMask) {
        // 清除成熟中状态
        statusMask = removeFlag(statusMask, COINBASE_MATURING);
        // 设置为已成熟状态
        statusMask = addFlag(statusMask, COINBASE_MATURED);
        return statusMask;
    }

    /**
     * 清除Coinbase相关状态
     * 移除成熟中和已成熟状态
     */
    public static long clearCoinbaseStatus(long statusMask) {
        statusMask = removeFlag(statusMask, COINBASE_MATURING);
        statusMask = removeFlag(statusMask, COINBASE_MATURED);
        return statusMask;
    }

    /**
     * 检查是否为成熟中的Coinbase
     */
    public static boolean isCoinbaseMaturing(long statusMask) {
        return hasFlag(statusMask, COINBASE_MATURING);
    }

    /**
     * 检查是否为已成熟的Coinbase
     */
    public static boolean isCoinbaseMatured(long statusMask) {
        return hasFlag(statusMask, COINBASE_MATURED);
    }

    /**
     * 检查是否为Coinbase输出（包含成熟中或已成熟）
     */
    public static boolean isCoinbaseOutput(long statusMask) {
        return isCoinbaseMaturing(statusMask) || isCoinbaseMatured(statusMask);
    }

    public static void main(String[] args) {
        System.out.println("===== UTXO状态示例 =====");

        // 1. 交易池中的高价值输出
        long status1 = 0L;
        status1 = setLifecycleStatus(status1, UNCONFIRMED_OUTPUT);
        status1 = addFlag(status1, HIGH_VALUE);
        status1 = addFlag(status1, RBF_ENABLED);
        System.out.println("1. 交易池高价值输出: " + getStatusSummary(status1));
        System.out.println("   可花费: " + isSpendable(status1) + " (预期: false)\n");

        // 2. 已确认的UTXO，正在被花费
        long status2 = 0L;
        status2 = setLifecycleStatus(status2, PENDING_SPENT);
        status2 = addFlag(status2, MEMPOOL_LOCKED);
        status2 = addFlag(status2, VERIFIED);
        status2 = addFlag(status2, CONFIRMED_WEAK);
        System.out.println("2. 花费中的UTXO: " + getStatusSummary(status2));
        System.out.println("   可花费: " + isSpendable(status2) + " (预期: false)\n");

        // 3. 安全的可花费UTXO
        long status3 = 0L;
        status3 = setLifecycleStatus(status3, CONFIRMED_UNSPENT);
        status3 = addFlag(status3, VERIFIED);
        status3 = addFlag(status3, CONFIRMED_SAFE);
        status3 = addFlag(status3, SIGNATURE_CHECKED);
        status3 = addFlag(status3, SCRIPT_CHECKED);
        System.out.println("3. 安全可花费UTXO: " + getStatusSummary(status3));
        System.out.println("   可花费: " + isSpendable(status3) + " (预期: true)\n");

        // 4. Coinbase成熟中的UTXO
        long status4 = 0L;
        status4 = setLifecycleStatus(status4, CONFIRMED_UNSPENT);
        status4 = addFlag(status4, COINBASE_MATURING);
        status4 = addFlag(status4, CONFIRMED_WEAK);
        System.out.println("4. Coinbase成熟中: " + getStatusSummary(status4));
        System.out.println("   可花费: " + isSpendable(status4) + " (预期: false)\n");

        // 5. 有双花嫌疑的UTXO
        long status5 = 0L;
        status5 = setLifecycleStatus(status5, CONFIRMED_UNSPENT);
        status5 = addFlag(status5, DOUBLE_SPEND_FLAG);
        System.out.println("5. 双花嫌疑UTXO: " + getStatusSummary(status5));
        System.out.println("   可花费: " + isSpendable(status5) + " (预期: false)\n");

        // 6. 已花费的UTXO
        long status6 = 0L;
        status6 = setLifecycleStatus(status6, SPENT);
        System.out.println("6. 已花费UTXO: " + getStatusSummary(status6));
        System.out.println("   在内存池: " + isInMempool(status6) + " (预期: false)\n");

        // 7. 组合使用
        long complexStatus = SAFE_SPENDABLE;
        complexStatus = addFlag(complexStatus, MULTISIG_OUTPUT);
        complexStatus = addFlag(complexStatus, TIME_LOCKED);
        System.out.println("7. 复杂状态示例: " + getStatusSummary(complexStatus));
        System.out.println("   状态合法: " + isValidCombination(complexStatus));
        System.out.println("   可花费: " + isSpendable(complexStatus) + " (预期: false，因为有时间锁定)\n");
    }
}