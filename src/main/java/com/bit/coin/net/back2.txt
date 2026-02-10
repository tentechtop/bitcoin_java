package com.bit.coin.net;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.p2p.impl.PeerClient;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.p2p.protocol.P2PMessage;
import com.bit.coin.p2p.protocol.ProtocolEnum;
import com.bit.coin.structure.block.Block;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Service
@RequiredArgsConstructor
public class NetServiceImpl implements NetService {

    @Autowired
    private RoutingTable routingTable;

    @Autowired
    private PeerClient peerClient;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    // 每批同步区块数量
    private static final int BATCH_SIZE = 100;
    // 每个节点并发批次数
    private static final int CONCURRENT_BATCHES_PER_NODE = 5;
    // 内存缓存上限: 500MB (估算每个区块约1MB，最多缓存500个)
    private static final long MAX_CACHE_SIZE = 500 * 1024 * 1024;
    // 每个节点最大并发请求数
    private static final int MAX_CONCURRENT_REQUESTS_PER_NODE = 3;
    // 超时配置
    private static final int BLOCK_FETCH_TIMEOUT_SECONDS = 15;
    // 区块下载重试次数
    private static final int BLOCK_FETCH_RETRY_TIMES = 2;
    // 全局线程池（核心数=CPU核心数*2，最大数=CPU核心数*4）
    private static final ExecutorService SYNC_EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            Runtime.getRuntime().availableProcessors() * 4,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(1000),
            new ThreadFactory() {
                private final AtomicInteger threadNum = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "block-sync-thread-" + threadNum.getAndIncrement());
                    t.setDaemon(true); // 守护线程，避免阻塞应用退出
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时由调用线程执行，避免任务丢失
    );
    // 节点并发控制信号量（每个节点最多3个并发）
    private final Map<String, Semaphore> peerSemaphores = new ConcurrentHashMap<>();
    // 已下载区块缓存（按高度有序存储，保证处理顺序）
    private final ConcurrentSkipListMap<Integer, Block> downloadedBlocks = new ConcurrentSkipListMap<>();
    // 已下载区块总大小（控制内存占用）
    private final AtomicLong cachedBlocksSize = new AtomicLong(0);
    // 当前待处理的最小高度（保证有序处理）
    private final AtomicInteger currentProcessHeight = new AtomicInteger(0);




    public void startSyncBlock() throws InterruptedException {
        int localHeight = getCurrentLocalHeight();
        int networkHeight = getBestNetworkHeight();

        if (networkHeight <= localHeight) {
            log.info("本地区块已最新，无需同步。本地高度={}, 网络高度={}", localHeight, networkHeight);
            return;
        }

        List<Peer> peers = getConnectedPeers();
        if (peers.isEmpty()) {
            log.warn("无可用节点，同步终止");
            return;
        }
        // 初始化待处理高度

        // 筛选最优节点（高度足够、响应稳定）
        List<Peer> optimalPeers = selectOptimalPeers(peers, networkHeight);
        log.info("开始区块同步：本地高度={}, 网络高度={}, 待同步区块数={}, 可用节点数={}",
                localHeight, networkHeight, (networkHeight - localHeight), optimalPeers.size());
        if (optimalPeers.isEmpty()){
            log.warn("无可用节点，同步终止");
            return;
        }
        //本地路标
        List<Landmark> localLandmarks = blockChainService.generateSyncLandmarks(mainTipBlock.getHeight());
        //远程节点
        Peer bestPeer = optimalPeers.getFirst();
        //找到共同祖先节点
        byte[] bytes = peerClient.sendData(bytesToHex(bestPeer.getId()),
                ProtocolEnum.P2P_Query_Common_Ancestor,
                Landmark.serializeList(localLandmarks), BLOCK_FETCH_TIMEOUT_SECONDS * 1000);
        P2PMessage deserialize = P2PMessage.deserialize(bytes);
        if (deserialize!=null){
            byte[] data = deserialize.getData();
            Landmark deserialize1 = Landmark.deserialize(data);
            log.info("共同祖先hash{} 共同祖先高度{}",bytesToHex(deserialize1.getHash()),deserialize1.getHeight());
            currentProcessHeight.set(deserialize1.getHeight() + 1);

            //先简单实现 就用第一个节点作为同步节点循环获取区块
            for (int i = currentProcessHeight.get(); i <= networkHeight; i++){
                //休眠0.5秒
                Thread.sleep(500);
                Block block = fetchBlockFromPeerWithRetry(bytesToHex(bestPeer.getId()), i);
                blockChainService.verifyBlock(block);
            }
        }
    }



    /**
     * 带重试的区块下载
     */
    private Block fetchBlockFromPeerWithRetry(String peerId, int height) {
        int retry = 0;
        while (retry <= BLOCK_FETCH_RETRY_TIMES) {
            try {
                Block block = fetchBlockFromPeer(peerId, height);
                if (block != null) {
                    return block;
                }
                retry++;
                log.warn("下载区块{}失败，重试第{}次（节点{}）", height, retry, peerId);
                Thread.sleep(500L * retry); // 指数退避重试
            } catch (InterruptedException e) {
                log.error("下载区块{}被中断（节点{}）", height, peerId, e);
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                retry++;
                log.error("下载区块{}异常，重试第{}次（节点{}）", height, retry, peerId, e);
                try {
                    Thread.sleep(500L * retry);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 从指定节点获取单个区块（基础方法，保留原有逻辑）
     */
    private Block fetchBlockFromPeer(String peerId, int height) {
        try {
            byte[] bytes = peerClient.sendData(peerId,
                    ProtocolEnum.P2P_Query_Block_By_Height,
                    RocksDb.intToBytes(height), BLOCK_FETCH_TIMEOUT_SECONDS * 1000);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            P2PMessage deserialize = P2PMessage.deserialize(bytes);
            if (deserialize != null && deserialize.getData() != null) {
                return Block.deserialize(deserialize.getData());
            }
        } catch (Exception e) {
            log.error("从节点{}获取区块{}异常", peerId, height, e);
        }
        return null;
    }




    public int getCurrentLocalHeight() {
        return mainTipBlock != null ? mainTipBlock.getHeight() : 0;
    }

    public int getBestNetworkHeight() {
        List<Peer> onlineNodes = routingTable.getOnlineNodes();
        return onlineNodes.stream()
                .mapToInt(Peer::getHeight)
                .max()
                .orElse(getCurrentLocalHeight());
    }


    public Peer getBestPeer() {
        List<Peer> onlineNodes = routingTable.getOnlineNodes();

        if (onlineNodes == null || onlineNodes.isEmpty()) {
            return null;
        }

        // 第一步：找到最高高度值
        long maxHeight = onlineNodes.stream()
                .mapToLong(Peer::getHeight)
                .max()
                .orElse(0);

        // 第二步：收集所有高度等于maxHeight的节点
        List<Peer> maxHeightPeers = onlineNodes.stream()
                .filter(peer -> peer.getHeight() == maxHeight)
                .toList();

        // 第三步：随机返回一个最高高度节点（也可返回第一个）
        Random random = new Random();
        return maxHeightPeers.get(random.nextInt(maxHeightPeers.size()));
    }



    public List<Peer> getConnectedPeers() {
        List<Peer> onlineNodes = routingTable.getOnlineNodes();
        return onlineNodes.stream()
                .filter(peer -> peer.getId() != null && peer.getHeight() > 0 && peer.isOnline())
                .collect(Collectors.toList());
    }


    public Block getLatestBlockFromOnlinePeers() {
        List<Peer> connectedPeers = getConnectedPeers();
        if (connectedPeers.isEmpty()) {
            log.warn("没有可用的在线节点");
            return null;
        }

        // 选择最优节点获取最新区块
        Peer bestPeer = selectOptimalPeers(connectedPeers, getBestNetworkHeight()).get(0);
        try {
            int latestHeight = bestPeer.getHeight();
            return fetchBlockFromPeerWithRetry(bytesToHex(bestPeer.getId()), latestHeight);
        } catch (Exception e) {
            log.error("获取最新区块时发生异常", e);
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("开始关闭区块同步服务，清理相关资源...");

        // 1. 优雅关闭同步线程池
        if (SYNC_EXECUTOR != null && !SYNC_EXECUTOR.isShutdown()) {
            try {
                // 第一步：发起有序关闭，不再接受新任务
                SYNC_EXECUTOR.shutdown();
                log.info("已发起线程池有序关闭，等待剩余任务完成...");

                // 第二步：等待指定时间（30秒）让现有任务完成，超时则强制关闭
                if (!SYNC_EXECUTOR.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("线程池等待超时，强制关闭剩余任务");
                    // 强制关闭并返回未执行的任务
                    List<Runnable> unfinishedTasks = SYNC_EXECUTOR.shutdownNow();
                    log.warn("强制关闭后，未执行的任务数：{}", unfinishedTasks.size());
                }

                log.info("区块同步线程池已成功关闭");
            } catch (InterruptedException e) {
                log.error("线程池关闭过程被中断，强制关闭剩余任务", e);
                // 恢复线程中断状态，遵循中断规范
                Thread.currentThread().interrupt();
                // 强制关闭
                SYNC_EXECUTOR.shutdownNow();
            }
        }

        // 2. 清理并发控制资源（避免内存泄漏）
        peerSemaphores.clear();

        // 3. 清理区块缓存，释放内存
        downloadedBlocks.clear();
        cachedBlocksSize.set(0);
        currentProcessHeight.set(0);

        log.info("区块同步服务资源清理完成，shutdown执行完毕");
    }

    /**
     * 筛选最优节点：高度足够、在线状态稳定
     */
    private List<Peer> selectOptimalPeers(List<Peer> peers, int networkHeight) {
        return peers.stream()
                // 过滤：节点高度≥网络最高高度（保证有完整数据）
                .filter(peer -> peer.getHeight() >= networkHeight)
                // 过滤：节点ID有效、连接状态正常（可扩展响应速度筛选）
                .filter(peer -> peer.getId() != null && peer.isOnline())
                // 按节点高度降序、响应时间升序排序（优先选数据完整、响应快的节点）
                .sorted((p1, p2) -> {
                    if (p1.getHeight() != p2.getHeight()) {
                        return Integer.compare(p2.getHeight(), p1.getHeight());
                    }
                    // 可扩展：根据历史响应时间排序
                    return 0;
                })
                .collect(Collectors.toList());
    }

}