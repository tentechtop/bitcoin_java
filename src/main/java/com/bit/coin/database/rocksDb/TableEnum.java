package com.bit.coin.database.rocksDb;

import lombok.Getter;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.ColumnFamilyOptions;

import java.util.HashMap;
import java.util.Map;


/**
 * 表枚举（集中管理所有表的元信息，作为唯一数据源）
 */
public enum TableEnum {
    ACCOUNT(
            (short) 1,
            "account",  // 列族实际存储名称
            new ColumnFamilyOptions(),  // 列族配置
            0,  //MB
            0
    ),
    // 链信息表：定义表标识、列族名称、列族配置
    CHAIN(
            (short) 2,
            "chain",  // 列族实际存储名称
            new ColumnFamilyOptions(),  // 列族配置
            0,  //MB
            0
    ),
    // 区块信息表：定义表标识、列族名称、列族配置 Hash->区块
    TBLOCK(
            (short) 3,
            "TBLOCK",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    PEER(
            (short) 4,
            "peer",  // 列族实际存储名称
            new ColumnFamilyOptions(),  // 列族配置
            0,  //MB
            0
    ),
    //主链高度到哈希的映射
    HEIGHT_TO_HASH(
            (short) 5,
            "height_to_hash",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    TUTXO(
            (short) 6,
            "utxo",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    TX_TO_BLOCK(
            (short) 7,
            "tx_to_block",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    ADDR_TO_UTXO(
            (short) 8,
            "addr_to_utxo",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    ADDR_TO_TX(
            (short) 9,
            "addr_to_tx",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    ORPHAN(
            (short) 10,
            "orphan",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(512 * 1024)  
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    TBLOCK_HEADER(
            (short) 11,
            "TBLOCK_HEADER",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    TBLOCK_BODY(
            (short) 12,
            "TBLOCK_BODY",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    HASH_TO_HEIGHT(
            (short) 13,
            "HASH_TO_HEIGHT",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    //HASH到工作总量
    HASH_TO_CHAIN_WORK(
            (short) 14,
            "HASH_TO_CHAIN_WORK",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setBlockCacheSize(1024 * 1024)
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),
    CHECKPOINT(
            (short) 15,
            "CHECKPOINT",  // 列族实际存储名称
            new ColumnFamilyOptions()  // 列族配置
                    .setTableFormatConfig(new BlockBasedTableConfig()
                            .setCacheIndexAndFilterBlocks(true)),
            0, //内存缓存 MB
            0
    ),



    TEST1(
            (short) 100,
            "height_to_hash",  // 列族实际存储名称
            new ColumnFamilyOptions(),
            0, //内存缓存 MB
            0
    ),

    TEST2(
            (short) 110,
            "height_to_hash",  // 列族实际存储名称
            new ColumnFamilyOptions(),
            0, //内存缓存 MB
            0
    ),


    ;
    @Getter private final short code;  // 表唯一标识（short类型）
    @Getter private final String columnFamilyName;  // 列族实际存储名称
    @Getter private final ColumnFamilyOptions columnFamilyOptions;  // 列族配置
    @Getter private final long cacheSize;  // 缓存大小
    @Getter private final long cacheTL;  // 缓存时长 单位秒

    // 构造方法：集中初始化表的所有元信息
    TableEnum(short code, String columnFamilyName, ColumnFamilyOptions columnFamilyOptions,long cacheSize,long cacheTL) {
        this.code = code;
        this.columnFamilyName = columnFamilyName;
        this.columnFamilyOptions = columnFamilyOptions;
        this.cacheSize = cacheSize;
        this.cacheTL = cacheTL;
    }

    // 缓存：标识 -> 枚举实例（提高查询效率）
    private static final Map<Short, TableEnum> CODE_TO_ENUM = new HashMap<>();

    static {
        for (TableEnum table : values()) {
            CODE_TO_ENUM.put(table.code, table);
        }
    }

    // 根据short标识获取枚举实例
    public static TableEnum getByCode(short code) {
        return CODE_TO_ENUM.get(code);
    }
}