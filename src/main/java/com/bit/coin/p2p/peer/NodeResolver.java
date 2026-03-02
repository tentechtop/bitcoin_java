package com.bit.coin.p2p.peer;

import java.util.ArrayList;
import java.util.List;

/**
 * 节点类型与状态解析器（位运算实现，支持多属性共存）
 * 基于long类型（64位）实现，支持更多属性扩展
 */
public class NodeResolver {
    // ======================== 节点类型定义（占用低32位，预留更多扩展位）========================
    public static final long FULL_NODE = 1L << 0;         // 全节点（完整数据存储）
    public static final long LIGHT_NODE = 1L << 1;        // 轻量级节点（仅核心数据）
    public static final long OUTBOUND_ONLY = 1L << 2;     // 仅出站节点（主动连接其他节点）
    public static final long INBOUND_SUPPORT = 1L << 3;   // 支持入站连接
    public static final long SYMMETRIC_NAT = 1L << 4;     // 对称NAT类型
    public static final long CONE_NAT = 1L << 5;          // 锥形NAT类型
    public static final long RELAY_NODE = 1L << 6;        // 中继节点（帮助NAT穿透）
    public static final long VALIDATOR_NODE = 1L << 7;    // 验证者节点（参与共识）
    // 预留扩展位（8-31位，共24个可扩展类型）
    // public static final long NEW_TYPE = 1L << 8;         // 示例：未来可添加的新类型

    // 常用类型组合
    public static final long STANDARD_NODE = FULL_NODE | INBOUND_SUPPORT;  // 标准全节点
    public static final long LIGHT_RELAY = LIGHT_NODE | RELAY_NODE;        // 轻量中继节点

    // ======================== 节点状态定义（占用高32位，预留更多扩展位）========================
    private static final int STATUS_OFFSET = 32;          // 状态起始偏移位（从32位开始，高32位）
    public static final long ONLINE = 1L << STATUS_OFFSET;            // 在线
    public static final long SYNCING = 1L << (STATUS_OFFSET + 1);     // 同步中
    public static final long BLOCKED = 1L << (STATUS_OFFSET + 2);     // 已封禁
    public static final long MAINTENANCE = 1L << (STATUS_OFFSET + 3); // 维护中
    public static final long HIGH_LOAD = 1L << (STATUS_OFFSET + 4);   // 高负载
    public static final long AUTHENTICATED = 1L << (STATUS_OFFSET + 5); // 已认证
    // 预留扩展位（38-63位，共26个可扩展状态）
    // public static final long NEW_STATUS = 1L << (STATUS_OFFSET + 6);  // 示例：未来可添加的新状态

    // 常用状态组合
    public static final long ACTIVE = ONLINE | SYNCING;  // 活跃状态（在线或同步中）
    public static final long UNAVAILABLE = BLOCKED | MAINTENANCE;  // 不可用状态

    /**
     * 检查是否包含指定节点类型
     */
    public static boolean hasType(long nodeBits, long targetType) {
        validateType(targetType);
        return (nodeBits & targetType) != 0;
    }

    /**
     * 检查是否包含指定节点状态
     */
    public static boolean hasStatus(long nodeBits, long targetStatus) {
        validateStatus(targetStatus);
        return (nodeBits & targetStatus) != 0;
    }

    /**
     * 添加节点类型
     */
    public static long addType(long nodeBits, long newType) {
        validateType(newType);
        return nodeBits | newType;
    }

    /**
     * 添加节点状态
     */
    public static long addStatus(long nodeBits, long newStatus) {
        validateStatus(newStatus);
        return nodeBits | newStatus;
    }

    /**
     * 移除节点类型
     */
    public static long removeType(long nodeBits, long typeToRemove) {
        validateType(typeToRemove);
        return nodeBits & ~typeToRemove;
    }

    /**
     * 移除节点状态
     */
    public static long removeStatus(long nodeBits, long statusToRemove) {
        validateStatus(statusToRemove);
        return nodeBits & ~statusToRemove;
    }

    /**
     * 清空所有属性（类型和状态）
     */
    public static long clearAll() {
        return 0L;
    }

    /**
     * 判断节点是否处于可用状态（在线且未被封禁）
     */
    public static boolean isAvailable(long nodeBits) {
        return hasStatus(nodeBits, ONLINE) &&
                !hasStatus(nodeBits, BLOCKED) &&
                !hasStatus(nodeBits, MAINTENANCE);
    }

    /**
     * 判断节点是否为完整功能节点（全节点且支持入站）
     */
    public static boolean isFullFunctionNode(long nodeBits) {
        return hasType(nodeBits, FULL_NODE) && hasType(nodeBits, INBOUND_SUPPORT);
    }

