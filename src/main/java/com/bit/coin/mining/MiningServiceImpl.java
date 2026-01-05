package com.bit.coin.mining;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.block.Block;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private BlockChainServiceImpl blockChainService;


    //矿工地址
    private static final String MINER_ADDRESS = "miner_address";


    volatile public static boolean isMining = false;
    private volatile boolean isResourcesInitialized = false;
    private boolean isExecutorsInitialized = false;
    private static ExecutorService executor;
    // 用于GPU挖矿 和 CPU挖矿 超时控制的调度器
    private ThreadPoolExecutor miningExecutor;

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
    private void initAllResources() throws Exception {
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

    private void mineOneBlock() throws Exception {
        // 2. 获取最新区块信息（确保基于最新主链打包）
        Block latestBlock = blockChainService.getMainLatestBlock();



    }

}
