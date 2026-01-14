package com.bit.coin.structure.tx;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易状态解析器（基于位运算实现多状态共存）
 * 低32位：生命周期状态
 * 高32位：附加状态标志
 */
public class TransactionStatusResolver {
    // ======================== 生命周期状态（互斥，只能有一个）========================
    public static final long PENDING         = 1L << 0;  // 待处理（刚接收，未验证）
    public static final long IN_POOL          = 1L << 1;  // 在交易池中（已验证，等待打包）
    public static final long PACKING          = 1L << 2;  // 打包中（已提取到区块，等待上链）
    public static final long CONFIRMED        = 1L << 3;  // 已确认（已上链）
    public static final long ORPHANED         = 1L << 4;  // 被孤立（父交易缺失）
    public static final long REJECTED         = 1L << 5;  // 已拒绝（验证失败）
    public static final long REPLACED         = 1L << 6;  // 已被替换（RBF）
    public static final long EXPIRED          = 1L << 7;  // 已过期（超时未确认）
    public static final long FORK             = 1L << 8;  // 所在区块非主链

    // 主要状态掩码（用于清除操作）
    public static final long LIFECYCLE_MASK =
            PENDING | IN_POOL | PACKING | CONFIRMED | ORPHANED | REJECTED | REPLACED | EXPIRED;

    // ======================== 附加状态标志（可组合）========================
    // 高32位：附加状态标志
    private static final int FLAG_OFFSET = 32;

    // 交易属性标志
    public static final long COINBASE           = 1L << (FLAG_OFFSET + 0);  // Coinbase交易
    public static final long HIGH_FEE           = 1L << (FLAG_OFFSET + 1);  // 高手续费交易
    public static final long DUST_TX            = 1L << (FLAG_OFFSET + 2);  // 粉尘交易
    public static final long LARGE_SIZE          = 1L << (FLAG_OFFSET + 3);  // 大体积交易

    // 验证相关标志
    public static final long VERIFIED           = 1L << (FLAG_OFFSET + 4);  // 已完全验证
    public static final long SIG_CHECKED        = 1L << (FLAG_OFFSET + 5);  // 签名已验证
    public static final long SCRIPT_CHECKED     = 1L << (FLAG_OFFSET + 6);  // 脚本已验证
    public static final long INPUTS_VERIFIED    = 1L << (FLAG_OFFSET + 7);  // 输入已验证
    public static final long OUTPUTS_VERIFIED   = 1L << (FLAG_OFFSET + 8);  // 输出已验证

    // 内存池状态标志
    public static final long MEMPOOL_LOCKED     = 1L << (FLAG_OFFSET + 9);  // 内存池锁定（打包中）
    public static final long PRIORITY_HIGH      = 1L << (FLAG_OFFSET + 10); // 高优先级
    public static final long PRIORITY_LOW       = 1L << (FLAG_OFFSET + 11); // 低优先级
    public static final long RBF_ENABLED       = 1L << (FLAG_OFFSET + 12); // 支持RBF替换
    public static final long CPFP_ENABLED      = 1L << (FLAG_OFFSET + 13); // 支持CPFP

    // 确认相关标志
    public static final long CONFIRMED_WEAK    = 1L << (FLAG_OFFSET + 14); // 弱确认（<6个确认）
    public static final long CONFIRMED_SAFE    = 1L << (FLAG_OFFSET + 15); // 安全确认（6+确认）
    public static final long DEEP_CONFIRMED    = 1L << (FLAG_OFFSET + 16); // 深度确认（100+确认）

    // 错误/警告标志
    public static final long DOUBLE_SPEND_FLAG = 1L << (FLAG_OFFSET + 17); // 双花嫌疑
    public static final long INVALID_SIG       = 1L << (FLAG_OFFSET + 18); // 无效签名
    public static final long INSUFFICIENT_FEE  = 1L << (FLAG_OFFSET + 19); // 手续费不足
    public static final long NON_STANDARD      = 1L << (FLAG_OFFSET + 20); // 非标准交易

    // 时间相关标志
    public static final long TIME_LOCKED       = 1L << (FLAG_OFFSET + 21); // 时间锁定
    public static final long HEIGHT_LOCKED     = 1L << (FLAG_OFFSET + 22); // 高度锁定

    // 特殊交易标志
    public static final long MULTISIG_TX       = 1L << (FLAG_OFFSET + 23); // 多重签名交易
    public static final long CONTRACT_TX        = 1L << (FLAG_OFFSET + 24); // 合约交易
    public static final long WITNESS_TX        = 1L << (FLAG_OFFSET + 25); // 隔离见证交易




