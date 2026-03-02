package com.bit.coin.mining;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.txpool.TxPool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jcuda.CudaException;
import jcuda.Pointer;
import jcuda.driver.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
import static jcuda.driver.CUresult.CUDA_SUCCESS;
import static jcuda.driver.JCudaDriver.*;


@Slf4j
@Service
public class MiningServiceImpl {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private DataBase dataBase;

    @Autowired
    private TxPool txPool;

    @Autowired
    private SystemConfig systemConfig;

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

    // 资源状态标记（避免重复释放）
    private boolean isCudaInitialized = false;


    // 静态加载的CUDA模块和函数（整个挖矿过程复用）
    private CUmodule ptxModule;
    private CUfunction kernelFunction;
    // 静态缓存的临时PTX文件（整个挖矿过程保持存在）
    private File tempPtxFile;
    private CUcontext cudaContext; // 类成员变量


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
        if (systemConfig.getMiningType().equals(2)){
            initCuda();
        }
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

        String first = systemConfig.getMinerAddressList().getFirst();
        if (first.isEmpty()){
            log.info("未配置挖矿地址");
            first = "Fu7zmpRzRmp89CJvqJPTB6cKGVNFJPDuDZvwXNj3ZCoz";
        }
        Transaction coinBase = BlockChainServiceImpl.createCoinBaseTransaction(systemConfig.getMinerAddressList().getFirst(), newBlockHeight, blockChainService.calculateTransactionFee(selectedTxs));
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
            Integer miningType = systemConfig.getMiningType();
            if (miningType.equals(2)){
                return gpuMineBlock(header);
            }else {
                return cpuMineBlock(header); // 复用CPU线程池
            }
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


