package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.mining.MiningServiceImpl;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/bitcoin/chain")
public class ChainResultApi {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private MiningServiceImpl miningService;

    @Autowired
    private UTXOCache utxoCache;

    @Autowired
    private TxPool txPool;

    /**
     * 查询地址下所有的UTXO
     */
    @GetMapping("/getAddressAllUTXO")
    public Result<List<UTXO>>  getAddressAllUTXOByStatus(String address, long status) {
        List<UTXO> addressAllUTXOByStatus = utxoCache.getAddressAllUTXOByStatus(address, status);
        return Result.OK(addressAllUTXOByStatus);
    }

    //分页查询用户的交易
    @GetMapping("/getTxListByAddres")
    public Result<List<Transaction>> getTxListByAddres(String address,int pageSize,String lastKey) {
        List<Transaction> transactionsByPage = blockChainService.getTransactionsByPage(address, pageSize,lastKey);
        return Result.OK(transactionsByPage);
    }

    //区块是否主链区块
    @GetMapping("/isMainChain")
    public boolean isMainChain(String hash) {
        return blockChainService.isMainBlock(hash);
    }


}

