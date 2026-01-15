package com.bit.coin.p2p.peer;

import jakarta.annotation.PostConstruct;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@NoArgsConstructor
@Component
public class RoutingTable {
    /* 路由表所有者的ID（节点ID） */
    protected byte[] localNodeId;

    /* 存储桶列表 */
    protected CopyOnWriteArrayList<Bucket> buckets;//读多写少场景

    // 最后访问时间，用于刷新桶
    private final Map<Integer, Long> lastBucketAccessTime = new ConcurrentHashMap<>();

    @Autowired
    private Settings settings;

    /**
     * Spring初始化方法（替代构造方法）
     * 当依赖注入完成后，Spring会自动调用此方法
     */
    @PostConstruct
    public void init() {
        localNodeId = settings.getId();
        buckets = new CopyOnWriteArrayList<>();
        for (int i = 0; i < settings.getIdentifierSize() + 1; i++) {
            buckets.add(createBucketOfId(i));
        }
    }

    private Bucket createBucketOfId(int id) {
        return new Bucket(id);
    }


    /**
     * 添加一个节点到路由表  如果满了就删除一个最后访问的
     */

    /**
     * 找到与这个ID最近的一个节点 根据节点类型
     */

    /**
     * 找到与这个ID最近的20个节点 根据节点类型
     */

    /**
     * 计算两个节点之间的距离
     */


    /**
     * 获取 K桶
     */
    public List<Bucket> getBuckets() {
        return buckets;
    }


    /**
     * 删除节点
     */
    public void delete(Peer peer) {

    }

    public Peer getNode(String nodeId) {
        //不为空 且解码正确
        return getNode(Base58.decode(nodeId));
    }
    public Peer getNode(byte[] nodeId) {
        //不为空
        return null;
    }

    /**
     * 是否包含节点 参数节点ID
     */

    /**
     * 恢复方法  从节点列表一个一个重新加载到路由表
     */

    /**
     * 持久化
     */

}
