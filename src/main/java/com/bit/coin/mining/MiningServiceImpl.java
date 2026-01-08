package com.bit.coin.mining;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.database.DataBase;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.txpool.TxPool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.bit.coin.blockchain.BlockChainServiceImpl.*;
import static com.bit.coin.database.rocksDb.RocksDb.bytesToInt;
import static com.bit.coin.database.rocksDb.TableEnum.CHAIN;
import static com.bit.coin.database.rocksDb.TableEnum.TBLOCK;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static java.lang.Thread.sleep;


@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private DataBase dataBase;

    @Autowired
    private TxPool txPool;

    //矿工地址
    private static final String MINER_ADDRESS = "Fu7zmpRzRmp89CJvqJPTB6cKGVNFJPDuDZvwXNj3ZCoz";


    volatile public static boolean isMining = false;
    private volatile boolean isResourcesInitialized = false;
    private boolean isExecutorsInitialized = false;
    private static ExecutorService executor;
    // 用于GPU挖矿 和 CPU挖矿 超时控制的调度器
    private ThreadPoolExecutor miningExecutor;
    private volatile Block currentMiningBlock;
    private static int threadCount =  Runtime.getRuntime().availableProcessors();

    // CPU挖矿性能控制（0-100，默认85%）
    private volatile int miningPerformance = 15;



    /**
     * 启动挖矿
     */
    public Result<String> startMining() throws Exception {
        if (isMining) {
            return Result.error("ERROR: The node is already mining ! ");
        }
        initAllResources();
        // 首次启动时初始化所有资源（仅执行一次）
        log.info("开始初始化挖矿服务...");
        isMining = true;
        miningExecutor.submit(() -> {
            while (true){
                if (!isMining) {
                    Thread.sleep(1000); // 未挖矿时休眠，减少CPU占用
                    continue;
                }
                try {
                    mineOneBlock();
                } catch (Exception e) {
                    log.error("挖矿异常，将重试", e);
                    Thread.sleep(1000);
                }
            }
        });
        return Result.ok();
    }


    /**
     * 初始化所有静态资源（线程池、CUDA、超时调度器），仅在首次启动时执行
     */
    public void initAllResources() throws Exception {
        if (isResourcesInitialized) {
            log.debug("资源已初始化，无需重复创建");
            return;
        }
        initMiningExecutor();
        isResourcesInitialized = true; // 标记资源已初始化
    }

    private void initMiningExecutor() {
        if (isExecutorsInitialized) {
            log.debug("线程池已初始化，无需重复创建");
            return;
        }
        if (miningExecutor == null || miningExecutor.isShutdown() || miningExecutor.isTerminated()) {
            // 线程工厂：增加异常处理，明确线程属性
            ThreadFactory threadFactory = r -> {
                Thread thread = new Thread(r, "mining-main-thread");
                thread.setPriority(Thread.NORM_PRIORITY);
                thread.setDaemon(false);
                thread.setUncaughtExceptionHandler((t, e) ->
                        log.error("挖矿线程[" + t.getName() + "]发生未捕获异常", e)
                );
                return thread;
            };

            // 拒绝策略：自定义策略，更贴合挖矿场景
            RejectedExecutionHandler rejectedHandler = (r, executor) -> {
                if (!executor.isShutdown()) {
                    try {
                        log.warn("挖矿线程池忙碌，提交线程将直接执行任务（当前队列已满）");
                        // 让提交任务的线程自己执行，避免任务丢失
                        r.run();
                    } catch (Exception e) {
                        log.error("提交线程执行挖矿任务时发生异常", e);
                    }
                } else {
                    log.warn("挖矿线程池已关闭，无法提交新任务");
                }
            };

            // 线程池参数
            miningExecutor = new ThreadPoolExecutor(
                    1,       // 核心线程数=1（固定单线程）
                    1,                  // 最大线程数=1（禁止扩容，确保串行）
                    0L,                 // 空闲时间=0（核心线程永不回收）
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(5),
                    threadFactory,
                    rejectedHandler
            );

            // 5. 预启动核心线程：避免首次任务的启动延迟
            miningExecutor.prestartCoreThread();
        }
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            int corePoolSize = Runtime.getRuntime().availableProcessors(); // CPU核心数
            int maximumPoolSize = corePoolSize * 2; // 固定线程数
            long keepAliveTime = 0L; // 核心线程不超时（因长期运行）
            TimeUnit unit = TimeUnit.MILLISECONDS;
            BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
            ThreadFactory threadFactory = r -> {
                Thread t = new Thread(r, "mining-thread-" + UUID.randomUUID().toString().substring(0, 8));
                t.setPriority(Thread.NORM_PRIORITY); // 挖矿线程优先级设为正常（避免抢占系统资源）
                t.setDaemon(false); // 非守护线程（确保挖矿可独立运行，不受主线程影响）
                return t;
            };
            RejectedExecutionHandler handler = new ThreadPoolExecutor.CallerRunsPolicy();
            executor = new ThreadPoolExecutor(
                    corePoolSize,
                    maximumPoolSize,
                    keepAliveTime,
                    unit,
                    workQueue,
                    threadFactory,
                    handler
            );
        }
        isExecutorsInitialized = true; // 标记初始化完成
    }

    public synchronized Block mineOneBlock() throws Exception {
        // 1. 获取最新区块信息（确保基于最新主链打包）
        byte[] tipHash = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HASH_KEY);
        byte[] tipHeight = dataBase.get(CHAIN, MAIN_CHAIN_TIP_HEIGHT_KEY);
        Block latestBlock = blockChainService.getBlockByHash(tipHash);
        int newBlockHeight = bytesToInt(tipHeight) + 1;

        // 2. 从交易池提取交易到区块（自动标记为打包中）
        Transaction[] extractedTxs = txPool.extractTxsForBlock();
        List<Transaction> selectedTxs = new ArrayList<>(extractedTxs.length);
        selectedTxs.addAll(Arrays.asList(extractedTxs));

        // 3. 构建新区块（含CoinBase交易、Merkle根等）
        assert latestBlock != null;
        Block newBlock = buildNewBlock(latestBlock, selectedTxs, newBlockHeight);
        MiningResult result = executeMining(newBlock);
        handleMiningResult(newBlock, result, selectedTxs);
        return newBlock;
    }




    private Block buildNewBlock(Block latestBlock, List<Transaction> selectedTxs, int newBlockHeight) {
        Block newBlock = new Block();
        BlockHeader blockHeader = new BlockHeader(1, latestBlock.getHash(), null, System.currentTimeMillis() / 1000, blockChainService.calculateNextBlockDifficulty(), 0);
        Transaction coinBase = BlockChainServiceImpl.createCoinBaseTransaction(MINER_ADDRESS, newBlockHeight, blockChainService.calculateTransactionFee(selectedTxs));
        List<Transaction> blockTxs = new ArrayList<>(selectedTxs);
        blockTxs.addFirst(coinBase); // CoinBase放在首位
        newBlock.setTransactions(blockTxs);
        newBlock.setTxCount(blockTxs.size());
        byte[] bytes = calculateMerkleRoot(blockTxs);
        blockHeader.setMerkleRoot(bytes);
        newBlock.setBlockHeader(blockHeader);
        newBlock.setHeight(newBlockHeight);
        return newBlock;
    }


    // 挖矿结果类
    static class MiningResult {
        byte[] hash;
        int nonce;
        boolean found = false;
    }


    /**
     * 执行挖矿（复用线程池/CUDA资源）
     */
    private MiningResult executeMining(Block newBlock) {
        BlockHeader header = newBlock.getBlockHeader();
        currentMiningBlock = newBlock; // 跟踪当前挖矿区块
        try {
            return cpuMineBlock(header); // 复用CPU线程池
        } finally {
            currentMiningBlock = null; // 重置跟踪
        }
    }


    /**
     * 打包交易，进行挖矿
     */
    public MiningResult cpuMineBlock(BlockHeader blockHeader) {
        MiningResult result = new MiningResult();
        Future<?>[] futures = new Future[threadCount];
        int nonceRange = Integer.MAX_VALUE / threadCount;
        // 重置结果状态
        result.found = false;
        // 提交所有线程任务
        for (int i = 0; i < threadCount; i++) {
            BlockHeader clone = blockHeader.clone();
            final int startNonce = i * nonceRange;
            final int endNonce = (i == threadCount - 1) ? Integer.MAX_VALUE : (i + 1) * nonceRange;
            futures[i] = executor.submit(() -> {
                int bits = clone.getBits();
                try {
                    for (int nonce = startNonce; nonce < endNonce && !result.found; nonce++) {
                        // 每5000次计算执行一次概率休眠
                        if (nonce % 1000 == 0 && miningPerformance < 100) {
                            // 性能控制：计算休眠概率（性能越低，休眠概率越高）
                            double sleepProbability = (100.0 - miningPerformance) / 100.0;
                            // 生成0-1之间的随机数，判断是否需要休眠
                            if (Math.random() < sleepProbability) {
                                try {
                                    // 休眠时长：性能越低，基础休眠时间越长（50ms ~ 200ms）
                                    // 性能为0时休眠200ms，性能100时休眠0ms（实际不会进入此分支）
                                    long sleepMs = (long) (200 - (miningPerformance * 1.5));
                                    sleepMs = Math.max(50, sleepMs); // 确保最小休眠50ms，避免频繁切换
                                    sleep(sleepMs);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt(); // 保留中断状态
                                    return; // 中断时退出当前线程的挖矿任务
                                }
                            }
                        }
                        clone.setNonce(nonce);
                        byte[] hash = clone.calculateHash();
                        if (clone.verifyProofOfWork()) {
                            synchronized (result) {
                                if (!result.found) {
                                    result.hash = hash;
                                    result.nonce = nonce;
                                    result.found = true;
                                    log.info("线程 " + Thread.currentThread().getName() + " 找到有效哈希! {}",bytesToHex(hash));
                                }
                            }
                            return;
                        }
                    }
                } catch (Exception e) {
                    log.error("线程 " + Thread.currentThread().getName() + " 计算哈希时异常", e);
                    Thread.currentThread().interrupt();
                }
            });
        }

        // 等待所有任务完成或找到结果（保持原有逻辑不变）
        try {
            boolean allCompleted = false;
            while (!allCompleted && !result.found) {
                allCompleted = true;
                for (Future<?> future : futures) {
                    if (future != null && !future.isDone()) {
                        allCompleted = false;
                        sleep(100);
                        break;
                    }
                }
            }
            for (Future<?> future : futures) {
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            for (Future<?> future : futures) {
                if (future != null) {
                    future.cancel(true);
                }
            }
        }
        return result.found ? result : null;
    }



    /**
     * 处理挖矿结果（成功则提交区块，失败则回退交易到交易池）
     */
    private void handleMiningResult(Block newBlock, MiningResult result, List<Transaction> selectedTxs) {
        if (result != null && result.found) {
            log.info("区块 #{} 挖矿成功", newBlock.getHeight());
            // 挖矿成功：提交区块（上链后自动删除交易池交易）
            newBlock.getBlockHeader().setNonce(result.nonce);
            newBlock.setHash(result.hash);
            blockChainService.verifyBlock(newBlock);
        } else {
            // 挖矿失败：回退交易到交易池，允许重新参与打包
            log.info("区块 #{} 挖矿失败，回退交易到交易池", newBlock.getHeight());
            if (!selectedTxs.isEmpty()) {
                txPool.onBlockFailed(selectedTxs.toArray(new Transaction[0]));
            }
        }
    }

}