    // ======================== 常用状态组合 ========================
    // 交易池中已验证的普通交易
    public static final long POOL_TX =
            IN_POOL | VERIFIED | SIG_CHECKED | SCRIPT_CHECKED | INPUTS_VERIFIED | OUTPUTS_VERIFIED;

    // 交易池中高优先级交易
    public static final long HIGH_PRIORITY_POOL_TX =
            IN_POOL | PRIORITY_HIGH | HIGH_FEE | VERIFIED;

    // 已确认的安全交易
    public static final long CONFIRMED_SAFE_TX =
            CONFIRMED | CONFIRMED_SAFE | VERIFIED | SIG_CHECKED | SCRIPT_CHECKED;

    // 打包中的交易
    public static final long PACKING_TX =
            PACKING | MEMPOOL_LOCKED | VERIFIED;

    // 有双花嫌疑的交易
    public static final long RISKY_TX =
            IN_POOL | DOUBLE_SPEND_FLAG | INSUFFICIENT_FEE;

    // Coinbase交易（已确认）
    public static final long CONFIRMED_COINBASE =
            COINBASE | CONFIRMED | CONFIRMED_SAFE | VERIFIED;

    // ======================== 状态查询和设置方法 ========================

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
     * 检查交易是否在交易池中
     */
    public static boolean isInMempool(long statusMask) {
        return hasLifecycle(statusMask, IN_POOL) || hasLifecycle(statusMask, PACKING);
    }

    /**
     * 检查交易是否已确认
     */
    public static boolean isConfirmed(long statusMask) {
        return hasLifecycle(statusMask, CONFIRMED);
    }

    /**
     * 检查交易是否已被安全确认（6+确认）
     */
    public static boolean isSafelyConfirmed(long statusMask) {
        return isConfirmed(statusMask) && hasFlag(statusMask, CONFIRMED_SAFE);
    }

    /**
     * 检查交易是否正在打包
     */
    public static boolean isPacking(long statusMask) {
        return hasLifecycle(statusMask, PACKING);
    }

    /**
     * 检查交易是否已验证
     */
    public static boolean isVerified(long statusMask) {
        return hasFlag(statusMask, VERIFIED);
    }

    /**
     * 检查交易是否支持RBF替换
     */
    public static boolean supportsRBF(long statusMask) {
        return hasFlag(statusMask, RBF_ENABLED);
    }

    /**
     * 检查交易是否为Coinbase交易
     */
    public static boolean isCoinbase(long statusMask) {
        return hasFlag(statusMask, COINBASE);
    }

    /**
     * 检查交易是否可用（可被处理）
     */
    public static boolean isAvailable(long statusMask) {
        long lifecycle = getLifecycleStatus(statusMask);

        // 只有在交易池中或待处理的交易才可用
        if (lifecycle != IN_POOL && lifecycle != PENDING && lifecycle != PACKING) {
            return false;
        }

        // 检查是否有锁定标志
        if (hasFlag(statusMask, TIME_LOCKED) ||
                hasFlag(statusMask, HEIGHT_LOCKED)) {
            return false;
        }

        // 检查是否有错误标志
        if (hasFlag(statusMask, DOUBLE_SPEND_FLAG) ||
                hasFlag(statusMask, INVALID_SIG) ||
                hasFlag(statusMask, NON_STANDARD)) {
            return false;
        }

        return true;
    }

