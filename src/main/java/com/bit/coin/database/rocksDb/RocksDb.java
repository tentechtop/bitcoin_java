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
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;


@Slf4j
@Component
@Data
public class RocksDb implements DataBase {

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
            if (getCacheMaxSize(table)!=0){
                Cache<byte[], byte[]> cache = Caffeine.newBuilder()
                        .maximumSize(getCacheMaxSize(table)) // 按表配置大小
                        .expireAfterWrite(getCacheTtl(table), TimeUnit.MINUTES) // 按表配置过期时间
                        .build();
                tableCaches.put(table, cache);
            }
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

    @Override
    public <T> PageResult<T> pageKey(TableEnum table, int pageSize, byte[] lastKey) {
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
                dataList.add((T) key);
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
     * 按前缀分页查询（返回包含值的键值对）
     * @param table 表枚举
     * @param prefix 键的前缀（byte[] 格式）
     * @param pageSize 每页条数（1-1000）
     * @param lastKey 上一页最后一个键（用于分页续查，第一页传null）
     * @return 分页结果（包含数据列表、当前页最后一个键、是否最后一页）
     */
    @Override
    public <T> PageResult<T> pageByPrefix(TableEnum table, byte[] prefix, int pageSize, byte[] lastKey) {
        // 1. 参数校验
        if (pageSize <= 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize 必须在 1-1000 之间");
        }
        if (table == null || prefix == null || prefix.length == 0) {
            throw new IllegalArgumentException("表名和前缀不能为空");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                return new PageResult<>(Collections.emptyList(), null, true);
            }

            // 2. 配置前缀扫描选项，提升查询效率
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(cfHandle, readOptions);

            List<T> dataList = new ArrayList<>(pageSize);
            byte[] currentLastKey = null;

            // 3. 定位迭代器起始位置
            if (lastKey != null && lastKey.length > 0) {
                // 从上一页最后一个键的下一个位置开始
                iterator.seek(lastKey);
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastKey)) {
                    iterator.next(); // 跳过上一页最后一个键
                }
            } else {
                // 第一页从前缀起始位置开始
                iterator.seek(prefix);
            }

            // 4. 读取分页数据
            int count = 0;
            while (iterator.isValid() && count < pageSize) {
                byte[] currentKey = iterator.key();

                // 校验当前键是否匹配前缀，不匹配则终止遍历
                if (!startsWith(currentKey, prefix)) {
                    break;
                }

                // 封装数据（value需根据泛型T反序列化，这里保持与原有page方法一致的强转逻辑）
                T data = (T) iterator.value();
                dataList.add(data);

                // 记录当前页最后一个键
                currentLastKey = currentKey.clone();

                iterator.next();
                count++;
            }

            // 5. 判断是否为最后一页（检查下一个键是否还匹配前缀）
            boolean isLastPage = true;
            if (iterator.isValid()) {
                byte[] nextKey = iterator.key();
                if (startsWith(nextKey, prefix)) {
                    isLastPage = false;
                }
            }

