package com.bit.coin.txpool;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.tx.*;
import com.bit.coin.structure.tx.transfer.TransferDTO;
import com.bit.coin.utils.Sha;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.bit.coin.api.TxApi.filterUtxoExcludePool;
import static com.bit.coin.structure.tx.UTXOStatusResolver.COINBASE_MATURED;
import static com.bit.coin.structure.tx.UTXOStatusResolver.CONFIRMED_UNSPENT;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@Slf4j
@Service
public class TxPoolImpl implements TxPool{

    @Autowired
    private DataBase dataBase;

    @Autowired
    private UTXOCache utxoCache;

    @Autowired
    private RoutingTable routingTable;

    // 内存池：hex txId -> Transaction
    private final Map<String, Transaction> mempool = new ConcurrentHashMap<>();
    // 交易时间戳：hex txId -> timestamp (用于RBF排序)
    private final Map<String, Long> txTimestamps = new ConcurrentHashMap<>();
    // 地址到交易的映射：address -> Set<byte[] txId>
    private final Map<String, Set<byte[]>> addressToTxs = new ConcurrentHashMap<>();
    // 打包中的交易：hex txId -> Transaction (用于防止重复打包)
    private final Map<String, Transaction> packingTxs = new ConcurrentHashMap<>();
    private final Map<String, String> spentOutpointToTx = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> txToSpentOutpoints = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> txToCreatedOutpoints = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> txToParents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> parentToChildren = new ConcurrentHashMap<>();

    // 区块大小限制 1MB
    private static final long MAX_BLOCK_SIZE = 1024 * 1024;
    // 交易池最大容量
    private static final int MAX_MEMPOOL_SIZE = 10000;
    private static final long TX_REVALIDATE_AGE_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long TX_REBROADCAST_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final double MIN_RELAY_FEE_RATE = 1D;
    // RBF手续费增加比例阈值（至少增加25%）
    private static final double RBF_FEE_INCREASE_RATIO = 1.25;
    private static final long RBF_SEQUENCE_THRESHOLD = 0xFFFFFFFEL;
    private static final int MAX_RBF_REPLACEMENT_CANDIDATES = 100;


    //花费中待确认UTXO key
    Set<String> spendingSet = ConcurrentHashMap.newKeySet();
    //到账中待确认的UTXO key
    Set<String> processingSet = ConcurrentHashMap.newKeySet();


