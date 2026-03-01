package com.bit.coin.txpool;

import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.tx.*;
import com.bit.coin.structure.tx.transfer.TransferDTO;
import com.bit.coin.utils.Sha;
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

    // 区块大小限制 1MB
    private static final long MAX_BLOCK_SIZE = 1024 * 1024;
    // 交易池最大容量
    private static final int MAX_MEMPOOL_SIZE = 10000;
    // RBF手续费增加比例阈值（至少增加25%）
    private static final double RBF_FEE_INCREASE_RATIO = 1.25;


    //花费中待确认UTXO key
    Set<String> spendingSet = ConcurrentHashMap.newKeySet();
    //到账中待确认的UTXO key
    Set<String> processingSet = ConcurrentHashMap.newKeySet();




    @Override
    synchronized public boolean verifyTx(Transaction tx) {
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
                return true;
            }

            // 3. 获取交易所有参与交易的UTXO
            List<TxInput> inputs = tx.getInputs();
            Map<String, UTXO> utxoMap = new HashMap<>();
            List<UTXO> utxoList = new ArrayList<>();

            for (TxInput txInput : inputs) {
                byte[] txId = txInput.getTxId();
                int index = txInput.getIndex();
                byte[] key = UTXO.getKey(txId, index);
                log.info("UTXOKey{}",bytesToHex(key));

                // 3.1 检查UTXO是否存在于数据库
                UTXO utxo = utxoCache.getUTXO(key);
                if (utxo == null) {
                    log.warn("交易验证失败：UTXO不存在，txId={}, index={}", bytesToHex(txId), index);
                    return false;
                }
                if (!utxo.isSpendable()){
                    log.warn("交易验证失败：UTXO不可花费，txId={}, index={}", bytesToHex(txId), index);
                    return false;
                }
                utxoMap.put(bytesToHex(key), utxo);
                utxoList.add(utxo);
            }

            // 4. 检查双花：检查UTXO是否已在交易池中被使用
            for (TxInput input : inputs) {
                byte[] key = UTXO.getKey(input.getTxId(), input.getIndex());
                for (Transaction memTx : mempool.values()) {
                    for (TxInput memInput : memTx.getInputs()) {
                        byte[] memKey = UTXO.getKey(memInput.getTxId(), memInput.getIndex());
                        if (Arrays.equals(key, memKey)) {
                            log.warn("交易验证失败：UTXO已在交易池中被使用");
                            // 标记双花嫌疑状态
                            long status = tx.getStatus();
                            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.REJECTED);
                            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.DOUBLE_SPEND_FLAG);
                            tx.setStatus(status);
                            return false;
                        }
                    }
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
            if (!verifyTx(tx)) {
                return false;
            }

            String txIdStr = bytesToHex(tx.getTxId());

            // 2. 检查交易是否已在池中
            if (mempool.containsKey(txIdStr)) {
                log.warn("交易已存在于内存池中：{}", txIdStr);
                return false;
            }

            // 3. 检查内存池容量
            if (mempool.size() >= MAX_MEMPOOL_SIZE) {
                log.warn("交易池已满，拒绝添加新交易");
                return false;
            }

            //是否已经上链
            byte[] blockIndexBytes = dataBase.get(TableEnum.TX_TO_BLOCK, tx.getTxId());
            if (blockIndexBytes != null) {
                //已经上链
                log.warn("交易已上链，拒绝添加新交易");
                return false;
            }

            // 4. 初始化交易状态为PENDING（待处理）
            long status = 0L;
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.PENDING);
            tx.setStatus(status);

            // 5. 添加到内存池
            mempool.put(txIdStr, tx);
            txTimestamps.put(txIdStr, System.currentTimeMillis());

            // 6. 缓存手续费
            long fee = calculateFee(tx);

            // 7. 根据手续费设置优先级标志
            status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.VERIFIED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SIG_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SCRIPT_CHECKED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.INPUTS_VERIFIED);
            status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.OUTPUTS_VERIFIED);

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

            // 8. 构建地址到交易的映射
            buildAddressMapping(tx, tx.getTxId());

            log.info("交易添加成功：txId={}, fee={}, status={}",
                    txIdStr, fee, TransactionStatusResolver.toString(status));

            // 注意：不在这里调用 addPendingTx 修改UTXO状态
            // UTXO状态只有在区块确认后（onBlockConfirmed）才修改
            // 这样可以避免挖矿失败时的回退逻辑

            List<String> utxoList = tx.getSpendingUTXOKeyList();
            spendingSet.addAll(utxoList);
            List<String> pendingConfirmationOfReceipt = tx.getPendingConfirmationOfReceipt();
            processingSet.addAll(pendingConfirmationOfReceipt);
            routingTable.BroadcastResource(1,tx.getTxId());
            return true;
        } catch (Exception e) {
            log.error("添加交易异常", e);
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
        // 用于记录需要回滚的操作
        List<Runnable> rollbackActions = new ArrayList<>();

        try {
            // 2. 预检查阶段：验证所有交易的有效性（原子性关键：先检查，后执行）
            for (Transaction tx : txList) {
                if (tx == null) {
                    log.warn("批量添加交易失败：包含null交易");
                    return false;
                }

                String txIdStr = bytesToHex(tx.getTxId());

                // 2.1 验证交易本身
                if (!verifyTx(tx)) {
                    log.warn("批量添加交易失败：交易验证不通过，txId={}", txIdStr);
                    return false;
                }

                // 2.2 检查交易是否已在池中
                if (mempool.containsKey(txIdStr)) {
                    log.warn("批量添加交易失败：交易已存在于内存池，txId={}", txIdStr);
                    return false;
                }

                // 2.3 预计算交易手续费和状态（避免添加时重复计算）
                long fee = calculateFee(tx);
                long status = 0L;
                // 初始化状态（和单个addTx逻辑一致）
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.PENDING);
                status = TransactionStatusResolver.setLifecycleStatus(status, TransactionStatusResolver.IN_POOL);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.VERIFIED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SIG_CHECKED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.SCRIPT_CHECKED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.INPUTS_VERIFIED);
                status = TransactionStatusResolver.addFlag(status, TransactionStatusResolver.OUTPUTS_VERIFIED);

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
                rollbackActions.add(() -> mempool.remove(txIdStr));

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
                spendingSet.addAll(utxoList);
                rollbackActions.add(() -> utxoList.forEach(spendingSet::remove));

                List<String> pendingList = tx.getPendingConfirmationOfReceipt();
                processingSet.addAll(pendingList);
                rollbackActions.add(() -> pendingList.forEach(processingSet::remove));

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
    public boolean replaceTx(Transaction tx) {
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
    public Transaction[] getBestTxs() {
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
    public Transaction[] extractTxsForBlock() {
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
    private long calculateFee(Transaction tx) {
        if (tx.isCoinbase()) {
            return 0;
        }

        long totalInput = 0;
        long totalOutput = 0;

        // 计算总输入
        for (TxInput input : tx.getInputs()) {
            byte[] key = UTXO.getKey(input.getTxId(), input.getIndex());
            UTXO utxo = utxoCache.getUTXO(key);
            if (utxo != null) {
                totalInput += utxo.getValue();
            }
        }

        // 计算总输出
        for (var output : tx.getOutputs()) {
            totalOutput += output.getValue();
        }
        long fee = totalInput - totalOutput;

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
