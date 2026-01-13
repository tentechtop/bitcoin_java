package com.bit.coin.api;

import com.bit.coin.structure.tx.transfer.TransferDTO;
import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.rocksDb.PageResult;
import com.bit.coin.mining.MiningServiceImpl;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

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
    public Result<PageResult<Transaction>> getTxListByAddres(String address,int pageSize,String lastKey) {
        PageResult<Transaction> transactionsByPage = blockChainService.getTransactionsByPage(address, pageSize, lastKey);
        return Result.OK(transactionsByPage);
    }

    //区块是否主链区块
    @GetMapping("/isMainChain")
    public boolean isMainChain(String hash) {
        return blockChainService.isMainBlock(hash);
    }


    //构建一笔交易给到区块链
    @PostMapping("/transfer")
    public Result<String> transfer(@RequestBody TransferDTO transferDTO) throws Exception {
        return txPool.addTx(transferDTO);
    }



    //main方法
/*    public static void main(String[] args) throws Exception {
        byte[] bytes = Base58.decode("J5JGY238f6TGBuscTBs5AQjDu6Jpau1mUaFYq89ty3GR");
        byte[] bytes1 = Sha.applyRIPEMD160(Sha.applySHA256(bytes));
        log.info("解锁脚本{}",bytesToHex(bytes1));
    }*/


    public static void main(String[] args) throws Exception {
        byte[] bytes2 = Base58.decode("J5JGY238f6TGBuscTBs5AQjDu6Jpau1mUaFYq89ty3GR");
        log.info("公钥Hash{}",bytesToHex(bytes2));
        byte[] bytes3 = Sha.applyRIPEMD160(Sha.applySHA256(bytes2));
        log.info("解锁脚本{}",bytesToHex(bytes3));

        byte[] bytes = Base58.decode("4Fu7xjEeBsFMyv19AxinTaBBBiUZzSev7UQeGQgPAxfU");
        log.info("公钥Hash{}",bytesToHex(bytes));

        byte[] bytes1 = Sha.applyRIPEMD160(Sha.applySHA256(bytes));
        log.info("解锁脚本{}",bytesToHex(bytes1));

        //[64][1][32]
        byte[] bytes4 = hexToBytes("deb53d6b5f472d77872c97ff2ffc44f1521f763528f7a50e4c976b59f93fa94c4772f6e4bb613df2e2a504ffc63c00a5e8014cce499f4dd362fe3d67d14bbe0e01");
        log.info("签名长度{}",bytes4.length);

    }
}

