package com.bit.coin.api;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/txpool")
public class TxPoolApi {

    @Autowired
    private TxPool txPool;


    //获取交易池全部的交易
    @RequestMapping("/getAllTxs")
    public Transaction[] getAllTxs(){

        return null;
    }



}
