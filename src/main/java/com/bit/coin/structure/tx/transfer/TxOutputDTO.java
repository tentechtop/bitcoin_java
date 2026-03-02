package com.bit.coin.structure.tx.transfer;

import com.bit.coin.structure.block.HexByteArraySerializer;
import lombok.Data;
import tools.jackson.databind.annotation.JsonSerialize;

@Data
public class TxOutputDTO {
    /**
     * [8字节] 金额（单位：satoshi）
     */
    private long value;

    /**
     * [20字节] 公钥哈希
     * 接收方44长度字符串解码后得到32字节公钥 - 经过Sha256 RIPEMD160 后的20字节生成的锁定脚本
     */
    private String scriptPubKey;
}
