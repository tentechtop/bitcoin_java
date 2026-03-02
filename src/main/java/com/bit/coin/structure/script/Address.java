package com.bit.coin.structure.script;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;


/**
 * 地址抽象层
 * 统一表示不同脚本类型的地址
 */
@Slf4j
@Data
public class Address {

    private ScriptType scriptType;
    private byte[] addressBytes;  // 解析出的统一地址(20或32字节)
    private byte[] fullScript;    // 完整的scriptPubKey(用于验证)

    public Address() {
    }

    public Address(ScriptType scriptType, byte[] addressBytes, byte[] fullScript) {
        this.scriptType = scriptType;
        this.addressBytes = addressBytes;
        this.fullScript = fullScript;
    }







}
