package com.bit.coin.mining;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MiningServiceImpl {

    @Autowired
    private BlockChainServiceImpl blockChainService;


    //矿工地址
    private static final String MINER_ADDRESS = "miner_address";







}
