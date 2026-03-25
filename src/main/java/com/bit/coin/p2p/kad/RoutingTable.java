package com.bit.coin.p2p.kad;

import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;

import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.p2p.protocol.dto.BroadcastResource;
import com.bit.coin.p2p.conn.QuicConnection;
import com.bit.coin.p2p.conn.QuicConnectionManager;

import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.KeyInfo;
import com.bit.coin.utils.MultiAddress;
import com.bit.coin.utils.UInt256;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;


import static com.bit.coin.config.SystemConfig.PEER_KEY;
import static com.bit.coin.database.rocksDb.RocksDb.intToBytes;
import static com.bit.coin.p2p.conn.QuicConnectionManager.getOnlinePeerIds;
import static com.bit.coin.p2p.conn.QuicConnectionManager.staticSendData;
import static com.bit.coin.p2p.conn.QuicConstants.EXIST_RESOURCE_CACHE;
import static com.bit.coin.utils.Ed25519HDWallet.generateMnemonic;
import static com.bit.coin.utils.Ed25519HDWallet.getSolanaKeyPair;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@NoArgsConstructor
@Component
@Data
@Order(1)
public class RoutingTable {

    private int bucketSize=20;
    private int findNodeSize=20;
    private int identifierSize = 256;//256位 256个桶 32*8

    // 增量触发阈值：累计新增/删除节点数达到50，触发一次持久化
    private int persistThreshold = 50;
    // 节点变动计数器（原子类保证线程安全）
    private AtomicInteger nodeChangeCount = new AtomicInteger(0);
    // 定时任务线程池（单线程即可，避免并发冲突）
    private ScheduledExecutorService persistScheduler;
    // 定时检查周期：1分钟（单位：毫秒）
    private static final long PERSIST_CHECK_PERIOD = 60 * 1000L;

    // 路由表刷新相关配置
    // 刷新周期：10分钟（Kademlia 标准）
    private static final long ROUTING_TABLE_REFRESH_PERIOD = 10 * 60 * 1000L;
    // 每个桶刷新时的并发查询数
    private static final int REFRESH_PARALLELISM = 3;
    // 单次查询超时时间
    private static final int REFRESH_QUERY_TIMEOUT = 3000;
    // 路由表刷新定时任务
    private ScheduledFuture<?> refreshTask;
    // 避免并发刷新的锁
    private final ReentrantLock refreshLock = new ReentrantLock();
    // 标记是否正在刷新
    private final AtomicBoolean isRefreshing = new AtomicBoolean(false);



    @Autowired
    private SystemConfig config;


    /* 路由表所有者的ID（节点ID）32字节公钥 */
    private byte[] localNodeId;

    /* 存储桶列表 */
    private CopyOnWriteArrayList<Bucket> buckets;//读多写少场景

    // 最后访问时间，用于刷新桶
    private final Map<Integer, Long> lastBucketAccessTime = new ConcurrentHashMap<>();

    private DataBase dataBase;

    // 在类变量区域添加
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private final Map<String, Future<List<Peer>>> pendingQueries = new ConcurrentHashMap<>();
    private final ReentrantLock queryLock = new ReentrantLock();


    /**
     * Spring初始化方法（替代构造方法）
     * 当依赖注入完成后，Spring会自动调用此方法
     */
    @PostConstruct
    public void init() throws Exception{
        dataBase = config.getDataBase();

        MultiAddress address = MultiAddress.build(
                "ip4",
                "127.0.0.1",
                MultiAddress.Protocol.QUIC,
                SystemConfig.SelfPeer.getPort(),
                Base58.encode(SystemConfig.SelfPeer.getId())
        );
        log.info("解释地址: {}" ,address.toRawAddress());
        log.info("地址: {}" ,address.getPeerIdHex());

        localNodeId = SystemConfig.SelfPeer.getId();
        buckets = new CopyOnWriteArrayList<>();
        for (int i = 0; i < identifierSize + 1; i++) {
            buckets.add(createBucketOfId(i));
        }

        // ========== 新增：初始化时恢复路由表节点 ==========
        restoreFromDatabase();

        // ========== 新增：启动定时持久化检查任务 ==========
        initPersistScheduler();

        // ========== 新增：启动路由表刷新任务 ==========
        initRefreshScheduler();
    }



    /**
     * 初始化路由表定时刷新任务
     */
    private void initRefreshScheduler() {
        // 复用持久化的定时线程池（单线程避免并发冲突）
        refreshTask = persistScheduler.scheduleAtFixedRate(
                this::refreshRoutingTable,
                ROUTING_TABLE_REFRESH_PERIOD, // 首次执行延迟10分钟
                ROUTING_TABLE_REFRESH_PERIOD, // 之后每10分钟执行一次
                TimeUnit.MILLISECONDS
        );
        log.info("路由表定时刷新任务已启动，刷新周期：{}分钟", ROUTING_TABLE_REFRESH_PERIOD / 60000);
    }

