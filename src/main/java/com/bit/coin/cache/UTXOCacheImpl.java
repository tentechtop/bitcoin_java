package com.bit.coin.cache;

import com.bit.coin.config.ParamsConfig;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.DataBase.KeyValue;
import com.bit.coin.database.KeyValueHandler;
import com.bit.coin.database.rocksDb.RocksDb;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.*;
import com.bit.coin.utils.Sha;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.bit.coin.blockchain.BlockChainServiceImpl.mainTipBlock;
import static com.bit.coin.database.rocksDb.TableEnum.ADDR_TO_UTXO;
import static com.bit.coin.structure.tx.UTXOStatusResolver.*;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;


/**
 * UTXO 缓存接口
 * 1. 查询 UTXO 优先从缓存获取，缓存不存在再查数据库
 * 2. 交易验证通过后，将消耗的 UTXO 和产生的 UTXO 记录在缓存中
 * 3. 区块上链确认后，更新缓存到数据库
 */
@Slf4j
@Component
public class UTXOCacheImpl implements UTXOCache {

    @Autowired
    private DataBase dataBase;

    @Autowired
    private SystemConfig config;

    /**
     * UTXO 缓存常量配置
     */
    private static final int MAX_CACHE_SIZE = 100_000_000; // 最大缓存 100M UTXO
    private static final long CACHE_TTL_HOURS = 24; // 缓存 TTL 24 小时
    private static final int INITIAL_CAPACITY = 100_000_0; // 初始容量

    /**
     * 主缓存：使用 Caffeine 实现 LRU 缓存，支持大小限制和过期
     * 存储已确认的 UTXO
     */
    private final Cache<String, UTXO> mainCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterAccess(CACHE_TTL_HOURS, TimeUnit.HOURS)
            .initialCapacity(INITIAL_CAPACITY)
            //当删除时执行删除锁定脚本到UTXO
            .removalListener((key, value, cause) -> {
                if (value != null) {
                    removeUTXOKeyFromAddress((UTXO) value);
                }
            })
            .recordStats()
            .build();

    //锁定脚本到UTXO
    private final Map<String, Set<String>> addressCache = new ConcurrentHashMap<>();

    /**
     * 用于同步操作的锁对象，确保复合操作的原子性
     */
    private final Object addressCacheLock = new Object();


    @Override
    public UTXO getUTXO(byte[] txId, int index) {
        byte[] key = UTXO.getKey(txId, index);
        return getUTXO(key);
    }

    @Override
    public UTXO getUTXOByStringKey(String key) {
        return getUTXO(hexToBytes(key));
    }

    @Override
    public UTXO getUTXO(byte[] key) {
        if (key == null) {
            return null;
        }
        // 2. 查主缓存
        UTXO entry = mainCache.getIfPresent(bytesToHex(key));
        if (entry != null) {
            return entry;
        }
        // 3. 查询数据库
        byte[] data = dataBase.get(TableEnum.TUTXO, key);
        if (data != null) {
            UTXO utxo = UTXO.deserialize(data);
            if (utxo!=null){
                long status1 = 0L;
                status1 = setLifecycleStatus(status1, CONFIRMED_UNSPENT);//新生成未确认
                utxo.setStatus(status1);
                addUTXO(utxo);
                return utxo;
            }
        }
        return null;
    }


    @Override
    public void addUTXO(UTXO utxo) {
        //加入到主缓存
        mainCache.put(bytesToHex(utxo.getKey()), utxo);
        addUTXOKeyToAddress(utxo);
    }


    private void addUTXOKeyToAddress(UTXO utxo) {
        if (utxo == null || utxo.getScript() == null || utxo.getKey() == null) {
            log.warn("UTXO或其锁定脚本/Key为空，无法建立地址索引");
            return;
        }
        String lockingScriptHex = bytesToHex(utxo.getScript());
        String utxoKeyHex = bytesToHex(utxo.getKey());

        synchronized (addressCacheLock) {
            Set<String> utxoKeys = addressCache.computeIfAbsent(lockingScriptHex,
                    k -> ConcurrentHashMap.newKeySet());
            boolean added = utxoKeys.add(utxoKeyHex);
            if (added) {
                log.debug("为UTXO[{}]建立地址索引，锁定脚本:{}", utxoKeyHex, lockingScriptHex);
            } else {
                log.debug("UTXO[{}]已存在于地址索引中", utxoKeyHex);
            }
        }
    }

