package com.bit.coin.cache;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ChainTipSnapshot;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.*;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.bitcoinj.base.Base58;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.bit.coin.utils.SerializeUtils.*;
import static com.bit.coin.structure.tx.UTXOStatusResolver.*;

/** 缓存已确认的 UTXO 查询结果。计算地址余额时，在持久化索引的基础上叠加交易池变动的一致视图。 */
@Component
public class UTXOCacheImpl implements UTXOCache {

    @Autowired
    private DataBase dataBase;
    @Autowired
    private SystemConfig config;
    @Autowired
    private ObjectProvider<BlockChainServiceImpl> chains;
    @Autowired
    private ObjectProvider<TxPool> pools;
    private final Cache<String, byte[]> mainCache = Caffeine.newBuilder()
            .maximumWeight(32L * 1024 * 1024)
            .weigher((String key, byte[] value) -> 256 + value.length)
            .expireAfterAccess(1, TimeUnit.HOURS).build();

    @Override
    public synchronized void invalidateAll() {
        mainCache.invalidateAll();
    }

    @Override
    public UTXO getUTXO(byte[] txId, int index) {
        return getUTXO(UTXO.getKey(txId, index));
    }

    @Override
    public UTXO getUTXOByStringKey(String key) {
        return getUTXO(hexToBytes(key));
    }

    @Override
    public synchronized UTXO getUTXO(byte[] key) {
        if (key == null || key.length != 36) {
            throw new IllegalArgumentException("Invalid outpoint");
        }
        byte[] encoded = mainCache.get(bytesToHex(key), ignored -> dataBase.get(TableEnum.TUTXO, key));
        if (encoded == null) {
            return null;
        }
        return confirmed(UTXO.deserialize(encoded));
    }

    @Override
    public synchronized void addUTXO(UTXO value) {
        mainCache.put(bytesToHex(value.getKey()), value.serialize());
    }

    @Override
    public void addPendingTx(Transaction transaction) {
        invalidateAll();
    }

    @Override
    public void revokePendingTx(Transaction transaction) {
        invalidateAll();
    }

    @Override
    public void addConfirmedTx(Transaction transaction, int height) {
        invalidateAll();
    }

    /** 按下一个候选区块的高度判断 coinbase 是否成熟，并更新查询结果的状态。 */
    private UTXO confirmed(UTXO utxo) {
        ChainTipSnapshot snapshot = chains.getObject().getMainTipSnapshot();
        int tip = snapshot == null ? 0 : snapshot.height();
        long status = setLifecycleStatus(0, CONFIRMED_UNSPENT);
        if (utxo.isCoinbase()) {
            status = (long) tip + 1 - utxo.getHeight() >= config.getParams().getCoinbaseMaturity()
                    ? setCoinbaseMatured(status)
                    : setCoinbaseMaturing(status);
        }
        utxo.setStatus(status);
        return utxo;
    }

    /** 解码地址中的 32 字节公钥，计算用于地址索引的 20 字节哈希。 */
    private static byte[] addressScript(String address) {
        if (address == null || address.length() > 64) {
            throw new IllegalArgumentException("Invalid address");
        }
        byte[] publicKey = Base58.decode(address);
        if (publicKey.length != 32) {
            throw new IllegalArgumentException("Address must encode a 32-byte public key");
        }
        return Sha.applyRIPEMD160(Sha.applySHA256(publicKey));
    }

    @Override
    public List<UTXO> getUTXOsByAddress(String address) {
        return getAddressAllUTXO(address);
    }

