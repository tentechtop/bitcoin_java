package com.bit.coin.p2p.peer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 测试Demo：筛选在线的验证者节点
 */
public class ValidatorFilterDemo {

    public static void main(String[] args) {
        // 1. 生成一批模拟节点（包含各种类型和状态）
        List<NodeInfo> allNodes = generateSampleNodes();
        System.out.println("===== 所有节点列表 =====");
        allNodes.forEach(node -> System.out.println(node));

        // 2. 筛选出"在线的验证者节点"
        List<NodeInfo> onlineValidators = filterOnlineValidators(allNodes);

        // 3. 输出筛选结果
        System.out.println("\n===== 在线的验证者节点 =====");
        onlineValidators.forEach(node -> System.out.println(node));
    }

    /**
     * 筛选条件：
     * - 节点类型包含 VALIDATOR_NODE（验证者节点）
     * - 节点状态包含 ONLINE（在线）
     * - 无属性冲突（避免异常状态的节点）
     */
    private static List<NodeInfo> filterOnlineValidators(List<NodeInfo> nodes) {
        return nodes.stream()
                .filter(node -> {
                    long nodeBits = node.getNodeBits();
                    // 验证者类型 + 在线状态 + 无冲突
                    return NodeResolver.hasType(nodeBits, NodeResolver.VALIDATOR_NODE)
                            && NodeResolver.hasStatus(nodeBits, NodeResolver.ONLINE)
                            && !NodeResolver.hasConflict(nodeBits);
                })
                .collect(Collectors.toList());
    }

    /**
     * 生成模拟节点数据（包含不同类型和状态）
     */
    private static List<NodeInfo> generateSampleNodes() {
        List<NodeInfo> nodes = new ArrayList<>();

        // 节点1：在线的验证者节点（正常状态）
        long validator1 = NodeResolver.addType(NodeResolver.clearAll(), NodeResolver.FULL_NODE);
        validator1 = NodeResolver.addType(validator1, NodeResolver.VALIDATOR_NODE);
        validator1 = NodeResolver.addStatus(validator1, NodeResolver.ONLINE);
        nodes.add(new NodeInfo("Validator-001", validator1));

        // 节点2：离线的验证者节点（不满足在线条件）
        long validator2 = NodeResolver.addType(NodeResolver.clearAll(), NodeResolver.VALIDATOR_NODE);
        validator2 = NodeResolver.addStatus(validator2, NodeResolver.SYNCING); // 仅同步中，不在线
        nodes.add(new NodeInfo("Validator-002", validator2));

        // 节点3：在线但被封禁的验证者（有冲突，不合法）
        long validator3 = NodeResolver.addType(NodeResolver.clearAll(), NodeResolver.VALIDATOR_NODE);
        validator3 = NodeResolver.addStatus(validator3, NodeResolver.ONLINE);
        validator3 = NodeResolver.addStatus(validator3, NodeResolver.BLOCKED); // 同时在线和封禁（冲突）
        nodes.add(new NodeInfo("Validator-003", validator3));

        // 节点4：非验证者的全节点（不满足验证者类型）
        long fullNode = NodeResolver.addType(NodeResolver.clearAll(), NodeResolver.FULL_NODE);
        fullNode = NodeResolver.addStatus(fullNode, NodeResolver.ONLINE);
        nodes.add(new NodeInfo("FullNode-001", fullNode));

        // 节点5：在线的轻量验证者节点（合法）
        long lightValidator = NodeResolver.addType(NodeResolver.clearAll(), NodeResolver.LIGHT_NODE);
        lightValidator = NodeResolver.addType(lightValidator, NodeResolver.VALIDATOR_NODE);
        lightValidator = NodeResolver.addStatus(lightValidator, NodeResolver.ONLINE);
        lightValidator = NodeResolver.addStatus(lightValidator, NodeResolver.AUTHENTICATED);
        nodes.add(new NodeInfo("Validator-004", lightValidator));

        return nodes;
    }

    /**
     * 节点信息封装类（包含节点ID和属性位）
     */
    static class NodeInfo {
        private final String nodeId;
        private final long nodeBits;

        public NodeInfo(String nodeId, long nodeBits) {
            this.nodeId = nodeId;
            this.nodeBits = nodeBits;
        }

        public long getNodeBits() {
            return nodeBits;
        }

        @Override
        public String toString() {
            return String.format("节点ID: %s, 属性: %s", nodeId, NodeResolver.toString(nodeBits));
        }
    }
}