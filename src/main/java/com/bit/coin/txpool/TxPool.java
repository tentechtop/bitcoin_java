package com.bit.coin.txpool;

import com.bit.coin.structure.tx.Transaction;

public interface TxPool {

    //验证交易
    boolean verifyTx(Transaction tx);

    //验证并加入到内存池
    boolean addTx(Transaction tx);

    //估算交易手续费
    long estimateTxFee(Transaction tx);

    //查询内存池中的交易
    Transaction getTx(String txHash);

    Transaction getTx(byte[] txHash);

    //RBF 通过手续费替换交易
    boolean replaceTx(Transaction tx);

    //获取最优质的交易列表 不超过1M
    Transaction[] getBestTxs();

    //根据地址查询交易池中的交易
    Transaction[] getTxsByAddress(String address);

    //根据地址查询交易池中待确认的UTXO
    Transaction[] getUTXOsByAddress(String address);



}