    @Override
    public List<UTXO> getAddressAllUTXO(String address) {
        byte[] script = addressScript(address);
        BlockChainServiceImpl chain = chains.getObject();
        synchronized (chain) {
            if (!chain.isStateHealthy()) {
                throw new IllegalStateException("Chain state unavailable");
            }
            Map<String, UTXO> result = new LinkedHashMap<>();
            dataBase.iterateByPrefix(TableEnum.ADDR_TO_UTXO, script, (key, value) -> {
                if (key.length != 56) {
                    throw new IllegalStateException("Corrupt address index");
                }
                if (result.size() >= 10_000) {
                    throw new IllegalArgumentException(
                            "Address exceeds legacy query limit; use /chain/utxos pagination");
                }
                byte[] outpoint = Arrays.copyOfRange(key, 20, 56);
                byte[] encoded = dataBase.get(TableEnum.TUTXO, outpoint);
                if (encoded == null) {
                    throw new IllegalStateException("Address index references missing UTXO");
                }
                UTXO utxo = UTXO.deserialize(encoded);
                if (!Arrays.equals(script, utxo.getScript()) || !Arrays.equals(outpoint, utxo.getKey())) {
                    throw new IllegalStateException("Inconsistent UTXO index");
                }
                result.put(bytesToHex(outpoint), confirmed(utxo));
                return true;
            });
            pools.getObject().overlayAddressUtxos(script, result);
            return List.copyOf(result.values());
        }
    }

    @Override
    public List<UTXO> getAddressAllUTXOByStatus(String address, long status) {
        return getAddressAllUTXO(address).stream()
                .filter(utxo -> matchesRequestedStatus(utxo, status))
                .toList();
    }

    /**
     * 余额查询使用 CONFIRMED_UNSPENT | COINBASE_MATURED 表示可花费余额。
     * 普通确认输出无需携带 coinbase 标志，只有 coinbase 输出需要检查成熟度。
     */
    private static boolean matchesRequestedStatus(UTXO utxo, long status) {
        if (status == (CONFIRMED_UNSPENT | COINBASE_MATURED)) {
            long lifecycle = getLifecycleStatus(utxo.getStatus());
            return lifecycle == CONFIRMED_UNSPENT
                    && (!utxo.isCoinbase() || isCoinbaseMatured(utxo.getStatus()));
        }
        return (utxo.getStatus() & status) == status;
    }

    @Override
    public Map<String, Object> getAddressAllUTXOAndTotalByStatus(String address, long status) {
        List<UTXO> utxos = getAddressAllUTXOByStatus(address, status);
        long total = 0;
        for (UTXO utxo : utxos) {
            total = Math.addExact(total, utxo.getValue());
        }
        return Map.of("total", total, "utxos", utxos);
    }

    @Override
    public Map<String, Object> getBalance(String address) {
        return getAddressAllUTXOAndTotalByStatus(address, CONFIRMED_UNSPENT | COINBASE_MATURED);
    }

    @Override
    public Map<String, Object> getUTXOsByAddressAndCount(String address) {
        return summary(address, false);
    }

    @Override
    public Map<String, Object> getUTXOsByAddressAndCountAndUTXO(String address) {
        return summary(address, true);
    }

    private Map<String, Object> summary(String address, boolean includeOutputs) {
        long available = 0, pendingReceive = 0, pendingSpend = 0, maturing = 0;
        List<UTXO> availableOutputs = new ArrayList<>(), pendingOutputs = new ArrayList<>(),
                maturingOutputs = new ArrayList<>();
        for (UTXO utxo : getAddressAllUTXO(address)) {
            long status = utxo.getStatus(), value = utxo.getValue();
            if (hasLifecycle(status, PENDING_SPENT)) {
                pendingSpend = Math.addExact(pendingSpend, value);
            } else if (hasLifecycle(status, UNCONFIRMED_OUTPUT)) {
                pendingReceive = Math.addExact(pendingReceive, value);
                pendingOutputs.add(utxo);
            } else if (isCoinbaseMaturing(status)) {
                maturing = Math.addExact(maturing, value);
                maturingOutputs.add(utxo);
            } else {
                available = Math.addExact(available, value);
                availableOutputs.add(utxo);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalBalance", Math.addExact(available, Math.addExact(pendingReceive, maturing)));
        result.put("availableBalance", available);
        result.put("pendingReceive", pendingReceive);
        result.put("pendingSpend", pendingSpend);
        result.put("maturingBalance", maturing);
        result.put("unavailableBalance", Math.addExact(pendingReceive, maturing));
        result.put("spentBalance", 0L);
        result.put("orphanedBalance", 0L);
        if (includeOutputs) {
            result.put("availableBalanceUTXOList", availableOutputs);
            result.put("pendingReceiveUTXOList", pendingOutputs);
            result.put("maturingBalanceUTXOList", maturingOutputs);
        }
        return result;
    }
}