    /**
     * 修复后的方法：删除UTXO的地址索引
     * 修复问题：竞态条件、操作原子性、空指针异常
     */
    private void removeUTXOKeyFromAddress(UTXO utxo) {
        // 1. 增强参数校验
        if (utxo == null || utxo.getKey() == null || utxo.getScript() == null) {
            log.warn("UTXO或其Key/锁定脚本为空，无法删除地址索引");
            return;
        }

        String lockingScriptHex = bytesToHex(utxo.getScript());
        String utxoKeyHex = bytesToHex(utxo.getKey());

        // 2. 使用同步块保证检查-删除操作的原子性
        synchronized (addressCacheLock) {
            Set<String> utxoKeys = addressCache.get(lockingScriptHex);
            if (utxoKeys == null) {
                log.debug("锁定脚本[{}]无对应的UTXO列表，无需删除", lockingScriptHex);
                return;
            }

            // 3. 原子性地移除UTXO Key并检查集合是否为空
            boolean removed = utxoKeys.remove(utxoKeyHex);
            if (removed) {
                log.debug("从锁定脚本[{}]中移除UTXO[{}]索引", lockingScriptHex, utxoKeyHex);

                // 4. 如果集合为空，移除整个映射（原子性操作）
                if (utxoKeys.isEmpty()) {
                    addressCache.remove(lockingScriptHex, Collections.emptySet());
                    log.debug("锁定脚本[{}]的UTXO列表为空，移除该地址索引", lockingScriptHex);
                }
            } else {
                log.warn("UTXO[{}]不在锁定脚本[{}]的索引列表中", utxoKeyHex, lockingScriptHex);
            }
        }
    }


    @Override
    public void addPendingTx(Transaction transaction) {
        log.info("添加一笔待确认的交易{}",bytesToHex(transaction.getTxId()));
        boolean coinbase = transaction.isCoinbase();
        List<TxInput> inputs = transaction.getInputs();
        List<TxOutput> outputs = transaction.getOutputs();
        //将输入标记为 花费中待确认
        for (TxInput txInput:inputs){
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            byte[] key = UTXO.getKey(txId, index);
            UTXO utxo = getUTXO(key);
            long status1 = 0L;
            status1 = setLifecycleStatus(status1, PENDING_SPENT);//花费中待确认
            utxo.setStatus(status1);
        }
        //将输出标记为 到账中待确认
        for (int i = 0; i < outputs.size(); i++) {
            TxOutput txOutput = outputs.get(i);
            UTXO utxo = new UTXO();
            utxo.setCoinbase(coinbase);
            utxo.setTxId(transaction.getTxId());
            utxo.setHeight(0);
            utxo.setValue(txOutput.getValue());
            utxo.setScript(txOutput.getScriptPubKey());
            utxo.setIndex(i);
            long status1 = 0L;
            status1 = setLifecycleStatus(status1, UNCONFIRMED_OUTPUT);//新生成未确认
            utxo.setStatus(status1);
            addUTXO(utxo);
        }
    }

    @Override
    public void revokePendingTx(Transaction transaction) {
        //移除输入锁定
        //移除输出
        boolean coinbase = transaction.isCoinbase();
        List<TxInput> inputs = transaction.getInputs();
        List<TxOutput> outputs = transaction.getOutputs();
        for (TxInput txInput:inputs){
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            byte[] key = UTXO.getKey(txId, index);
            UTXO utxo = getUTXO(key);
            long status1 = 0L;
            status1 = setLifecycleStatus(status1, CONFIRMED_UNSPENT);
            utxo.setStatus(status1);
            // 注意：这里不需要调用 removeUTXOKeyFromAddress，
            // 因为 addPendingTx 时并没有从地址索引中移除输入的UTXO
        }
        for (int i = 0; i < outputs.size(); i++) {
            TxOutput txOutput = outputs.get(i);
            byte[] key = UTXO.getKey(transaction.getTxId(), i);
            //从主缓存移除
            mainCache.invalidate(bytesToHex(key));
        }
    }



