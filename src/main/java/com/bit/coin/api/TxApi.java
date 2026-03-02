package com.bit.coin.api;

import com.bit.coin.api.dto.TransferTestDTO;
import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.cache.UTXOCache;
import com.bit.coin.database.DataBase;
import com.bit.coin.structure.Result;
import com.bit.coin.structure.script.Script;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

import static com.bit.coin.structure.tx.UTXOStatusResolver.*;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
@RestController
@RequestMapping("/tx")
public class TxApi {

    // 字节常量定义（提取为常量，便于维护）
    private static final int INPUT_FIXED_SIZE = 133;   // 单个输入固定字节数
    private static final int OUTPUT_FIXED_SIZE = 28;  // 单个输出固定字节数
    private static final int BASE_FEE = 1000;          // 基础手续费
    private static final int FEE_PER_BYTE = 10;        // 每字节手续费

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


    /**
     * 私钥(hex): 2d342afca014aafdb29b1f5c46df5d075f17211bb59867bc97a0ab0d657688e9
     * 公钥(hex): 2b6998339d42f3d6ad529a931e689cb6e8829f691c835a7df95a834f0fc3c95e
     * Solana地址: 3vTvKgqexhYRXMimkchzMoeHExXBzAyogzxbJDDvT2pu
     * Solana地址: 2b6998339d42f3d6ad529a931e689cb6e8829f691c835a7df95a834f0fc3c95e
     */


