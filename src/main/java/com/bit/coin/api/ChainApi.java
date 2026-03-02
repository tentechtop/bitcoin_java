package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.Landmark;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.mining.MiningServiceImpl;
import com.bit.coin.net.NetServiceImpl;
import com.bit.coin.p2p.kad.RoutingTable;
import com.bit.coin.p2p.peer.Peer;
import com.bit.coin.structure.block.Block;
import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.A;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.structure.tx.UTXOStatusResolver.*;
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

    @Autowired
    private NetServiceImpl netService;

    @Autowired
    private RoutingTable routingTable;

    /**
     * 最新顶端区块
     */
    @GetMapping("/getMainLatestBlock")
    public Block getMainLatestBlock() {
        return blockChainService.getMainLatestBlock();
    }







    /**
     * 分页查询区块 开始高度 结束高度 每次不超过100个
     */
    @GetMapping("/getBlocksByPage")
    public List<Block> getBlocksByPage(int start, int end) {
        return blockChainService.getBlocksByRange(start, end);
    }

    /**
     * 分页查询交易
     */
    @GetMapping("/getTransactionsByPage")
    public List<Transaction> getTransactionsByPage(int pageNum, int pageSize) {
        return blockChainService.getTransactionsByPage(pageNum, pageSize);
    }

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
        return blockChainService.getBlockHeaderByHash(hexToBytes(hash));
    }

    /**
     * 根据Hash查询区块
     */
    @GetMapping("/getTxByHash")
    public Transaction getTxByHash(String hash) {
        Transaction tx = blockChainService.getTxAndQueryInputByHash(hexToBytes(hash));
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
    @GetMapping("/getUTXOsByAddressAndCount")
    public Map<String, Object> getUTXOsByAddressAndCount(String address) {
        return utxoCache.getUTXOsByAddressAndCount(address);
    }




    @GetMapping("/getUTXOsByAddres2")
    public Map<String, Object> getUTXOsByAddres2(String address) {
        return utxoCache.getAddressAllUTXOAndTotalByStatus(address, CONFIRMED_UNSPENT | COINBASE_MATURED | COINBASE_MATURING);
    }


    //查询地址余额
    @GetMapping("/getBalance")
    public Map<String, Object> getBalance(String address) {
        return utxoCache.getBalance(address);
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


    //开始同步
    @GetMapping("/startSync")
    public void startSync() throws InterruptedException {
        netService.startSyncBlock();
    }

    @GetMapping("/getLatestBlcok")
    public Block getLatestBlcok() {
        Block mainLatestBlock = blockChainService.getMainLatestBlock();
        return mainLatestBlock;
    }

    //获取在线节点列表
    @GetMapping("/getConnectedPeers")
    public List<Peer> getConnectedPeers() {
        return routingTable.getOnlineNodes();
    }


    //从在线节点获取最新的区块
    @GetMapping("/getLatestBlockFromOnlinePeers")
    public Block getLatestBlockFromOnlinePeers() {
        return netService.getLatestBlockFromOnlinePeers();
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

    @GetMapping("/mingOneBlockByCount")
    public List<Block> mingOneBlock(int count) throws Exception {
        ArrayList<Block> blocks = new ArrayList<>();
        for (int i = 0; i < count; i++){
            miningService.initAllResources();
            Block block = miningService.mineOneBlock();
            blocks.add(block);
        }
        return blocks;
    }




    @GetMapping("/getRoadMark")
    public List<Landmark> getRoadMark(int height) {
        List<Landmark> landmarks = blockChainService.generateSyncLandmarks(height);
        return landmarks;
    }

    @GetMapping("/getLoclRoadMark")
    public List<Landmark> getLoclRoadMark() {
        List<Landmark> landmarksLocal = blockChainService.generateSyncLandmarks(mainTipBlock.getHeight());
        return landmarksLocal;
    }


    //用这个路标和本地做对比找到共同祖先
    @PostMapping("/getCommonAncestor")
    public Landmark getCommonAncestor(@RequestBody  List<Landmark> landmarks) {
        return blockChainService.findCommonAncestorByLandmark(landmarks);
    }



    //指定广播一个区块 参数hashhex
    @GetMapping("/broadcastResource")
    public void broadcastResource(String hashHex) {
        byte[] hash = hexToBytes(hashHex);
        routingTable.BroadcastResource(0, hash);
    }


    //广播交易池中的交易
    @GetMapping("/broadcastTxPool")
    public void broadcastTxPool() {
        txPool.broadcastTxPool();
    }


    //获取全部的孤儿区块
    @GetMapping("/getOrphanBlocks")
    public List<Block> getOrphanBlocks() {
        return blockChainService.getOrphanBlocks();
    }





}