    @Override
    public void addConfirmedTx(Transaction transaction,int height) {
        //主链才有UTXO 所以方法需要进行 非空判断
        boolean coinbase = transaction.isCoinbase();
        List<TxInput> inputs = transaction.getInputs();
        List<TxOutput> outputs = transaction.getOutputs();
        //将输入标记为 已经花费
        for (TxInput txInput:inputs){
            byte[] txId = txInput.getTxId();
            int index = txInput.getIndex();
            byte[] key = UTXO.getKey(txId, index);
            UTXO utxo = getUTXO(key);
            if (utxo!=null){
                long status1 = 0L;
                status1 = setLifecycleStatus(status1, SPENT);//已经花费
                utxo.setStatus(status1);
            }
        }
        //将输出标记为 CONFIRMED_UNSPENT
        for (int i = 0; i < outputs.size(); i++) {
            TxOutput txOutput = outputs.get(i);
            byte[] key = UTXO.getKey(transaction.getTxId(), i);
            UTXO utxo = getUTXO(key);
            if (utxo!=null){
                utxo.setHeight(height);
                long status1 = 0L;
                status1 = setLifecycleStatus(status1, CONFIRMED_UNSPENT);
                utxo.setStatus(status1);
                addUTXO(utxo);
            }
        }
    }

    @Override
    public List<UTXO> getUTXOsByAddress(String address) {
        // 1. 基础校验：地址为空直接返回空列表
        if (address == null || address.trim().isEmpty()) {
            log.warn("查询UTXO的地址为空，返回空列表");
            return Collections.emptyList();
        }

        List<UTXO> resultUTXOs = new ArrayList<>();
        byte[] lockingScriptHash = null;
        try {
            byte[] decode = Base58.decode(address);
            lockingScriptHash = Sha.applyRIPEMD160(Sha.applySHA256(decode));
            log.info("地址[{}]解码后的锁定脚本哈希:{}", address, bytesToHex(lockingScriptHash));

            //addressCache
        } catch (Exception e) {
            // 捕获Base58解码失败（无效地址）、哈希计算失败等异常
            log.error("地址[{}]解码/哈希计算失败，无效地址", address, e);
            return Collections.emptyList();
        }

        Set<String> utxoKeys = addressCache.get(bytesToHex(lockingScriptHash));
        if (utxoKeys == null || utxoKeys.isEmpty()) {
            log.info("地址[{}]无对应的UTXO Key，返回空列表", address);
            return Collections.emptyList();
        }

        // 4. 遍历UTXO Key，获取UTXO并过滤未花费的
        // 遍历拷贝后的列表，避免并发修改异常
        for (String utxoKey : new ArrayList<>(utxoKeys)) {
            if (utxoKey == null) {
                log.warn("地址[{}]对应的UTXO Key为空，跳过", address);
                continue;
            }
            UTXO utxo = getUTXOByStringKey(utxoKey);
            resultUTXOs.add(utxo);
        }
        log.info("地址[{}]查询到{}个未花费UTXO", address, resultUTXOs.size());
        return resultUTXOs;
    }

    public List<String> getUTXOKeyListByAddr(String address) {
        //对参数校验
        if (address == null || address.trim().isEmpty()) {
            log.warn("查询UTXO的参数无效，返回空列表");
            return Collections.emptyList();
        }
        byte[] pubkey = Base58.decode(address);
        byte[] addr = Sha.applyRIPEMD160(Sha.applySHA256(pubkey));
        List<String> utxos = new ArrayList<>();
        dataBase.iterateByPrefix(ADDR_TO_UTXO,addr, (key, value) -> {
            byte[] utxoKey = Arrays.copyOfRange((byte[]) key, 20, ((byte[]) key).length);
            utxos.add(bytesToHex(utxoKey));
            return true;
        });
        return utxos;
    }