    @PostMapping("/transfer")
    public Result<Transaction> transfer(@RequestBody TransferTestDTO transferDTO) throws Exception {

        try{
            // 1. 解析私钥
            byte[] privateKey = SerializeUtils.hexToBytes(transferDTO.getPrivateKeyHex());
            if (privateKey.length != 32) {
                throw new IllegalArgumentException("私钥格式不正确，必须为32字节的十六进制字符串");
            }

            // 2. 解析目标地址并生成锁定脚本
            byte[] addressBytes = Base58.decode(transferDTO.getTargetAddress());
            if (addressBytes == null || addressBytes.length != 32) {
                throw new IllegalArgumentException("目标地址格式不正确");
            }
            byte[] targetScriptPubKey = Sha.applyRIPEMD160(Sha.applySHA256(addressBytes));

            String publicKeyHex = transferDTO.getPublicKeyHex();
            byte[] bytes = hexToBytes(publicKeyHex);
            // 3. 获取发送方的UTXO
            assert bytes != null;
            String senderAddress = Base58.encode(bytes);
            Map<String, Object> utxOs = utxoCache.getAddressAllUTXOAndTotalByStatus(senderAddress, CONFIRMED_UNSPENT | COINBASE_MATURED   );

            List<UTXO> utxos = (List<UTXO>) utxOs.get("utxos");
            //筛选掉交易池中锁定的UTXO
            List<UTXO> utxoInPool = txPool.getUTXOInPool();
            //utxos要去除utxoInPool 判断utxo的key是否相等
            utxos = filterUtxoExcludePool(utxos, utxoInPool);

            log.info("可用余额{}",utxos.size());
            long totalAvailable = (long) utxOs.get("total"); // 账户总可用余额

            // 4. 预校验：总可用余额是否足够覆盖 转账金额 + 最小预估手续费（避免白忙活）
            // 最小预估手续费：假设1个输入+1个输出的场景（最基础场景）
            long minEstimatedFee = BASE_FEE + (INPUT_FIXED_SIZE + OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
            if (totalAvailable < transferDTO.getValue() + minEstimatedFee) {
                throw new IllegalArgumentException("账户总余额不足，需要: " + (transferDTO.getValue() + minEstimatedFee) +
                        " satoshi（转账金额+最小手续费），可用: " + totalAvailable + " satoshi");
            }

            // 5. 选择UTXO构建交易输入（核心修改：考虑手续费）
            List<UTXO> selectedUtxos = new ArrayList<>();
            long totalInput = 0;
            // 目标金额 = 转账金额 + 预估手续费（先按1个输入+1个输出预估）
            long targetAmount = transferDTO.getValue() + minEstimatedFee;

            for (UTXO utxo : utxos) {
                selectedUtxos.add(utxo);
                totalInput += utxo.getValue();

                // 关键修改：判断条件改为 总输入 ≥ 转账金额 + 预估手续费
                // 每次选择新的UTXO后，重新预估手续费（因为输入数变了）
                long estimatedFee = BASE_FEE + (selectedUtxos.size() * INPUT_FIXED_SIZE + 1 * OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
                targetAmount = transferDTO.getValue() + estimatedFee;

                if (totalInput >= targetAmount) {
                    break; // 凑够 转账金额+手续费 就停止
                }
            }

            // 校验：选中的UTXO是否足够覆盖 转账金额+手续费
            long finalEstimatedFee = BASE_FEE + (selectedUtxos.size() * INPUT_FIXED_SIZE + 1 * OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
            if (totalInput < transferDTO.getValue() + finalEstimatedFee) {
                throw new IllegalArgumentException("UTXO选择失败，无法凑够足够的金额（含手续费）。需要: " +
                        (transferDTO.getValue() + finalEstimatedFee) + " satoshi，选中UTXO总金额: " + totalInput + " satoshi");
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
                input.setScriptSig(new byte[TxInput.SCRIPT_SIG_LENGTH]);

                inputs.add(input);
                utxoMap.put(bytesToHex(utxo.getKey()), utxo);
            }
            tx.setInputs(inputs);
            tx.setUtxoMap(utxoMap);

            // 8. 构建交易输出 & 计算动态手续费
            List<TxOutput> outputs = new ArrayList<>();

            // 主输出：转账给目标地址
            TxOutput mainOutput = new TxOutput();
            mainOutput.setValue(transferDTO.getValue());
            mainOutput.setScriptPubKey(targetScriptPubKey);
            outputs.add(mainOutput);

            // 计算交易总字节数和最终手续费
            int inputCount = inputs.size();
            int outputCount = outputs.size();
            long txSize = (long) inputCount * INPUT_FIXED_SIZE + outputCount * OUTPUT_FIXED_SIZE;
            long fee = BASE_FEE + (txSize * FEE_PER_BYTE);

            // 计算找零（总输入 - 转账金额 - 手续费）
            long change = totalInput - transferDTO.getValue() - fee;

            // 处理找零输出
            if (change > 0) {
                TxOutput changeOutput = new TxOutput();
                changeOutput.setValue(change);
                // 解析发送方地址生成锁定脚本
                byte[] senderAddressBytes = Base58.decode(senderAddress);
                byte[] senderScriptPubKey = Sha.applyRIPEMD160(Sha.applySHA256(senderAddressBytes));
                changeOutput.setScriptPubKey(senderScriptPubKey);
                outputs.add(changeOutput);

                // 重新计算含找零的手续费（输出数变为2）
                outputCount = outputs.size();
                txSize = (long) inputCount * INPUT_FIXED_SIZE + outputCount * OUTPUT_FIXED_SIZE;
                fee = BASE_FEE + (txSize * FEE_PER_BYTE);
                change = totalInput - transferDTO.getValue() - fee;
                changeOutput.setValue(change);

                // 最终校验：找零为负（极端情况，理论上UTXO选择阶段已规避）
                if (change < 0) {
                    throw new IllegalArgumentException("余额不足以支付手续费，需补充余额。当前总输入：" + totalInput +
                            "，转账金额：" + transferDTO.getValue() + "，手续费：" + fee);
                }
            } else if (change < 0) {
                throw new IllegalArgumentException("余额不足以支付手续费，需补充余额。当前总输入：" + totalInput +
                        "，转账金额：" + transferDTO.getValue() + "，手续费：" + fee);
            }

            tx.setOutputs(outputs);

            // 9. 签名交易
            tx.sign(privateKey, SerializeUtils.hexToBytes(transferDTO.getPublicKeyHex()));

            // 10. 计算交易ID
            byte[] txId = tx.calculateTxId();
            tx.setTxId(txId);

            // 11. 验证交易
            if (!tx.validate()) {
                throw new RuntimeException("交易验证失败");
            }

            // 12. 添加到交易池
            boolean added = txPool.addTx(tx);
            if (!added) {
                throw new RuntimeException("交易添加到池失败");
            }

            String txIdHex = bytesToHex(txId);
            log.info("转账交易创建成功: txId={}, 输入金额={}, 输出金额={}, 手续费={}, 找零={}, 交易大小={}字节",
                    txIdHex, totalInput, transferDTO.getValue() + (change > 0 ? change : 0), fee, change, txSize);

            return Result.OK("提交成功",tx);
        } catch (Exception e) {
            log.error("转账异常", e);
            return Result.error(e.getMessage());
        }
    }


    //TODO 废弃 无法兼容索引需要重新设计
    @PostMapping("/transfer101")
    public Result<Transaction> transfer101(@RequestBody TransferTestDTO transferDTO) throws Exception {

        try{
            // 1. 解析私钥
            byte[] privateKey = SerializeUtils.hexToBytes(transferDTO.getPrivateKeyHex());
            if (privateKey.length != 32) {
                throw new IllegalArgumentException("私钥格式不正确，必须为32字节的十六进制字符串");
            }

            // 2. 解析目标地址并生成锁定脚本
            byte[] addressBytes = Base58.decode(transferDTO.getTargetAddress());
            if (addressBytes == null || addressBytes.length != 32) {
                throw new IllegalArgumentException("目标地址格式不正确");
            }

            //锁定脚本
            byte[] pubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(addressBytes));

            Script standardLockingScript = new Script();
            standardLockingScript.addOpCode(Script.OP_DUP);
            standardLockingScript.addOpCode(Script.OP_HASH160);
            standardLockingScript.addData(pubKeyHash);
            standardLockingScript.addOpCode(Script.OP_EQUALVERIFY);
            standardLockingScript.addOpCode(Script.OP_CHECKSIG);



            String publicKeyHex = transferDTO.getPublicKeyHex();
            byte[] bytes = hexToBytes(publicKeyHex);
            // 3. 获取发送方的UTXO
            assert bytes != null;
            String senderAddress = Base58.encode(bytes);
            Map<String, Object> utxOs = utxoCache.getAddressAllUTXOAndTotalByStatus(senderAddress, CONFIRMED_UNSPENT  | COINBASE_MATURED);

            List<UTXO> utxos = (List<UTXO>) utxOs.get("utxos");
            log.info("可用余额{}",utxos.size());
            long totalAvailable = (long) utxOs.get("total"); // 账户总可用余额

            // 4. 预校验：总可用余额是否足够覆盖 转账金额 + 最小预估手续费（避免白忙活）
            // 最小预估手续费：假设1个输入+1个输出的场景（最基础场景）
            long minEstimatedFee = BASE_FEE + (INPUT_FIXED_SIZE + OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
            if (totalAvailable < transferDTO.getValue() + minEstimatedFee) {
                throw new IllegalArgumentException("账户总余额不足，需要: " + (transferDTO.getValue() + minEstimatedFee) +
                        " satoshi（转账金额+最小手续费），可用: " + totalAvailable + " satoshi");
            }

            // 5. 选择UTXO构建交易输入（核心修改：考虑手续费）
            List<UTXO> selectedUtxos = new ArrayList<>();
            long totalInput = 0;
            // 目标金额 = 转账金额 + 预估手续费（先按1个输入+1个输出预估）
            long targetAmount = transferDTO.getValue() + minEstimatedFee;

            for (UTXO utxo : utxos) {
                selectedUtxos.add(utxo);
                totalInput += utxo.getValue();

                // 关键修改：判断条件改为 总输入 ≥ 转账金额 + 预估手续费
                // 每次选择新的UTXO后，重新预估手续费（因为输入数变了）
                long estimatedFee = BASE_FEE + (selectedUtxos.size() * INPUT_FIXED_SIZE + 1 * OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
                targetAmount = transferDTO.getValue() + estimatedFee;

                if (totalInput >= targetAmount) {
                    break; // 凑够 转账金额+手续费 就停止
                }
            }

            // 校验：选中的UTXO是否足够覆盖 转账金额+手续费
            long finalEstimatedFee = BASE_FEE + (selectedUtxos.size() * INPUT_FIXED_SIZE + 1 * OUTPUT_FIXED_SIZE) * FEE_PER_BYTE;
            if (totalInput < transferDTO.getValue() + finalEstimatedFee) {
                throw new IllegalArgumentException("UTXO选择失败，无法凑够足够的金额（含手续费）。需要: " +
                        (transferDTO.getValue() + finalEstimatedFee) + " satoshi，选中UTXO总金额: " + totalInput + " satoshi");
            }

            // 6. 创建交易对象
            Transaction tx = new Transaction();
            tx.setVersion(101);
            tx.setLockTime(0);

            // 7. 构建交易输入
            Map<String, UTXO> utxoMap = new HashMap<>();
            List<TxInput> inputs = new ArrayList<>();

            for (UTXO utxo : selectedUtxos) {
                TxInput input = new TxInput();
                input.setTxId(utxo.getTxId());
                input.setIndex(utxo.getIndex());
                input.setScriptSig(new byte[TxInput.SCRIPT_SIG_LENGTH]);

                inputs.add(input);
                utxoMap.put(bytesToHex(utxo.getKey()), utxo);
            }
            tx.setInputs(inputs);
            tx.setUtxoMap(utxoMap);

            // 8. 构建交易输出 & 计算动态手续费
            List<TxOutput> outputs = new ArrayList<>();

            // 主输出：转账给目标地址
            TxOutput mainOutput = new TxOutput();
            mainOutput.setValue(transferDTO.getValue());
            mainOutput.setScriptPubKey(standardLockingScript.serialize());
            outputs.add(mainOutput);

            // 计算交易总字节数和最终手续费
            int inputCount = inputs.size();
            int outputCount = outputs.size();
            long txSize = (long) inputCount * INPUT_FIXED_SIZE + outputCount * OUTPUT_FIXED_SIZE;
            long fee = BASE_FEE + (txSize * FEE_PER_BYTE);

            // 计算找零（总输入 - 转账金额 - 手续费）
            long change = totalInput - transferDTO.getValue() - fee;

            // 处理找零输出
            if (change > 0) {
                TxOutput changeOutput = new TxOutput();
                changeOutput.setValue(change);
                // 解析发送方地址生成锁定脚本
                byte[] senderAddressBytes = Base58.decode(senderAddress);
                byte[] senderScriptPubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(senderAddressBytes));

                Script sendStandardLockingScript = new Script();
                sendStandardLockingScript.addOpCode(Script.OP_DUP);
                sendStandardLockingScript.addOpCode(Script.OP_HASH160);
                sendStandardLockingScript.addData(senderScriptPubKeyHash);
                sendStandardLockingScript.addOpCode(Script.OP_EQUALVERIFY);
                sendStandardLockingScript.addOpCode(Script.OP_CHECKSIG);

                changeOutput.setScriptPubKey(sendStandardLockingScript.serialize());
                outputs.add(changeOutput);

                // 重新计算含找零的手续费（输出数变为2）
                outputCount = outputs.size();
                txSize = (long) inputCount * INPUT_FIXED_SIZE + outputCount * OUTPUT_FIXED_SIZE;
                fee = BASE_FEE + (txSize * FEE_PER_BYTE);
                change = totalInput - transferDTO.getValue() - fee;
                changeOutput.setValue(change);

                // 最终校验：找零为负（极端情况，理论上UTXO选择阶段已规避）
                if (change < 0) {
                    throw new IllegalArgumentException("余额不足以支付手续费，需补充余额。当前总输入：" + totalInput +
                            "，转账金额：" + transferDTO.getValue() + "，手续费：" + fee);
                }
            } else if (change < 0) {
                throw new IllegalArgumentException("余额不足以支付手续费，需补充余额。当前总输入：" + totalInput +
                        "，转账金额：" + transferDTO.getValue() + "，手续费：" + fee);
            }

            tx.setOutputs(outputs);

            // 9. 签名交易
            tx.sign(privateKey, SerializeUtils.hexToBytes(transferDTO.getPublicKeyHex()));

            // 10. 计算交易ID
            byte[] txId = tx.calculateTxId();
            tx.setTxId(txId);

            // 11. 验证交易
            if (!tx.validate()) {
                throw new RuntimeException("交易验证失败");
            }

            // 12. 添加到交易池
            boolean added = txPool.addTx(tx);
            if (!added) {
                throw new RuntimeException("交易添加到池失败");
            }

            String txIdHex = bytesToHex(txId);
            log.info("转账交易创建成功: txId={}, 输入金额={}, 输出金额={}, 手续费={}, 找零={}, 交易大小={}字节",
                    txIdHex, totalInput, transferDTO.getValue() + (change > 0 ? change : 0), fee, change, txSize);

            return Result.OK("提交成功",tx);
        } catch (Exception e) {
            log.error("转账异常", e);
            return Result.error(e.getMessage());
        }
    }



    /**
     * 从原始UTXO列表中移除交易池锁定的UTXO（使用Stream API，简洁高效）
     * @param originalUtxos 原始UTXO列表
     * @param utxoInPool 交易池锁定的UTXO列表
     * @return 过滤后的UTXO列表
     */
    public static List<UTXO> filterUtxoExcludePool(List<UTXO> originalUtxos, List<UTXO> utxoInPool) {
        // 空值校验，避免空指针
        if (originalUtxos == null || originalUtxos.isEmpty()) {
            return new ArrayList<>();
        }
        if (utxoInPool == null || utxoInPool.isEmpty()) {
            return new ArrayList<>(originalUtxos);
        }

        // 1. 提取交易池中UTXO的key字符串，存入Set（O(1)查找效率）
        Set<String> poolUtxoKeySet = utxoInPool.stream()
                .map(UTXO::getKeyStr)  // 使用UTXO自带的getKeyStr()方法获取key字符串
                .collect(Collectors.toSet());

        // 2. 过滤原始列表，保留不在交易池中的UTXO
        return originalUtxos.stream()
                .filter(utxo -> !poolUtxoKeySet.contains(utxo.getKeyStr()))
                .collect(Collectors.toList());
    }

    /**
     * 从原始UTXO列表中移除交易池锁定的UTXO（传统循环方式，适合新手理解）
     * @param originalUtxos 原始UTXO列表
     * @param utxoInPool 交易池锁定的UTXO列表
     * @return 过滤后的UTXO列表
     */
    public static List<UTXO> filterUtxoExcludePoolByLoop(List<UTXO> originalUtxos, List<UTXO> utxoInPool) {
        // 空值校验
        List<UTXO> result = new ArrayList<>();
        if (originalUtxos == null || originalUtxos.isEmpty()) {
            return result;
        }
        if (utxoInPool == null || utxoInPool.isEmpty()) {
            result.addAll(originalUtxos);
            return result;
        }

        // 1. 构建交易池UTXO的key集合
        Set<String> poolUtxoKeySet = new HashSet<>();
        for (UTXO utxo : utxoInPool) {
            poolUtxoKeySet.add(utxo.getKeyStr());
        }

        // 2. 遍历原始列表，过滤掉交易池中的UTXO
        for (UTXO utxo : originalUtxos) {
            if (!poolUtxoKeySet.contains(utxo.getKeyStr())) {
                result.add(utxo);
            }
        }

        return result;
    }



}
