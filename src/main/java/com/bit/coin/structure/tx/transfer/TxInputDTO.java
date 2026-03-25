package com.bit.coin.structure.tx.transfer;

import lombok.Data;

@Data
public class TxInputDTO {

    /**
     * [32字节] 交易ID
     */
    private String txId;

    /**
     * [4字节] 交易的输出索引
     */
    private int index;


    /**
     * 解锁脚本
     * 包含签名和公钥 [固定64字节签名][1字节签名类型][32公钥]
     */
    private String scriptSig;

    //序列号
    private long sequence;
}