    // 初始化定时任务（Spring容器启动后执行）
    @PostConstruct
    public void initScheduledTasks() {
        // 10分钟重广播未确认交易
        ScheduledExecutorService rebroadcastExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tx-pool-rebroadcast");
            t.setDaemon(true); // 守护线程，应用关闭时自动退出
            return t;
        });

        // 延迟1分钟启动，之后每10分钟执行一次
        rebroadcastExecutor.scheduleAtFixedRate(
                this::rebroadcastUnconfirmedTxs,
                1,  // 初始延迟（分钟）
                10, // 间隔（分钟）
                TimeUnit.MINUTES
        );
    }


    /**
     * 重广播交易池中未确认的交易（核心逻辑）
     */
    private void rebroadcastUnconfirmedTxs() {
        try {
            // 过滤出需要重广播的交易：未确认、未打包、最后广播时间超过5分钟
            long now = System.currentTimeMillis();
            List<Transaction> toRebroadcast = new ArrayList<>();

            for (Transaction tx : new ArrayList<>(mempool.values())) {
                if (tx == null || tx.isCoinbase()) {
                    continue;
                }

                if (tx.getTxId() == null) {
                    tx.setTxId(tx.calculateTxId());
                }
                String txIdStr = bytesToHex(tx.getTxId());
                if (!mempool.containsKey(txIdStr)) {
                    continue;
                }

                long status = tx.getStatus();
                boolean inPool = TransactionStatusResolver.getLifecycleStatus(status) == TransactionStatusResolver.IN_POOL;
                boolean locked = TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.MEMPOOL_LOCKED);
                if (!inPool || locked) {
                    continue;
                }

                Long joinTime = txTimestamps.get(txIdStr);
                if (joinTime != null && now - joinTime >= TX_REVALIDATE_AGE_MS
                        && !verifyTx(tx, Collections.singleton(txIdStr))) {
                    removeTxAndDescendants(txIdStr, TransactionStatusResolver.REJECTED,
                            TransactionStatusResolver.NON_STANDARD);
                    continue;
                }

                Long lastBroadcast = tx.getBroadcastTime();
                if (lastBroadcast == null || now - lastBroadcast > TX_REBROADCAST_INTERVAL_MS) {
                    toRebroadcast.add(tx);
                }
            }

            if (toRebroadcast.isEmpty()) {
                log.debug("暂无需要重广播的交易，交易池当前大小：{}", mempool.size());
                return;
            }

            log.info("开始重广播未确认交易，数量：{}", toRebroadcast.size());
            // 复用你已有的广播逻辑（间隔10ms，避免网络拥塞）
            broadcastTxList(toRebroadcast);

            // 更新最后广播时间
            toRebroadcast.forEach(tx -> tx.setBroadcastTime(System.currentTimeMillis()));
            log.info("重广播完成，已更新 {} 笔交易的广播时间", toRebroadcast.size());

        } catch (Exception e) {
            log.error("定时重广播交易失败", e);
        }
    }

    /**
     * 复用的批量广播逻辑（抽离原broadcastTxPool的逻辑）
     */
    private void broadcastTxList(List<Transaction> txList) {
        if (txList.isEmpty()) return;

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger index = new AtomicInteger(0);

        executor.scheduleAtFixedRate(() -> {
            int i = index.getAndIncrement();
            if (i >= txList.size()) {
                executor.shutdown();
                return;
            }

            Transaction tx = txList.get(i);
            try {
                routingTable.BroadcastResource(1, tx.getTxId());
                log.debug("重广播交易成功：{}", bytesToHex(tx.getTxId()));
            } catch (Exception e) {
                log.error("重广播交易 {} 失败", bytesToHex(tx.getTxId()), e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));
    }


    @Override
    synchronized public boolean verifyTx(Transaction tx) {
        return verifyTx(tx, Collections.emptySet());
    }

    private boolean verifyTx(Transaction tx, Set<String> allowedConflictingTxIds) {
        try {
            // 1. 基础格式校验
            if (tx == null || tx.getInputs() == null || tx.getInputs().isEmpty()
                    || tx.getOutputs() == null || tx.getOutputs().isEmpty()) {
                log.warn("交易验证失败：交易格式不正确");
                return false;
            }

            // 2. Coinbase交易单独处理
            if (tx.isCoinbase()) {
                if (tx.getInputs().size() != 1 || !Arrays.equals(tx.getInputs().getFirst().getTxId(), new byte[32])) {
                    log.warn("Coinbase交易验证失败：格式不正确");
                    return false;
                }
                log.warn("Coinbase transaction cannot enter mempool");
                return false;
            }

            for (TxOutput output : tx.getOutputs()) {
                if (output == null || output.getValue() < 0) {
                    log.warn("Transaction validation failed: invalid output value");
                    long status = tx.getStatus();
                    status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.NON_STANDARD);
                    tx.setStatus(status);
                    return false;
                }
            }

            // 3. 获取交易所有参与交易的UTXO
            List<TxInput> inputs = tx.getInputs();
            Map<String, UTXO> utxoMap = new HashMap<>();
            List<UTXO> utxoList = new ArrayList<>();
            Set<String> seenInputKeys = new HashSet<>();

            for (TxInput txInput : inputs) {
                if (txInput == null || txInput.getTxId() == null || txInput.getIndex() < 0) {
                    log.warn("Transaction validation failed: invalid input");
                    long status = tx.getStatus();
                    status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.NON_STANDARD);
                    tx.setStatus(status);
                    return false;
                }
                byte[] txId = txInput.getTxId();
                int index = txInput.getIndex();
                byte[] key = UTXO.getKey(txId, index);
                String keyHex = bytesToHex(key);
                if (!seenInputKeys.add(keyHex)) {
                    log.warn("Transaction validation failed: duplicate input {}", keyHex);
                    long status = tx.getStatus();
                    status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.DOUBLE_SPEND_FLAG);
                    tx.setStatus(status);
                    return false;
                }
                log.info("UTXOKey{}",bytesToHex(key));

                // 3.1 检查UTXO是否存在于数据库
                UTXO utxo = resolveInputUTXO(txInput, key);
                if (utxo == null) {
                    log.warn("交易验证失败：UTXO不存在，txId={}, index={}", bytesToHex(txId), index);
                    return false;
                }
                if (!isInputUTXOSpendable(utxo, txInput)){
                    log.warn("交易验证失败：UTXO不可花费，txId={}, index={}", bytesToHex(txId), index);
                    return false;
                }
                utxoMap.put(keyHex, utxo);
                utxoList.add(utxo);
            }

            // 4. 检查双花：检查UTXO是否已在交易池中被使用
            for (String keyHex : seenInputKeys) {
                String conflictTxId = spentOutpointToTx.get(keyHex);
                if (conflictTxId != null && !allowedConflictingTxIds.contains(conflictTxId)) {
                    log.warn("Transaction validation failed: outpoint already spent in mempool {}", keyHex);
                    long status = tx.getStatus();
                    status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.DOUBLE_SPEND_FLAG);
                    tx.setStatus(status);
                    return false;
                }
            }
            // 5. 设置UTXO映射并验证交易
            tx.setUtxoMap(utxoMap);
            //设置每一个输入的引用
            for (int i = 0; i < inputs.size(); i++) {
                TxInput txInput = inputs.get(i);
                byte[] key = UTXO.getKey(txInput.getTxId(), txInput.getIndex());
                UTXO utxo = utxoMap.get(bytesToHex(key));
                TxOutput txOutput = new TxOutput();
                txOutput.setValue(utxo.getValue());
                txOutput.setScriptPubKey(utxo.getScript());
                inputs.get(i).setOutput(txOutput);
            }

            if (!tx.validate()) {
                log.warn("交易验证失败：validate()返回false");
                // 标记验证失败状态
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.INVALID_SIG);
                tx.setStatus(status);
                return false;
            }

            // 6. 验证交易ID
            byte[] calculatedTxId = tx.calculateTxId();
            if (!Arrays.equals(tx.getTxId(), calculatedTxId)) {
                log.warn("交易验证失败：交易ID不匹配");
                // 标记ID不匹配状态
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                tx.setStatus(status);
                return false;
            }

            log.info("交易验证成功：txId={}", bytesToHex(tx.getTxId()));
            return true;

        } catch (Exception e) {
            log.error("交易验证异常", e);
            // 标记异常状态
            if (tx != null) {
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                tx.setStatus(status);
            }
            return false;
        }
    }

    @Override
    synchronized public boolean addTx(Transaction tx) {
        if (tx == null) {
            log.warn("添加交易失败：交易为null");
            return false;
        }

        try {
            // 1. 验证交易
            if (tx.getTxId() == null) {
                tx.setTxId(tx.calculateTxId());
            }
            String txIdStr = bytesToHex(tx.getTxId());
            if (mempool.containsKey(txIdStr)) {
                log.warn("Transaction already exists in mempool: {}", txIdStr);
                return false;
            }
            Set<String> conflictingTxIds = getConflictingTxIds(tx);
            if (!conflictingTxIds.isEmpty()) {
                log.info("Transaction conflicts with mempool entries, trying RBF replacement: txId={}, conflicts={}",
                        txIdStr, conflictingTxIds);
                return replaceTx(tx);
            }

            if (!verifyTx(tx)) {
                return false;
            }

            //是否已经上链
            byte[] blockIndexBytes = dataBase.get(TableEnum.TX_TO_BLOCK, tx.getTxId());
            if (blockIndexBytes != null) {
                //已经上链
                log.warn("交易已上链，拒绝添加新交易");
                return false;
            }

            // 4. 缓存手续费
            long fee = calculateFee(tx);
            tx.setFee(fee);
            if (!meetsMinimumRelayFee(tx)) {
                long rejectedStatus = TransactionStatusResolver.setLifecycleStatus(0L, TransactionStatusResolver.REJECTED);
                rejectedStatus = TransactionStatusResolver.addFlag(rejectedStatus, TransactionStatusResolver.INSUFFICIENT_FEE);
                tx.setStatus(rejectedStatus);
                return false;
            }
            if (!ensureCapacityFor(tx)) {
                long rejectedStatus = TransactionStatusResolver.setLifecycleStatus(0L, TransactionStatusResolver.REJECTED);
                rejectedStatus = TransactionStatusResolver.addFlag(rejectedStatus, TransactionStatusResolver.INSUFFICIENT_FEE);
                tx.setStatus(rejectedStatus);
                return false;
            }

            // 5. 初始化交易状态为PENDING（待处理）
            long status = 0L;
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.PENDING);
            tx.setStatus(status);

            // 7. 根据手续费设置优先级标志
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.VERIFIED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SIG_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SCRIPT_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.INPUTS_VERIFIED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.OUTPUTS_VERIFIED);
            status = applyRbfFlagIfEligible(status, tx);

            // 高手续费交易标记为高优先级
            if (fee > 10000) { // 手续费 > 0.0001 BTC
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.HIGH_FEE);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.PRIORITY_HIGH);
            } else if (fee < 1000) { // 手续费 < 0.00001 BTC
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.PRIORITY_LOW);
            }

            // Coinbase交易标记
            if (tx.isCoinbase()) {
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.COINBASE);
            }

            tx.setFee(fee);
            tx.setStatus(status);

            // 8. 添加到内存池并构建地址到交易的映射
            mempool.put(txIdStr, tx);
            txTimestamps.put(txIdStr, System.currentTimeMillis());
            indexTransaction(txIdStr, tx);
            buildAddressMapping(tx, tx.getTxId());

            log.info("交易添加成功：txId={}, fee={}, status={}",
                    txIdStr, fee, TransactionStatusResolver.toString(status));

            // 注意：不在这里调用 addPendingTx 修改UTXO状态
            // UTXO状态只有在区块确认后（onBlockConfirmed）才修改
            // 这样可以避免挖矿失败时的回退逻辑

            List<String> utxoList = tx.getSpendingUTXOKeyList();
            if (utxoList != null) {
                spendingSet.addAll(utxoList);
            }
            List<String> pendingConfirmationOfReceipt = tx.getPendingConfirmationOfReceipt();
            if (pendingConfirmationOfReceipt != null) {
                processingSet.addAll(pendingConfirmationOfReceipt);
            }
            routingTable.BroadcastResource(1,tx.getTxId());
            return true;
        } catch (Exception e) {
            log.error("添加交易异常", e);
            try {
                removeTx(bytesToHex(tx.getTxId()));
            } catch (Exception rollbackEx) {
                log.error("Rollback addTx failed", rollbackEx);
            }
            return false;
        }
    }

    /**
     * 要保持原子性 要么全部添加成功 要么全部失败
     * @param txList
     * @return
     */
    @Override
    synchronized public boolean addTx(List<Transaction> txList) {
        // 1. 参数基础校验
        if (txList == null || txList.isEmpty()) {
            log.warn("批量添加交易失败：交易列表为空或null");
            return false;
        }

        // 用于暂存预检查通过的交易信息，避免重复计算
        Map<String, Transaction> validTxMap = new HashMap<>();
        Map<String, Long> validTxTimestamps = new HashMap<>();
        Map<String, Long> validTxFees = new HashMap<>();
        Map<String, Long> validTxStatuses = new HashMap<>();
        Set<String> batchSpentOutpoints = new HashSet<>();
        // 用于记录需要回滚的操作
        List<Runnable> rollbackActions = new ArrayList<>();

        try {
            // 2. 预检查阶段：验证所有交易的有效性（原子性关键：先检查，后执行）
            for (Transaction tx : txList) {
                if (tx == null) {
                    log.warn("批量添加交易失败：包含null交易");
                    return false;
                }

                if (tx.getTxId() == null) {
                    tx.setTxId(tx.calculateTxId());
                }
                String txIdStr = bytesToHex(tx.getTxId());
                if (validTxMap.containsKey(txIdStr)) {
                    log.warn("Batch add failed: duplicate transaction {}", txIdStr);
                    return false;
                }
                if (mempool.containsKey(txIdStr)) {
                    log.warn("Batch add failed: transaction already exists in mempool {}", txIdStr);
                    return false;
                }

                // 2.1 验证交易本身
                if (!verifyTx(tx)) {
                    log.warn("批量添加交易失败：交易验证不通过，txId={}", txIdStr);
                    return false;
                }

                // 2.3 预计算交易手续费和状态（避免添加时重复计算）
                if (dataBase.get(TableEnum.TX_TO_BLOCK, tx.getTxId()) != null) {
                    log.warn("Batch add failed: transaction already confirmed {}", txIdStr);
                    return false;
                }
                for (String inputKey : getInputKeys(tx)) {
                    if (!batchSpentOutpoints.add(inputKey)) {
                        log.warn("Batch add failed: input already spent in batch {}", inputKey);
                        return false;
                    }
                }

                long fee = calculateFee(tx);
                tx.setFee(fee);
                if (!meetsMinimumRelayFee(tx)) {
                    log.warn("Batch add failed: insufficient relay fee {}", txIdStr);
                    return false;
                }
                long status = 0L;
                // 初始化状态（和单个addTx逻辑一致）
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.PENDING);
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.VERIFIED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SIG_CHECKED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SCRIPT_CHECKED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.INPUTS_VERIFIED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.OUTPUTS_VERIFIED);
                status = applyRbfFlagIfEligible(status, tx);

                // 高/低手续费标记
                if (fee > 10000) {
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.HIGH_FEE);
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.PRIORITY_HIGH);
                } else if (fee < 1000) {
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.PRIORITY_LOW);
                }

                // Coinbase交易标记
                if (tx.isCoinbase()) {
                    status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.COINBASE);
                }

                // 暂存验证通过的交易信息
                validTxMap.put(txIdStr, tx);
                validTxTimestamps.put(txIdStr, System.currentTimeMillis());
                validTxFees.put(txIdStr, fee);
                validTxStatuses.put(txIdStr, status);

                // 2.4 检查内存池容量（批量添加后不超过最大值）
                if (mempool.size() + validTxMap.size() > MAX_MEMPOOL_SIZE) {
                    log.warn("批量添加交易失败：交易池容量不足，当前={}, 待添加={}, 最大={}",
                            mempool.size(), validTxMap.size(), MAX_MEMPOOL_SIZE);
                    return false;
                }
            }

            // 3. 原子添加阶段：批量写入所有验证通过的交易
            for (Map.Entry<String, Transaction> entry : validTxMap.entrySet()) {
                String txIdStr = entry.getKey();
                Transaction tx = entry.getValue();
                Long fee = validTxFees.get(txIdStr);
                Long status = validTxStatuses.get(txIdStr);
                Long timestamp = validTxTimestamps.get(txIdStr);

                // 3.1 设置交易属性
                tx.setFee(fee);
                tx.setStatus(status);

                // 3.2 添加到内存池，并记录回滚操作
                mempool.put(txIdStr, tx);
                indexTransaction(txIdStr, tx);
                rollbackActions.add(() -> removeTx(txIdStr));

                // 3.3 添加时间戳，并记录回滚操作
                txTimestamps.put(txIdStr, timestamp);
                rollbackActions.add(() -> txTimestamps.remove(txIdStr));

                // 3.4 构建地址映射，并记录回滚操作
                byte[] txId = tx.getTxId();
                buildAddressMapping(tx, txId);
                rollbackActions.add(() -> removeAddressMapping(tx));

                // 3.5 更新spendingSet和processingSet，并记录回滚操作
                // 注意：不在这里调用 addPendingTx 修改UTXO状态
                // UTXO状态只有在区块确认后（onBlockConfirmed）才修改
                // 这样可以避免挖矿失败时的回退逻辑

                List<String> utxoList = tx.getSpendingUTXOKeyList();
                if (utxoList != null) {
                    spendingSet.addAll(utxoList);
                    rollbackActions.add(() -> utxoList.forEach(spendingSet::remove));
                }

                List<String> pendingList = tx.getPendingConfirmationOfReceipt();
                if (pendingList != null) {
                    processingSet.addAll(pendingList);
                    rollbackActions.add(() -> pendingList.forEach(processingSet::remove));
                }

                // 3.6 广播交易
                routingTable.BroadcastResource(1, txId);

                log.info("批量添加交易成功（单条）：txId={}, fee={}, status={}",
                        txIdStr, fee, TransactionStatusResolver.toString(status));
            }

            log.info("批量添加交易完成：共成功添加 {} 笔交易", txList.size());
            return true;

        } catch (Exception e) {
            log.error("批量添加交易异常，执行回滚", e);
            // 4. 回滚所有已执行的操作（逆序执行回滚动作，保证数据一致性）
            Collections.reverse(rollbackActions);
            for (Runnable rollback : rollbackActions) {
                try {
                    rollback.run();
                } catch (Exception rollbackEx) {
                    log.error("回滚操作异常", rollbackEx);
                }
            }
            return false;
        }
    }


    @Override
    public Result<String> addTx(TransferDTO tx) {
        Transaction transaction = tx.toTransaction();
        byte[] txId = transaction.calculateTxId();
        transaction.setTxId(txId);
        if (addTx(transaction)){
            return Result.OK("交易已经提交到交易池",bytesToHex(txId));
        }else {
            return Result.error("交易添加失败");
        }
    }



    @Override
    public long estimateTxFee(Transaction tx) {
        return calculateFee(tx);
    }

    @Override
    public Transaction getTx(String txHash) {
        if (txHash == null) {
            return null;
        }
        return mempool.get(txHash);
    }

    @Override
    public Transaction getTx(byte[] txHash) {
        if (txHash == null) {
            return null;
        }
        String txIdStr = bytesToHex(txHash);
        return mempool.get(txIdStr);
    }

    @Override
    public Transaction[] getAllTxs() {
        return mempool.values().stream()
                .filter(Objects::nonNull)
                .toArray(Transaction[]::new);
    }

    @Override
    public TxPoolStatusSnapshot getStatusSnapshot() {
        long now = System.currentTimeMillis();
        List<TxPoolTransactionSnapshot> transactions = mempool.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> toTransactionSnapshot(entry.getKey(), entry.getValue(), now))
                .sorted(Comparator
                        .comparing(TxPoolTransactionSnapshot::packing).reversed()
                        .thenComparing(Comparator.comparingDouble(TxPoolTransactionSnapshot::feeRate).reversed())
                        .thenComparing(TxPoolTransactionSnapshot::txId))
                .toList();

        long totalFee = transactions.stream().mapToLong(TxPoolTransactionSnapshot::fee).sum();
        long totalSize = transactions.stream().mapToLong(TxPoolTransactionSnapshot::size).sum();
        int dependencyCount = parentToChildren.values().stream()
                .filter(Objects::nonNull)
                .mapToInt(Set::size)
                .sum();
        int createdOutpointCount = txToCreatedOutpoints.values().stream()
                .filter(Objects::nonNull)
                .mapToInt(Set::size)
                .sum();

        return new TxPoolStatusSnapshot(
                now,
                MAX_MEMPOOL_SIZE,
                transactions.size(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::available).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::packing).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::locked).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::highPriority).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::lowPriority).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::rbfEnabled).count(),
                (int) transactions.stream().filter(TxPoolTransactionSnapshot::doubleSpend).count(),
                totalFee,
                totalSize,
                totalSize == 0L ? 0D : (double) totalFee / (double) totalSize,
                spentOutpointToTx.size(),
                createdOutpointCount,
                dependencyCount,
                spendingSet.size(),
                processingSet.size(),
                transactions
        );
    }

    @Override
    synchronized public boolean replaceTx(Transaction tx) {
        if (tx == null || tx.isCoinbase()) {
            log.warn("RBF replace failed: tx is null or coinbase");
            return false;
        }

        try {
            if (tx.getTxId() == null) {
                tx.setTxId(tx.calculateTxId());
            }
            String txIdStr = bytesToHex(tx.getTxId());
            if (mempool.containsKey(txIdStr)) {
                log.warn("Transaction already exists in mempool: {}", txIdStr);
                return false;
            }
            Set<String> directConflicts = getConflictingTxIds(tx);
            if (directConflicts.isEmpty()) {
                log.warn("RBF replace failed: no conflicting transaction found");
                return false;
            }
            for (String conflictTxId : directConflicts) {
                if (!isReplaceableInMempool(conflictTxId)) {
                    log.warn("RBF replace failed: original transaction is not replaceable {}", conflictTxId);
                    return false;
                }
            }

            Set<String> txsToReplace = new LinkedHashSet<>();
            Set<String> requiredInputs = new HashSet<>();
            long originalFee = 0L;
            for (String conflictTxId : directConflicts) {
                txsToReplace.addAll(collectDescendantsIncludingSelf(conflictTxId));
                Set<String> conflictInputs = txToSpentOutpoints.get(conflictTxId);
                if (conflictInputs != null) {
                    requiredInputs.addAll(conflictInputs);
                }
            }
            if (txsToReplace.size() > MAX_RBF_REPLACEMENT_CANDIDATES) {
                log.warn("RBF replace failed: too many transactions would be replaced count={}", txsToReplace.size());
                return false;
            }

            for (String replaceId : txsToReplace) {
                Transaction original = mempool.get(replaceId);
                if (original == null) {
                    continue;
                }
                if (packingTxs.containsKey(replaceId)) {
                    log.warn("RBF replace failed: transaction is packing {}", replaceId);
                    return false;
                }
                originalFee = Math.addExact(originalFee, original.getFee());
            }

            Set<String> replacementInputs = getInputKeys(tx);
            if (!replacementInputs.containsAll(requiredInputs)) {
                log.warn("RBF replace failed: replacement does not cover original inputs");
                return false;
            }
            if (!usesOnlyAllowedUnconfirmedInputs(tx, txsToReplace)) {
                log.warn("RBF replace failed: replacement uses new unconfirmed inputs");
                return false;
            }

            if (!verifyTx(tx, txsToReplace)) {
                log.warn("RBF replace failed: replacement validation failed");
                return false;
            }

            long newFee = calculateFee(tx);
            long requiredFee = (long) Math.ceil(originalFee * RBF_FEE_INCREASE_RATIO);
            if (newFee < requiredFee) {
                log.warn("RBF replace failed: insufficient fee increase original={}, new={}, required={}",
                        originalFee, newFee, requiredFee);
                return false;
            }

            for (String conflictTxId : directConflicts) {
                removeTxAndDescendants(conflictTxId, TransactionStatusResolver.REPLACED, 0L);
            }

            boolean added = addTx(tx);
            if (added) {
                log.info("RBF replace success: replacement={}, removed={}", txIdStr, txsToReplace.size());
            }
            return added;
        } catch (Exception e) {
            log.error("RBF replace failed", e);
            return false;
        }
    }

    private boolean replaceTxLegacy(Transaction tx) {
        if (tx == null || tx.isCoinbase()) {
            log.warn("替换交易失败：交易为null或为Coinbase交易");
            return false;
        }

        try {
            String txIdStr = bytesToHex(tx.getTxId());
            String originalTxIdStr = getOriginalTxId(tx);

            if (originalTxIdStr == null) {
                log.warn("替换交易失败：找不到原始交易");
                return false;
            }

            Transaction originalTx = mempool.get(originalTxIdStr);
            if (originalTx == null) {
                log.warn("替换交易失败：原始交易不在内存池中");
                return false;
            }

            // 1. 验证新交易
            if (!verifyTx(tx)) {
                log.warn("替换交易失败：新交易验证失败");
                return false;
            }

            // 2. 检查是否替换相同的输入（双花检测）
            if (!hasSameInputs(tx, originalTx)) {
                log.warn("替换交易失败：新交易和原交易输入不一致");
                return false;
            }

            // 3. 计算手续费
            long newFee = calculateFee(tx);
            long originalFee = originalTx.getFee();

            // 4. 检查手续费是否满足RBF规则（至少增加25%）
            if (newFee < originalFee * RBF_FEE_INCREASE_RATIO) {
                log.warn("替换交易失败：手续费增加不足，原始={}, 新={}, 要求={}",
                        originalFee, newFee, (long)(originalFee * RBF_FEE_INCREASE_RATIO));
                return false;
            }

            // 5. 标记原交易为REPLACED（被替换）
            long originalStatus = originalTx.getStatus();
            originalStatus = TransactionStatusResolver.setLifecycleStatus(originalStatus, TransactionStatusResolver.REPLACED);
            originalTx.setStatus(originalStatus);
            log.info("标记原交易为被替换：{}", originalTxIdStr);

            // 6. 移除原交易
            removeTx(originalTxIdStr);

            // 7. 添加新交易到内存池
            long status = 0L;
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.VERIFIED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SIG_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SCRIPT_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.RBF_ENABLED);

            if (newFee > 10000) {
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.HIGH_FEE);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.PRIORITY_HIGH);
            }

            tx.setFee(newFee);
            tx.setStatus(status);

            mempool.put(txIdStr, tx);
            txTimestamps.put(txIdStr, System.currentTimeMillis());

            // 8. 重建地址映射
            buildAddressMapping(tx, tx.getTxId());

            log.info("交易替换成功：原始={}, 新={}, 原始手续费={}, 新手续费={}",
                    originalTxIdStr, txIdStr, originalFee, newFee);
            return true;

        } catch (Exception e) {
            log.error("替换交易异常", e);
            return false;
        }
    }

    @Override
    synchronized public Transaction[] getBestTxs() {
        return selectTransactionsForBlock(false);
    }

    private Transaction[] getBestTxsLegacy() {
        // 按手续费降序排序
        List<Transaction> sortedTxs = mempool.values().stream()
                .sorted((a, b) -> {
                    long feeA = a.getFee();
                    long feeB = b.getFee();
                    return Long.compare(feeB, feeA); // 降序
                })
                .toList();

        // 选择交易直到超过1MB
        List<Transaction> selectedTxs = new ArrayList<>();
        long currentSize = 0;

        for (Transaction tx : sortedTxs) {
            String txIdStr = bytesToHex(tx.getTxId());

            // 跳过已在打包中的交易
            if (packingTxs.containsKey(txIdStr)) {
                continue;
            }

            int txSize = estimateTxSize(tx);
            if (currentSize + txSize > MAX_BLOCK_SIZE) {
                break;
            }
            selectedTxs.add(tx);
            currentSize += txSize;
        }

        return selectedTxs.toArray(new Transaction[0]);
    }

    @Override
    synchronized public Transaction[] extractTxsForBlock() {
        return selectTransactionsForBlock(true);
    }

    private Transaction[] extractTxsForBlockLegacy() {
        // 按手续费降序排序
        List<Transaction> sortedTxs = mempool.values().stream()
                .sorted((a, b) -> {
                    long feeA = a.getFee();
                    long feeB = b.getFee();
                    return Long.compare(feeB, feeA); // 降序
                })
                .toList();

        // 选择交易直到超过1MB
        List<Transaction> selectedTxs = new ArrayList<>();
        long currentSize = 0;

        for (Transaction tx : sortedTxs) {
            String txIdStr = bytesToHex(tx.getTxId());

            // 跳过已在打包中的交易
            if (packingTxs.containsKey(txIdStr)) {
                continue;
            }

            // 跳过不可用的交易（有时间锁定等）
            long status = tx.getStatus();
            if (!TransactionStatusResolver.isAvailable(status)) {
                continue;
            }

            int txSize = estimateTxSize(tx);
            if (currentSize + txSize > MAX_BLOCK_SIZE) {
                break;
            }
            selectedTxs.add(tx);
            currentSize += txSize;
        }

        // 将选中的交易标记为打包中
        for (Transaction tx : selectedTxs) {
            String txIdStr = bytesToHex(tx.getTxId());
            Transaction memTx = mempool.get(txIdStr);
            if (memTx != null) {
                packingTxs.put(txIdStr, memTx);

                // 更新交易状态为PACKING
                long txStatus = memTx.getStatus();
                txStatus = TransactionStatusResolver.setLifecycleStatus(txStatus, TransactionStatusResolver.PACKING);
                txStatus = TransactionStatusResolver.addFlag(txStatus, TransactionStatusResolver.MEMPOOL_LOCKED);
                memTx.setStatus(txStatus);

                log.debug("提取交易到区块，标记为打包中：{}，状态={}",
                        txIdStr, TransactionStatusResolver.toString(txStatus));
            }
        }

        log.info("从交易池提取 {} 笔交易到区块", selectedTxs.size());
        return selectedTxs.toArray(new Transaction[0]);
    }

    @Override
    public Transaction[] getTxsByAddress(String address) {
        byte[] decode = Base58.decode(address);
        address = bytesToHex(Sha.applyRIPEMD160(Sha.applySHA256(decode)));
        Set<byte[]> txIds = addressToTxs.get(address);
        if (txIds == null || txIds.isEmpty()) {
            return new Transaction[0];
        }
        return txIds.stream()
                .map(txId -> {
                    String txIdStr = bytesToHex(txId);
                    return mempool.get(txIdStr);
                })
                .filter(Objects::nonNull)
                .toArray(Transaction[]::new);
    }


    // =============== 辅助方法 ===============

    /**
     * 计算交易手续费
     */
    private TxPoolTransactionSnapshot toTransactionSnapshot(String txIdStr, Transaction tx, long now) {
        long status = tx.getStatus();
        long size = Math.max(0L, estimateTxSize(tx));
        long fee = tx.getFee();
        Long joinTime = txTimestamps.getOrDefault(txIdStr, tx.getJoinTime());
        boolean packing = packingTxs.containsKey(txIdStr)
                || TransactionStatusResolver.hasLifecycle(status, TransactionStatusResolver.PACKING);
        boolean locked = packing || TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.MEMPOOL_LOCKED);
        boolean highPriority = TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.PRIORITY_HIGH)
                || TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.HIGH_FEE);
        boolean lowPriority = TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.PRIORITY_LOW);
        boolean rbfEnabled = TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.RBF_ENABLED);
        boolean doubleSpend = TransactionStatusResolver.hasFlag(status, TransactionStatusResolver.DOUBLE_SPEND_FLAG);
        boolean available = !locked && TransactionStatusResolver.isAvailable(status);

        Set<String> inputOutpoints = txToSpentOutpoints.get(txIdStr);
        Set<String> outputOutpoints = txToCreatedOutpoints.get(txIdStr);

        return new TxPoolTransactionSnapshot(
                txIdStr,
                status,
                lifecycleCode(status),
                statusFlags(status),
                available,
                packing,
                locked,
                highPriority,
                lowPriority,
                rbfEnabled,
                doubleSpend,
                fee,
                size,
                size == 0L ? 0D : (double) fee / (double) size,
                tx.getInputs() == null ? 0 : tx.getInputs().size(),
                tx.getOutputs() == null ? 0 : tx.getOutputs().size(),
                joinTime,
                tx.getBroadcastTime(),
                joinTime == null ? 0L : Math.max(0L, (now - joinTime) / 1000L),
                sortedValues(inputOutpoints == null ? inputOutpointsOf(tx) : inputOutpoints),
                sortedValues(outputOutpoints == null ? outputOutpointsOf(txIdStr, tx) : outputOutpoints),
                sortedValues(txToParents.get(txIdStr)),
                sortedValues(parentToChildren.get(txIdStr))
        );
    }

    private Set<String> inputOutpointsOf(Transaction tx) {
        if (tx == null || tx.getInputs() == null) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (TxInput input : tx.getInputs()) {
            if (input == null || input.getTxId() == null) {
                continue;
            }
            keys.add(UTXO.getKeyStr(input.getTxId(), input.getIndex()));
        }
        return keys;
    }

    private Set<String> outputOutpointsOf(String txIdStr, Transaction tx) {
        if (tx == null || tx.getOutputs() == null) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            if (tx.getTxId() == null) {
                keys.add(txIdStr + ":" + i);
            } else {
                keys.add(UTXO.getKeyStr(tx.getTxId(), i));
            }
        }
        return keys;
    }

    private List<String> sortedValues(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .sorted()
                .toList();
    }

    private String lifecycleCode(long status) {
        long lifecycle = TransactionStatusResolver.getLifecycleStatus(status);
        if ((lifecycle & TransactionStatusResolver.PENDING) != 0L) return "PENDING";
        if ((lifecycle & TransactionStatusResolver.IN_POOL) != 0L) return "IN_POOL";
        if ((lifecycle & TransactionStatusResolver.PACKING) != 0L) return "PACKING";
        if ((lifecycle & TransactionStatusResolver.CONFIRMED) != 0L) return "CONFIRMED";
        if ((lifecycle & TransactionStatusResolver.ORPHANED) != 0L) return "ORPHANED";
        if ((lifecycle & TransactionStatusResolver.REJECTED) != 0L) return "REJECTED";
        if ((lifecycle & TransactionStatusResolver.REPLACED) != 0L) return "REPLACED";
        if ((lifecycle & TransactionStatusResolver.EXPIRED) != 0L) return "EXPIRED";
        if ((status & TransactionStatusResolver.FORK) != 0L) return "FORK";
        return "UNKNOWN";
    }

    private List<String> statusFlags(long status) {
        List<String> flags = new ArrayList<>();
        addFlagCode(flags, status, TransactionStatusResolver.COINBASE, "COINBASE");
        addFlagCode(flags, status, TransactionStatusResolver.HIGH_FEE, "HIGH_FEE");
        addFlagCode(flags, status, TransactionStatusResolver.DUST_TX, "DUST_TX");
        addFlagCode(flags, status, TransactionStatusResolver.LARGE_SIZE, "LARGE_SIZE");
        addFlagCode(flags, status, TransactionStatusResolver.VERIFIED, "VERIFIED");
        addFlagCode(flags, status, TransactionStatusResolver.SIG_CHECKED, "SIG_CHECKED");
        addFlagCode(flags, status, TransactionStatusResolver.SCRIPT_CHECKED, "SCRIPT_CHECKED");
        addFlagCode(flags, status, TransactionStatusResolver.INPUTS_VERIFIED, "INPUTS_VERIFIED");
        addFlagCode(flags, status, TransactionStatusResolver.OUTPUTS_VERIFIED, "OUTPUTS_VERIFIED");
        addFlagCode(flags, status, TransactionStatusResolver.MEMPOOL_LOCKED, "MEMPOOL_LOCKED");
        addFlagCode(flags, status, TransactionStatusResolver.PRIORITY_HIGH, "PRIORITY_HIGH");
        addFlagCode(flags, status, TransactionStatusResolver.PRIORITY_LOW, "PRIORITY_LOW");
        addFlagCode(flags, status, TransactionStatusResolver.RBF_ENABLED, "RBF_ENABLED");
        addFlagCode(flags, status, TransactionStatusResolver.CPFP_ENABLED, "CPFP_ENABLED");
        addFlagCode(flags, status, TransactionStatusResolver.CONFIRMED_WEAK, "CONFIRMED_WEAK");
        addFlagCode(flags, status, TransactionStatusResolver.CONFIRMED_SAFE, "CONFIRMED_SAFE");
        addFlagCode(flags, status, TransactionStatusResolver.DEEP_CONFIRMED, "DEEP_CONFIRMED");
        addFlagCode(flags, status, TransactionStatusResolver.DOUBLE_SPEND_FLAG, "DOUBLE_SPEND_FLAG");
        addFlagCode(flags, status, TransactionStatusResolver.INVALID_SIG, "INVALID_SIG");
        addFlagCode(flags, status, TransactionStatusResolver.INSUFFICIENT_FEE, "INSUFFICIENT_FEE");
        addFlagCode(flags, status, TransactionStatusResolver.NON_STANDARD, "NON_STANDARD");
        addFlagCode(flags, status, TransactionStatusResolver.TIME_LOCKED, "TIME_LOCKED");
        addFlagCode(flags, status, TransactionStatusResolver.HEIGHT_LOCKED, "HEIGHT_LOCKED");
        addFlagCode(flags, status, TransactionStatusResolver.MULTISIG_TX, "MULTISIG_TX");
        addFlagCode(flags, status, TransactionStatusResolver.CONTRACT_TX, "CONTRACT_TX");
        addFlagCode(flags, status, TransactionStatusResolver.WITNESS_TX, "WITNESS_TX");
        return flags;
    }

    private void addFlagCode(List<String> flags, long status, long flag, String code) {
        if (TransactionStatusResolver.hasFlag(status, flag)) {
            flags.add(code);
        }
    }

    private long applyRbfFlagIfEligible(long status, Transaction tx) {
        if (isTransactionReplaceable(tx)) {
            return TransactionStatusResolver.addFlag(status, TransactionStatusResolver.RBF_ENABLED);
        }
        return status;
    }

    private boolean isTransactionReplaceable(Transaction tx) {
        return signalsExplicitRBF(tx) || inheritsRBF(tx);
    }

    private boolean signalsExplicitRBF(Transaction tx) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().isEmpty()) {
            return false;
        }
        for (TxInput input : tx.getInputs()) {
            if (input != null && input.getSequence() < RBF_SEQUENCE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private boolean inheritsRBF(Transaction tx) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().isEmpty()) {
            return false;
        }
        for (TxInput input : tx.getInputs()) {
            if (input == null || input.getTxId() == null) {
                continue;
            }
            if (isReplaceableInMempool(bytesToHex(input.getTxId()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isReplaceableInMempool(String txIdStr) {
        return isReplaceableInMempool(txIdStr, new HashSet<>());
    }

    private boolean isReplaceableInMempool(String txIdStr, Set<String> visited) {
        if (txIdStr == null || !visited.add(txIdStr)) {
            return false;
        }
        Transaction tx = mempool.get(txIdStr);
        if (tx == null) {
            return false;
        }
        if (TransactionStatusResolver.hasFlag(tx.getStatus(), TransactionStatusResolver.RBF_ENABLED)
                || signalsExplicitRBF(tx)) {
            return true;
        }
        Set<String> parents = txToParents.get(txIdStr);
        if (parents == null || parents.isEmpty()) {
            return false;
        }
        for (String parent : parents) {
            if (isReplaceableInMempool(parent, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean usesOnlyAllowedUnconfirmedInputs(Transaction replacement, Set<String> txsToReplace) {
        Set<String> originalUnconfirmedInputs = new HashSet<>();
        for (String replaceId : txsToReplace) {
            Transaction original = mempool.get(replaceId);
            if (original == null || original.getInputs() == null) {
                continue;
            }
            for (TxInput input : original.getInputs()) {
                if (input == null || input.getTxId() == null) {
                    continue;
                }
                String parentTxId = bytesToHex(input.getTxId());
                if (mempool.containsKey(parentTxId) && !txsToReplace.contains(parentTxId)) {
                    originalUnconfirmedInputs.add(UTXO.getKeyStr(input.getTxId(), input.getIndex()));
                }
            }
        }

        if (replacement == null || replacement.getInputs() == null) {
            return true;
        }
        for (TxInput input : replacement.getInputs()) {
            if (input == null || input.getTxId() == null) {
                continue;
            }
            String parentTxId = bytesToHex(input.getTxId());
            if (!mempool.containsKey(parentTxId)) {
                continue;
            }
            if (txsToReplace.contains(parentTxId)) {
                return false;
            }
            String inputKey = UTXO.getKeyStr(input.getTxId(), input.getIndex());
            if (!originalUnconfirmedInputs.contains(inputKey)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> getInputKeys(Transaction tx) {
        if (tx == null || tx.getInputs() == null || tx.getInputs().isEmpty() || tx.isCoinbase()) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (TxInput input : tx.getInputs()) {
            if (input == null || input.getTxId() == null) {
                continue;
            }
            keys.add(UTXO.getKeyStr(input.getTxId(), input.getIndex()));
        }
        return keys;
    }

    private Set<String> getCreatedOutpointKeys(Transaction tx) {
        if (tx == null || tx.isCoinbase() || tx.getTxId() == null || tx.getOutputs() == null) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < tx.getOutputs().size(); i++) {
            keys.add(UTXO.getKeyStr(tx.getTxId(), i));
        }
        return keys;
    }

    private UTXO resolveInputUTXO(TxInput input, byte[] key) {
        if (input == null || input.getTxId() == null || key == null) {
            return null;
        }
        UTXO confirmed = utxoCache.getUTXO(key);
        if (confirmed != null) {
            return confirmed;
        }
        Transaction parentTx = mempool.get(bytesToHex(input.getTxId()));
        if (parentTx == null || parentTx.getOutputs() == null
                || input.getIndex() < 0 || input.getIndex() >= parentTx.getOutputs().size()) {
            return null;
        }
        TxOutput parentOutput = parentTx.getOutputs().get(input.getIndex());
        if (parentOutput == null || parentOutput.getValue() < 0) {
            return null;
        }
        UTXO utxo = new UTXO();
        utxo.setTxId(input.getTxId());
        utxo.setIndex(input.getIndex());
        utxo.setValue(parentOutput.getValue());
        utxo.setScript(parentOutput.getScriptPubKey());
        utxo.setCoinbase(false);
        utxo.setHeight(0);
        utxo.setStatus(UTXOStatusResolver.setLifecycleStatus(0L, UTXOStatusResolver.UNCONFIRMED_OUTPUT));
        return utxo;
    }

    private boolean isInputUTXOSpendable(UTXO utxo, TxInput input) {
        if (utxo == null) {
            return false;
        }
        if (utxo.isSpendable()) {
            return true;
        }
        return input != null && input.getTxId() != null
                && mempool.containsKey(bytesToHex(input.getTxId()))
                && UTXOStatusResolver.hasLifecycle(utxo.getStatus(), UTXOStatusResolver.UNCONFIRMED_OUTPUT);
    }

    private Set<String> getConflictingTxIds(Transaction tx) {
        Set<String> conflicts = new LinkedHashSet<>();
        for (String inputKey : getInputKeys(tx)) {
            String conflictTxId = spentOutpointToTx.get(inputKey);
            if (conflictTxId != null) {
                conflicts.add(conflictTxId);
            }
        }
        return conflicts;
    }

    private void indexTransaction(String txIdStr, Transaction tx) {
        if (txIdStr == null || tx == null || tx.isCoinbase() || tx.getInputs() == null) {
            return;
        }

        Set<String> spentKeys = ConcurrentHashMap.newKeySet();
        spentKeys.addAll(getInputKeys(tx));
        txToSpentOutpoints.put(txIdStr, spentKeys);
        for (String spentKey : spentKeys) {
            spentOutpointToTx.put(spentKey, txIdStr);
        }

        Set<String> createdKeys = ConcurrentHashMap.newKeySet();
        createdKeys.addAll(getCreatedOutpointKeys(tx));
        txToCreatedOutpoints.put(txIdStr, createdKeys);

        Set<String> parents = ConcurrentHashMap.newKeySet();
        for (TxInput input : tx.getInputs()) {
            if (input == null || input.getTxId() == null) {
                continue;
            }
            String parentTxId = bytesToHex(input.getTxId());
            if (!parentTxId.equals(txIdStr) && mempool.containsKey(parentTxId)) {
                parents.add(parentTxId);
                parentToChildren.computeIfAbsent(parentTxId, ignored -> ConcurrentHashMap.newKeySet()).add(txIdStr);
            }
        }
        txToParents.put(txIdStr, parents);
    }

    private void unindexTransaction(String txIdStr, Transaction tx) {
        Set<String> spentKeys = txToSpentOutpoints.remove(txIdStr);
        if (spentKeys != null) {
            for (String spentKey : spentKeys) {
                spentOutpointToTx.remove(spentKey, txIdStr);
            }
        }
        txToCreatedOutpoints.remove(txIdStr);

        Set<String> parents = txToParents.remove(txIdStr);
        if (parents != null) {
            for (String parent : parents) {
                Set<String> children = parentToChildren.get(parent);
                if (children != null) {
                    children.remove(txIdStr);
                    if (children.isEmpty()) {
                        parentToChildren.remove(parent);
                    }
                }
            }
        }

        Set<String> children = parentToChildren.remove(txIdStr);
        if (children != null) {
            for (String child : children) {
                Set<String> childParents = txToParents.get(child);
                if (childParents != null) {
                    childParents.remove(txIdStr);
                }
            }
        }
    }

    private Set<String> collectDescendantsIncludingSelf(String txIdStr) {
        if (txIdStr == null) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(txIdStr);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!result.add(current)) {
                continue;
            }
            Set<String> children = parentToChildren.get(current);
            if (children != null) {
                queue.addAll(children);
            }
        }
        return result;
    }

    private void removeTxAndDescendants(String txIdStr, long lifecycle, long flag) {
        List<String> removeOrder = new ArrayList<>(collectDescendantsIncludingSelf(txIdStr));
        Collections.reverse(removeOrder);
        for (String removeId : removeOrder) {
            Transaction tx = mempool.get(removeId);
            if (tx != null) {
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, lifecycle);
                if (flag != 0L) {
                    status = TransactionStatusResolver.addFlag(status, flag);
                }
                tx.setStatus(status);
            }
            removeTx(removeId);
        }
    }

    private Transaction[] selectTransactionsForBlock(boolean markPacking) {
        List<Transaction> sortedTxs = mempool.values().stream()
                .filter(this::isSelectableForBlock)
                .sorted((a, b) -> {
                    int feeRateCompare = Double.compare(packageFeeRate(b), packageFeeRate(a));
                    if (feeRateCompare != 0) {
                        return feeRateCompare;
                    }
                    return Long.compare(b.getFee(), a.getFee());
                })
                .toList();

        List<Transaction> selectedTxs = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        long currentSize = 0L;

        for (Transaction tx : sortedTxs) {
            String txIdStr = bytesToHex(tx.getTxId());
            if (selectedIds.contains(txIdStr)) {
                continue;
            }

            List<Transaction> packageTxs = getPackageInMiningOrder(txIdStr);
            if (packageTxs.isEmpty()) {
                continue;
            }

            long packageSize = 0L;
            boolean packageValid = true;
            for (Transaction packageTx : packageTxs) {
                String packageTxId = bytesToHex(packageTx.getTxId());
                if (selectedIds.contains(packageTxId)) {
                    continue;
                }
                if (!isSelectableForBlock(packageTx) || !verifyTx(packageTx, Collections.singleton(packageTxId))) {
                    removeTxAndDescendants(packageTxId, TransactionStatusResolver.REJECTED,
                            TransactionStatusResolver.NON_STANDARD);
                    packageValid = false;
                    break;
                }
                packageSize = Math.addExact(packageSize, estimateTxSize(packageTx));
            }

            if (!packageValid || currentSize + packageSize > MAX_BLOCK_SIZE) {
                continue;
            }

            for (Transaction packageTx : packageTxs) {
                String packageTxId = bytesToHex(packageTx.getTxId());
                if (selectedIds.add(packageTxId)) {
                    selectedTxs.add(packageTx);
                    currentSize += estimateTxSize(packageTx);
                }
            }
        }

        if (markPacking) {
            for (Transaction tx : selectedTxs) {
                String txIdStr = bytesToHex(tx.getTxId());
                Transaction memTx = mempool.get(txIdStr);
                if (memTx != null) {
                    packingTxs.put(txIdStr, memTx);
                    long txStatus = memTx.getStatus();
                    txStatus = TransactionStatusResolver.setLifecycleStatus(txStatus, TransactionStatusResolver.PACKING);
                    txStatus = TransactionStatusResolver.addFlag(txStatus, TransactionStatusResolver.MEMPOOL_LOCKED);
                    memTx.setStatus(txStatus);
                }
            }
        }

        return selectedTxs.toArray(new Transaction[0]);
    }

    private boolean isSelectableForBlock(Transaction tx) {
        if (tx == null || tx.isCoinbase()) {
            return false;
        }
        String txIdStr = bytesToHex(tx.getTxId());
        return !packingTxs.containsKey(txIdStr) && TransactionStatusResolver.isAvailable(tx.getStatus());
    }

    private double packageFeeRate(Transaction tx) {
        String txIdStr = bytesToHex(tx.getTxId());
        List<Transaction> packageTxs = getPackageInMiningOrder(txIdStr);
        long packageFee = 0L;
        long packageSize = 0L;
        for (Transaction packageTx : packageTxs) {
            packageFee += packageTx.getFee();
            packageSize += Math.max(1, estimateTxSize(packageTx));
        }
        return packageSize == 0L ? 0D : (double) packageFee / (double) packageSize;
    }

    private double feeRate(Transaction tx) {
        if (tx == null) {
            return 0D;
        }
        return (double) tx.getFee() / (double) Math.max(1, estimateTxSize(tx));
    }

    private boolean meetsMinimumRelayFee(Transaction tx) {
        return feeRate(tx) >= MIN_RELAY_FEE_RATE;
    }

    private boolean ensureCapacityFor(Transaction candidate) {
        if (mempool.size() < MAX_MEMPOOL_SIZE) {
            return true;
        }

        Transaction lowestFeeRateTx = mempool.values().stream()
                .filter(Objects::nonNull)
                .filter(tx -> !packingTxs.containsKey(bytesToHex(tx.getTxId())))
                .min(Comparator.comparingDouble(this::feeRate))
                .orElse(null);
        if (lowestFeeRateTx == null || feeRate(candidate) <= feeRate(lowestFeeRateTx)) {
            return false;
        }

        removeTxAndDescendants(bytesToHex(lowestFeeRateTx.getTxId()),
                TransactionStatusResolver.REJECTED,
                TransactionStatusResolver.INSUFFICIENT_FEE);
        return mempool.size() < MAX_MEMPOOL_SIZE;
    }

    private List<Transaction> getPackageInMiningOrder(String txIdStr) {
        List<Transaction> ordered = new ArrayList<>();
        addAncestorsFirst(txIdStr, new HashSet<>(), ordered);
        return ordered;
    }

    private void addAncestorsFirst(String txIdStr, Set<String> visited, List<Transaction> ordered) {
        if (!visited.add(txIdStr)) {
            return;
        }
        Set<String> parents = txToParents.get(txIdStr);
        if (parents != null) {
            for (String parent : parents) {
                if (mempool.containsKey(parent)) {
                    addAncestorsFirst(parent, visited, ordered);
                }
            }
        }
        Transaction tx = mempool.get(txIdStr);
        if (tx != null) {
            ordered.add(tx);
        }
    }

    private long calculateFee(Transaction tx) {
        if (tx == null) {
            throw new IllegalArgumentException("transaction is null");
        }
        if (tx.isCoinbase()) {
            return 0;
        }

        long totalInput = 0;
        long totalOutput = 0;

        // 计算总输入
        for (TxInput input : tx.getInputs()) {
            if (input == null || input.getTxId() == null || input.getIndex() < 0) {
                throw new IllegalArgumentException("invalid transaction input");
            }
            byte[] key = UTXO.getKey(input.getTxId(), input.getIndex());
            UTXO utxo = resolveInputUTXO(input, key);
            if (utxo == null) {
                throw new IllegalArgumentException("missing input UTXO");
            }
            totalInput = Math.addExact(totalInput, utxo.getValue());
        }

        // 计算总输出
        for (var output : tx.getOutputs()) {
            if (output == null || output.getValue() < 0) {
                throw new IllegalArgumentException("negative output value");
            }
            totalOutput = Math.addExact(totalOutput, output.getValue());
        }
        long fee = totalInput - totalOutput;
        if (fee < 0) {
            throw new IllegalArgumentException("negative transaction fee");
        }

        tx.setFee(fee);
        return fee;
    }

    /**
     * 获取原始交易ID（用于RBF）
     */
    private String getOriginalTxId(Transaction newTx) {
        // 查找与新交易输入相同的原始交易
        for (TxInput newInput : newTx.getInputs()) {
            for (Map.Entry<String, Transaction> entry : mempool.entrySet()) {
                Transaction memTx = entry.getValue();
                for (TxInput memInput : memTx.getInputs()) {
                    if (Arrays.equals(newInput.getTxId(), memInput.getTxId())
                            && newInput.getIndex() == memInput.getIndex()) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 检查两笔交易是否有相同的输入
     */
    private boolean hasSameInputs(Transaction tx1, Transaction tx2) {
        Set<String> inputs1 = tx1.getInputs().stream()
                .map(i -> bytesToHex(i.getTxId()) + ":" + i.getIndex())
                .collect(Collectors.toSet());

        Set<String> inputs2 = tx2.getInputs().stream()
                .map(i -> bytesToHex(i.getTxId()) + ":" + i.getIndex())
                .collect(Collectors.toSet());

        return inputs1.equals(inputs2);
    }

    /**
     * 移除交易
     */
    private void removeTx(String txIdStr) {
        Transaction tx = mempool.remove(txIdStr);
        if (tx != null) {
            txTimestamps.remove(txIdStr);
            packingTxs.remove(txIdStr);
            unindexTransaction(txIdStr, tx);

            // 移除地址映射
            byte[] txId = tx.getTxId();
            for (var output : tx.getOutputs()) {
                String address = bytesToHex(output.getScriptPubKey());
                Set<byte[]> txIds = addressToTxs.get(address);
                if (txIds != null) {
                    txIds.remove(txId);
                    if (txIds.isEmpty()) {
                        addressToTxs.remove(address);
                    }
                }
            }
            List<String> spendingKeys = tx.getSpendingUTXOKeyList();
            if (spendingKeys != null) {
                spendingKeys.forEach(spendingSet::remove);
            }
            List<String> createdKeys = tx.getPendingConfirmationOfReceipt();
            if (createdKeys != null) {
                createdKeys.forEach(processingSet::remove);
            }
        }
    }

    /**
     * 构建地址到交易的映射
     */
    private void buildAddressMapping(Transaction tx, byte[] txId) {
        for (var output : tx.getOutputs()) {
            String address = bytesToHex(output.getScriptPubKey());
            log.info("地址到交易的索引{}",address);
            addressToTxs.computeIfAbsent(address, k -> ConcurrentHashMap.newKeySet()).add(txId);
        }
    }

    /**
     * 估算交易大小（字节）
     */
    private int estimateTxSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            // 如果序列化失败，使用估算值
            int baseSize = 10; // 版本、lockTime等固定开销
            int inputSize = tx.getInputs().size() * 133; // 每个输入约133字节
            int outputSize = tx.getOutputs().size() * 28; // 每个输出约28字节
            return baseSize + inputSize + outputSize;
        }
    }

    @Override
    synchronized public void onBlockConfirmed(Transaction[] confirmedTxs,int height) {
        if (confirmedTxs == null || confirmedTxs.length == 0) {
            return;
        }
        try {
            Set<String> confirmedIds = new HashSet<>();
            Set<String> blockSpentOutpoints = new LinkedHashSet<>();

            for (Transaction tx : confirmedTxs) {
                if (tx == null || tx.isCoinbase()) {
                    continue;
                }
                String txIdStr = bytesToHex(tx.getTxId());
                confirmedIds.add(txIdStr);
                blockSpentOutpoints.addAll(getInputKeys(tx));

                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.CONFIRMED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.CONFIRMED_SAFE);
                tx.setStatus(status);

                removeTx(txIdStr);
                utxoCache.addConfirmedTx(tx, height);
            }

            for (String spentOutpoint : blockSpentOutpoints) {
                String conflictTxId = spentOutpointToTx.get(spentOutpoint);
                if (conflictTxId != null && !confirmedIds.contains(conflictTxId)) {
                    removeTxAndDescendants(conflictTxId, TransactionStatusResolver.REJECTED,
                            TransactionStatusResolver.DOUBLE_SPEND_FLAG);
                }
            }
            log.info("Block confirmed: removed {} confirmed txs and cleaned conflicts", confirmedIds.size());
        } catch (Exception e) {
            log.error("Failed to update tx pool after block confirmation", e);
        }
    }

    private void onBlockConfirmedLegacy(Transaction[] confirmedTxs,int height) {
        if (confirmedTxs == null || confirmedTxs.length == 0) {
            return;
        }
        try {
            for (Transaction tx : confirmedTxs) {
                if (tx == null || tx.isCoinbase()) {
                    continue;
                }
                String txIdStr = bytesToHex(tx.getTxId());

                // 更新交易状态为CONFIRMED（已确认）
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.CONFIRMED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.CONFIRMED_SAFE);
                tx.setStatus(status);

                // 从内存池删除
                Transaction removed = mempool.remove(txIdStr);
                if (removed != null) {
                    // 删除时间戳
                    txTimestamps.remove(txIdStr);
                    // 从打包状态删除
                    packingTxs.remove(txIdStr);
                    // 删除地址映射
                    removeAddressMapping(removed);
                    log.info("区块上链成功，从交易池删除交易：{}，状态={}",
                            txIdStr, TransactionStatusResolver.toString(status));
                }
                utxoCache.addConfirmedTx(tx,height);

                List<String> spendingUTXOKeyList = tx.getSpendingUTXOKeyList();
                //去除
                spendingUTXOKeyList.forEach(spendingSet::remove);
                List<String> pendingConfirmationOfReceipt = tx.getPendingConfirmationOfReceipt();
                pendingConfirmationOfReceipt.forEach(processingSet::remove);
            }
            log.info("区块上链成功，共删除 {} 笔交易", confirmedTxs.length);
        } catch (Exception e) {
            log.error("处理区块上链成功异常", e);
        }
    }

    @Override
    synchronized public void onBlockConfirmed(Transaction tx,int height) {
        if (tx != null) {
            onBlockConfirmed(new Transaction[]{tx}, height);
        }
    }

    private void onBlockConfirmedLegacy(Transaction tx,int height) {
        if (tx == null) {
            return;
        }
        try {
            if (tx.isCoinbase()) {
              return;
            }
            String txIdStr = bytesToHex(tx.getTxId());

            // 更新交易状态为CONFIRMED（已确认）
            long status = tx.getStatus();
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.CONFIRMED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.CONFIRMED_SAFE);
            tx.setStatus(status);

            // 从内存池删除
            Transaction removed = mempool.remove(txIdStr);
            if (removed != null) {
                // 删除时间戳
                txTimestamps.remove(txIdStr);
                // 从打包状态删除
                packingTxs.remove(txIdStr);
                // 删除地址映射
                removeAddressMapping(removed);
                log.info("区块上链成功，从交易池删除交易：{}，状态={}",
                        txIdStr, TransactionStatusResolver.toString(status));
            }
            utxoCache.addConfirmedTx(tx,height);

            List<String> spendingUTXOKeyList = tx.getSpendingUTXOKeyList();
            //去除
            spendingUTXOKeyList.forEach(spendingSet::remove);
            List<String> pendingConfirmationOfReceipt = tx.getPendingConfirmationOfReceipt();
            pendingConfirmationOfReceipt.forEach(processingSet::remove);
            log.info("区块上链成功，共删除 1 笔交易");
        } catch (Exception e) {
            log.error("处理区块上链成功异常", e);
        }
    }


    @Override
    synchronized public void onBlockFailed(Transaction[] failedTxs) {
        if (failedTxs == null || failedTxs.length == 0) {
            return;
        }

        try {
            for (Transaction tx : failedTxs) {
                if (tx == null || tx.isCoinbase()) {
                    continue;
                }

                String txIdStr = bytesToHex(tx.getTxId());

                // 回退交易状态到IN_POOL
                long status = tx.getStatus();
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
                status = TransactionStatusResolver.removeFlag(status, TransactionStatusResolver.MEMPOOL_LOCKED);
                tx.setStatus(status);

                // 从打包状态移除，交易仍留在mempool中（可重新参与打包）
                Transaction removed = packingTxs.remove(txIdStr);
                if (removed != null) {
                    log.debug("区块上链失败，移除打包标记：{}，状态={}",
                            txIdStr, TransactionStatusResolver.toString(status));
                }

                // 注意：挖矿失败时不调用 revokePendingTx，也不修改UTXO状态
                // 因为区块没有上链，UTXO状态没有变化
                // 交易仍在mempool中，下次挖矿可以继续使用
            }
            log.info("区块上链失败，已移除 {} 笔交易的打包标记", failedTxs.length);
        } catch (Exception e) {
            log.error("处理区块上链失败异常", e);
        }
    }

    /**
     * 获取交易池中已经使用的UTXO
     * @return
     */
// 优化版：使用并行流提升大交易池场景下的效率
    @Override
    public List<UTXO> getUTXOInPool() {
        try {
            return mempool.values().stream()
                    .parallel() // 并行处理
                    .filter(tx -> tx != null && !tx.isCoinbase())
                    .flatMap(tx -> tx.getInputs().stream())
                    .filter(input -> input != null)
                    .map(input -> {
                        byte[] key = UTXO.getKey(input.getTxId(), input.getIndex());
                        return utxoCache.getUTXO(key);
                    })
                    .filter(utxo -> utxo != null && utxo.isSpendable())
                    .distinct() // 去重
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取交易池中使用的UTXO失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public void broadcastTxPool() {
        // 先过滤出需要广播的交易
        List<Transaction> txList = new ArrayList<>(mempool.values()).stream()
                .filter(tx -> tx != null && !tx.isCoinbase())
                .collect(Collectors.toList());

        if (txList.isEmpty()) {
            return;
        }

        // 使用 ScheduledExecutorService 实现精准的 10ms 间隔广播
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger index = new AtomicInteger(0);

        executor.scheduleAtFixedRate(() -> {
            int i = index.getAndIncrement();
            // 所有交易广播完成后关闭线程池
            if (i >= txList.size()) {
                executor.shutdown();
                return;
            }

            Transaction tx = txList.get(i);
            try {
                routingTable.BroadcastResource(1, tx.getTxId());
            } catch (Exception e) {
                log.error("广播交易 {} 失败", bytesToHex(tx.getTxId()), e);
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        // 优雅关闭（可选）
        Runtime.getRuntime().addShutdownHook(new Thread(executor::shutdownNow));
    }

    @Override
    public Map<String, Object> getUTXOsAvailableBalance(String address) {
        // 1. 获取地址下 已确认未花费 + 币基成熟 的所有UTXO
        Map<String, Object> utxOsMap = utxoCache.getAddressAllUTXOAndTotalByStatus(address, CONFIRMED_UNSPENT | COINBASE_MATURED);
        List<UTXO> utxos = (List<UTXO>) utxOsMap.get("utxos");

        // 空值防护：避免空指针异常
        if (utxos == null) {
            utxos = new ArrayList<>();
        }

        // 2. 获取交易池中锁定的UTXO，并过滤掉这些锁定的UTXO
        List<UTXO> utxoInPool = getUTXOInPool();
        List<UTXO> availableUTXOs = filterUtxoExcludePool(utxos, utxoInPool);

        // 3. 计算可用总余额：遍历可用UTXO，累加value（UTXO的金额字段）
        long availableBalance = 0L;
        for (UTXO utxo : availableUTXOs) {
            availableBalance += utxo.getValue();
        }

        // 4. 封装返回结果：包含可用UTXO列表、可用总余额
        return Map.of(
                "availableUTXOs", availableUTXOs,   // 可用UTXO列表
                "availableBalance", availableBalance // 可用总余额（单位：聪/最小货币单位）
        );
    }

    /**
     * 删除交易的地址映射
     */
    private void removeAddressMapping(Transaction tx) {
        byte[] txId = tx.getTxId();
        for (var output : tx.getOutputs()) {
            String address = bytesToHex(output.getScriptPubKey());
            Set<byte[]> txIds = addressToTxs.get(address);
            if (txIds != null) {
                txIds.remove(txId);
                if (txIds.isEmpty()) {
                    addressToTxs.remove(address);
                }
            }
        }
    }
}
