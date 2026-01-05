package com.bit.coin.database.rocksDb;


import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.KeyValueHandler;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;



@Slf4j
@Component
@Data
public class RocksDb implements DataBase {

    /**
     * 增加布隆过滤器 快速准确判断 一个Key不存在  减少磁盘IO
     */

    /**
     * 高写入吞吐量（区块、交易等高频写入）；
     * 不可篡改性（数据一旦写入不轻易删除，仅追加）；
     * 范围查询高效（如按区块高度、时间范围查询）；
     * 数据一致性（尤其在节点同步场景）；
     * 持久化可靠性（避免数据丢失）。
     */

    // 类中添加缓存实例（全局唯一）加速高频访问的完整业务对象查询  按照表隔离
    private final Map<TableEnum, Cache<byte[], byte[]>> tableCaches = new ConcurrentHashMap<>();


    private RocksDB db;
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private String dbPath;

    @Override
    public boolean createDatabase(SystemConfig config) {
        String path = config.getPath();
        if (path == null) {
            return false;
        }
        dbPath = path;

        // 初始化时为每个表创建缓存（在createDatabase中）
        for (TableEnum table : TableEnum.values()) {
            Cache<byte[], byte[]> cache = Caffeine.newBuilder()
                    .maximumSize(getCacheMaxSize(table)) // 按表配置大小
                    .expireAfterWrite(getCacheTtl(table), TimeUnit.MINUTES) // 按表配置过期时间
                    .build();
            tableCaches.put(table, cache);
        }

        try {
            File dbDir = new File(dbPath);
            if (!dbDir.exists() && !dbDir.mkdirs()) {
                log.error("创建数据库目录失败: {}", dbPath);
                return false;
            }

            // 初始化列族描述符列表
            List<ColumnFamilyDescriptor> cfDescriptors = new ArrayList<>();
            List<ColumnFamilyHandle> cfHandles = new ArrayList<>();

            // 1. 添加默认列族（索引0）
            cfDescriptors.add(new ColumnFamilyDescriptor(
                    RocksDB.DEFAULT_COLUMN_FAMILY,
                    new ColumnFamilyOptions()
            ));

            // 2. 获取自定义列族描述符（用LinkedHashMap保证顺序）
            Map<TableEnum, ColumnFamilyDescriptor> customDescriptors = RTable.getColumnFamilyDescriptors();
            List<TableEnum> tableEnums = new ArrayList<>(customDescriptors.keySet());

            // 关键：将自定义列族描述符添加到cfDescriptors（否则不会创建）
            for (TableEnum table : tableEnums) {
                cfDescriptors.add(customDescriptors.get(table));
            }

            // 3. 打开数据库（此时cfDescriptors包含：默认列族 + 所有自定义列族）
            DBOptions options = new DBOptions()
                    .setCreateIfMissing(true)
                    .setCreateMissingColumnFamilies(true)
                    .setInfoLogLevel(InfoLogLevel.ERROR_LEVEL);

            db = RocksDB.open(options, dbPath, cfDescriptors, cfHandles);

            // 4. 绑定列族句柄（cfHandles顺序与cfDescriptors严格一致）
            // 校验句柄数量是否匹配（避免索引越界）
            if (cfHandles.size() != cfDescriptors.size()) {
                throw new RuntimeException("列族句柄数量与描述符不匹配，初始化失败");
            }

            // 自定义列族从索引1开始绑定
            for (int i = 0; i < tableEnums.size(); i++) {
                int handleIndex = i + 1; // 索引0是默认列族
                if (handleIndex >= cfHandles.size()) {
                    throw new RuntimeException("列族句柄索引越界，表：" + tableEnums.get(i));
                }
                TableEnum table = tableEnums.get(i);
                ColumnFamilyHandle handle = cfHandles.get(handleIndex);
                RTable.setColumnFamilyHandle(table, handle);
                log.debug("绑定表[{}]的列族句柄，索引: {}", table, handleIndex);
            }

            // 注册关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
            log.info("RocksDB创建成功，路径: {}，列族总数: {}", dbPath, cfDescriptors.size());
            return true;
        } catch (RocksDBException e) {
            log.error("创建RocksDB失败", e);
            return false;
        }
    }

