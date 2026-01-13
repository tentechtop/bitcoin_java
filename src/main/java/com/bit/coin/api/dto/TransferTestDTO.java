package com.bit.coin.api.dto;

import lombok.Data;

@Data
public class TransferTestDTO {

    private String privateKeyHex;

    private String publicKeyHex;

    private String targetAddress;

    private long value;
}
