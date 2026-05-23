package com.bit.coin.api;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.transfer.TransferDTO;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.txpool.TxPoolStatusSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/txpool")
public class TxPoolApi {

    @Autowired
    private TxPool txPool;


    //获取交易池全部的交易
    @GetMapping("/getAllTxs")
    public Transaction[] getAllTxs(){

        return txPool.getAllTxs();
    }

    @GetMapping("/status")
    public TxPoolStatusSnapshot status() {
        return txPool.getStatusSnapshot();
    }

    @PostMapping("/replace")
    public boolean replace(@RequestBody TransferDTO transferDTO) {
        Transaction tx = transferDTO.toTransaction();
        tx.setTxId(tx.calculateTxId());
        return txPool.replaceTx(tx);
    }



}
