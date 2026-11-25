package com.bit.coin.cache;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.blockchain.ChainTipSnapshot;
import com.bit.coin.config.SystemConfig;
import com.bit.coin.database.DataBase;
import com.bit.coin.database.KeyValueHandler;
import com.bit.coin.database.rocksDb.TableEnum;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.Sha;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.bit.coin.structure.tx.UTXOStatusResolver.COINBASE_MATURED;
import static com.bit.coin.structure.tx.UTXOStatusResolver.COINBASE_MATURING;
import static com.bit.coin.structure.tx.UTXOStatusResolver.hasFlag;
import static com.bit.coin.structure.tx.UTXOStatusResolver.isSpendable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UTXOCacheStatusTest {

    @Test
    @SuppressWarnings("unchecked")
    void ordinaryOutputsAreNotCoinbaseAndBalanceRequiresMatureCoinbase() {
        DataBase dataBase = mock(DataBase.class);
        BlockChainServiceImpl chain = mock(BlockChainServiceImpl.class);
        ObjectProvider<BlockChainServiceImpl> chains = mock(ObjectProvider.class);
        ObjectProvider<TxPool> pools = mock(ObjectProvider.class);
        TxPool txPool = mock(TxPool.class);
        SystemConfig config = new SystemConfig();
        config.setNetParams("RegressionNetParams");

        byte[] publicKey = new byte[32];
        Arrays.fill(publicKey, (byte) 9);
        byte[] script = Sha.applyRIPEMD160(Sha.applySHA256(publicKey));
        UTXO ordinary = utxo(1, 11L, false, 10, script);
        UTXO immature = utxo(2, 22L, true, 99, script);
        UTXO mature = utxo(3, 33L, true, 1, script);
        List<UTXO> records = List.of(ordinary, immature, mature);

        when(chains.getObject()).thenReturn(chain);
        when(chain.getMainTipSnapshot()).thenReturn(new ChainTipSnapshot(100, new byte[32], null));
        when(chain.isStateHealthy()).thenReturn(true);
        when(pools.getObject()).thenReturn(txPool);
        when(dataBase.get(eq(TableEnum.TUTXO), any(byte[].class))).thenAnswer(invocation -> {
            byte[] key = invocation.getArgument(1);
            return records.stream()
                    .filter(utxo -> Arrays.equals(utxo.getKey(), key))
                    .map(UTXO::serialize)
                    .findFirst()
                    .orElse(null);
        });
        doAnswer(invocation -> {
            KeyValueHandler handler = invocation.getArgument(2);
            for (UTXO record : records) {
                assertTrue(handler.handle(record.getAddressKey(), new byte[0]));
            }
            return null;
        }).when(dataBase).iterateByPrefix(eq(TableEnum.ADDR_TO_UTXO), any(byte[].class), any(KeyValueHandler.class));

        UTXOCacheImpl cache = new UTXOCacheImpl();
        ReflectionTestUtils.setField(cache, "dataBase", dataBase);
        ReflectionTestUtils.setField(cache, "config", config);
        ReflectionTestUtils.setField(cache, "chains", chains);
        ReflectionTestUtils.setField(cache, "pools", pools);

        UTXO ordinaryRead = cache.getUTXO(ordinary.getKey());
        assertFalse(hasFlag(ordinaryRead.getStatus(), COINBASE_MATURED));
        assertFalse(hasFlag(ordinaryRead.getStatus(), COINBASE_MATURING));
        assertTrue(isSpendable(ordinaryRead.getStatus()));

        String address = Base58.encode(publicKey);
        Map<String, Object> balance = cache.getBalance(address);
        assertEquals(44L, balance.get("total"));
        List<UTXO> spendable = (List<UTXO>) balance.get("utxos");
        assertEquals(2, spendable.size());
        assertTrue(spendable.stream().anyMatch(utxo -> Arrays.equals(utxo.getTxId(), ordinary.getTxId())));
        assertTrue(spendable.stream().anyMatch(utxo -> Arrays.equals(utxo.getTxId(), mature.getTxId())));
        assertFalse(spendable.stream().anyMatch(utxo -> Arrays.equals(utxo.getTxId(), immature.getTxId())));
    }

    private static UTXO utxo(int id, long value, boolean coinbase, int height, byte[] script) {
        UTXO utxo = new UTXO();
        utxo.setTxId(ByteBuffer.allocate(32).putInt(28, id).array());
        utxo.setIndex(0);
        utxo.setValue(value);
        utxo.setCoinbase(coinbase);
        utxo.setHeight(height);
        utxo.setScript(script.clone());
        return utxo;
    }
}
