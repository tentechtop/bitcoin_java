package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.mining.MiningServiceImpl;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@RestController
@RequestMapping("/chain")
public class ChainApi {

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private MiningServiceImpl miningService;

    @Autowired
    private UTXOCache utxoCache;

    @Autowired
    private TxPool txPool;

    /**
     * 根据Hash查询区块
     */
    @GetMapping("/getBlockByHash")
    public Block getBlockByHash(String hash) {
        Block block = blockChainService.getBlockByHash(hexToBytes(hash));
        return block;
    }

    @GetMapping("/getBlockByHeight")
    public Block getBlockByHeight(int height) {
        Block block = blockChainService.getBlockByHeight(height);
        return block;
    }


    @GetMapping("/getBlockHeaderByHash")
    public BlockHeader getBlockHeaderByHash(String hash) {
        BlockHeader block = blockChainService.getBlockHeaderByHash(hexToBytes(hash));
        return block;
    }

    /**
     * 根据Hash查询区块
     */
    @GetMapping("/getTxByHash")
    public Transaction getTxByHash(String hash) {
        Transaction tx = blockChainService.getTxByHash(hexToBytes(hash));
        return tx;
    }

    /**
     * 根据地址查询地址下所有的UTXO
     */
    @GetMapping("/getUTXOListByAddres")
    public Map<String,Object> getUTXOs(String address) {
        return blockChainService.getUTXOs(address);
    }


    /**
     * 根据地址查询地址下所有的UTXO
     */
    @GetMapping("/getUTXOListByAddresInCaChe")
    public List<UTXO> getUTXOListByAddresInCaChe(String address) {
        return utxoCache.getUTXOsByAddress(address);
    }

    /**
     * 查询地址下所有的UTXO
     */
    @GetMapping("/getUTXOsByAddres")
    public List<UTXO> getUTXOsByAddres(String address) {
        return utxoCache.getAddressAllUTXO(address);
    }

    /**
     * 查询地址下所有的UTXO
     */
    @GetMapping("/getAddressAllUTXO")
    public List<UTXO> getAddressAllUTXOByStatus(String address,long status) {
        return utxoCache.getAddressAllUTXOByStatus(address,status);
    }


    /**
     * 查询地址下所有的交易
     */
    @GetMapping("/getTxListByAddres")
    public List<Transaction> getTxListByAddres(String address) {
        return blockChainService.getTxListByAddres(address);
    }

    /**
     * 查询地址下所有的交易
     */
    @GetMapping("/getTxListByAddresInPool")
    public List<Transaction> getTxListByAddresInPool(String address) {
        return List.of(txPool.getTxsByAddress(address));
    }



    @GetMapping("/getUtxo")
    public UTXO getUtxo(String key) {
        byte[] bytes = hexToBytes(key);
        log.info("打印Key{}",bytes);
        return blockChainService.getUtxo(key);
    }




    /**
     * 下一个区块应有难度
     * @return
     */
    @GetMapping("/calculateNextBlockDifficulty")
    public int calculateNextBlockDifficulty() {
        return blockChainService.calculateNextBlockDifficulty();
    }


    @GetMapping("/startMining")
    public Block startMining() throws Exception {
        miningService.startMining();
        return blockChainService.getMainLatestBlock();
    }

    @GetMapping("/mingOneBlock")
    public Block mingOneBlock() throws Exception {
        miningService.initAllResources();
        Block block = miningService.mineOneBlock();
        return block;
    }





}
