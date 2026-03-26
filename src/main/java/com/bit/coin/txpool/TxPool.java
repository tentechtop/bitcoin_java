package com.bit.coin.txpool;

import com.bit.coin.structure.Result;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.structure.tx.transfer.TransferDTO;

import java.util.List;
import java.util.Map;

public interface TxPool {

    //提交一笔交易






    //验证交易
    boolean verifyTx(Transaction tx);

    //验证并加入到内存池
    boolean addTx(Transaction tx);

    boolean addTx(List<Transaction> tx);


    Result<String> addTx(TransferDTO tx);

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

    // 从交易池提取交易到区块（将交易移入打包状态，返回可打包的交易数组）
    Transaction[] extractTxsForBlock();

    // 区块上链成功，从交易池删除已确认的交易
    void onBlockConfirmed(Transaction[] confirmedTxs,int height);

    void onBlockConfirmed(Transaction confirmedTx,int height);


    // 区块上链失败，回退交易到交易池（将交易从打包状态释放回可用状态）
    void onBlockFailed(Transaction[] failedTxs);


    //交易池中的已经使用的UTXO
    List<UTXO> getUTXOInPool();


    //广播交易池中的交易
    void broadcastTxPool();


    Map<String, Object> getUTXOsAvailableBalance(String address);
}
