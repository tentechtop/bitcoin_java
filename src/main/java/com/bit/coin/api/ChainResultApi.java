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

import java.util.ArrayList;
import java.util.List;

import static com.bit.coin.structure.tx.UTXOStatusResolver.CONFIRMED_UNSPENT;
import static com.bit.coin.structure.tx.UTXOStatusResolver.UNCONFIRMED_OUTPUT;
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
    public Result<List<UTXO>>  getAddressAllUTXOByStatus(String address) {
        ArrayList<UTXO> utxos = new ArrayList<>();
        List<UTXO> addressAllUTXOByStatus = utxoCache.getAddressAllUTXOByStatus(address, CONFIRMED_UNSPENT);
        List<UTXO> addressAllUTXOByStatus1 = utxoCache.getAddressAllUTXOByStatus(address, UNCONFIRMED_OUTPUT);
        utxos.addAll(addressAllUTXOByStatus);
        utxos.addAll(addressAllUTXOByStatus1);
        return Result.OK(utxos);
    }

    //分页查询用户的交易
    @GetMapping("/getTxListByAddres")
    public Result<PageResult<Transaction>> getTxListByAddres(String address,int pageSize,String lastKey) {
        PageResult<Transaction> transactionsByPage = blockChainService.getTransactionsByPage(address, pageSize, lastKey);
        return Result.OK(transactionsByPage);
    }


    //进行中
    //查询用户交易池中的交易
    @GetMapping("/getTxListByAddresInTxPool")
    public Result<Transaction[]> getTxListByAddresInTxPool(String address) {
        Transaction[] txsByAddress = txPool.getTxsByAddress(address);
        return Result.OK(txsByAddress);
    }



    //查询地址最近的交易
    @GetMapping("/getRecentTx")
    public Result<Transaction[]> getRecentTx(String address) {
        Transaction[] txByAddress = blockChainService.getRecentTxByAddress(address);
        return Result.OK(txByAddress);
    }


    //判断地址是否有交易
    @GetMapping("/hasTx")
    public boolean hasTx(String address) {
       return blockChainService.hasTx(address);
    }

    @PostMapping("/hasTx")
    public byte[] hasTx(@RequestBody List<String> addressList) {
        //地址到交易的索引
        return blockChainService.hasTx(addressList);
    }

    //点击进入钱包设置 实现地址间隙恢复 间隙限制”（gap limit） 默认为20 但可以设置
    //钱包从网络中扫描自己使用过的地址
    //0账户0地址是默认地址 创建钱包默认使用的就是这个 扫描资产也是必扫
    //先扫描0账户的1地址 如果有资产就保存并继续扫描知道连续20个地址都无资产就停止扫描
    //再扫描1账户的0地址 如果有资产就保存并继续扫描知道连续20个地址都无资产就停止扫描
    //再扫描2账户的0地址 如果0地址没有资产就停止扫描 并认为扫描完成了  空账户
    //
    //在创建钱包后 后台启动从网络中恢复钱包过去可能使用的地址
    //可以一次扫描1024个地址 节点返回128字节 1024位的数据 0表示无交易 1表示有交易
    //钱包能根据交易判断这笔交易对该钱包来说是支出还是收入


    //查询地址下已经确认的交易带出输入引用 分页

    //查询交易池中的交易带出输入引用







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

        byte[] bytes = Base58.decode("Fu7zmpRzRmp89CJvqJPTB6cKGVNFJPDuDZvwXNj3ZCoz");
        log.info("公钥Hash{}",bytesToHex(bytes));

        byte[] bytes1 = Sha.applyRIPEMD160(Sha.applySHA256(bytes));
        log.info("解锁脚本{}",bytesToHex(bytes1));

        //[64][1][32]
        byte[] bytes4 = hexToBytes("deb53d6b5f472d77872c97ff2ffc44f1521f763528f7a50e4c976b59f93fa94c4772f6e4bb613df2e2a504ffc63c00a5e8014cce499f4dd362fe3d67d14bbe0e01");
        log.info("签名长度{}",bytes4.length);

    }
}

