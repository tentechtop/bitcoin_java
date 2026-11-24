package com.bit.coin.database.rocksDb;

/** 此枚举仅描述列族元数据，原生选项和句柄由各数据库实例持有。 */
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

    /** 默认缓存策略：缓存是加速层，数据库仍然是唯一事实来源。 */
    private static final long DEFAULT_CACHE_TTL_MILLIS = 5 * 60 * 1_000L;

    private final short code;
    private final String columnFamilyName;
    private final long cacheSize;

    TableEnum(short code, String name) {
        this.code = code;
        this.columnFamilyName = name;
        this.cacheSize = defaultCacheSize(name);
    }

    private static long defaultCacheSize(String name) {
        return switch (name) {
            case "TBLOCK", "TBLOCK_BODY", "block_undo" -> 128L;
            case "TBLOCK_HEADER" -> 512L;
            case "utxo", "addr_to_utxo" -> 4_096L;
            case "orphan" -> 256L;
            default -> 2_048L;
        };
    }

    public short getCode() {
        return code;
    }

    public String getColumnFamilyName() {
        return columnFamilyName;
    }

    public long getCacheSize() {
        return cacheSize;
    }

    public long getCacheTL() {
        return DEFAULT_CACHE_TTL_MILLIS;
    }

    public static TableEnum getByCode(short code) {
        for (TableEnum table : values()) {
            if (table.code == code) {
                return table;
            }
        }
        return null;
    }
}