    /**
     * 检查节点属性是否存在冲突
     */
    public static boolean hasConflict(long nodeBits) {
        // NAT类型冲突（对称NAT和锥形NAT互斥）
        if (hasType(nodeBits, SYMMETRIC_NAT) && hasType(nodeBits, CONE_NAT)) {
            return true;
        }

        // 状态冲突（在线与封禁/维护互斥）
        if (hasStatus(nodeBits, ONLINE) &&
                (hasStatus(nodeBits, BLOCKED) || hasStatus(nodeBits, MAINTENANCE))) {
            return true;
        }

        // 节点类型冲突（全节点与轻量节点互斥）
        return hasType(nodeBits, FULL_NODE) && hasType(nodeBits, LIGHT_NODE);
    }

    /**
     * 转换为可读字符串
     */
    public static String toString(long nodeBits) {
        if (nodeBits == 0) {
            return "[NONE]";
        }

        List<String> typeList = new ArrayList<>();
        if (hasType(nodeBits, FULL_NODE)) typeList.add("FULL_NODE");
        if (hasType(nodeBits, LIGHT_NODE)) typeList.add("LIGHT_NODE");
        if (hasType(nodeBits, OUTBOUND_ONLY)) typeList.add("OUTBOUND_ONLY");
        if (hasType(nodeBits, INBOUND_SUPPORT)) typeList.add("INBOUND_SUPPORT");
        if (hasType(nodeBits, SYMMETRIC_NAT)) typeList.add("SYMMETRIC_NAT");
        if (hasType(nodeBits, CONE_NAT)) typeList.add("CONE_NAT");
        if (hasType(nodeBits, RELAY_NODE)) typeList.add("RELAY_NODE");
        if (hasType(nodeBits, VALIDATOR_NODE)) typeList.add("VALIDATOR_NODE");

        List<String> statusList = new ArrayList<>();
        if (hasStatus(nodeBits, ONLINE)) statusList.add("ONLINE");
        if (hasStatus(nodeBits, SYNCING)) statusList.add("SYNCING");
        if (hasStatus(nodeBits, BLOCKED)) statusList.add("BLOCKED");
        if (hasStatus(nodeBits, MAINTENANCE)) statusList.add("MAINTENANCE");
        if (hasStatus(nodeBits, HIGH_LOAD)) statusList.add("HIGH_LOAD");
        if (hasStatus(nodeBits, AUTHENTICATED)) statusList.add("AUTHENTICATED");

        return String.format("类型: [%s], 状态: [%s]",
                String.join(", ", typeList),
                String.join(", ", statusList));
    }

    /**
     * 验证节点类型是否合法（必须是低32位的2的幂）
     */
    private static void validateType(long type) {
        if (type <= 0 || (type & (type - 1)) != 0 || (type & (0xFFFFFFFFL << STATUS_OFFSET)) != 0) {
            throw new IllegalArgumentException("无效的节点类型: " + type);
        }
    }

    /**
     * 验证节点状态是否合法（必须是高32位的2的幂）
     */
    private static void validateStatus(long status) {
        if (status <= 0 || (status & (status - 1)) != 0 || (status & 0xFFFFFFFFL) != 0) {
            throw new IllegalArgumentException("无效的节点状态: " + status);
        }
    }

    public static void main(String[] args) {
        // 1. 创建一个全节点（初始状态：离线）
        long node = clearAll();
        node = addType(node, FULL_NODE);
        node = addType(node, INBOUND_SUPPORT);
        System.out.println("1. 初始全节点: " + toString(node));
        System.out.println("   是否为完整功能节点: " + isFullFunctionNode(node) + "（预期：true）\n");

        // 2. 节点上线并开始同步
        node = addStatus(node, ONLINE);
        node = addStatus(node, SYNCING);
        System.out.println("2. 上线同步中: " + toString(node));
        System.out.println("   是否可用: " + isAvailable(node) + "（预期：true）\n");

        // 3. 同步完成（移除同步中状态）
        node = removeStatus(node, SYNCING);
        node = addStatus(node, AUTHENTICATED);
        System.out.println("3. 同步完成: " + toString(node) + "\n");

        // 4. 轻量节点（带NAT类型）
        long lightNode = clearAll();
        lightNode = addType(lightNode, LIGHT_NODE);
        lightNode = addType(lightNode, CONE_NAT);
        lightNode = addStatus(lightNode, ONLINE);
        System.out.println("4. 轻量节点: " + toString(lightNode) + "\n");

        // 5. 冲突状态测试（同时设置对称NAT和锥形NAT）
        long conflictNode = addType(lightNode, SYMMETRIC_NAT);
        System.out.println("5. 冲突节点: " + toString(conflictNode));
        System.out.println("   是否存在冲突: " + hasConflict(conflictNode) + "（预期：true）\n");

        // 6. 验证者节点（高负载状态）
        long validator = clearAll();
        validator = addType(validator, FULL_NODE);
        validator = addType(validator, VALIDATOR_NODE);
        validator = addStatus(validator, ONLINE);
        validator = addStatus(validator, HIGH_LOAD);
        System.out.println("6. 验证者节点: " + toString(validator));
        System.out.println("   是否可用: " + isAvailable(validator) + "（预期：true）");
    }
}