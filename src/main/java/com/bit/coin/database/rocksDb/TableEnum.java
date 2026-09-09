package com.bit.coin.database.rocksDb;

/** Column-family metadata only; native options and handles belong to each database instance. */
public enum TableEnum {
    BLOCK_UNDO((short) 16, "block_undo"),
    ACCOUNT((short) 1, "account"),
    CHAIN((short) 2, "chain"),
    TBLOCK((short) 3, "TBLOCK"),
    PEER((short) 4, "peer"),
    HEIGHT_TO_HASH((short) 5, "height_to_hash"),
    TUTXO((short) 6, "utxo"),
    TX_TO_BLOCK((short) 7, "tx_to_block"),
    ADDR_TO_UTXO((short) 8, "addr_to_utxo"),
    ADDR_TO_TX((short) 9, "addr_to_tx"),
    ORPHAN((short) 10, "orphan"),
    TBLOCK_HEADER((short) 11, "TBLOCK_HEADER"),
    TBLOCK_BODY((short) 12, "TBLOCK_BODY"),
    HASH_TO_HEIGHT((short) 13, "HASH_TO_HEIGHT"),
    HASH_TO_CHAIN_WORK((short) 14, "HASH_TO_CHAIN_WORK"),
    CHECKPOINT((short) 15, "CHECKPOINT"),
    TEST1((short) 100, "height_to_hash"),
    TEST2((short) 110, "height_to_hash");
    private final short code;
    private final String columnFamilyName;
    TableEnum(short code, String name) { this.code = code; this.columnFamilyName = name; }
    public short getCode() { return code; }
    public String getColumnFamilyName() { return columnFamilyName; }
    public long getCacheSize() { return 0; }
    public long getCacheTL() { return 0; }
    public static TableEnum getByCode(short code) {
        for (TableEnum table : values()) if (table.code == code) return table;
        return null;
    }
}
