package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import com.bit.coin.utils.SerializeUtils;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.bit.coin.structure.tx.UTXOStatusResolver.CONFIRMED_UNSPENT;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@RestController
@RequestMapping("/tx")
public class TxApi {


    @Autowired
    private TxPool txPool;

    @Autowired
    private UTXOCache utxoCache;

    @Autowired
    private BlockChainServiceImpl blockChainService;

    @Autowired
    private DataBase dataBase;

    /**
     * ===== 基础信息 =====
     * 助记词: mass danger foster party drive afraid dance bottom exhaust win wrestle language
     * ---------------------
     * 10:53:57.458 [main] INFO org.bitcoinj.crypto.MnemonicCode -- PBKDF2 took 105.3 ms
     * ===== 基准密钥对（账户0，地址0） =====
     * 派生路径: m/44'/501'/0'/0/0
     * 私钥(hex): 1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8
     * 公钥(hex): dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1
     * 地址: Fu7zmpRzRmp89CJvqJPTB6cKGVNFJPDuDZvwXNj3ZCoz
     */




    @RequestMapping("/transfer")
    public Transaction transfer(@RequestBody  TransferDTO transferDTO) throws Exception {

        // 1. 解析私钥
        byte[] privateKey = SerializeUtils.hexToBytes(transferDTO.getPrivateKeyHex());
        if (privateKey == null || privateKey.length != 32) {
            throw new IllegalArgumentException("私钥格式不正确，必须为32字节的十六进制字符串");
        }

        // 2. 解析目标地址并生成锁定脚本
        byte[] addressBytes = Base58.decode(transferDTO.getTargetAddress());
        if (addressBytes == null || addressBytes.length != 32) {
            throw new IllegalArgumentException("目标地址格式不正确");
        }
        byte[] targetScriptPubKey = Sha.applyRIPEMD160(Sha.applySHA256(addressBytes));

        // 3. 获取发送方的UTXO
        String senderAddress = "Fu7zmpRzRmp89CJvqJPTB6cKGVNFJPDuDZvwXNj3ZCoz";
        Map<String, Object> utxOs = utxoCache.getAddressAllUTXOAndTotalByStatus(senderAddress,CONFIRMED_UNSPENT);

        List<UTXO> utxos = (List<UTXO>) utxOs.get("utxos");
        long total = (long) utxOs.get("total");

        // 4. 检查余额是否足够
        if (total < transferDTO.getValue()) {
            throw new IllegalArgumentException("余额不足，需要: " + transferDTO.getValue() + " satoshi，可用: " + total + " satoshi");
        }

        // 5. 选择UTXO构建交易输入
        List<UTXO> selectedUtxos = new ArrayList<>();
        long totalInput = 0;

        for (UTXO utxo : utxos) {
            selectedUtxos.add(utxo);
            totalInput += utxo.getValue();
            if (totalInput >= transferDTO.getValue()) {
                break;
            }
        }

        if (totalInput < transferDTO.getValue()) {
            throw new IllegalArgumentException("UTXO选择失败，无法凑够足够的金额");
        }

        // 6. 创建交易对象
        Transaction tx = new Transaction();
        tx.setVersion(1);
        tx.setLockTime(0);

        // 7. 构建交易输入
        Map<String, UTXO> utxoMap = new HashMap<>();
        List<TxInput> inputs = new ArrayList<>();

        for (UTXO utxo : selectedUtxos) {
            TxInput input = new TxInput();
            input.setTxId(utxo.getTxId());
            input.setIndex(utxo.getIndex());
            // 暂时设置空的签名脚本，稍后签名
            input.setScriptSig(new byte[TxInput.SCRIPT_SIG_LENGTH]);

            inputs.add(input);

            // 构建UTXO映射用于验证 - 使用UTXO.getKey()方法
            utxoMap.put(bytesToHex(utxo.getKey()), utxo);
        }
        tx.setInputs(inputs);
        tx.setUtxoMap(utxoMap);

        // 8. 构建交易输出
        List<TxOutput> outputs = new ArrayList<>();

        // 主输出：转账给目标地址
        TxOutput mainOutput = new TxOutput();
        mainOutput.setValue(transferDTO.getValue());
        mainOutput.setScriptPubKey(targetScriptPubKey);
        outputs.add(mainOutput);

        // 找零输出：将剩余金额返回给发送方
        long fee = 1000; // 手续费 1000 satoshi
        long change = totalInput - transferDTO.getValue() - fee;

        if (change > 0) {
            TxOutput changeOutput = new TxOutput();
            changeOutput.setValue(change);
            // 解析发送方地址生成锁定脚本
            byte[] senderAddressBytes = Base58.decode(senderAddress);
            byte[] senderScriptPubKey = Sha.applyRIPEMD160(Sha.applySHA256(senderAddressBytes));
            changeOutput.setScriptPubKey(senderScriptPubKey);
            outputs.add(changeOutput);
        }

        tx.setOutputs(outputs);

        // 9. 使用私钥对所有输入进行签名
        tx.sign(privateKey, SerializeUtils.hexToBytes(transferDTO.getPublicKeyHex()));

        // 10. 计算交易ID
        byte[] txId = tx.calculateTxId();
        tx.setTxId(txId);

        // 11. 验证交易
        if (!tx.validate()) {
            throw new RuntimeException("交易验证失败");
        }

        // 12. 将交易添加到交易池
        boolean added = txPool.addTx(tx);
        if (!added) {
            throw new RuntimeException("交易添加到池失败");
        }

        String txIdHex = bytesToHex(txId);
        log.info("转账交易创建成功: txId={}, 输入金额={}, 输出金额={}, 手续费={}, 找零={}",
                txIdHex, totalInput, transferDTO.getValue() + change, fee, change);

        return tx;
    }
}
