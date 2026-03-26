package com.bit.coin.cache;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * UTXO 缓存接口
 * 1. 查询 UTXO 优先从缓存获取，缓存不存在再查数据库
 * 2. 交易验证通过后，将消耗的 UTXO 和产生的 UTXO 记录在缓存中
 * 3. 区块上链确认后，更新缓存到数据库
 */
public interface UTXOCache {

    /**
     * 从缓存或数据库获取 UTXO
     * @param txId 交易ID
     * @param index 输出索引
     * @return UTXO 对象，如果不存在返回 null
     */
    UTXO getUTXO(byte[] txId, int index);

    UTXO getUTXOByStringKey(String key);


    /**
     * 从缓存或数据库获取 UTXO（使用预计算的 key）
     * @param key UTXO 的 key [txId][index]
     * @return UTXO 对象，如果不存在返回 null
     */
    UTXO getUTXO(byte[] key);

    void addUTXO(UTXO utxo);


    /**
     * 添加待确认的交易 已经通过交易池的验证
     */
    void addPendingTx(Transaction transaction);

    /**
     * 添加后撤销这笔交易 从交易池删除
     */
    void revokePendingTx(Transaction transaction);

    /**
     * 添加已经确认的交易 已经上链确认
     */
    void addConfirmedTx(Transaction transaction,int height);


    List<UTXO> getUTXOsByAddress(String address);

    List<UTXO> getAddressAllUTXO(String address);


    List<UTXO> getAddressAllUTXOByStatus(String address, long status);

    Map<String, Object> getAddressAllUTXOAndTotalByStatus(String address, long status);


    Map<String, Object> getBalance(String address);

    Map<String, Object> getUTXOsByAddressAndCount(String address);


    Map<String, Object> getUTXOsByAddressAndCountAndUTXO(String address);
}
