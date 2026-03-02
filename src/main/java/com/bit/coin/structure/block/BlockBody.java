package com.bit.coin.structure.block;

import com.bit.coin.structure.tx.Transaction;
import lombok.Data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

@Data
public class BlockBody {

    private int height;

    //交易数量
    private int txCount;

    private List<Transaction> transactions;


    /**
     * 序列化 Block 对象为字节数组
     */
    public byte[] serialize()  {
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeInt(height);
            //交易数量
            dos.writeInt(txCount);
            //读取32字节

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
    public static BlockBody deserialize(byte[] data)  {
        try {
            if (data == null || data.length == 0) {
                return null;
            }

            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);
            BlockBody blockBody = new BlockBody();

            try {
                blockBody.setHeight(dis.readInt());
                //交易数量
                blockBody.setTxCount(dis.readInt());

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
                    blockBody.setTransactions(txList);
                } else {
                    blockBody.setTransactions(new ArrayList<>());
                }

            } finally {
                dis.close();
                bais.close();
            }
            return blockBody;
        }catch (Exception e){
            throw new IllegalArgumentException("区块反序列化失败");
        }
    }



}
