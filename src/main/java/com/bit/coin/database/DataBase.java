package com.bit.coin.database;



import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.rocksDb.PageResult;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;

import java.util.List;

//KV数据库操作 自带内存缓存 自定义缓存大小
public interface DataBase {
    //Table 是表约束数据类型
    // 数据库操作 增删改查 批量增删改查
    // 迭代器
    // 分页  自动提交

    /**
     * 创建数据库
     * @param config
     * @return
     */
    boolean createDatabase(SystemConfig config);

    /**
     * 关闭数据库
     * @return
     */
    boolean closeDatabase();

    /**
     * 判断是否存在
     * @param table
     * @param key
     * @return
     */
    boolean isExist(TableEnum table, byte[] key);

    /**
     * 插入一条数据
     * @param table
     * @param key
     * @param value
     */
    void insert(TableEnum table,byte[] key, byte[] value);

    /**
     * 输出一条数据
     * @param table
     * @param key
     */
    void delete(TableEnum table,byte[] key);

    /**
     * 修改一条数据
     * @param table
     * @param key
     * @param value
     */
    void update(TableEnum table,byte[] key, byte[] value);

    /**
     * 获取一条数据
     * @param table
     * @param key
     * @return
     */
    byte[] get(TableEnum table,byte[] key);

    /**
     * 获取一条数据
     * @param table
     * @param key
     * @return
     */
    byte[] get(TableEnum table,int key);

    /**
     * 获取一条数据
     * @param table
     * @param key
     * @return
     */
    byte[] get(TableEnum table,long key);


    /**
     * 数据数量
     * @param table
     * @return
     */
    int count(TableEnum table);

    //在一个事务内完成 自动提交
    void batchInsert(TableEnum table,byte[][] keys, byte[][] values);
    void batchDelete(TableEnum table,byte[][] keys);
    void batchUpdate(TableEnum table,byte[][] keys, byte[][] values);
    byte[][] batchGet(TableEnum table,byte[][] keys);
    void close();
    void compact(byte[] start, byte[] limit);

    /**
     * 泛型分页查询方法
     * @param table 表名
     * @param pageSize 每页条数
     * @param lastKey 上一页最后一个键（用于分页定位，首次查询传 null）
     * @param <T> 分页数据的类型（如 byte[]、UTXO、Block 等）
     * @return 分页结果（包含当前页数据、最后一个键、是否为最后一页）
     */
    <T> PageResult<T> page(TableEnum table, int pageSize, byte[] lastKey);
    <T> PageResult<T> pageByPrefix(TableEnum table,byte[] prefix, int pageSize, byte[] lastKey);

    <T> PageResult<T> pageKey(TableEnum table, int pageSize, byte[] lastKey);
    <T> PageResult<T> pageKeyByPrefix(TableEnum table,byte[] prefix, int pageSize, byte[] lastKey);
    <T> PageResult<T> pageKeyByPrefixReverse(TableEnum table, byte[] prefix, int pageSize, byte[] lastKey);
    boolean existsByPrefix(TableEnum table, byte[] prefix);
    /**
     * 事务完成
     */
    boolean dataTransaction(List<RocksDb.DbOperation> operations);


    /**
     * 按键范围查询数据（[startKey, endKey)）
     * @param table 表名
     * @param startKey 起始键（包含，null 表示最小键）
     * @param endKey 结束键（不包含，null 表示最大键）
     * @param <T> 数据类型
     * @return 范围内的所有数据（键值对列表）
     */
    <T> List<KeyValue<T>> rangeQuery(TableEnum table, byte[] startKey, byte[] endKey);

    /**
     * 按键范围查询并限制条数（避免一次性返回过多数据）
     * @param table 表名
     * @param startKey 起始键
     * @param endKey 结束键
     * @param limit 最大返回条数
     * @param <T> 数据类型
     * @return 范围内的有限数据
     */
    <T> List<KeyValue<T>> rangeQueryWithLimit(TableEnum table, byte[] startKey, byte[] endKey, int limit);

    // 辅助类：封装键值对（用于范围查询返回结果）
    class KeyValue<T> {
        private byte[] key;
        private T value;
        // 构造器、getter、setter
        public byte[] getKey() { return key; }
        public void setKey(byte[] key) { this.key = key; }
        public T getValue() { return value; }
        public void setValue(T value) { this.value = value; }
    }

    /**
     * 清理指定表的本地缓存（如Caffeine缓存）
     * @param table 表名（null 表示清理所有表缓存）
     */
    void clearCache(TableEnum table);

    /**
     * 设置表的缓存策略（如过期时间、最大容量）
     * @param table 表名
     * @param ttl 缓存存活时间（毫秒，0 表示永不过期）
     * @param maxSize 最大缓存条数
     */
    void setCachePolicy(TableEnum table, long ttl, int maxSize);

    /**
     * 强制刷新缓存（从数据库同步最新数据到缓存）
     * @param table 表名
     * @param key 键（null 表示刷新表内所有缓存键）
     */
    void refreshCache(TableEnum table, byte[] key);


    /**
     * 开启事务（手动提交模式）
     * @return 事务ID（用于标识当前事务）
     */
    String beginTransaction();

    /**
     * 提交事务（通过事务ID指定）
     * @param transactionId 事务ID（beginTransaction 返回的值）
     * @return 是否提交成功
     */
    boolean commitTransaction(String transactionId);

    /**
     * 回滚事务（放弃未提交的操作）
     * @param transactionId 事务ID
     * @return 是否回滚成功
     */
    boolean rollbackTransaction(String transactionId);

    /**
     * 添加操作到指定事务（替代原 dataTransaction 的批量操作）
     * @param transactionId 事务ID
     * @param operation 单个操作（插入/删除/更新）
     */
    void addToTransaction(String transactionId, RocksDb.DbOperation operation);


    /**
     * 获取数据库所有表名（列族名）
     * @return 表名列表
     */
    List<String> listAllTables();

    /**
     * 检查数据库健康状态（如文件完整性、是否可读写）
     * @return 健康状态（true 表示正常）
     */
    boolean checkHealth();


    /**
     * 迭代器遍历（支持自定义处理逻辑，避免一次性加载所有数据到内存）
     * @param table 表名
     * @param handler 迭代器处理器（处理每条键值对）
     */
    void iterate(TableEnum table, KeyValueHandler handler);

    void iterateByPrefix(TableEnum table, byte[] prefix, KeyValueHandler handler);


    /**
     * 批量删除指定范围的键（比逐条删除更高效）
     * @param table 表名
     * @param startKey 起始键（包含）
     * @param endKey 结束键（不包含）
     */
    void batchDeleteRange(TableEnum table, byte[] startKey, byte[] endKey);

    /**
     * 开启数据库 WAL（Write-Ahead Log）日志（提升崩溃恢复能力）
     * @param enable 是否开启
     */
    void enableWAL(boolean enable);
}