    @Override
    public boolean closeDatabase() {
        try {
            close();
            log.info("数据库已关闭");
            return true;
        } catch (Exception e) {
            log.error("关闭数据库失败", e);
            return false;
        }
    }

    @Override
    public boolean isExist(TableEnum table, byte[] key) {
        rwLock.readLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) return false;
            return db.get(cfHandle, key) != null;
        } catch (RocksDBException e) {
            log.error("检查键是否存在失败, table={}", table, e);
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }


    @Override
    public void insert(TableEnum table, byte[] key, byte[] value) {
        rwLock.writeLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                throw new IllegalArgumentException("表不存在: " + table);
            }
            db.put(cfHandle, key, value);
        } catch (RocksDBException e) {
            log.error("插入数据失败, table={}", table, e);
            throw new RuntimeException("插入数据失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void delete(TableEnum table, byte[] key) {
        rwLock.writeLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                throw new IllegalArgumentException("表不存在: " + table);
            }
            db.delete(cfHandle, key);
        } catch (RocksDBException e) {
            log.error("删除数据失败, table={}", table, e);
            throw new RuntimeException("删除数据失败", e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void update(TableEnum table, byte[] key, byte[] value) {
        // RocksDB的更新就是覆盖写入
        insert(table, key, value);
    }


    @Override
    public byte[] get(TableEnum table, byte[] key) {
        rwLock.readLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) return null;
            return db.get(cfHandle, key);
        } catch (RocksDBException e) {
            log.error("获取数据失败, table={}", table, e);
            throw new RuntimeException("获取数据失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public byte[] get(TableEnum table, int key) {
        //int转字节
        byte[] bytes = ByteBuffer.allocate(4).putInt(key).array();
        return get(table, bytes);
    }

    @Override
    public byte[] get(TableEnum table, long key) {
        //long转字节
        byte[] bytes = ByteBuffer.allocate(8).putLong(key).array();
        return get(table, bytes);
    }

    @Override
    public int count(TableEnum table) {
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) return 0;
            iterator = db.newIterator(cfHandle);
            iterator.seekToFirst();
            int count = 0;
            while (iterator.isValid()) {
                count++;
                iterator.next();
            }
            return count;
        } finally {
            if (iterator != null) iterator.close();
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void batchInsert(TableEnum table, byte[][] keys, byte[][] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("键值数组长度不匹配");
        }

        rwLock.writeLock().lock();
        WriteBatch writeBatch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                throw new IllegalArgumentException("表不存在: " + table);
            }

            for (int i = 0; i < keys.length; i++) {
                writeBatch.put(cfHandle, keys[i], values[i]);
            }
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量插入失败, table={}", table, e);
            throw new RuntimeException("批量插入失败", e);
        } finally {
            writeBatch.close();
            writeOptions.close();
            rwLock.writeLock().unlock();
        }
    }


    @Override
    public void batchDelete(TableEnum table, byte[][] keys) {
        rwLock.writeLock().lock();
        WriteBatch writeBatch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                throw new IllegalArgumentException("表不存在: " + table);
            }

            for (byte[] key : keys) {
                writeBatch.delete(cfHandle, key);
            }
            db.write(writeOptions, writeBatch);
        } catch (RocksDBException e) {
            log.error("批量删除失败, table={}", table, e);
            throw new RuntimeException("批量删除失败", e);
        } finally {
            writeBatch.close();
            writeOptions.close();
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void batchUpdate(TableEnum table, byte[][] keys, byte[][] values) {
        // 批量更新等同于批量插入（覆盖写入）
        batchInsert(table, keys, values);
    }


    @Override
    public byte[][] batchGet(TableEnum table, byte[][] keys) {
        rwLock.readLock().lock();
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                return new byte[0][];
            }

            List<byte[]> results = new ArrayList<>(keys.length);
            for (byte[] key : keys) {
                results.add(db.get(cfHandle, key));
            }
            return results.toArray(new byte[0][]);
        } catch (RocksDBException e) {
            log.error("批量获取失败, table={}", table, e);
            throw new RuntimeException("批量获取失败", e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void close() {
        if (db != null) {
            // 从TableEnum遍历所有表，释放对应列族句柄（适配新的集中管理逻辑）
            for (TableEnum table : TableEnum.values()) {
                ColumnFamilyHandle handle = RTable.getColumnFamilyHandle(table);
                if (handle != null) {
                    handle.close();
                    log.debug("已关闭表[{}]的列族句柄", table);
                }
            }
            db.close();
            db = null;
            log.info("RocksDB连接已关闭");
        }
    }

    /**
     * 这段这段代码是 RocksDB 数据库中手动触发范围压缩（Compaction） 的实现方法。RocksDB
     * 作为 LSM-Tree（日志结构合并树）架构的嵌入式数据库，写入数据时会先缓存到内存，达到阈值后刷盘为 SST 文件，
     * 随着数据量增长，SST 文件会越来越多，查
     * 询性能会下降。Compaction（压缩） 是 RocksDB 的核心机制，用于合并小文件、清除过期 / 删除数据、优化查询效率。
     * @param start
     * @param limit
     */
    @Override
    public void compact(byte[] start, byte[] limit) {
        rwLock.writeLock().lock();
        try {
            if (db == null) return;
            db.compactRange(start, limit);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 分页查询
     *
     * @param pageSize
     * @param lastKey
     * @return
     */
    /**
     * 实现泛型分页查询
     * 注意：实际使用时需根据 T 的类型进行反序列化（这里以 byte[] 为例，如需其他类型需扩展）
     */
    @Override
    public <T> PageResult<T> page(TableEnum table, int pageSize, byte[] lastKey) {
        // 校验参数
        if (pageSize <= 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize 必须在 1-1000 之间");
        }
        if (table == null) {
            throw new IllegalArgumentException("表名不能为空");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                // 返回空结果（泛型为 T，这里用 Collections.emptyList() 兼容）
                return new PageResult<>(Collections.emptyList(), null, true);
            }

            iterator = db.newIterator(cfHandle);
            List<T> dataList = new ArrayList<>(pageSize);
            byte[] currentLastKey = null;

            // 定位迭代器起始位置
            if (lastKey != null && lastKey.length > 0) {
                iterator.seek(lastKey);
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastKey)) {
                    iterator.next(); // 跳过上一页最后一个键
                }
            } else {
                iterator.seekToFirst(); // 第一页从开头开始
            }

            // 读取 pageSize 条数据
            int count = 0;
            while (iterator.isValid() && count < pageSize) {
                byte[] key = iterator.key();
                byte[] value = iterator.value();

                // 关键：根据 T 的类型处理 value（这里以 byte[] 为例，如需其他类型需反序列化）
                // 若 T 是自定义对象（如 UTXO），需用 SerializeUtils.deSerialize(value) 转换
                T data = (T) value; // 类型转换（实际使用时需根据 T 调整，避免强转异常）
                dataList.add(data);

                currentLastKey = key.clone(); // 保存当前页最后一个键
                iterator.next();
                count++;
            }

            // 判断是否为最后一页
            boolean isLastPage = !iterator.isValid();
            return new PageResult<>(dataList, currentLastKey, isLastPage);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
            rwLock.readLock().unlock();
        }
    }


    /**
     * 执行跨列族事务（原子操作）
     * @param operations 事务操作列表（包含多个表的增删改）
     * @return 事务是否成功
     */
    public boolean dataTransaction(List<DbOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            log.warn("事务操作列表为空，无需执行");
            return true;
        }

        rwLock.writeLock().lock();
        WriteBatch writeBatch = null;
        WriteOptions writeOptions = null;
        try {
            writeBatch = new WriteBatch();
            writeOptions = new WriteOptions();
            writeOptions.setSync(false); // 非同步写入（性能优先，若需强一致性可设为 true）

            // 1. 校验所有操作的表（列族）是否存在，并添加到事务批次
            for (DbOperation op : operations) {
                ColumnFamilyHandle cfHandle = getColumnFamilyHandle(op.table);
                if (cfHandle == null) {
                    throw new IllegalArgumentException("事务中存在不存在的表: " + op.table);
                }

                switch (op.type) {
                    case INSERT:
                    case UPDATE:
                        writeBatch.put(cfHandle, op.key, op.value);
                        break;
                    case DELETE:
                        writeBatch.delete(cfHandle, op.key);
                        break;
                    default:
                        throw new IllegalArgumentException("不支持的操作类型: " + op.type);
                }
            }

            // 2. 执行事务（原子提交）
            db.write(writeOptions, writeBatch);
            log.info("事务执行成功，操作数: {}", operations.size());
            return true;

        } catch (RocksDBException e) {
            log.error("事务执行失败", e);
            return false; // 失败时，RocksDB 会自动回滚（WriteBatch 要么全成功，要么全失败）
        } finally {
            // 释放资源
            if (writeBatch != null) {
                writeBatch.close();
            }
            if (writeOptions != null) {
                writeOptions.close();
            }
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public <T> List<KeyValue<T>> rangeQuery(TableEnum table, byte[] startKey, byte[] endKey) {
        return rangeQueryWithLimit(table, startKey, endKey, Integer.MAX_VALUE); // 无限制条数
    }

    @Override
    public <T> List<KeyValue<T>> rangeQueryWithLimit(TableEnum table, byte[] startKey, byte[] endKey, int limit) {
        if (table == null  || limit <= 0) {
            throw new IllegalArgumentException("无效参数：表名不能为空或limit必须为正数");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                return Collections.emptyList();
            }

            iterator = db.newIterator(cfHandle);
            List<KeyValue<T>> result = new ArrayList<>(limit);

            // 定位起始位置
            if (startKey != null && startKey.length > 0) {
                iterator.seek(startKey);
            } else {
                iterator.seekToFirst();
            }

            // 遍历范围 [startKey, endKey)
            while (iterator.isValid() && result.size() < limit) {
                byte[] currentKey = iterator.key();
                // 超出endKey范围则停止
                if (endKey != null && endKey.length > 0 && Arrays.compare(currentKey, endKey) >= 0) {
                    break;
                }

                // 封装键值对（支持泛型转换）
                KeyValue<T> kv = new KeyValue<>();
                kv.setKey(currentKey.clone()); // 克隆避免迭代器复用导致的数组覆盖
                kv.setValue((T) iterator.value()); // 实际使用时需反序列化（如：SerializeUtils.deSerialize(iterator.value())）
                result.add(kv);

                iterator.next();
            }
            return result;
        } finally {
            if (iterator != null) iterator.close();
            rwLock.readLock().unlock();
        }
    }


    @Override
    public void clearCache(TableEnum table) {

    }

    @Override
    public void setCachePolicy(TableEnum table, long ttl, int maxSize) {

    }


    @Override
    public void refreshCache(TableEnum table, byte[] key) {

    }

    private final ConcurrentHashMap<String, WriteBatch> transactionMap = new ConcurrentHashMap<>();
    private final AtomicLong transactionIdGenerator = new AtomicLong(0);

    @Override
    public String beginTransaction() {
        String transactionId = "txn_" + transactionIdGenerator.incrementAndGet();
        transactionMap.put(transactionId, new WriteBatch());
        log.debug("开始事务：{}", transactionId);
        return transactionId;
    }


    @Override
    public boolean commitTransaction(String transactionId) {
        if (transactionId == null || !transactionMap.containsKey(transactionId)) {
            log.warn("提交事务失败：无效的事务ID[{}]", transactionId);
            return false;
        }
        rwLock.writeLock().lock();
        WriteBatch writeBatch = transactionMap.get(transactionId);
        WriteOptions writeOptions = new WriteOptions();
        try {
            writeOptions.setSync(true); // 事务提交默认使用同步写入保证一致性
            db.write(writeOptions, writeBatch);
            transactionMap.remove(transactionId);
            log.info("事务提交成功：{}", transactionId);
            return true;
        } catch (RocksDBException e) {
            log.error("事务提交失败：{}", transactionId, e);
            return false;
        } finally {
            writeBatch.close();
            writeOptions.close();
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean rollbackTransaction(String transactionId) {
        if (transactionId == null || !transactionMap.containsKey(transactionId)) {
            log.warn("回滚事务失败：无效的事务ID[{}]", transactionId);
            return false;
        }
        // 事务回滚只需移除未提交的WriteBatch
        WriteBatch writeBatch = transactionMap.remove(transactionId);
        if (writeBatch != null) {
            writeBatch.close();
        }
        log.info("事务回滚成功：{}", transactionId);
        return true;
    }

    @Override
    public void addToTransaction(String transactionId, DbOperation operation) {
        if (transactionId == null || operation == null) {
            log.warn("添加事务操作失败：事务ID或操作不能为空");
            return;
        }
        WriteBatch writeBatch = transactionMap.get(transactionId);
        if (writeBatch == null) {
            log.warn("添加事务操作失败：事务[{}]不存在", transactionId);
            return;
        }
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(operation.table);
            if (cfHandle == null) {
                log.warn("添加事务操作失败：表[{}]不存在", operation.table);
                return;
            }
            switch (operation.type) {
                case INSERT:
                case UPDATE:
                    writeBatch.put(cfHandle, operation.key, operation.value);
                    break;
                case DELETE:
                    writeBatch.delete(cfHandle, operation.key);
                    break;
            }
            log.debug("已添加操作到事务[{}]：{}", transactionId, operation.type);
        } catch (RocksDBException e) {
            log.error("添加事务操作失败", e);
        }
    }

    @Override
    public List<String> listAllTables() {
        List<String> tables = new ArrayList<>();
        // 从 TableEnum 中获取所有表的列族名称（唯一数据源）
        for (TableEnum table : TableEnum.values()) {
            tables.add(table.getColumnFamilyName());
        }
        return tables;
    }

    @Override
    public boolean checkHealth() {
        if (db == null) {
            log.warn("数据库健康检查失败：连接未初始化");
            return false;
        }
        rwLock.readLock().lock();
        try {
            // 通过简单操作验证数据库可用性
            byte[] testKey = "health_check".getBytes();
            db.put(RocksDB.DEFAULT_COLUMN_FAMILY, testKey);
            db.delete(RocksDB.DEFAULT_COLUMN_FAMILY);
            log.info("数据库健康检查通过");
            return true;
        } catch (RocksDBException e) {
            log.error("数据库健康检查失败", e);
            return false;
        } finally {
            rwLock.readLock().unlock();
        }
    }


    @Override
    public void iterate(TableEnum table, KeyValueHandler handler) {
        if (table == null || handler == null) {
            log.warn("迭代表失败：表名或处理器不能为空");
            return;
        }
        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                log.warn("迭代表失败：表[{}]不存在", table);
                return;
            }
            iterator = db.newIterator(cfHandle);
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                byte[] value = iterator.value();
                // 调用处理器处理键值对，返回false则停止迭代
                if (!handler.handle(key, value)) {
                    break;
                }
                iterator.next();
            }
        } finally {
            if (iterator != null) {
                iterator.close();
            }
            rwLock.readLock().unlock();
        }
    }

    /**
     * 按照指定前缀遍历所有匹配的键值对。
     *
     * @param table   要操作的表（列族）。
     * @param prefix  键的前缀（byte[] 格式）。
     * @param handler 一个回调处理器，用于处理遍历到的每一个键值对。
     *                如果 handler.handle() 返回 false，则会提前终止遍历。
     */
    public void iterateByPrefix(TableEnum table, byte[] prefix, KeyValueHandler handler) {
        // 1. 参数校验
        if (table == null || prefix == null || prefix.length == 0 || handler == null) {
            log.warn("按前缀遍历失败：表、前缀或处理器不能为空");
            return;
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        try {
            // 2. 获取列族句柄和迭代器
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                log.warn("按前缀遍历失败：表[{}]不存在", table);
                return;
            }
            iterator = db.newIterator(cfHandle);

            // 3. 定位到第一个大于或等于前缀的键
            iterator.seek(prefix);

            // 4. 循环遍历并检查前缀
            while (iterator.isValid()) {
                byte[] currentKey = iterator.key();

                // 核心判断：检查当前键是否仍然以目标前缀开头
                // 如果不是，则说明所有前缀匹配的键已遍历完毕，退出循环
                if (!startsWith(currentKey, prefix)) {
                    break;
                }

                // 调用回调处理器处理当前键值对
                // 如果处理器返回 false，则提前终止遍历
                if (!handler.handle(currentKey, iterator.value())) {
                    log.debug("处理器要求终止遍历，前缀: {}", Arrays.toString(prefix));
                    break;
                }

                // 移动到下一个键
                iterator.next();
            }
            log.debug("按前缀遍历完成，前缀: {}", Arrays.toString(prefix));

        } finally {
            // 5. 确保迭代器被关闭，释放资源
            if (iterator != null) {
                iterator.close();
            }
            rwLock.readLock().unlock();
        }
    }

    /**
     * 辅助方法：检查一个字节数组是否以另一个字节数组作为前缀。
     *
     * @param key    要检查的键。
     * @param prefix 前缀。
     * @return 如果 key 以 prefix 开头，则返回 true；否则返回 false。
     */
    private boolean startsWith(byte[] key, byte[] prefix) {
        // 如果键的长度比前缀还短，肯定不匹配
        if (key.length < prefix.length) {
            return false;
        }
        // 逐字节比较前缀部分
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }


    @Override
    public void batchDeleteRange(TableEnum table, byte[] startKey, byte[] endKey) {
        if (table == null || startKey == null || endKey == null) {
            log.warn("批量删除范围失败：参数不能为空");
            return;
        }
        rwLock.writeLock().lock();
        WriteBatch writeBatch = new WriteBatch();
        WriteOptions writeOptions = new WriteOptions();
        RocksIterator iterator = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                log.warn("批量删除范围失败：表[{}]不存在", table);
                return;
            }
            iterator = db.newIterator(cfHandle);
            iterator.seek(startKey);
            while (iterator.isValid()) {
                byte[] currentKey = iterator.key();
                if (Arrays.compare(currentKey, endKey) >= 0) {
                    break;
                }
                writeBatch.delete(cfHandle, currentKey);
                iterator.next();
            }
            db.write(writeOptions, writeBatch);
            log.info("表[{}]中范围[{}, {})的记录已批量删除", table, Arrays.toString(startKey), Arrays.toString(endKey));
        } catch (RocksDBException e) {
            log.error("批量删除范围失败", e);
            throw new RuntimeException("批量删除范围失败", e);
        } finally {
            if (iterator != null) {
                iterator.close();
            }
            writeBatch.close();
            writeOptions.close();
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void enableWAL(boolean enable) {
        // 通过设置默认WriteOptions修改WAL状态（实际应用中可能需要维护全局WriteOptions）
        // 这里仅为示例，实际实现需根据业务场景调整
        log.info("{} Write-Ahead Log", enable ? "启用" : "禁用");
        // 注意：RocksDB默认启用WAL，禁用会降低安全性但提高写入性能
    }

    /**
     * 获取表对应的列族句柄
     */
    private ColumnFamilyHandle getColumnFamilyHandle(TableEnum table) {
        return RTable.getColumnFamilyHandle(table);
    }

    // 内部静态类：封装事务中的单个操作
    public static class DbOperation {
        public enum OpType { INSERT, UPDATE, DELETE }

        public final TableEnum table; // 表枚举
        public final byte[] key;      // 键
        public final byte[] value;    // 值（DELETE 操作可为 null）
        public final OpType type;     // 操作类型

        public DbOperation(TableEnum table, byte[] key, byte[] value, OpType type) {
            this.table = table;
            this.key = key;
            this.value = value;
            this.type = type;
        }
    }

    /**
     * 从缓存键中提取表枚举（缓存键格式：2字节表标识(short) + 原始业务键）
     * 表标识与TableEnum中的code字段对应（short类型，2字节）
     * @param key 缓存键（byte[] 类型）
     * @return 提取的表枚举，解析失败返回null
     */
    private TableEnum extractTableFromKey(byte[] key) {
        if (key == null || key.length < 2) {
            log.warn("缓存键为空或长度不足2字节，无法提取表标识");
            return null;
        }

        // 前2字节为表标识（short类型），转换为short值
        short tableCode;
        try {
            // 字节数组转short（大端模式，与编码时保持一致）
            tableCode = (short) ((key[0] & 0xFF) << 8 | (key[1] & 0xFF));
        } catch (Exception e) {
            log.error("解析表标识失败（字节转short错误）", e);
            return null;
        }

        // 根据表标识获取对应的TableEnum
        TableEnum table = TableEnum.getByCode(tableCode);
        if (table == null) {
            log.warn("未找到与表标识[{}]匹配的TableEnum", tableCode);
        }
        return table;
    }

    // 示例：区块表缓存更大、过期时间更长，账户表按需调整
    private long getCacheMaxSize(TableEnum table) {
        return table.getCacheSize(); // 区块表缓存更多
    }

    private long getCacheTtl(TableEnum table) {
        return table.getCacheTL(); // 区块表缓存更多
    }
}