    @Override
    public List<UTXO> getAddressAllUTXO(String address) {
        ParamsConfig.Params params = config.getParams();

        // 1. 获取地址对应的UTXO键列表和缓存中的UTXO键集合
        List<String> utxoKeyListByAddr = getUTXOKeyListByAddr(address);
        byte[] decode = Base58.decode(address);
        String addr = bytesToHex(Sha.applyRIPEMD160(Sha.applySHA256(decode)));
        Set<String> utxoKeys = addressCache.get(addr);

        // 2. 合并与去重处理：将utxoKeyListByAddr与utxoKeys合并，并去重
        Set<String> uniqueKeys = new LinkedHashSet<>();
        if (utxoKeyListByAddr != null) {
            uniqueKeys.addAll(utxoKeyListByAddr); // 添加列表中的键
        }
        if (utxoKeys != null) {
            uniqueKeys.addAll(utxoKeys); // 合并Set，自动去重
        }

        // 3. 基础校验：合并去重后的集合为空才返回空列表
        if (uniqueKeys.isEmpty()) {
            log.warn("合并去重后的UTXO键集合为空，返回空列表");
            return Collections.emptyList();
        }

        List<UTXO> result = new ArrayList<>(uniqueKeys.size());

        // 4. 批量从主缓存中获取UTXO
        Map<String, UTXO> cachedUTXOs = mainCache.getAllPresent(uniqueKeys);

        // 5. 找出缓存中不存在的Key
        Set<String> missingKeys = new HashSet<>(uniqueKeys);
        missingKeys.removeAll(cachedUTXOs.keySet());

        // 6. 将缓存命中的UTXO加入结果集
        result.addAll(cachedUTXOs.values());

        // 7. 批量查询数据库中缺失的UTXO
        if (!missingKeys.isEmpty()) {
            log.debug("批量查询中有{}个UTXO需要从数据库加载", missingKeys.size());

            for (String keyHex : missingKeys) {
                try {
                    byte[] key = hexToBytes(keyHex);
                    byte[] data = dataBase.get(TableEnum.TUTXO, key);

                    if (data != null) {
                        UTXO utxo = UTXO.deserialize(data);
                        long status = 0L;
                        status = setLifecycleStatus(status, CONFIRMED_UNSPENT);
                        //是否成熟 coinBase 100
                        int coinbaseMaturity = params.getCoinbaseMaturity();
                        if (utxo.isCoinbase()){
                            int height = utxo.getHeight();
                            int mainHeight = mainTipBlock.getHeight();
                            // 计算确认数：当前主链高度 - UTXO所在区块高度 + 1
                            int confirmations = mainHeight - height + 1;
                            // 判断是否成熟：确认数必须大于Coinbase成熟度
                            boolean isMature = confirmations > coinbaseMaturity;
                            if (isMature){
                                status = setCoinbaseMatured(status);
                            }else {
                                status = setCoinbaseMaturing(status);
                            }
                        }
                        utxo.setStatus(status);

                        // 添加到缓存和结果集
                        addUTXO(utxo);
                        result.add(utxo);
                        log.debug("从数据库加载UTXO[{}]到缓存", keyHex);
                    } else {
                        log.debug("UTXO[{}]在数据库中不存在", keyHex);
                    }
                } catch (Exception e) {
                    log.error("查询UTXO[{}]时发生异常", keyHex, e);
                }
            }
        }

        log.info("批量查询UTXO完成：合并去重后{}个，命中缓存{}个，查询数据库{}个，最终返回{}个",
                uniqueKeys.size(), cachedUTXOs.size(), missingKeys.size(), result.size());

        //重新设置成熟度
        for (UTXO utxo : result) {
            long status = utxo.getStatus();
            int coinbaseMaturity = params.getCoinbaseMaturity();
            if (utxo.isCoinbase()){
                int height = utxo.getHeight();
                int mainHeight = mainTipBlock.getHeight();
                // 计算确认数：当前主链高度 - UTXO所在区块高度 + 1
                int confirmations = mainHeight - height + 1;
                // 判断是否成熟：确认数必须大于Coinbase成熟度
                boolean isMature = confirmations > coinbaseMaturity;
                if (isMature){
                    status = setCoinbaseMatured(status);
                }else {
                    status = setCoinbaseMaturing(status);
                }
            }
            utxo.setStatus(status);
        }

        return result;
    }

    @Override
    public List<UTXO> getAddressAllUTXOByStatus(String address, long status) {
        List<UTXO> addressAllUTXO = getAddressAllUTXO(address);
        return addressAllUTXO.stream()
                .filter(utxo -> {
                    // 核心修改：从 !=0 改为 == status，确保所有目标位都匹配
                    long utxoStatus = utxo.getStatus();
                    return (utxoStatus & status) == status;
                })
                .toList();
    }

    @Override
    public Map<String, Object> getAddressAllUTXOAndTotalByStatus(String address, long status) {
        List<UTXO> addressAllUTXOByStatus = getAddressAllUTXOByStatus(address, status);
        AtomicLong total = new AtomicLong();
        for (UTXO utxo : addressAllUTXOByStatus){
            total.addAndGet(utxo.getValue());
        }
        return Map.of("total",total.get(),"utxos",addressAllUTXOByStatus);
    }

    @Override
    public Map<String, Object> getBalance(String address) {
        return getAddressAllUTXOAndTotalByStatus(address, CONFIRMED_UNSPENT | COINBASE_MATURED);
    }

