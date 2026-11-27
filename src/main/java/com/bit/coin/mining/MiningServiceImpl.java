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

    /** Mining state belongs to this node instance; keeping it static makes independent
     * Spring contexts (tests, embedded nodes, or rolling restarts) interfere with one
     * another and can leave a newly-created service attached to a shut-down executor. */
    public volatile boolean isMining = false;
    private volatile boolean isResourcesInitialized = false;
    private boolean isExecutorsInitialized = false;
    private ExecutorService executor;
    // 用于GPU挖矿 和 CPU挖矿 超时控制的调度器
    private ThreadPoolExecutor miningExecutor;
    private volatile Block currentMiningBlock;
    private static final int threadCount = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));  // CPU 挖矿并行搜索的 nonce 分区数，每个工作线程负责一个分区
    private volatile Future<?> miningLoop;

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
    public synchronized Result<String> startMining() throws Exception {
        if (isMining || (miningLoop != null && !miningLoop.isDone())) {
            return Result.error("Mining already active");
        }
        validateMiningConfiguration();
        initAllResources();
        isMining = true;
        miningLoop = miningExecutor.submit(() -> {
            try {
                while (isMining && !Thread.currentThread().isInterrupted()) {
                    try {
                        mineOneBlock();
                    } catch (InterruptedException stop) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception failure) {
                        log.warn("Mining attempt failed: {}", failure.getMessage());
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException stop) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } finally {
                isMining = false;
            }
        });
        return Result.ok();
    }

    public void stopMining() {
        isMining = false;
        Future<?> active = miningLoop;
        if (active != null) {
            active.cancel(true);
        }
    }

    private void validateMiningConfiguration() {
        if (!blockChainService.isStateHealthy()) {
            throw new IllegalStateException("Node is not ready for mining");
        }
        if (systemConfig.getMinerAddressList() == null || systemConfig.getMinerAddressList().isEmpty()
                || systemConfig.getMinerAddressList().getFirst().isBlank()
                || org.bitcoinj.base.Base58.decode(systemConfig.getMinerAddressList().getFirst()).length != 32) {
            throw new IllegalArgumentException("Configure a Base58 encoded 32-byte reward public key before mining");
        }
    }

    /**
     * 初始化所有静态资源（线程池、CUDA、超时调度器），仅在首次启动时执行
     */
    public synchronized void initAllResources() throws Exception {
        if (isResourcesInitialized) {
            log.debug("资源已初始化，无需重复创建");
            return;
        }
        initMiningExecutor();
        if (systemConfig.getMiningType().equals(2)) {
            initCuda();
        }
        isResourcesInitialized = true; // 标记资源已初始化
    }

    private void initMiningExecutor() {
        if (isExecutorsInitialized) {
            return;
        }
        miningExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                task -> {
                    Thread t = new Thread(task, "mining-coordinator");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor = new ThreadPoolExecutor(threadCount, threadCount, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threadCount),
                task -> {
                    Thread t = new Thread(task, "mining-worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        isExecutorsInitialized = true;
    }

    /** 从当前链顶构建模板，找到 nonce 后校验并提交区块，退出时释放打包锁。 */
    public synchronized Block mineOneBlock() throws Exception {
        validateMiningConfiguration();
        initAllResources();
        // 交易提取会设置打包锁；无论模板过期、挖矿失败还是区块被拒绝，finally 都负责释放。
        Block latest = blockChainService.getMainLatestBlock();
        Transaction[] extracted = txPool.extractTxsForBlock();
        List<Transaction> selected = new ArrayList<>(Arrays.asList(extracted));
        try {
            Block block = buildNewBlock(latest, selected, Math.addExact(latest.getHeight(), 1));
            MiningResult result = executeMining(block);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Mining cancelled");
            }
            if (result == null || !result.found) {
                throw new IllegalStateException("Mining timed out or tip changed");
            }
            block.getBlockHeader().setNonce(result.nonce);
            block.setHash(result.hash);
            if (!blockChainService.verifyBlock(block)) {
                throw new IllegalStateException("Mined block was rejected");
            }
            return block;
        } finally {
            // 区块被拒绝、区块过时或提交前发生异常时，也要解锁交易。
            txPool.onBlockFailed(extracted);
        }
    }

    private Block buildNewBlock(Block latestBlock, List<Transaction> selectedTxs, int newBlockHeight) {
        Block newBlock = new Block();
        long time = Math.max(System.currentTimeMillis() / 1000,
                com.bit.coin.blockchain.ConsensusRules.medianTime(latestBlock, blockChainService::getHeaderContext)
                        + 1);
        int bits = com.bit.coin.blockchain.ConsensusRules.expectedBits(latestBlock, time,
                systemConfig.getParams(), blockChainService::getHeaderContext);
        // 先固定候选父块上下文和难度，再将 coinbase 放在交易列表首位并计算 Merkle 根。
        Transaction coinBase = BlockChainServiceImpl.createCoinBaseTransaction(
                systemConfig.getMinerAddressList().getFirst(),
                newBlockHeight, blockChainService.calculateTransactionFee(selectedTxs), systemConfig.getParams());
        List<Transaction> blockTxs = new ArrayList<>(selectedTxs);
        blockTxs.addFirst(coinBase); // CoinBase放在首位
        newBlock.setTransactions(blockTxs);
        newBlock.setTxCount(blockTxs.size());
        byte[] bytes = calculateMerkleRoot(blockTxs);
        BlockHeader blockHeader = new BlockHeader(1, latestBlock.getHash(), bytes, time, bits, 0);
        newBlock.setBlockHeader(blockHeader);
        newBlock.setHeight(newBlockHeight);
        return newBlock;
    }

    // 挖矿结果类
    static class MiningResult {

        byte[] hash;
        int nonce;
        volatile boolean found = false;
    }

    /**
     * 执行挖矿（复用线程池/CUDA资源）
     */
    private MiningResult executeMining(Block newBlock) {
        BlockHeader header = newBlock.getBlockHeader();
        currentMiningBlock = newBlock; // 跟踪当前挖矿区块
        try {
            Integer miningType = systemConfig.getMiningType();
            if (miningType.equals(2)) {
                return gpuMineBlock(header);
            } else {
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
        // 各 worker 按 lane + n * threadCount 搜索互不重叠的 nonce；定期检查超时和父块，及时放弃过期模板。
        MiningResult result = new MiningResult();
        List<Future<?>> workers = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            for (int i = 0; i < threadCount; i++) {
                final int lane = i;
                workers.add(executor.submit(() -> {
                    BlockHeader header = blockHeader.clone();
                    for (long nonce = lane; nonce <= 0xFFFFFFFFL && !result.found
                            && !Thread.currentThread().isInterrupted(); nonce += threadCount) {
                        if ((nonce / threadCount) % 1024 == 0) {
                            var tip = blockChainService.getMainTipSnapshot();
                            if (System.nanoTime() >= deadline || tip == null
                                    || !Arrays.equals(tip.hash(), header.getPreviousHash())) {
                                break;
                            }
                            if (miningPerformance < 100) {
                                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                            }
                        }
                        header.setNonce((int) nonce);
                        if (header.verifyProofOfWork(false)) {
                            synchronized (result) {
                                if (!result.found) {
                                    result.nonce = (int) nonce;
                                    result.hash = header.calculateHash();
                                    result.found = true;
                                }
                            }
                            break;
                        }
                    }
                }));
            }
            while (!result.found && workers.stream().anyMatch(worker -> !worker.isDone())
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } finally {
            workers.forEach(worker -> worker.cancel(true));
        }
        return result;
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

            int baseBlockSize = 128;// 从256降低，减少并行线程数
            int startNonce = 0;
            int endNonce = Integer.MAX_VALUE;
            int actualGridSize = 128;

            // 3. 配置内核参数（使用静态加载的kernelFunction）
            Pointer kernelParams = Pointer.to(
                    Pointer.to(dHeader),
                    Pointer.to(new int[] { startNonce }),
                    Pointer.to(new int[] { endNonce }));

            // 4. 启动GPU内核（复用静态函数）
            int launchResult = cuLaunchKernel(
                    kernelFunction,
                    actualGridSize, 1, 1,
                    baseBlockSize, 1, 1,
                    0, null,
                    kernelParams, null);
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
        } catch (Exception e) {
            log.error("获取全局变量失败", e);
        }
        return null;
    }

    /**
     * 处理挖矿结果（成功则提交区块，失败则回退交易到交易池）
     */
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

    private void initCuda() {
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
        stopMining();
        if (miningExecutor != null) {
            miningExecutor.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        try {
            if (miningExecutor != null) {
                miningExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
            if (executor != null) {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
        miningLoop = null;
        miningExecutor = null;
        executor = null;
        isExecutorsInitialized = false;
        isResourcesInitialized = false;
        cleanCudaResources();
    }
}
