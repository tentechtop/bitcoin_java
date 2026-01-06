package com.bit.coin.txpool;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TxPoolImpl implements TxPool{

    @Autowired
    private DataBase dataBase;

    // 内存池：hex txId -> Transaction
    private final Map<String, Transaction> mempool = new ConcurrentHashMap<>();
    // 交易时间戳：hex txId -> timestamp (用于RBF排序)
    private final Map<String, Long> txTimestamps = new ConcurrentHashMap<>();
    // 地址到交易的映射：address -> Set<byte[] txId>
    private final Map<String, Set<byte[]>> addressToTxs = new ConcurrentHashMap<>();

    // 区块大小限制 1MB
    private static final long MAX_BLOCK_SIZE = 1024 * 1024;
    // 交易池最大容量
    private static final int MAX_MEMPOOL_SIZE = 10000;
    // RBF手续费增加比例阈值（至少增加25%）
    private static final double RBF_FEE_INCREASE_RATIO = 1.25;

    @Override
    public boolean verifyTx(Transaction tx) {
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
            Map<byte[], UTXO> utxoMap = new HashMap<>();
            List<UTXO> utxoList = new ArrayList<>();

            for (TxInput txInput : inputs) {
                byte[] txId = txInput.getTxId();
                int index = txInput.getIndex();
                byte[] key = UTXO.getKey(txId, index);

                // 3.1 检查UTXO是否存在于数据库
                byte[] bytes = dataBase.get(TableEnum.TUTXO, key);
                if (bytes == null) {
                    log.warn("交易验证失败：UTXO不存在，txId={}, index={}", SerializeUtils.bytesToHex(txId), index);
                    return false;
                }

                UTXO utxo = UTXO.deserialize(bytes);
                utxoMap.put(key, utxo);
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
                            return false;
                        }
                    }
                }
            }

            // 5. 设置UTXO映射并验证交易
            tx.setUtxoMap(utxoMap);
            if (!tx.validate()) {
                log.warn("交易验证失败：validate()返回false");
                return false;
            }

            // 6. 验证交易ID
            byte[] calculatedTxId = tx.calculateTxId();
            if (!Arrays.equals(tx.getTxId(), calculatedTxId)) {
                log.warn("交易验证失败：交易ID不匹配");
                return false;
            }

            log.info("交易验证成功：txId={}", SerializeUtils.bytesToHex(tx.getTxId()));
            return true;

        } catch (Exception e) {
            log.error("交易验证异常", e);
            return false;
        }
    }

    @Override
    public boolean addTx(Transaction tx) {
        if (tx == null) {
            log.warn("添加交易失败：交易为null");
            return false;
        }

        try {
            // 1. 验证交易
            if (!verifyTx(tx)) {
                return false;
            }

            String txIdStr = SerializeUtils.bytesToHex(tx.getTxId());

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

            // 4. 添加到内存池
            mempool.put(txIdStr, tx);
            txTimestamps.put(txIdStr, System.currentTimeMillis());

            // 缓存手续费
            long fee = calculateFee(tx);
            tx.setFee(fee);

            // 5. 构建地址到交易的映射
            buildAddressMapping(tx, tx.getTxId());

            log.info("交易添加成功：txId={}, fee={}", txIdStr, fee);
            return true;

        } catch (Exception e) {
            log.error("添加交易异常", e);
            return false;
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
        String txIdStr = SerializeUtils.bytesToHex(txHash);
        return mempool.get(txIdStr);
    }

    @Override
    public boolean replaceTx(Transaction tx) {
        if (tx == null || tx.isCoinbase()) {
            log.warn("替换交易失败：交易为null或为Coinbase交易");
            return false;
        }

        try {
            String txIdStr = SerializeUtils.bytesToHex(tx.getTxId());
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

            // 5. 移除原交易
            removeTx(originalTxIdStr);

            // 6. 添加新交易
            mempool.put(txIdStr, tx);
            txTimestamps.put(txIdStr, System.currentTimeMillis());
            tx.setFee(newFee);

            // 重建地址映射
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
    public Transaction[] getTxsByAddress(String address) {
        if (address == null) {
            return new Transaction[0];
        }
        Set<byte[]> txIds = addressToTxs.get(address);
        if (txIds == null || txIds.isEmpty()) {
            return new Transaction[0];
        }
        return txIds.stream()
                .map(txId -> {
                    String txIdStr = SerializeUtils.bytesToHex(txId);
                    return mempool.get(txIdStr);
                })
                .filter(Objects::nonNull)
                .toArray(Transaction[]::new);
    }

    @Override
    public Transaction[] getUTXOsByAddress(String address) {
        // 返回地址相关的待确认UTXO（即交易池中输出到该地址的交易）
        return getTxsByAddress(address);
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
            byte[] bytes = dataBase.get(TableEnum.TUTXO, key);
            if (bytes != null) {
                UTXO utxo = UTXO.deserialize(bytes);
                totalInput += utxo.getValue();
            }
        }

        // 计算总输出
        for (var output : tx.getOutputs()) {
            totalOutput += output.getValue();
        }

        return totalInput - totalOutput;
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
                .map(i -> SerializeUtils.bytesToHex(i.getTxId()) + ":" + i.getIndex())
                .collect(Collectors.toSet());

        Set<String> inputs2 = tx2.getInputs().stream()
                .map(i -> SerializeUtils.bytesToHex(i.getTxId()) + ":" + i.getIndex())
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
                String address = SerializeUtils.bytesToHex(output.getScriptPubKey());
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
            String address = SerializeUtils.bytesToHex(output.getScriptPubKey());
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
}