    @Override
    public Map<String, Object> getUTXOsByAddressAndCount(String address) {
        // 1. 获取地址下所有UTXO
        List<UTXO> allUtxos = getAddressAllUTXO(address);
        if (allUtxos.isEmpty()) {
            return Map.of(
                    "totalBalance", 0L,        // 总余额（所有有效UTXO金额之和，扣除待支出）
                    "availableBalance", 0L,    // 可用余额（可直接花费）
                    "pendingReceive", 0L,      // 待到账（未确认输出）
                    "pendingSpend", 0L,        // 待支出（花费中）
                    "maturingBalance", 0L,     // 成熟中（coinbase未成熟）
                    "spentBalance", 0L,        // 已花费
                    "orphanedBalance", 0L      // 被孤立
            );
        }

        // 2. 初始化线程安全的统计计数器
        AtomicLong totalBalance = new AtomicLong(0);
        AtomicLong availableBalance = new AtomicLong(0);
        AtomicLong pendingReceive = new AtomicLong(0);
        AtomicLong pendingSpend = new AtomicLong(0);
        AtomicLong maturingBalance = new AtomicLong(0);
        AtomicLong spentBalance = new AtomicLong(0);
        AtomicLong orphanedBalance = new AtomicLong(0);

        // 3. 遍历UTXO，修正状态判断和余额计算逻辑
        allUtxos.forEach(utxo -> {
            long value = utxo.getValue();
            long status = utxo.getStatus();

            // ========== 修正后的状态判断逻辑 ==========
            // 1. 未确认输出 → 待到账（计入总余额，不计入可用余额）
            if (UTXOStatusResolver.hasLifecycle(status, UNCONFIRMED_OUTPUT)) {
                pendingReceive.addAndGet(value);
                totalBalance.addAndGet(value); // 待到账计入总余额
            }
            // 2. 已确认未花费 → 区分coinbase是否成熟
            else if (UTXOStatusResolver.hasLifecycle(status, CONFIRMED_UNSPENT)) {
                totalBalance.addAndGet(value); // 计入总余额
                if (UTXOStatusResolver.isCoinbaseMaturing(status)) {
                    // coinbase成熟中 → 不计入可用余额
                    maturingBalance.addAndGet(value);
                } else if (UTXOStatusResolver.isCoinbaseMatured(status) || !utxo.isCoinbase()) {
                    // coinbase已成熟 或 非coinbase UTXO → 计入可用余额
                    availableBalance.addAndGet(value);
                }
            }
            // 3. 花费中 → 待支出（不计入总余额和可用余额）
            else if (UTXOStatusResolver.hasLifecycle(status, PENDING_SPENT)) {
                pendingSpend.addAndGet(value);
                // 关键修正：待支出的UTXO不计入总余额
            }
            // 4. 已花费 → 不计入总余额
            else if (UTXOStatusResolver.hasLifecycle(status, SPENT)) {
                spentBalance.addAndGet(value);
            }
            // 5. 被孤立 → 不计入总余额
            else if (UTXOStatusResolver.hasLifecycle(status, ORPHANED)) {
                orphanedBalance.addAndGet(value);
            }
            // 未知状态
            else {
                log.warn("未知的UTXO生命周期状态: {}, UTXOKey: {}",
                        UTXOStatusResolver.getLifecycleStatus(status),
                        bytesToHex(utxo.getKey()));
            }
        });

        // 4. 封装统计结果（修正不可用余额计算逻辑）
        Map<String, Object> result = new HashMap<>();
        result.put("totalBalance", totalBalance.get());
        result.put("availableBalance", availableBalance.get());
        result.put("pendingReceive", pendingReceive.get());
        result.put("pendingSpend", pendingSpend.get());
        result.put("maturingBalance", maturingBalance.get());
        result.put("spentBalance", spentBalance.get());
        result.put("orphanedBalance", orphanedBalance.get());
        // 修正：不可用余额 = 总余额 - 可用余额（此时总余额已扣除待支出）
        result.put("unavailableBalance", totalBalance.get() - availableBalance.get());

        // 日志输出统计结果
        log.info("地址[{}]余额统计 - 总余额: {}, 可用: {}, 待到账: {}, 待支出: {}, 成熟中: {}",
                address, totalBalance.get(), availableBalance.get(),
                pendingReceive.get(), pendingSpend.get(), maturingBalance.get());

        return result;
    }

}