    /**
     * 初始化定时持久化任务：每10分钟检查一次，有变动则持久化
     */
    private void initPersistScheduler() {
        // 单线程定时任务池，避免并发持久化冲突
        persistScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "routing-table-persist-scheduler");
            thread.setDaemon(true); // 守护线程，应用关闭时自动退出
            return thread;
        });

        persistScheduler.scheduleAtFixedRate(
                this::checkAndPersist,
                PERSIST_CHECK_PERIOD,
                PERSIST_CHECK_PERIOD,
                TimeUnit.MILLISECONDS
        );
        log.info("路由表定时持久化任务已启动，检查周期：{}分钟", PERSIST_CHECK_PERIOD / 60000);
    }

    /**
     * 定时检查并执行持久化（有变动才执行）
     */
    private void checkAndPersist() {
        try {
            int changeCount = nodeChangeCount.get();
            if (changeCount > 0) {
                log.info("定时检查发现{}个节点变动，执行持久化", changeCount);
                persistToDatabase();
                // 持久化成功后重置计数器
                nodeChangeCount.set(0);

            } else {
                log.debug("定时检查无节点变动，跳过持久化");
            }
        } catch (Exception e) {
            log.error("定时持久化检查执行失败", e);
        }
    }





    private Bucket createBucketOfId(int id) {
        return new Bucket(id);
    }

    /**
     * 计算两个节点ID的异或距离（Kad协议核心）
     * @param nodeId1 节点1 ID（32字节）
     * @param nodeId2 节点2 ID（32字节）
     * @return 异或结果（32字节），距离越大表示节点越远
     */
    public byte[] calculateDistance(byte[] nodeId1, byte[] nodeId2) {
        if (nodeId1 == null || nodeId2 == null || nodeId1.length != 32 || nodeId2.length != 32) {
            throw new IllegalArgumentException("节点ID必须是32字节的非空数组");
        }
        byte[] distance = new byte[32];
        for (int i = 0; i < 32; i++) {
            distance[i] = (byte) (nodeId1[i] ^ nodeId2[i]);
        }
        return distance;
    }

    /**
     * 计算目标节点ID对应的K桶索引
     * @param targetNodeId 目标节点ID（32字节）
     * @return 桶索引（0-255），索引越大表示距离越近
     */
    public int getBucketIndex(byte[] targetNodeId) {
        byte[] distance = calculateDistance(localNodeId, targetNodeId);
        // 找到异或结果中最高有效位的位置
        for (int i = 0; i < 32; i++) {
            byte b = distance[i];
            if (b == 0) continue;
            // 计算当前字节内的最高位
            for (int j = 7; j >= 0; j--) {
                if ((b & (1 << j)) != 0) {
                    return 255 - (i * 8 + j); // 转换为0-255的桶索引
                }
            }
        }
        // 异或结果为0（目标节点是本地节点），返回0号桶
        return 0;
    }

    /**
     * 添加一个节点到路由表（遵循Kad协议规则）
     * 规则：
     * 1. 若节点是本地节点，忽略
     * 2. 若桶中已存在该节点，移到桶头部（更新活跃度）
     * 3. 若桶未满，添加到桶头部
     * 4. 若桶已满，移除桶尾部最不活跃节点，添加新节点到头部
     * @param peer 要添加的节点
     */
    public void addPeer(Peer peer) {
        if (peer == null) {
            log.warn("尝试添加空节点，忽略");
            return;
        }
        peer.setLastSeen(System.currentTimeMillis());
        byte[] peerId = peer.getId();
        // 跳过本地节点
        if (Arrays.equals(peerId, localNodeId)) {
            return;
        }
        boolean isNodeChanged = false; // 标记是否发生节点变动
        try {
            int bucketIndex = getBucketIndex(peerId);
            Bucket bucket = buckets.get(bucketIndex);
            String peerIdHex = bytesToHex(peerId);

            // 更新桶最后访问时间
            lastBucketAccessTime.put(bucketIndex, System.currentTimeMillis());

            synchronized (bucket) { // 桶级别加锁，保证操作原子性
                if (bucket.contains(peerIdHex)) {
                    // 节点已存在，移到头部（更新活跃度）
                    bucket.pushToFront(peer);
                    log.debug("节点{}已存在，更新活跃度并移到桶{}头部", peerIdHex, bucketIndex);
                    isNodeChanged = true; // 新增节点，标记变动
                } else {
                    if (bucket.size() < bucketSize) {
                        // 桶未满，直接添加到头部
                        bucket.add(peer);
                        log.info("桶{}未满，添加节点{}到头部", bucketIndex, peerIdHex);
                        isNodeChanged = true; // 新增节点，标记变动
                    } else {
                        //TODO
                        // 桶已满，移除尾部节点，添加新节点到头部（Kad协议简化实现，可扩展为检查尾节点存活状态）
                        String lastPeerId = bucket.getNodeIds().iterator().hasNext() ?
                                bucket.getNodeIds().iterator().next() : null; // 尾节点ID
                        if (lastPeerId != null) {
                            Peer lastPeer = bucket.getNode(lastPeerId);
                            bucket.remove(lastPeer);
                            log.debug("桶{}已满，移除最不活跃节点{}", bucketIndex, lastPeerId);
                            isNodeChanged = true; // 移除旧节点+添加新节点，标记变动
                        }
                        bucket.add(peer);
                        log.debug("桶{}已满，添加新节点{}到头部", bucketIndex, peerIdHex);
                        isNodeChanged = true; // 新增节点，标记变动
                    }
                }
            }
        } catch (Exception e) {
            log.error("添加节点{}失败", bytesToHex(peerId), e);
        }

        // ========== 新增：节点变动时更新计数器，达到阈值则立即持久化 ==========
        if (isNodeChanged) {
            int currentCount = nodeChangeCount.incrementAndGet();
            log.debug("节点变动计数：{}", currentCount);
            // 累计达到50次变动，立即持久化
            if (currentCount >= persistThreshold) {
                log.info("累计节点变动达到{}次，触发立即持久化", persistThreshold);
                persistToDatabase();
                nodeChangeCount.set(0); // 重置计数器
            }
        }
    }

    //仅仅更新最后访问时间
    public void updateLastSeenByBucket(byte[] peerId) {
        if (peerId == null || peerId.length != 32) {
            log.warn("节点ID格式错误，忽略");
            return;
        }
        //找到该节点所在的桶子
        int bucketIndex = getBucketIndex(peerId);
        Bucket bucket = buckets.get(bucketIndex);
        //更新节点最后访问时间
        bucket.updateLastSeen(peerId);
    }





    /**
     * 找到与目标ID最近的一个节点
     * @param targetId 目标节点ID（32字节）
     * @return 最近的节点，无则返回null
     */
    public Peer findClosestPeer(byte[] targetId) {
        List<Peer> closestPeers = findClosestPeers(targetId, 1);
        return closestPeers.isEmpty() ? null : closestPeers.get(0);
    }


    /**
     * 找到与目标ID最近的N个节点（Kad协议find_node）
     * @param targetId 目标节点ID（32字节）
     * @param count 要查找的节点数量（最大为findNodeSize=20）
     * @return 按距离升序排列的节点列表
     */
    /**
     * 找到与目标ID最近的N个节点（Kad协议find_node）
     * @param targetId 目标节点ID（32字节）
     * @param count 要查找的节点数量（最大为findNodeSize=20）
     * @return 按距离升序排列的节点列表
     */
    public List<Peer> findClosestPeers(byte[] targetId, int count) {
        if (targetId == null || targetId.length != 32) {
            log.warn("目标ID格式错误，返回空列表");
            return Collections.emptyList();
        }

        // ===================== 新增逻辑：在线节点<20则返回全部 =====================
        int onlineCount = getOnlinePeerCount();
        int maxCount;
        if (onlineCount < 20) {
            maxCount = Integer.MAX_VALUE; // 不限制数量，返回所有节点
            log.debug("在线节点数{} < 20，查询最近节点时返回全部节点", onlineCount);
        } else {
            maxCount = Math.min(count, findNodeSize); // 正常逻辑，限制20个
        }

        // 收集所有桶中的节点，并计算与目标ID的距离
        Map<Peer, byte[]> peerDistanceMap = new HashMap<>();
        for (Bucket bucket : buckets) {
            for (Peer peer : bucket.getNodesArrayList()) {
                if (peer != null) {
                    peerDistanceMap.put(peer, calculateDistance(peer.getId(), targetId));
                }
            }
        }

        // 按距离升序排序（异或结果越小，距离越近）
        return peerDistanceMap.entrySet().stream()
                .sorted((e1, e2) -> compareDistance(e1.getValue(), e2.getValue()))
                .map(Map.Entry::getKey)
                .limit(maxCount) // 在线<20时无限制，正常则限制20
                .collect(Collectors.toList());
    }

    /**
     * 比较两个异或距离的大小（用于排序）
     * @param d1 距离1
     * @param d2 距离2
     * @return d1 < d2 返回-1，d1 > d2 返回1，相等返回0
     */
    private int compareDistance(byte[] d1, byte[] d2) {
        for (int i = 0; i < 32; i++) {
            int b1 = d1[i] & 0xFF;
            int b2 = d2[i] & 0xFF;
            if (b1 != b2) {
                return Integer.compare(b1, b2);
            }
        }
        return 0;
    }

    /**
     * 根据节点ID（Base58格式）获取节点
     * @param nodeId Base58编码的节点ID
     * @return 节点信息，无则返回null
     */
    public Peer getNodeByBase58Id(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return null;
        }
        try {
            byte[] nodeIdBytes = Base58.decode(nodeId);
            return getNode(nodeIdBytes);
        } catch (Exception e) {
            log.error("解码Base58节点ID{}失败", nodeId, e);
            return null;
        }
    }


    /**
     * 根据节点ID（字节数组）获取节点
     * @param nodeId 32字节的节点ID
     * @return 节点信息，无则返回null
     */
    public Peer getNode(byte[] nodeId) {
        if (nodeId == null || nodeId.length != 32) {
            return null;
        }
        try {
            int bucketIndex = getBucketIndex(nodeId);
            Bucket bucket = buckets.get(bucketIndex);
            String nodeIdHex = bytesToHex(nodeId);
            return bucket.getNode(nodeIdHex);
        } catch (Exception e) {
            log.error("获取节点{}失败", bytesToHex(nodeId), e);
            return null;
        }
    }

    public Peer getNodeByHexId(String hexId) {
        return getNode(hexToBytes(hexId));
    }

    /**
     * 检查是否包含指定节点
     * @param nodeId 32字节的节点ID
     * @return 包含返回true，否则false
     */
    public boolean contains(byte[] nodeId) {
        if (nodeId == null || nodeId.length != 32) {
            return false;
        }
        try {
            int bucketIndex = getBucketIndex(nodeId);
            Bucket bucket = buckets.get(bucketIndex);
            String nodeIdHex = bytesToHex(nodeId);
            return bucket.contains(nodeIdHex);
        } catch (Exception e) {
            log.error("检查节点{}是否存在失败", bytesToHex(nodeId), e);
            return false;
        }
    }

    /**
     * 检查是否包含指定节点（Base58格式ID）
     * @param nodeId Base58编码的节点ID
     * @return 包含返回true，否则false
     */
    public boolean contains(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return false;
        }
        try {
            byte[] nodeIdBytes = Base58.decode(nodeId);
            return contains(nodeIdBytes);
        } catch (Exception e) {
            log.error("检查节点{}是否存在失败", nodeId, e);
            return false;
        }
    }

    /**
     * 从路由表中删除指定节点
     * @param peer 要删除的节点
     */
    public void delete(Peer peer) {
        if (peer == null) {
            log.warn("尝试删除空节点，忽略");
            return;
        }
        delete(peer.getId());
    }

    /**
     * 从路由表中删除指定ID的节点
     * @param nodeId 32字节的节点ID
     */
    public void delete(byte[] nodeId) {
        if (nodeId == null || nodeId.length != 32) {
            log.warn("节点ID格式错误，忽略删除操作");
            return;
        }
        boolean isNodeChanged = false; // 标记是否发生节点变动
        try {
            int bucketIndex = getBucketIndex(nodeId);
            Bucket bucket = buckets.get(bucketIndex);
            String nodeIdHex = bytesToHex(nodeId);

            synchronized (bucket) {
                Peer peer = bucket.getNode(nodeIdHex);
                if (peer != null) {
                    bucket.remove(peer);
                    log.debug("从桶{}中删除节点{}", bucketIndex, nodeIdHex);
                    isNodeChanged = true; // 删除节点，标记变动
                }
            }
        } catch (Exception e) {
            log.error("删除节点{}失败", bytesToHex(nodeId), e);
        }

        // ========== 新增：节点变动时更新计数器，达到阈值则立即持久化 ==========
        if (isNodeChanged) {
            int currentCount = nodeChangeCount.incrementAndGet();
            log.debug("节点变动计数：{}", currentCount);
            // 累计达到50次变动，立即持久化
            if (currentCount >= persistThreshold) {
                log.info("累计节点变动达到{}次，触发立即持久化", persistThreshold);
                persistToDatabase();
                nodeChangeCount.set(0); // 重置计数器
            }
        }
    }

    /**
     * 从路由表中删除指定ID的节点（Base58格式）
     * @param nodeId Base58编码的节点ID
     */
    public void delete(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return;
        }
        try {
            byte[] nodeIdBytes = Base58.decode(nodeId);
            delete(nodeIdBytes);
        } catch (Exception e) {
            log.error("删除节点{}失败", nodeId, e);
        }
    }



    /**
     * 获取 K桶
     */
    public List<Bucket> getBuckets() {
        return buckets;
    }


    /**
     * 持久化路由表中的所有节点到数据库
     */
    public void persistToDatabase() {
        try {
            // 收集所有节点
            List<Peer> allPeers = new ArrayList<>();
            for (Bucket bucket : buckets) {
                allPeers.addAll(bucket.getNodesArrayList());
            }
            if (allPeers.isEmpty()) {
                log.debug("路由表为空，无需持久化");
                return;
            }

            // 序列化节点列表（这里假设Peer类支持列表序列化，或自定义序列化逻辑）
            byte[] serializedPeers = serializePeers(allPeers);
            dataBase.insert(TableEnum.PEER, "ROUTING_TABLE_PEERS".getBytes(StandardCharsets.UTF_8), serializedPeers);
            log.info("成功持久化{}个节点到路由表", allPeers.size());
        } catch (Exception e) {
            log.error("持久化路由表失败", e);
        }
    }


    /**
     * 从数据库恢复路由表节点
     */
    public void restoreFromDatabase() {
        log.info("从数据库回复节点");
        try {
            byte[] serializedPeers = dataBase.get(TableEnum.PEER, "ROUTING_TABLE_PEERS".getBytes());
            if (serializedPeers == null || serializedPeers.length == 0) {
                log.info("无持久化的路由表节点数据");
                return;
            }

            // 反序列化节点列表
            List<Peer> peers = deserializePeers(serializedPeers);
            log.info("从数据库恢复{}个节点", peers.size());

            // 逐个添加到路由表
            restoreFromPeerList(peers);
        } catch (Exception e) {
            log.error("恢复路由表失败", e);
        }
    }


    /**
     * 从节点列表重新加载到路由表（恢复方法）
     * @param peerList 待加载的节点列表
     */
    public void restoreFromPeerList(List<Peer> peerList) {
        if (peerList == null || peerList.isEmpty()) {
            return;
        }
        log.info("开始从节点列表恢复路由表，共{}个节点", peerList.size());
        for (Peer peer : peerList) {
            try {
                //打印节点最后访问时间 转yyyy-mm-ss
                log.info("最后访问时间:{}", new SimpleDateFormat("yyyy-MM-dd").format(new Date(peer.getLastSeen())));
                //只添加3天内活跃的节点
                if (peer.getLastSeen() > System.currentTimeMillis() - 3 * 24 * 60 * 60 * 1000) {
                    log.info("活跃");
                    addPeer(peer);
                }else {
                    log.info("不活跃");
                }
            } catch (Exception e) {
                log.error("恢复节点{}失败", bytesToHex(peer.getId()), e);
            }
        }
        log.info("路由表恢复完成");

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                int onlineCount = getOnlinePeerCount();
                List<Peer> peersToConnect;

                // ===================== 核心逻辑：在线<20则连接所有节点 =====================
                if (onlineCount < 20) {
                    peersToConnect = getAll();
                    log.info("在线节点数{} < 20，自动连接路由表中所有节点", onlineCount);
                } else {
                    peersToConnect = getAll(); // 原逻辑保留
                    log.info("在线节点数≥20，正常执行节点连接");
                }

                // 遍历连接节点
                for (Peer peer : peersToConnect) {
                    log.info("连接节点：{}", peer.getMultiaddr());
                    try {
                        QuicConnection quicConnection = QuicConnectionManager.connectRemoteByAddr(peer.getMultiaddr());
                        if (quicConnection != null) {
                            findNodeIterative(peer.getId(), localNodeId);
                        }
                    } catch (Exception e) {
                        log.error("连接节点{}失败", peer.getMultiaddr(), e);
                    }
                }
            }
        }, 5000);
    }

    // ===================== 序列化/反序列化工具方法（需根据Peer类调整） =====================

    /**
     * 序列化节点列表（示例实现，需根据实际Peer序列化逻辑调整）
     * @param peers 节点列表
     * @return 序列化后的字节数组
     */
    public byte[] serializePeers(List<Peer> peers) throws Exception {
        // 这里仅为示例，实际需根据Peer的serialize()方法实现列表序列化
        // 例如：使用ByteBuffer拼接所有节点的序列化字节
        List<Byte> byteList = new ArrayList<>();
        for (Peer peer : peers) {
            byte[] peerBytes = peer.serialize();
            // 先写入长度，再写入数据
            for (byte b : ByteBuffer.allocate(4).putInt(peerBytes.length).array()) {
                byteList.add(b);
            }
            for (byte b : peerBytes) {
                byteList.add(b);
            }
        }
        byte[] result = new byte[byteList.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = byteList.get(i);
        }
        return result;
    }

    /**
     * 反序列化节点列表（示例实现，需与serializePeers对应）
     * @param bytes 序列化后的字节数组
     * @return 节点列表
     */
    public List<Peer> deserializePeers(byte[] bytes) throws Exception {
        List<Peer> peers = new ArrayList<>();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= 4) {
            // 先读取长度
            int len = buffer.getInt();
            if (buffer.remaining() < len) {
                break;
            }
            // 读取节点数据
            byte[] peerBytes = new byte[len];
            buffer.get(peerBytes);
            Peer peer = Peer.deserialize(peerBytes);
            peers.add(peer);
        }
        return peers;
    }

    /**
     * 获取路由表中所有的有效 Peer 节点
     * @return 包含所有有效节点的列表（过滤 null 和本地节点）
     */
    public List<Peer> getAll() {
        // 初始化线程安全的列表
        List<Peer> allValidPeers = new ArrayList<>();
        // 遍历所有K桶
        for (Bucket bucket : buckets) {
            if (bucket == null) continue;
            // 直接遍历nodeMap的values（ConcurrentHashMap的values是线程安全的）
            for (Peer peer : bucket.getNodeMap().values()) {
                if (peer == null || Arrays.equals(peer.getId(), localNodeId)) {
                    continue;
                }
                // 基于equals判断（已实现），避免重复
                if (!allValidPeers.contains(peer)) {
                    allValidPeers.add(peer);
                }
            }
        }
        // 返回不可修改的列表，避免外部修改
        return Collections.unmodifiableList(allValidPeers);
    }

    public void BroadcastResource(int i, byte[] hash) {
        BroadcastResource broadcastResource = new BroadcastResource();
        broadcastResource.setHash(hash);
        broadcastResource.setType(i);
        //将这个消息广播给所有的在线节点
        List<Peer> onlineNodes = getOnlineNodes();
        log.info("广播消息{}",onlineNodes.size());
        for (Peer peer : onlineNodes) {
            log.info("发送消息给节点{}",bytesToHex(peer.getId()));
            staticSendData(bytesToHex(peer.getId()), ProtocolEnum.P2P_Broadcast_Simple_Resource, broadcastResource.toBytes(), 5000);
        }
        log.info("全部发送完毕");
        //将资源加入到已经存在的列表 保持
        EXIST_RESOURCE_CACHE.put(bytesToHex(hash),i);
    }

    public List<Peer> getOnlineNodes() {
        List<Peer> all = getAll();
        Set<String> onlinePeerIds = getOnlinePeerIds();//all的id一定要在onlinePeerIds中
        List<Peer> onlineNodes = all.stream()
                .filter(peer -> onlinePeerIds.contains(bytesToHex(peer.getId())))
                .toList();
        //onlineNodes中的online字段改成true
        onlineNodes.forEach(peer -> peer.setOnline(true));
        return onlineNodes;
    }

    //获取路由表 且按照桶排序 返回list<list<peer>>
    /**
     * 获取路由表 且按照桶排序 返回list<list<peer>>
     * 外层List：按桶ID从小到大排序的所有K桶
     * 内层List：对应K桶中的所有有效节点（过滤本地节点，保证线程安全）
     * @return 按桶排序的节点列表，空桶对应空的内层List
     */
    public List<List<Peer>> getRoutingTable() {
        // 1. 初始化结果列表，容量与桶数量一致，保证顺序
        List<List<Peer>> routingTable = new ArrayList<>(buckets.size());

        // 2. 遍历所有桶（CopyOnWriteArrayList遍历天然线程安全）
        for (Bucket bucket : buckets) {
            if (bucket == null) {
                // 空桶添加空列表
                routingTable.add(Collections.emptyList());
                continue;
            }

            // 3. 线程安全地获取桶内节点，并过滤本地节点
            List<Peer> bucketPeers = new ArrayList<>();
            // 加锁保证桶内节点读取的原子性（与add/delete操作的锁对齐）
            synchronized (bucket) {
                for (Peer peer : bucket.getNodesArrayList()) {
                    // 过滤null节点和本地节点，避免返回无效数据
                    if (peer != null && !Arrays.equals(peer.getId(), localNodeId)) {
                        bucketPeers.add(peer);
                    }
                }
            }

            // 4. 转为不可修改列表，防止外部修改内部数据
            routingTable.add(Collections.unmodifiableList(bucketPeers));
        }

        // 5. 返回不可修改的外层列表，保证数据安全性
        return Collections.unmodifiableList(routingTable);
    }

    public void updateLatest(String peerId, String hashHex, int height, UInt256 chainWork) {
        //获取节点并更新
        Peer peer = getNodeByHexId(peerId);
        if (peer!=null){
            //如果当前高度大于过去记录的高度就更新
            if (height>peer.getHeight()){
                peer.setHashHex(hashHex);
                peer.setHeight(height);
                peer.setChainWork(chainWork);
            }
            addPeer(peer);
        }
    }




    //更新节点的最后访问时间
    public void updateLastSeen(String peerId) {
        Peer peer = getNodeByHexId(peerId);
        if (peer!=null){
            peer.setLastSeen(System.currentTimeMillis());
            addPeer(peer);
        }
    }




    /**
     * 辅助类：存储节点和距离
     */
    private static class PeerDistance implements Comparable<PeerDistance> {
        final Peer peer;
        final byte[] distance;
        final String distanceHex; // 用于比较

        PeerDistance(Peer peer, byte[] distance) {
            this.peer = peer;
            this.distance = distance;
            this.distanceHex = bytesToHex(distance);
        }

        @Override
        public int compareTo(PeerDistance other) {
            // 距离越小，优先级越高
            for (int i = 0; i < distance.length; i++) {
                int cmp = (distance[i] & 0xFF) - (other.distance[i] & 0xFF);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        }
    }


    /**
     * 向目标节点发起节点查询请求
     * @param targetId
     * @param searchId
     */
    public List<Peer> sendFindNode(byte[] targetId,byte[] searchId)  {
        try {
            int count = 20;
            byte[] data = new byte[36];
            //[32字节查询节点][4字节数量]
            System.arraycopy(searchId, 0, data, 0, 32);
            System.arraycopy(intToBytes(count), 0, data, 32, 4);
            log.info("请求sendFindNode");
            byte[] p2pRes = staticSendData(bytesToHex(targetId), ProtocolEnum.P2P_Find_Node_Req, data, 5000);
            P2PMessage deserialize = P2PMessage.deserialize(p2pRes);
            byte[] res = deserialize.getData();
            if (res!=null){
                List<Peer> peers = deserializePeers(res);
                //通过返回的节点迭代查询
                log.info("返回的数据长度{}",peers.size());
                for (Peer peer : peers){
                    log.info("返回的节点{}",peer.getAddress());
                    byte[] id = peer.getId();
                    log.info("节点ID{}",Base58.encode(id));
                    log.info("Hex节点ID{}",bytesToHex(id));
                    //如果不是本地节点就迭代查询
                    return peers;
                }
            }
            return null;
        }catch (Exception e){
            log.error("发送失败{}",e.getMessage());
            return null;
        }
    }



    /**
     * 执行Kademlia迭代查询查找节点
     * @param searchIdHex 查找节点ID的Hex字符串
     * @return 查找到的最近节点列表
     */
    public List<Peer> iterativeFindNode(String searchIdHex) {
        return iterativeFindNode(hexToBytes(searchIdHex), 20, 3, 2000);
    }

    public List<Peer> iterativeFindNode(byte[] searchId) {
        return iterativeFindNode(searchId, 20, 3, 2000);
    }

    /**
     * 执行Kademlia迭代查询查找节点
     * @param searchId 查询节点ID字节数组
     * @param k 每次查询返回的节点数量
     * @param parallelism 并发查询的节点数
     * @param timeoutMillis 单次查询超时时间(毫秒)
     * @return 查找到的最近节点列表
     */
    public List<Peer> iterativeFindNode(byte[] searchId, int k, int parallelism, int timeoutMillis) {
        if (searchId == null || searchId.length != 32) {
            log.warn("目标节点ID格式错误");
            return Collections.emptyList();
        }

        // 1. 初始化最近节点列表
        List<Peer> closestPeers = new CopyOnWriteArrayList<>();
        Set<String> queriedNodes = ConcurrentHashMap.newKeySet();
        Set<String> contactedNodes = ConcurrentHashMap.newKeySet();
        PriorityQueue<PeerDistance> alphaNodes = new PriorityQueue<>();

        // 2. 从本地路由表中获取初始的α个最近节点
        List<Peer> initialPeers = findClosestPeers(searchId, parallelism);
        for (Peer peer : initialPeers) {
            if (peer != null) {
                byte[] distance = calculateDistance(peer.getId(), searchId);
                alphaNodes.offer(new PeerDistance(peer, distance));
            }
        }

        // 3. 迭代查询
        int maxRounds = 10; // 最大迭代轮数
        for (int round = 0; round < maxRounds; round++) {
            if (alphaNodes.isEmpty()) {
                break;
            }

            // 3.1 从α节点中选择parallelism个节点并发查询
            List<CompletableFuture<List<Peer>>> futures = new ArrayList<>();
            int queriesInRound = 0;

            while (!alphaNodes.isEmpty() && queriesInRound < parallelism) {
                PeerDistance peerDist = alphaNodes.poll();
                Peer peer = peerDist.peer;
                String peerIdHex = bytesToHex(peer.getId());

                if (queriedNodes.contains(peerIdHex)) {
                    continue;
                }

                queriedNodes.add(peerIdHex);
                contactedNodes.add(peerIdHex);

                // 异步发送查询请求
                CompletableFuture<List<Peer>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return sendFindNodeToPeer(peer, searchId, k, timeoutMillis);
                    } catch (Exception e) {
                        log.debug("查询节点{}失败: {}", peerIdHex, e.getMessage());
                        return Collections.<Peer>emptyList();
                    }
                }, executor);

                futures.add(future);
                queriesInRound++;
            }

            // 3.2 等待所有查询完成
            try {
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                        futures.toArray(new CompletableFuture[0])
                );
                allFutures.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.debug("等待查询结果超时: {}", e.getMessage());
            }

            // 3.3 收集查询结果
            Set<Peer> discoveredPeers = new HashSet<>();
            for (CompletableFuture<List<Peer>> future : futures) {
                try {
                    if (future.isDone() && !future.isCompletedExceptionally()) {
                        List<Peer> result = future.get();
                        discoveredPeers.addAll(result);
                    }
                } catch (Exception e) {
                    // 忽略异常
                }
            }

            // 3.4 更新最近节点列表
            for (Peer peer : discoveredPeers) {
                if (peer == null || Arrays.equals(peer.getId(), localNodeId)) {
                    continue;
                }

                String peerIdHex = bytesToHex(peer.getId());
                if (contactedNodes.contains(peerIdHex)) {
                    continue;
                }

                byte[] distance = calculateDistance(peer.getId(), searchId);
                contactedNodes.add(peerIdHex);

                // 添加到最近节点列表
                synchronized (closestPeers) {
                    // 如果列表未满，直接添加
                    if (closestPeers.size() < k) {
                        closestPeers.add(peer);
                        addToPriorityQueue(alphaNodes, new PeerDistance(peer, distance), k);
                    } else {
                        // 如果列表已满，比较距离
                        Peer farthest = getFarthestPeer(closestPeers, searchId);
                        byte[] farthestDist = calculateDistance(farthest.getId(), searchId);

                        if (compareDistance(distance, farthestDist) < 0) {
                            closestPeers.remove(farthest);
                            closestPeers.add(peer);
                            addToPriorityQueue(alphaNodes, new PeerDistance(peer, distance), k);
                        }
                    }
                }
            }

            // 3.5 检查收敛条件
            if (isConverged(alphaNodes, closestPeers, searchId)) {
                log.debug("迭代查询在第{}轮收敛", round + 1);
                break;
            }

            // 3.6 准备下一轮查询
            List<PeerDistance> remainingNodes = new ArrayList<>();
            while (!alphaNodes.isEmpty()) {
                remainingNodes.add(alphaNodes.poll());
            }

            // 添加新发现的节点到优先级队列
            synchronized (closestPeers) {
                for (Peer peer : closestPeers) {
                    String peerIdHex = bytesToHex(peer.getId());
                    if (!queriedNodes.contains(peerIdHex)) {
                        byte[] distance = calculateDistance(peer.getId(), searchId);
                        remainingNodes.add(new PeerDistance(peer, distance));
                    }
                }
            }

            // 排序并重新填充alphaNodes
            remainingNodes.sort(Comparator.comparing(pd -> pd.distanceHex));
            for (int i = 0; i < Math.min(parallelism, remainingNodes.size()); i++) {
                alphaNodes.offer(remainingNodes.get(i));
            }

            log.debug("第{}轮查询完成，发现{}个节点，最近节点列表: {}",
                    round + 1, discoveredPeers.size(), closestPeers.size());
        }

        // 4. 对最终结果排序
        closestPeers.sort((p1, p2) -> {
            byte[] d1 = calculateDistance(p1.getId(), searchId);
            byte[] d2 = calculateDistance(p2.getId(), searchId);
            return compareDistance(d1, d2);
        });

        return closestPeers;
    }

    /**
     * 向指定的targetId节点发起针对searchId的迭代查询（递归迭代直到无新节点返回）
     * @param targetId 初始查询的目标节点ID（32字节）
     * @param searchId 要查找的目标节点ID（32字节）
     * @param k 每次查询返回的节点数量
     * @param parallelism 并发查询数
     * @param timeoutMillis 单次查询超时时间(毫秒)
     * @return 所有找到的节点列表（按与searchId的距离升序排列）
     */
    public List<Peer> findNodeIterative(byte[] targetId, byte[] searchId, int k, int parallelism, int timeoutMillis) {
        // 1. 参数校验
        if (targetId == null || targetId.length != 32 || searchId == null || searchId.length != 32) {
            log.warn("targetId或searchId格式错误（必须32字节）");
            return Collections.emptyList();
        }

        // 2. 初始化核心容器（线程安全）
        // 待查询的节点队列（初始放入指定的targetId节点）
        ConcurrentLinkedQueue<Peer> toQueryQueue = new ConcurrentLinkedQueue<>();
        // 已查询过的节点ID（去重，避免重复查询）
        Set<String> queriedNodeIds = ConcurrentHashMap.newKeySet();
        // 最终收集到的所有有效节点
        Set<Peer> discoveredPeers = ConcurrentHashMap.newKeySet();

        // 3. 初始化队列：获取初始targetId对应的节点并加入队列
        Peer initialPeer = getNode(targetId);
        if (initialPeer != null) {
            String initialPeerIdHex = bytesToHex(initialPeer.getId());
            queriedNodeIds.add(initialPeerIdHex);
            toQueryQueue.offer(initialPeer);
            log.info("初始查询节点加入队列: {}", initialPeerIdHex.substring(0, 8) + "...");
        } else {
            log.warn("初始目标节点{}不存在于路由表，无法发起迭代查询", bytesToHex(targetId).substring(0, 8) + "...");
            return Collections.emptyList();
        }

        // 4. 核心迭代逻辑：直到队列为空（无新节点可查）
        while (!toQueryQueue.isEmpty()) {
            // 取出队列头部的节点进行查询
            Peer currentPeer = toQueryQueue.poll();
            if (currentPeer == null) {
                continue;
            }

            String currentPeerIdHex = bytesToHex(currentPeer.getId());
            try {
                // 5. 向当前节点发送FindNode请求
                List<Peer> responsePeers = sendFindNodeToPeer(currentPeer, searchId, k, timeoutMillis);

                // 6. 处理返回结果：过滤+去重+加入队列
                if (responsePeers != null && !responsePeers.isEmpty()) {
                    log.debug("从节点{}获取到{}个新节点", currentPeerIdHex.substring(0, 8) + "...", responsePeers.size());

                    for (Peer peer : responsePeers) {
                        // 过滤无效节点、本地节点
                        if (peer == null || Arrays.equals(peer.getId(), localNodeId)) {
                            continue;
                        }

                        String peerIdHex = bytesToHex(peer.getId());
                        // 过滤已查询/已发现的节点
                        if (queriedNodeIds.contains(peerIdHex) || discoveredPeers.contains(peer)) {
                            continue;
                        }

                        // 7. 新增节点处理：加入发现列表+待查询队列+路由表
                        discoveredPeers.add(peer);
                        addPeer(peer); // 加入本地路由表
                        queriedNodeIds.add(peerIdHex); // 标记为待查询（避免重复加入队列）
                        toQueryQueue.offer(peer); // 加入查询队列，继续迭代

                        log.debug("新增节点{}加入迭代队列", peerIdHex.substring(0, 8) + "...");
                    }
                } else {
                    log.debug("节点{}无有效返回，停止该分支迭代", currentPeerIdHex.substring(0, 8) + "...");
                }
            } catch (Exception e) {
                log.error("查询节点{}失败: {}", currentPeerIdHex.substring(0, 8) + "...", e.getMessage());
                // 单个节点查询失败不终止整体迭代，继续处理队列中下一个节点
                continue;
            }
        }

        // 8. 迭代结束：对结果按与searchId的距离升序排序
        List<Peer> resultList = new ArrayList<>(discoveredPeers);
        resultList.sort((p1, p2) -> {
            byte[] d1 = calculateDistance(p1.getId(), searchId);
            byte[] d2 = calculateDistance(p2.getId(), searchId);
            return compareDistance(d1, d2);
        });

        log.info("迭代查询完成，共发现{}个有效节点（已按距离排序）", resultList.size());
        return resultList;
    }

    // 重载方法：简化调用（使用默认参数）
    public List<Peer> findNodeIterative(byte[] targetId, byte[] searchId) {
        log.info("迭代查询");
        // 默认参数：k=20，并发数=3，超时=2000ms
        return findNodeIterative(targetId, searchId, 20, 3, 2000);
    }



    /**
     * 向指定节点发送FindNode请求
     */
    private List<Peer> sendFindNodeToPeer(Peer peer, byte[] searchId, int count, int timeoutMillis) {
        return sendFindNodeToPeer(bytesToHex(peer.getId()), searchId, count, timeoutMillis);
    }


    /**
     * 向指定节点发送FindNode请求
     */
    public List<Peer> sendFindNodeToPeer(String peerIdHex, byte[] searchId, int count, int timeoutMillis) {
        try {
            // 构造查询数据
            byte[] data = new byte[36];
            System.arraycopy(searchId, 0, data, 0, 32);
            System.arraycopy(intToBytes(count), 0, data, 32, 4);

            // 发送查询请求
            byte[] p2pRes = staticSendData(peerIdHex, ProtocolEnum.P2P_Find_Node_Req, data, timeoutMillis);
            if (p2pRes == null) {
                log.debug("节点{}无响应", peerIdHex);
                return Collections.emptyList();
            }

            // 解析响应
            P2PMessage response = P2PMessage.deserialize(p2pRes);
            if (response == null || response.getData() == null) {
                return Collections.emptyList();
            }

            List<Peer> peers = deserializePeers(response.getData());
            if (peers == null) {
                return Collections.emptyList();
            }

            // 添加新发现的节点到路由表
            for (Peer discoveredPeer : peers) {
                if (discoveredPeer != null && !Arrays.equals(discoveredPeer.getId(), localNodeId)) {
                    addPeer(discoveredPeer);
                }else {
                    log.info("节点是自己");
                }
            }

            log.debug("从节点{}获取到{}个节点", peerIdHex, peers.size());
            return peers;

        } catch (Exception e) {
            log.debug("查询节点{}失败: {}", peerIdHex, e.getMessage());
            return Collections.emptyList();
        }
    }


    /**
     * 检查迭代查询是否收敛
     */
    private boolean isConverged(PriorityQueue<PeerDistance> alphaNodes, List<Peer> closestPeers, byte[] targetId) {
        if (alphaNodes.isEmpty()) {
            return true;
        }

        // 如果最近节点列表已满，且alphaNodes中的节点都比列表中最远的节点远
        if (closestPeers.size() >= 20) { // 使用Kademlia的k值
            Peer farthest = getFarthestPeer(closestPeers, targetId);
            byte[] farthestDist = calculateDistance(farthest.getId(), targetId);

            for (PeerDistance pd : alphaNodes) {
                if (compareDistance(pd.distance, farthestDist) < 0) {
                    return false;
                }
            }
            return true;
        }

        return false;
    }

    /**
     * 获取最远的节点
     */
    private Peer getFarthestPeer(List<Peer> peers, byte[] targetId) {
        Peer farthest = null;
        byte[] maxDistance = null;

        for (Peer peer : peers) {
            byte[] dist = calculateDistance(peer.getId(), targetId);
            if (maxDistance == null || compareDistance(dist, maxDistance) > 0) {
                maxDistance = dist;
                farthest = peer;
            }
        }

        return farthest;
    }

    /**
     * 添加节点到优先级队列
     */
    private void addToPriorityQueue(PriorityQueue<PeerDistance> queue,
                                    PeerDistance peerDist, int maxSize) {
        queue.offer(peerDist);
        if (queue.size() > maxSize) {
            // 移除最远的节点
            queue.poll();
        }
    }



    /**
     * 启动Kademlia DHT迭代查询
     * @param searchNodeIdHex 目标节点ID的Hex字符串
     * @return 查询到的最近节点列表
     */
    public List<Peer> findNodeIterative(String searchNodeIdHex) {
        log.info("开始迭代查询节点: {}", searchNodeIdHex);

        try {
            byte[] targetId = hexToBytes(searchNodeIdHex);
            List<Peer> closestNodes = iterativeFindNode(targetId, 20, 3, 2000);

            log.info("迭代查询完成，找到{}个最近节点", closestNodes.size());
            for (Peer peer : closestNodes) {
                log.info("节点: {}, 距离: {}",
                        bytesToHex(peer.getId()).substring(0, 8) + "...",
                        bytesToHex(calculateDistance(peer.getId(), targetId)).substring(0, 8) + "...");
            }

            return closestNodes;
        } catch (Exception e) {
            log.error("迭代查询失败", e);
            return Collections.emptyList();
        }
    }


    // ========== 新增：应用关闭时销毁定时任务池，防止资源泄漏 ==========
    @PreDestroy
    public void destroy() {
        // 关闭定时任务池
        if (persistScheduler != null && !persistScheduler.isShutdown()) {
            persistScheduler.shutdown();
            try {
                // 等待10秒，若仍未关闭则强制终止
                if (!persistScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    persistScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                persistScheduler.shutdownNow();
            }
            log.info("路由表定时持久化任务池已关闭");
        }

        // 关闭业务线程池
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
            log.info("路由表业务线程池已关闭");
        }
    }


    /**
     * 执行路由表全量刷新（Kademlia 标准流程）
     * 1. 遍历所有 K 桶
     * 2. 为每个桶生成随机目标 ID（落在桶的距离区间）
     * 3. 对目标 ID 执行迭代查找
     * 4. 用新节点更新桶，淘汰失效节点
     */
    private void refreshRoutingTable() {
        // 防止并发刷新
        if (!refreshLock.tryLock()) {
            log.debug("路由表刷新任务已在执行，跳过本次");
            return;
        }

        if (isRefreshing.get()) {
            refreshLock.unlock();
            return;
        }

        isRefreshing.set(true);

        try {
            log.info("开始执行路由表刷新任务");
            long startTime = System.currentTimeMillis();

            // 遍历所有 K 桶（跳过空桶/无节点的桶）
            for (int bucketIndex = 0; bucketIndex < buckets.size(); bucketIndex++) {
                Bucket bucket = buckets.get(bucketIndex);
                if (bucket == null || bucket.size() == 0) {
                    continue;
                }

                // 跳过长期未访问的桶（优化：仅刷新30分钟内有访问的桶）
                Long lastAccessTime = lastBucketAccessTime.get(bucketIndex);
                if (lastAccessTime != null &&
                        System.currentTimeMillis() - lastAccessTime > 30 * 60 * 1000) {
                    log.debug("桶{}超过30分钟未访问，跳过刷新", bucketIndex);
                    continue;
                }

                // 为当前桶生成随机目标 ID（核心：目标 ID 落在该桶的距离区间）
                byte[] randomTargetId = generateRandomTargetIdForBucket(bucketIndex);
                if (randomTargetId == null) {
                    log.warn("桶{}生成随机目标ID失败，跳过刷新", bucketIndex);
                    continue;
                }

                log.debug("开始刷新桶{}，随机目标ID：{}",
                        bucketIndex, bytesToHex(randomTargetId).substring(0, 8) + "...");

                // 对随机目标 ID 执行迭代查找（复用现有迭代查找逻辑）
                List<Peer> newPeers = iterativeFindNode(
                        randomTargetId,
                        bucketSize,       // 返回20个节点（与桶大小一致）
                        REFRESH_PARALLELISM, // 并发查询数
                        REFRESH_QUERY_TIMEOUT // 超时时间
                );

                // 用新节点更新当前桶
                updateBucketWithNewPeers(bucketIndex, newPeers);

                //TODO
                // 清理桶内失效节点（PING 检测）

                log.debug("桶{}刷新完成，新增/更新{}个节点", bucketIndex, newPeers.size());
            }

            long costTime = System.currentTimeMillis() - startTime;
            log.info("路由表刷新任务执行完成，耗时{}ms", costTime);

        } catch (Exception e) {
            log.error("路由表刷新任务执行失败", e);
        } finally {
            isRefreshing.set(false);
            refreshLock.unlock();
        }
    }


    /**
     * 为指定桶生成随机目标 ID（落在该桶的距离区间）
     * Kademlia 核心：目标 ID 与本地节点的异或距离，刚好落在该桶的区间
     * @param bucketIndex 桶索引（0-255）
     * @return 随机目标 ID（32字节）
     */
    private byte[] generateRandomTargetIdForBucket(int bucketIndex) {
        try {
            // 1. 生成随机32字节ID
            byte[] randomId = new byte[32];
            new SecureRandom().nextBytes(randomId);

            // 2. 计算本地节点ID与随机ID的异或距离
            byte[] distance = calculateDistance(localNodeId, randomId);

            // 3. 调整距离，使其落在目标桶的区间
            // 桶索引越大 → 距离越近 → 最高有效位越靠后
            int targetBitPosition = 255 - bucketIndex; // 目标最高有效位的位置
            int byteIndex = targetBitPosition / 8;     // 目标字节索引（0-31）
            int bitIndex = targetBitPosition % 8;      // 目标位索引（0-7）

            // 重置所有更高位为0，确保最高有效位是目标位
            for (int i = 0; i < byteIndex; i++) {
                distance[i] = 0;
            }
            // 清空当前字节的更高位
            byte mask = (byte) (0xFF >> (8 - bitIndex));
            distance[byteIndex] = (byte) (distance[byteIndex] & mask);
            // 设置目标位为1（确保最高有效位是目标位）
            distance[byteIndex] |= (byte) (1 << bitIndex);

            // 4. 异或回去得到目标ID：targetId = localNodeId XOR distance
            byte[] targetId = new byte[32];
            for (int i = 0; i < 32; i++) {
                targetId[i] = (byte) (localNodeId[i] ^ distance[i]);
            }

            // 验证：目标ID对应的桶索引是否正确
            int verifyIndex = getBucketIndex(targetId);
            if (verifyIndex != bucketIndex) {
                log.warn("桶{}生成的目标ID验证失败，实际桶{}", bucketIndex, verifyIndex);
            }

            return targetId;
        } catch (Exception e) {
            log.error("生成桶{}的随机目标ID失败", bucketIndex, e);
            return null;
        }
    }

    /**
     * 用迭代查找返回的新节点更新指定桶
     * @param bucketIndex 桶索引
     * @param newPeers 新发现的节点列表
     */
    private void updateBucketWithNewPeers(int bucketIndex, List<Peer> newPeers) {
        if (newPeers == null || newPeers.isEmpty()) {
            return;
        }

        Bucket bucket = buckets.get(bucketIndex);
        if (bucket == null) {
            return;
        }

        synchronized (bucket) {
            for (Peer newPeer : newPeers) {
                if (newPeer == null || Arrays.equals(newPeer.getId(), localNodeId)) {
                    continue;
                }

                // 复用addPeer逻辑，自动处理桶的满/空、节点活跃度
                addPeer(newPeer);
            }
        }

        log.debug("桶{}已更新，新增{}个有效节点", bucketIndex, newPeers.size());
    }


    /**
     * 获取当前在线节点数量
     */
    private int getOnlinePeerCount() {
        return getOnlinePeerIds().size();
    }

}
