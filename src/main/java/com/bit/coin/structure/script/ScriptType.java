package com.bit.coin.structure.script;

/**
 * 脚本类型枚举
 * 支持比特币主流脚本类型
 */
public enum ScriptType {
    P2PKH(0x00, "Pay to Public Key Hash", 20),           // 20字节
    P2SH(0x01, "Pay to Script Hash", 20),                // 20字节
    P2WPKH(0x02, "Witness v0 Key Hash", 20),             // 20字节
    P2WSH(0x03, "Witness v0 Script Hash", 32),           // 32字节
    P2TR(0x04, "Taproot", 32),                           // 32字节
    P2PK(0x05, "Pay to Public Key", -1),                 // 32字节
    UNKNOWN(0xFF, "Unknown Script", 0);

    private final int code;
    private final String description;
    private final int addressLength;  // 地址字节长度(-1表示不定长)

    ScriptType(int code, String description, int addressLength) {
        this.code = code;
        this.description = description;
        this.addressLength = addressLength;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public int getAddressLength() {
        return addressLength;
    }

    /**
     * 根据code获取脚本类型
     */
    public static ScriptType fromCode(int code) {
        for (ScriptType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * 判断是否为有效地址长度
     */
    public boolean isValidAddressLength(int length) {
        if (addressLength == -1) {
            // P2PK支持33(压缩)或65(非压缩)字节
            return length == 33 || length == 65;
        }
        return length == addressLength;
    }
}
