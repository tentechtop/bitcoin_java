package com.bit.coin.blockchain;

import com.bit.coin.structure.tx.Transaction;

import java.util.List;

public interface BlockChainService {

    //分页查询地址下所有的交易 已经确认上链的交易
    public List<Transaction> getTransactionsByPage(String address,int pageSize,String lastKey);

    //区块是否主链
    public boolean isMainBlock(String hash);

}
