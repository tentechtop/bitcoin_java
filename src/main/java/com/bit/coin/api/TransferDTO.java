package com.bit.coin.api;

import lombok.Data;

@Data
public class TransferDTO {

    private String privateKeyHex;

    private String publicKeyHex;

    private String targetAddress;

    private long value;
}