    public MiningResult gpuMineBlock(BlockHeader blockHeader) {
        // 检查静态资源是否有效，无效则降级到CPU
        if (cudaContext == null || ptxModule == null || kernelFunction == null) {
            log.warn("CUDA静态资源未初始化，使用CPU挖矿");
            return cpuMineBlock(blockHeader);
        }
        log.debug("正在使用GPU挖矿");
        MiningResult result = new MiningResult();
        CUdeviceptr dHeader = null;
        CUdeviceptr dResult = null;
        try {
            // 切换到已初始化的CUDA上下文
            cuCtxSetCurrent(cudaContext);
            // 1. 准备区块头数据（序列化并复制到GPU内存）
            byte[] headerData = blockHeader.serialize();
            if (headerData.length != 80) {
                log.error("区块头序列化错误，长度应为80字节，实际：" + headerData.length);
                return result;
            }
            dHeader = new CUdeviceptr();
            int allocResult = cuMemAlloc(dHeader, headerData.length);
            if (allocResult != CUDA_SUCCESS) {
                log.error("GPU内存分配失败，错误码：" + allocResult);
                return result;
            }
            cuMemcpyHtoD(dHeader, Pointer.to(headerData), headerData.length);

            // 2. 设置挖矿参数（nonce范围）
/*            int baseBlockSize = 256;
            int startNonce = 0;
            int endNonce = Integer.MAX_VALUE;
            int actualGridSize =1024;*/

            int baseBlockSize = 256;// 从256降低，减少并行线程数
            int startNonce = 0;
            int endNonce = Integer.MAX_VALUE;
            int actualGridSize =1024;




            // 3. 配置内核参数（使用静态加载的kernelFunction）
            Pointer kernelParams = Pointer.to(
                    Pointer.to(dHeader),
                    Pointer.to(new int[]{startNonce}),
                    Pointer.to(new int[]{endNonce})
            );

            // 4. 启动GPU内核（复用静态函数）
            int launchResult = cuLaunchKernel(
                    kernelFunction,
                    actualGridSize, 1, 1,
                    baseBlockSize, 1, 1,
                    0, null,
                    kernelParams, null
            );
            if (launchResult != CUDA_SUCCESS) {
                log.error("GPU内核启动失败，错误码：" + launchResult);
                return result;
            }
            // 等待内核执行完成
            cuCtxSynchronize();
            log.info("GPU内核执行完成");
            // 5. 读取挖矿结果
            dResult = getGlobalVariable(ptxModule, "devResult");
            byte[] resultBuffer = new byte[40]; // 结构体大小：4+4+32=40字节
            cuMemcpyDtoH(Pointer.to(resultBuffer), dResult, 40);
            // 解析结果
            int found = ByteBuffer.wrap(resultBuffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int nonce = ByteBuffer.wrap(resultBuffer, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] hash = new byte[32];
            System.arraycopy(resultBuffer, 8, hash, 0, 32);
            result.found = (found == 1);
            result.nonce = nonce;
            result.hash = hash;
            if (result.found) {
                log.info("GPU找到有效哈希！nonce={}, hash={}", nonce, bytesToHex(hash));
            } else {
                log.info("GPU未找到有效哈希，最后尝试nonce={}", nonce);
            }
            return result;
        } catch (Exception e) {
            log.error("GPU挖矿异常", e);
            return cpuMineBlock(blockHeader); // 异常时降级到CPU
        } finally {
            // 释放本次挖矿的临时资源（保留静态资源）
            if (dHeader != null) {
                cuMemFree(dHeader);
            }
        }
    }


    /**
     * 辅助方法：获取CUDA全局变量地址
     */
    private CUdeviceptr getGlobalVariable(CUmodule module, String name) {
        CUdeviceptr ptr = new CUdeviceptr();
        long[] size = new long[1];
        try {
            cuModuleGetGlobal(ptr, size, module, name);
            return ptr;
        }catch (Exception e){
            log.error("获取全局变量失败", e);
        }
        return null;
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


    /**
     * 释放CUDA相关资源（严格按依赖顺序释放）
     * 释放顺序：内核函数 → 模块 → 上下文 → 临时文件
     */
    private void cleanCudaResources() {
        //释放内核函数（无显式释放方法，随模块释放）
        kernelFunction = null;
        // 2. 卸载CUDA模块（必须在上下文销毁前）
        if (ptxModule != null) {
            try {
                cuModuleUnload(ptxModule);
                log.debug("CUDA模块卸载成功");
            } catch (CudaException e) {
                log.error("CUDA模块卸载失败", e);
            } finally {
                ptxModule = null;
            }
        }

        // 3. 销毁CUDA上下文（必须在模块卸载后）
        if (cudaContext != null) {
            try {
                cuCtxDestroy(cudaContext);
                log.debug("CUDA上下文销毁成功");
            } catch (CudaException e) {
                log.error("CUDA上下文销毁失败", e);
            } finally {
                cudaContext = null;
            }
        }

        // 4. 删除PTX临时文件（确保文件存在且未被占用）
        if (tempPtxFile != null && tempPtxFile.exists()) {
            try {
                if (tempPtxFile.delete()) {
                    log.debug("PTX临时文件删除成功：{}", tempPtxFile.getAbsolutePath());
                } else {
                    log.warn("PTX临时文件删除失败，将依赖JVM退出时自动清理：{}", tempPtxFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.error("删除PTX临时文件时发生异常", e);
            } finally {
                tempPtxFile = null;
            }
        }
        isCudaInitialized = false;
        log.info("CUDA资源释放完成");
    }


    private void initCuda(){
        if (isCudaInitialized) {
            log.debug("CUDA已初始化，无需重复创建");
            return;
        }
        try {
            // 初始化CUDA驱动
            JCudaDriver.setExceptionsEnabled(true);
            cuInit(0);

            // 获取GPU设备
            int[] deviceCount = new int[1];
            cuDeviceGetCount(deviceCount);
            if (deviceCount[0] == 0) {
                log.warn("未检测到GPU设备，将使用CPU挖矿");
                return;
            }

            CUdevice device = new CUdevice();
            cuDeviceGet(device, 0);  // 使用第1块GPU

            // 创建上下文
            cudaContext = new CUcontext();
            cuCtxCreate(cudaContext, 0, device);

            // 获取GPU信息
            byte[] name = new byte[256];
            cuDeviceGetName(name, name.length, device);
            log.info("CUDA初始化成功，使用GPU设备: " + new String(name).trim());

            //加载执行文件
            // 1. 加载resources/cuda目录下的PTX文件
            ClassPathResource ptxResource = new ClassPathResource("cuda/miningKernel.ptx");
            if (!ptxResource.exists()) {
                throw new RuntimeException("PTX文件不存在: resources/cuda/miningKernel.ptx");
            }

            // 2. 复制到临时文件（整个挖矿过程中保持存在）
            tempPtxFile = File.createTempFile("miningKernel-", ".ptx");
            tempPtxFile.deleteOnExit(); // JVM退出时自动删除
            try (InputStream is = ptxResource.getInputStream();
                 OutputStream os = Files.newOutputStream(tempPtxFile.toPath())) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
            log.info("PTX文件静态加载到临时路径: {}", tempPtxFile.getAbsolutePath());

            // 3. 加载CUDA模块和函数（缓存到成员变量）
            ptxModule = new CUmodule();
            int loadResult = cuModuleLoad(ptxModule, tempPtxFile.getAbsolutePath());
            if (loadResult != CUDA_SUCCESS) {
                throw new RuntimeException("CUDA模块加载失败，错误码: " + loadResult);
            }
            kernelFunction = new CUfunction();
            int getFuncResult = cuModuleGetFunction(kernelFunction, ptxModule, "findValidNonceGPU");
            if (getFuncResult != 0) {
                throw new RuntimeException("获取CUDA函数失败，错误码: " + getFuncResult);
            }
            log.info("PTX模块和函数静态加载成功");
            isCudaInitialized = true; // 标记初始化完成
        } catch (Exception e) {
            log.error("CUDA初始化失败（可能无GPU或驱动问题）", e);
            log.warn("将自动降级为CPU挖矿");
            cleanCudaResources();
            isCudaInitialized = false;
        }
    }

    @PreDestroy
    public void destroy() {
        log.info("开始释放所有挖矿资源...");
        try {
            // 1. 停止所有线程池（先停止任务，避免资源被占用）

            // 2. 释放CUDA相关资源（计算资源依赖线程池已停止）
            cleanCudaResources();


            log.info("所有挖矿资源释放完成");
        } catch (Exception e) {
            log.error("资源释放过程中发生异常", e);
        }
    }

}