    /**
     * 验证状态组合是否合法
     */
    public static boolean isValidCombination(long statusMask) {
        long lifecycle = getLifecycleStatus(statusMask);

        // 必须有一个且只有一个生命周期状态
        if (Long.bitCount(lifecycle) != 1) {
            return false;
        }

        // 已确认或已拒绝的交易不能有内存池标志
        if (hasLifecycle(statusMask, CONFIRMED) ||
                hasLifecycle(statusMask, REJECTED) ||
                hasLifecycle(statusMask, ORPHANED) ||
                hasLifecycle(statusMask, REPLACED) ||
                hasLifecycle(statusMask, EXPIRED)) {
            if (hasFlag(statusMask, MEMPOOL_LOCKED) ||
                    hasFlag(statusMask, IN_POOL)) {
                return false;
            }
        }

        // Coinbase交易必须是已确认状态
        if (hasFlag(statusMask, COINBASE)) {
            if (!hasLifecycle(statusMask, CONFIRMED)) {
                return false;
            }
        }

        // 验证标志必须与生命周期匹配
        if (hasFlag(statusMask, VERIFIED)) {
            // 已验证的交易不能是待处理或被拒绝状态
            if (hasLifecycle(statusMask, PENDING) ||
                    hasLifecycle(statusMask, REJECTED)) {
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
        if (hasLifecycle(statusMask, PENDING)) parts.add("待处理");
        if (hasLifecycle(statusMask, IN_POOL)) parts.add("交易池");
        if (hasLifecycle(statusMask, PACKING)) parts.add("打包中");
        if (hasLifecycle(statusMask, CONFIRMED)) parts.add("已确认");
        if (hasLifecycle(statusMask, ORPHANED)) parts.add("被孤立");
        if (hasLifecycle(statusMask, REJECTED)) parts.add("已拒绝");
        if (hasLifecycle(statusMask, REPLACED)) parts.add("已替换");
        if (hasLifecycle(statusMask, EXPIRED)) parts.add("已过期");
        if (hasLifecycle(statusMask, FORK)) parts.add("非主链交易");


        // 交易属性
        if (hasFlag(statusMask, COINBASE)) parts.add("Coinbase");
        if (hasFlag(statusMask, HIGH_FEE)) parts.add("高手续费");
        if (hasFlag(statusMask, DUST_TX)) parts.add("粉尘交易");
        if (hasFlag(statusMask, LARGE_SIZE)) parts.add("大体积");

        // 验证标志
        if (hasFlag(statusMask, VERIFIED)) parts.add("已验证");
        if (hasFlag(statusMask, SIG_CHECKED)) parts.add("签名验证");
        if (hasFlag(statusMask, SCRIPT_CHECKED)) parts.add("脚本验证");
        if (hasFlag(statusMask, INPUTS_VERIFIED)) parts.add("输入验证");
        if (hasFlag(statusMask, OUTPUTS_VERIFIED)) parts.add("输出验证");

        // 内存池标志
        if (hasFlag(statusMask, MEMPOOL_LOCKED)) parts.add("内存池锁定");
        if (hasFlag(statusMask, PRIORITY_HIGH)) parts.add("高优先级");
        if (hasFlag(statusMask, PRIORITY_LOW)) parts.add("低优先级");
        if (hasFlag(statusMask, RBF_ENABLED)) parts.add("支持RBF");
        if (hasFlag(statusMask, CPFP_ENABLED)) parts.add("支持CPFP");

        // 确认标志
        if (hasFlag(statusMask, CONFIRMED_WEAK)) parts.add("弱确认");
        if (hasFlag(statusMask, CONFIRMED_SAFE)) parts.add("安全确认");
        if (hasFlag(statusMask, DEEP_CONFIRMED)) parts.add("深度确认");

        // 错误/警告标志
        if (hasFlag(statusMask, DOUBLE_SPEND_FLAG)) parts.add("双花嫌疑");
        if (hasFlag(statusMask, INVALID_SIG)) parts.add("无效签名");
        if (hasFlag(statusMask, INSUFFICIENT_FEE)) parts.add("手续费不足");
        if (hasFlag(statusMask, NON_STANDARD)) parts.add("非标准");

        // 时间/高度锁定
        if (hasFlag(statusMask, TIME_LOCKED)) parts.add("时间锁定");
        if (hasFlag(statusMask, HEIGHT_LOCKED)) parts.add("高度锁定");

        // 特殊交易
        if (hasFlag(statusMask, MULTISIG_TX)) parts.add("多重签名");
        if (hasFlag(statusMask, CONTRACT_TX)) parts.add("合约交易");
        if (hasFlag(statusMask, WITNESS_TX)) parts.add("隔离见证");


        return String.join(" | ", parts);
    }

    /**
     * 获取状态摘要
     */
    public static String getStatusSummary(long statusMask) {
        StringBuilder sb = new StringBuilder();

        if (isAvailable(statusMask)) {
            sb.append("[可用] ");
        } else if (isInMempool(statusMask)) {
            sb.append("[内存池] ");
        } else if (isConfirmed(statusMask)) {
            sb.append("[已确认] ");
        } else if (hasLifecycle(statusMask, ORPHANED)) {
            sb.append("[孤立] ");
        } else if (hasLifecycle(statusMask, REJECTED)) {
            sb.append("[拒绝] ");
        } else if (hasLifecycle(statusMask, REPLACED)) {
            sb.append("[被替换] ");
        } else if (hasLifecycle(statusMask, EXPIRED)) {
            sb.append("[过期] ");
        }

        sb.append(toString(statusMask));
        return sb.toString();
    }

    // ======================== 私有验证方法 ========================

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

    // ======================== 测试用例 ========================

    public static void main(String[] args) {
        System.out.println("===== 交易状态示例 =====");

        // 1. 交易池中已验证的交易
        long status1 = 0L;
        status1 = setLifecycleStatus(status1, IN_POOL);
        status1 = addFlag(status1, VERIFIED);
        status1 = addFlag(status1, SIG_CHECKED);
        status1 = addFlag(status1, SCRIPT_CHECKED);
        status1 = addFlag(status1, HIGH_FEE);
        status1 = addFlag(status1, PRIORITY_HIGH);
        System.out.println("1. 交易池高手续费交易: " + getStatusSummary(status1));
        System.out.println("   可用: " + isAvailable(status1) + " (预期: true)\n");

        // 2. 打包中的交易
        long status2 = 0L;
        status2 = setLifecycleStatus(status2, PACKING);
        status2 = addFlag(status2, MEMPOOL_LOCKED);
        status2 = addFlag(status2, VERIFIED);
        System.out.println("2. 打包中的交易: " + getStatusSummary(status2));
        System.out.println("   在内存池: " + isInMempool(status2) + " (预期: true)");
        System.out.println("   正在打包: " + isPacking(status2) + " (预期: true)\n");

        // 3. 已确认的Coinbase交易
        long status3 = 0L;
        status3 = setLifecycleStatus(status3, CONFIRMED);
        status3 = addFlag(status3, COINBASE);
        status3 = addFlag(status3, CONFIRMED_SAFE);
        status3 = addFlag(status3, DEEP_CONFIRMED);
        status3 = addFlag(status3, VERIFIED);
        System.out.println("3. 已确认Coinbase: " + getStatusSummary(status3));
        System.out.println("   已确认: " + isConfirmed(status3) + " (预期: true)");
        System.out.println("   安全确认: " + isSafelyConfirmed(status3) + " (预期: true)");
        System.out.println("   是Coinbase: " + isCoinbase(status3) + " (预期: true)\n");

        // 4. 被替换的交易（RBF）
        long status4 = 0L;
        status4 = setLifecycleStatus(status4, REPLACED);
        status4 = addFlag(status4, RBF_ENABLED);
        System.out.println("4. 被替换的交易: " + getStatusSummary(status4));
        System.out.println("   可用: " + isAvailable(status4) + " (预期: false)\n");

        // 5. 有双花嫌疑的交易
        long status5 = 0L;
        status5 = setLifecycleStatus(status5, IN_POOL);
        status5 = addFlag(status5, DOUBLE_SPEND_FLAG);
        status5 = addFlag(status5, INSUFFICIENT_FEE);
        System.out.println("5. 双花嫌疑交易: " + getStatusSummary(status5));
        System.out.println("   可用: " + isAvailable(status5) + " (预期: false)\n");

        // 6. 被孤立的交易
        long status6 = 0L;
        status6 = setLifecycleStatus(status6, ORPHANED);
        System.out.println("6. 被孤立的交易: " + getStatusSummary(status6));
        System.out.println("   可用: " + isAvailable(status6) + " (预期: false)\n");

        // 7. 支持RBF的高优先级交易
        long status7 = POOL_TX;
        status7 = addFlag(status7, RBF_ENABLED);
        status7 = addFlag(status7, PRIORITY_HIGH);
        status7 = addFlag(status7, HIGH_FEE);
        System.out.println("7. RBF高优先级交易: " + getStatusSummary(status7));
        System.out.println("   支持RBF: " + supportsRBF(status7) + " (预期: true)");
        System.out.println("   已验证: " + isVerified(status7) + " (预期: true)\n");

        // 8. 时间锁定的交易
        long status8 = POOL_TX;
        status8 = addFlag(status8, TIME_LOCKED);
        status8 = addFlag(status8, MULTISIG_TX);
        System.out.println("8. 时间锁定多签交易: " + getStatusSummary(status8));
        System.out.println("   可用: " + isAvailable(status8) + " (预期: false，时间锁定)\n");

        // 9. 使用预定义组合
        long status9 = CONFIRMED_SAFE_TX;
        System.out.println("9. 已确认安全交易: " + getStatusSummary(status9));
        System.out.println("   状态合法: " + isValidCombination(status9) + " (预期: true)");
        System.out.println("   安全确认: " + isSafelyConfirmed(status9) + " (预期: true)\n");

        // 10. 状态组合测试
        long complexStatus = PACKING_TX;
        complexStatus = addFlag(complexStatus, CPFP_ENABLED);
        complexStatus = addFlag(complexStatus, WITNESS_TX);
        System.out.println("10. 复杂状态组合: " + getStatusSummary(complexStatus));
        System.out.println("    状态合法: " + isValidCombination(complexStatus) + " (预期: true)");
        System.out.println("    正在打包: " + isPacking(complexStatus) + " (预期: true)");
        System.out.println("    支持CPFP: " + hasFlag(complexStatus, CPFP_ENABLED) + " (预期: true)");
    }
}
