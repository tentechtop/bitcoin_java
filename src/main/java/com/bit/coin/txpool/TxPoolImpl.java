package com.bit.coin.txpool;

import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.UTXO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class TxPoolImpl implements TxPool{

    @Autowired
    private DataBase dataBase;

    @Override
    public boolean verifyTx(Transaction tx) {
        //获取交易所有参与交易的UTXO
        List<TxInput> inputs = tx.getInputs();
        ArrayList<UTXO> utxos = new ArrayList<>();
        for (TxInput txInput:inputs){
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            byte[] key = UTXO.getKey(txId, index);
            byte[] bytes = dataBase.get(TableEnum.TUTXO, key);
            assert bytes != null;
            UTXO deserialize = UTXO.deserialize(bytes);
            utxos.add(deserialize);
        }




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
