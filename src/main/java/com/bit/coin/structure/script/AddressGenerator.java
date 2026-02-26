package com.bit.coin.structure.script;

import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;


import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 地址生成器
 */
@Slf4j
public class AddressGenerator {

    private static final byte MAINNET_PUBKEY_HASH = 0x00;  // P2PKH地址前缀
    private static final byte MAINNET_SCRIPT_HASH = 0x05;  // P2SH地址前缀







}
