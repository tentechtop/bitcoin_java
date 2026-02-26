package com.bit.coin.structure.tx.transfer;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import lombok.Data;
import org.checkerframework.checker.units.qual.A;

import java.util.ArrayList;
import java.util.List;

import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Data
public class TransferDTO {

    /**
     * 版本
     */
    private int version;

    /**
     * 锁定时间
     */
    private long lockTime;

    /**
     * 交易输入
     */
    private List<TxInputDTO> inputs = new ArrayList<>();

    /**
     * 交易输出
     */
    private List<TxOutputDTO> outputs = new ArrayList<>();

    //转化为Transaction
    public Transaction toTransaction(){
        Transaction transaction = new Transaction();
        transaction.setVersion(version);
        transaction.setLockTime(lockTime);
        //将inputs 转 List<TxInput>
        ArrayList<TxInput> txInputs = new ArrayList<>();
        for (TxInputDTO inputDTO : inputs) {
            TxInput input = new TxInput();
            input.setTxId(hexToBytes(inputDTO.getTxId()));
            input.setIndex(inputDTO.getIndex());
            input.setScriptSig(hexToBytes(inputDTO.getScriptSig()));
            //序列号
            input.setSequence(inputDTO.getSequence());
            txInputs.add(input);
        }
        transaction.setInputs(txInputs);
        //将outputs 转 List<TxOutput>
        ArrayList<TxOutput> txOutputs = new ArrayList<>();
        for (TxOutputDTO outputDTO : outputs) {
            TxOutput output = new TxOutput();
            output.setValue(outputDTO.getValue());
            output.setScriptPubKey(hexToBytes(outputDTO.getScriptPubKey()));
            txOutputs.add(output);
        }
        transaction.setOutputs(txOutputs);
        return transaction;
    }

}
