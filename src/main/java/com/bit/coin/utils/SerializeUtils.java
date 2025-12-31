package com.bit.coin.utils;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.interfaces.ECKey;

public class SerializeUtils {
    /**
     * 将 int 以小端字节序写入 DataOutputStream。
     *
     * @param dos 输出流
     * @param value 要写入的 int 值
     * @throws IOException 如果发生I/O错误
     */
    public static void writeLittleEndianInt(DataOutputStream dos, int value) throws IOException {
        dos.write(ByteBuffer.allocate(4).putInt(value).array());
    }

    /**
     * 将一个整数作为可变长度整数 (VarInt) 写入流中。
     *
     * @param dos 输出流
     * @param value 要写入的整数值
     * @throws IOException 如果发生I/O错误
     */
    public static void writeVarInt(DataOutputStream dos, int value) throws IOException {
        if (value < 0xFD) {
            dos.write(value);
        } else if (value <= 0xFFFF) {
            dos.write(0xFD);
            dos.writeShort(Short.reverseBytes((short) value));
        } else {
            dos.write(0xFE);
            dos.writeInt(Integer.reverseBytes(value));
        }
    }





}
