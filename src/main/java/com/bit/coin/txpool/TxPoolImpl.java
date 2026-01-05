package com.bit.coin.txpool;

import com.bit.coin.structure.tx.Transaction;
import org.springframework.stereotype.Service;

@Service
public class TxPoolImpl implements TxPool{


    @Override
    public boolean verifyTx(Transaction tx) {
        return false;
    }

    @Override
    public boolean addTx(Transaction tx) {
        return false;
    }

    @Override
    public long estimateTxFee(Transaction tx) {
        return 0;
    }

    @Override
    public Transaction getTx(String txHash) {
        return null;
    }

    @Override
    public Transaction getTx(byte[] txHash) {
        return null;
    }

    @Override
    public boolean replaceTx(Transaction tx) {
        return false;
    }

    @Override
    public Transaction[] getBestTxs() {
        return new Transaction[0];
    }

    @Override
    public Transaction[] getTxsByAddress(String address) {
        return new Transaction[0];
    }

    @Override
    public Transaction[] getUTXOsByAddress(String address) {
        return new Transaction[0];
    }
}
