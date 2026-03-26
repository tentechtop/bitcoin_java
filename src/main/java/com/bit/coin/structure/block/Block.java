package com.bit.coin.structure.block;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.HexByteArraySerializer;
import com.bit.coin.utils.UInt256;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.annotation.JsonSerialize;


import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.bit.coin.utils.SerializeUtils.bytesToHex;

@JsonPropertyOrder({
        "hash",           // 区块哈希
        "height",         // 区块高度
        "blockHeader",    // 区块头
        "chainWorkDecimal",
        "txCount",
        "transactions",    // 交易列表

})
@Slf4j
@Data
public class Block {
    //非核心不参与序列化
    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] hash;
    private int height;
    @JsonIgnore
    private int size;
    //核心
    @JsonUnwrapped//输出json时去掉类属性
    private BlockHeader blockHeader;
    //交易数量
    private int txCount;

    @JsonIgnore
    private UInt256 chainWork = UInt256.fromLong(0L);

    private List<Transaction> transactions;

    @JsonIgnore
    private long medianTime;



    public BigInteger getChainWorkDecimal(){
        if (chainWork==null){
            chainWork = UInt256.fromLong(0L);
        }
        return chainWork.toBigInteger();
    }


    public byte[] getHash(){
        if (hash==null){
            hash = blockHeader.calculateHash();
        }
        return hash;
    }


    //获取区块中全部的交易输出
    @JsonIgnore
    public List<UTXO> getUTXOs(){
        List<UTXO> utxos = new ArrayList<>();
        for (Transaction tx : transactions) {
            for (int i = 0; i < tx.getOutputs().size(); i++) {
                boolean coinbase = tx.isCoinbase();
                TxOutput txOutput = tx.getOutputs().get(i);
                UTXO utxo = new UTXO();
                utxo.setCoinbase(coinbase);
                utxo.setTxId(tx.getTxId());
                utxo.setHeight(height);
                utxo.setValue(txOutput.getValue());
                utxo.setScript(txOutput.getScriptPubKey());
                utxo.setIndex(i);
                utxos.add(utxo);
            }
        }
        return utxos;
    }

    /**
     * 获取参与交易的UTXO
     * @return
     */
    @JsonIgnore
    public List<UTXO> getSpentUTXOKeys(){
        List<UTXO> utxos = new ArrayList<>();
        for (Transaction tx : transactions) {
            for (int i = 0; i < tx.getInputs().size(); i++) {
                boolean coinbase = tx.isCoinbase();
                if (!coinbase){
                    Map<String, UTXO> utxoMap = tx.getUtxoMap();
                    TxInput txInput = tx.getInputs().get(i);
                    int index = txInput.getIndex();
                    byte[] txId = txInput.getTxId();
                    byte[] key = UTXO.getKey(txId, index);
                    UTXO utxo = utxoMap.get(bytesToHex(key));
                    utxos.add(utxo);
                }
            }
        }
        return utxos;
    }







    //序列化反序列化
    /**
     * 序列化 Block 对象为字节数组
     */
    public byte[] serialize()  {
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // 序列化 BlockHeader
            byte[] headerBytes = blockHeader.serialize();
            dos.write(headerBytes);
            dos.writeInt(height);
            //交易数量
            dos.writeInt(txCount);
            //读取32字节

            //工作总量
            dos.write(chainWork.toBytes());

            // 序列化 Transaction 列表
            dos.writeInt(transactions != null ? transactions.size() : 0);
            if (transactions != null && !transactions.isEmpty()) {
                for (Transaction tx : transactions) {
                    byte[] txBytes = tx.serialize();
                    dos.writeInt(txBytes.length);
                    dos.write(txBytes);
                }
            }

            dos.flush();
            byte[] result = baos.toByteArray();
            dos.close();
            baos.close();

            return result;
        }catch (Exception e){
            throw new  IllegalArgumentException("区块序列化失败",e);
        }
    }

    /**
     * 从字节数组反序列化为 Block 对象
     */
    public static Block deserialize(byte[] data)  {
        try {
            if (data == null || data.length == 0) {
                return null;
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            Block block = new Block();

            try {
                // 反序列化 BlockHeader
                byte[] headerBytes = new byte[80];
                dis.readFully(headerBytes);
                block.setBlockHeader(BlockHeader.deserialize(headerBytes));

                block.setHeight(dis.readInt());
                //交易数量
                block.setTxCount(dis.readInt());

                //工作总量
                block.setChainWork(UInt256.fromBytes(dis.readNBytes(32)));

                // 反序列化 Transaction 列表
                int txCount = dis.readInt();
                if (txCount > 0) {
                    List<Transaction> txList = new ArrayList<>(txCount);
                    for (int i = 0; i < txCount; i++) {
                        int txLength = dis.readInt();
                        byte[] txBytes = new byte[txLength];
                        dis.readFully(txBytes);
                        Transaction tx = Transaction.deserialize(txBytes);
                        txList.add(tx);
                    }
                    block.setTransactions(txList);
                } else {
                    block.setTransactions(new ArrayList<>());
                }

            } finally {
                dis.close();
                bais.close();
            }
            return block;
        }catch (Exception e){
            throw new IllegalArgumentException("区块反序列化失败");
        }
    }


    //根据区块头和区块体组合成完整区块
    public static Block buildBlock(BlockHeader blockHeader,BlockBody blockBody) {
        Block block = new Block();
        block.setBlockHeader(blockHeader);
        block.setTransactions(blockBody.getTransactions());
        block.setHeight(blockBody.getHeight());
        block.setTxCount(blockBody.getTxCount());
        return block;
    }

    @JsonIgnore
    public BlockBody getBlockBody(){
        BlockBody blockBody = new BlockBody();
        blockBody.setTransactions(transactions);
        blockBody.setHeight(height);
        blockBody.setTxCount(txCount);
        return blockBody;
    }

}