            return new PageResult<>(dataList, currentLastKey, isLastPage);
        } finally {
            // 6. 释放资源
            if (iterator != null) {
                iterator.close();
            }
            if (readOptions != null) {
                readOptions.close();
            }
            rwLock.readLock().unlock();
        }
    }

    /**
     * 按前缀分页查询（仅返回键）
     * @param table 表枚举
     * @param prefix 键的前缀（byte[] 格式）
     * @param pageSize 每页条数（1-1000）
     * @param lastKey 上一页最后一个键（用于分页续查，第一页传null）
     * @return 分页结果（包含键列表、当前页最后一个键、是否最后一页）
     */
    @Override
    public <T> PageResult<T> pageKeyByPrefix(TableEnum table, byte[] prefix, int pageSize, byte[] lastKey) {
        // 1. 参数校验
        if (pageSize <= 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize 必须在 1-1000 之间");
        }
        if (table == null || prefix == null || prefix.length == 0) {
            throw new IllegalArgumentException("表名和前缀不能为空");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                return new PageResult<>(Collections.emptyList(), null, true);
            }

            // 2. 配置前缀扫描选项
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(cfHandle, readOptions);

            List<T> keyList = new ArrayList<>(pageSize);
            byte[] currentLastKey = null;

            // 3. 定位迭代器起始位置
            if (lastKey != null && lastKey.length > 0) {
                iterator.seek(lastKey);
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastKey)) {
                    iterator.next(); // 跳过上一页最后一个键
                }
            } else {
                iterator.seek(prefix);
            }

            // 4. 读取分页键数据
            int count = 0;
            while (iterator.isValid() && count < pageSize) {
                byte[] currentKey = iterator.key();

                // 校验前缀匹配
                if (!startsWith(currentKey, prefix)) {
                    break;
                }

                // 仅添加键数据
                keyList.add((T) currentKey.clone());

                // 记录当前页最后一个键
                currentLastKey = currentKey.clone();

                iterator.next();
                count++;
            }

            // 5. 判断是否为最后一页
            boolean isLastPage = true;
            if (iterator.isValid()) {
                byte[] nextKey = iterator.key();
                if (startsWith(nextKey, prefix)) {
                    isLastPage = false;
                }
            }

            return new PageResult<>(keyList, currentLastKey, isLastPage);
        } finally {
            // 6. 释放资源
            if (iterator != null) {
                iterator.close();
            }
            if (readOptions != null) {
                readOptions.close();
            }
            rwLock.readLock().unlock();
        }
    }

    //NewIterator 创建一个迭代器，需要传入读配置项
    //Seek 查找一个key
    //SeekToFirst 迭代器移动到db的第一个key位置，一般用于顺序遍历整个db的所有key
    //SeekToLast 迭代器移动到db的最后一个key位置， 一般用于反向遍历整个db的所有key
    //SeekForPrev移动到当前key的上一个位置，一般用于遍历(limit, start]之间的key
    //Next 迭代器移动到下一个key
    //Prev迭代器移动到上一个key
    @Override
    public <T> PageResult<T> pageKeyByPrefixReverse(TableEnum table, byte[] prefix, int pageSize, byte[] lastKey) {
        // 1. 参数校验（和原逻辑一致）
        if (pageSize <= 0 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize 必须在 1-1000 之间");
        }
        if (table == null || prefix == null || prefix.length == 0) {
            throw new IllegalArgumentException("表名和前缀不能为空");
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                return new PageResult<>(Collections.emptyList(), null, true);
            }

            // 2. 配置扫描选项（仅保留prefix配置，无setReverse）
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(cfHandle, readOptions);

            List<T> keyList = new ArrayList<>(pageSize);
            byte[] currentLastKey = null;

            // 3. 定位迭代器起始位置（修正seekForPrev参数）
            if (lastKey != null && lastKey.length > 0) {
                // 有上一页的lastKey：定位到该key → prev()跳过（反向遍历）
                iterator.seek(lastKey);
                if (iterator.isValid() && Arrays.equals(iterator.key(), lastKey)) {
                    iterator.prev(); // 跳过上一页最后一个key，准备读取下一批
                }
            } else {
                // 无lastKey（第一页）：定位到前缀的最后一个key
                byte[] prefixUpper = getPrefixUpperBound(prefix);
                // 关键修正：seekForPrev必须传入参数（前缀上限key）
                iterator.seekForPrev(prefixUpper);

                // 边界校验：如果定位后的key不匹配前缀，说明前缀下无数据
                if (iterator.isValid() && !startsWith(iterator.key(), prefix)) {
                    iterator = null; // 置空，后续遍历直接终止
                }
            }

            // 4. 反向读取分页键数据（核心：用prev()替代next()）
            int count = 0;
            // 增加iterator非空校验（避免前缀无数据时的空指针）
            while (iterator != null && iterator.isValid() && count < pageSize) {
                byte[] currentKey = iterator.key();

                // 校验前缀匹配：反向遍历超出前缀范围则终止
                if (!startsWith(currentKey, prefix)) {
                    break;
                }

                // 添加当前key（克隆避免引用泄露）
                keyList.add((T) currentKey.clone());

                // 记录当前页最后一个key（供下一页查询使用）
                currentLastKey = currentKey.clone();

                iterator.prev(); // 反向遍历：移动到上一个key（替代原next()）
                count++;
            }

            // 5. 判断是否为最后一页（反向遍历的最后一页=没有更多更小的key）
            boolean isLastPage = true;
            // 检查是否还有更多符合前缀的key
            if (iterator != null && iterator.isValid()) {
                byte[] prevKey = iterator.key();
                if (startsWith(prevKey, prefix)) {
                    isLastPage = false;
                }
            }

            return new PageResult<>(keyList, currentLastKey, isLastPage);
        } finally {
            // 6. 释放资源（和原逻辑一致）
            if (iterator != null) {
                iterator.close();
            }
            if (readOptions != null) {
                readOptions.close();
            }
            rwLock.readLock().unlock();
        }
    }
    @Override
    public boolean existsByPrefix(TableEnum table, byte[] prefix) {
        // 1. 参数校验：表名/前缀为空直接返回false
        if (table == null || prefix == null || prefix.length == 0) {
            log.warn("existsByPrefix参数非法：表名[{}] 前缀长度[{}]", table, prefix == null ? 0 : prefix.length);
            return false;
        }

        rwLock.readLock().lock(); // 复用读写锁，保证线程安全
        RocksIterator iterator = null;
        ReadOptions readOptions = null;
        try {
            // 2. 获取列族句柄（和pageKeyByPrefixReverse逻辑一致）
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                log.warn("existsByPrefix获取列族句柄失败：表名[{}]", table);
                return false;
            }

            // 3. 配置读选项：开启前缀优化，提升查询效率
            readOptions = new ReadOptions()
                    .setPrefixSameAsStart(true) // 前缀查询优化
                    .setTotalOrderSeek(false); // 关闭全序扫描，加速前缀查询

            // 4. 创建迭代器并定位到前缀起始位置
            iterator = db.newIterator(cfHandle, readOptions);
            iterator.seek(prefix); // 定位到第一个>=前缀的key

            // 5. 核心判断：迭代器有效 + 当前key以指定前缀开头
            if (iterator.isValid()) {
                byte[] currentKey = iterator.key();
                boolean match = startsWith(currentKey, prefix); // 复用你已有的前缀匹配方法
                if (match) {
                    log.debug("existsByPrefix命中：表[{}] 前缀[{}] 匹配key[{}]",
                            table, bytesToHex(prefix), bytesToHex(currentKey));
                    return true;
                }
            }

            // 6. 无匹配记录
            log.debug("existsByPrefix未命中：表[{}] 前缀[{}]", table, bytesToHex(prefix));
            return false;

        } catch (Exception e) {
            // 7. 异常兜底：捕获所有异常，避免程序崩溃
            log.error("existsByPrefix执行异常：表[{}] 前缀[{}]", table, bytesToHex(prefix), e);
            return false;
        } finally {
            // 8. 强制释放资源（避免内存泄漏）
            if (iterator != null) {
                try {
                    iterator.close();
                } catch (Exception e) {
                    log.warn("关闭RocksIterator失败", e);
                }
            }
            if (readOptions != null) {
                try {
                    readOptions.close();
                } catch (Exception e) {
                    log.warn("关闭ReadOptions失败", e);
                }
            }
            rwLock.readLock().unlock(); // 必须释放读锁
        }
    }


    /**
     * 计算前缀的上限（用于反向迭代时定位最大的匹配key）
     * 前缀的范围是 [prefix, prefixUpperBound)，例如前缀是 [0x01,0x02]，上限是 [0x01,0x03]
     * @param prefix 原前缀
     * @return 前缀的上限字节数组
     */
    private byte[] getPrefixUpperBound(byte[] prefix) {
        byte[] upperBound = Arrays.copyOf(prefix, prefix.length);
        // 从最后一个字节开始加1，处理进位（类似数字加1）
        for (int i = upperBound.length - 1; i >= 0; i--) {
            if (upperBound[i] == (byte) 0xFF) {
                upperBound[i] = 0x00;
            } else {
                upperBound[i] += 1;
                break;
            }
        }
        return upperBound;
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
    /**
     * 安全地遍历具有指定前缀的键值对（修复版）
     * @param table    要操作的表（列族）
     * @param prefix   键的前缀（byte[] 格式）
     * @param handler  回调处理器，处理每一个键值对。返回false则终止遍历
     */
    public void iterateByPrefix(TableEnum table, byte[] prefix, KeyValueHandler handler) {
        // 1. 参数校验
        if (table == null || prefix == null || prefix.length == 0 || handler == null) {
            log.warn("按前缀遍历失败：表、前缀或处理器不能为空");
            return;
        }

        rwLock.readLock().lock();
        RocksIterator iterator = null;
        ReadOptions readOptions = null;

        try {
            // 2. 获取列族句柄
            ColumnFamilyHandle cfHandle = getColumnFamilyHandle(table);
            if (cfHandle == null) {
                log.warn("按前缀遍历失败：表[{}]不存在", table);
                return;
            }

            // 3. 配置ReadOptions启用前缀扫描模式
            readOptions = new ReadOptions().setPrefixSameAsStart(true);
            iterator = db.newIterator(cfHandle, readOptions);

            // 4. 定位到第一个大于或等于前缀的键
            iterator.seek(prefix);

            // 5. 循环遍历
            while (iterator.isValid()) {
                byte[] currentKey = iterator.key();

                // 关键修复：在处理前先检查前缀匹配
                if (!startsWith(currentKey, prefix)) {
                    log.info("前缀{}",bytesToHex(prefix));
                    log.info("当前Key{}",bytesToHex(currentKey));
                    break; // 遇到不匹配前缀的键，立即终止
                }

                // 调用处理器处理当前键值对
                if (!handler.handle(currentKey, iterator.value())) {
                    log.debug("处理器要求终止遍历，前缀: {}", Arrays.toString(prefix));
                    break;
                }

                // 移动到下一个键
                iterator.next();
            }

            log.debug("按前缀遍历完成，前缀: {}", Arrays.toString(prefix));

        } catch (Exception e) {
            log.error("按前缀遍历异常，前缀: {}", Arrays.toString(prefix), e);
        } finally {
            // 6. 确保资源释放
            if (iterator != null) {
                iterator.close();
            }
            if (readOptions != null) {
                readOptions.close();
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

    // int 转 byte[]（您已提供的代码）
    public static byte[] intToBytes(int value){
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    // long 转 byte[]（您已提供的代码）
    public static byte[] longToBytes(long value){
        return ByteBuffer.allocate(8).putLong(value).array();
    }

    // byte[] 转 int
    public static int bytesToInt(byte[] bytes) {
        // 新增null校验和空数组校验
        if (bytes == null || bytes.length != 4) {
            log.error("bytesToInt参数无效：array为null或长度不是4字节，array={}", bytes);
            return -1; // 返回无效高度标记
        }
        return ByteBuffer.wrap(bytes).getInt();
    }

    // byte[] 转 long
    public static long bytesToLong(byte[] bytes) {
        return ByteBuffer.wrap(bytes).getLong();
    }

    // 支持字节序的增强版本
    public static int bytesToInt(byte[] bytes, ByteOrder byteOrder) {
        return ByteBuffer.wrap(bytes).order(byteOrder).getInt();
    }

    public static long bytesToLong(byte[] bytes, ByteOrder byteOrder) {
        return ByteBuffer.wrap(bytes).order(byteOrder).getLong();
    }

    // 支持偏移量的版本
    public static int bytesToInt(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 4).getInt();
    }

    public static long bytesToLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).getInt();
    }

    // 手动位运算实现（备选方案）
    public static int bytesToIntManual(byte[] bytes) {
        if (bytes.length < 4) {
            throw new IllegalArgumentException("字节数组长度至少为4");
        }
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }

    public static long bytesToLongManual(byte[] bytes) {
        if (bytes.length < 8) {
            throw new IllegalArgumentException("字节数组长度至少为8");
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (bytes[i] & 0xFF)) << (56 - 8 * i);
        }
        return value;
    }


}
