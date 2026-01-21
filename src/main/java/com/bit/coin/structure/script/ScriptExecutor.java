package com.bit.coin.structure.script;

import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.BiFunction;

import static com.bit.coin.structure.tx.Transaction.SIGHASH_ALL;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;

@Slf4j
public class ScriptExecutor {

    // 执行栈
    private Deque<byte[]> stack;
    // 备用栈
    private Deque<byte[]> altStack;
    // 条件栈（用于OP_IF/OP_NOTIF）
    private Deque<Boolean> conditionStack;
    // 当前是否执行
    private boolean exec = true;
    // 脚本验证结果
    private boolean verified = false;
    // 脚本执行器
    private Script script;
    // 脚本执行位置
    private int cursor = 0;

    public ScriptExecutor() {
        this.stack = new ArrayDeque<>();
        this.altStack = new ArrayDeque<>();
        this.conditionStack = new ArrayDeque<>();
    }

    /**
     * 执行脚本
     * @param script 要执行的脚本
     * @param tx 交易（用于签名验证）
     * @param inputIndex 输入索引
     * @return 执行结果
     */
    public boolean execute(Script script, Transaction tx, int inputIndex) {
        this.script = script;
        this.cursor = 0;
        this.stack.clear();
        this.altStack.clear();
        this.conditionStack.clear();
        this.exec = true;
        this.verified = false;

        List<Script.ScriptElement> elements = script.getElements();

        try {
            while (cursor < elements.size()) {
                Script.ScriptElement element = elements.get(cursor);
                cursor++;

                if (!exec && !isConditionalOp(element)) {
                    // 当前不执行，跳过（除非是条件控制操作码）
                    continue;
                }

                if (element.isOpCode()) {
                    int opCode = element.getOpCode();
                    if (!executeOpCode(opCode, tx, inputIndex)) {
                        return false;
                    }
                } else {
                    // 数据入栈
                    byte[] data = element.getData();
                    if (data == null) {
                        push(new byte[0]);
                    } else {
                        push(data);
                    }
                }
            }

            // 检查最终结果
            if (stack.isEmpty()) {
                return false;
            }

            byte[] result = pop();
            verified = castToBool(result);
            return verified;

        } catch (ScriptExecutionException e) {
            log.error("脚本执行错误: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("脚本执行异常: ", e);
            return false;
        }
    }

    /**
     * 执行操作码
     */
    private boolean executeOpCode(int opCode, Transaction tx, int inputIndex) throws ScriptExecutionException {
        switch (opCode) {
            // 常量操作码
            case Script.OP_0:
                push(new byte[0]);
                break;
            case Script.OP_1NEGATE:
                push(encodeNumber(-1));
                break;
            case Script.OP_1:
            case Script.OP_2:
            case Script.OP_3:
            case Script.OP_4:
            case Script.OP_5:
            case Script.OP_6:
            case Script.OP_7:
            case Script.OP_8:
            case Script.OP_9:
            case Script.OP_10:
            case Script.OP_11:
            case Script.OP_12:
            case Script.OP_13:
            case Script.OP_14:
            case Script.OP_15:
            case Script.OP_16:
                push(encodeNumber(opCode - 80));  // OP_1 = 81 -> 1
                break;

            // 流程控制
            case Script.OP_IF:
            case Script.OP_NOTIF:
                return executeIf(opCode);
            case Script.OP_ELSE:
                return executeElse();
            case Script.OP_ENDIF:
                return executeEndif();
            case Script.OP_RETURN:
                return false;  // 立即失败

            // 栈操作
            case Script.OP_DUP:
                return executeDup();
            case Script.OP_DROP:
                return executeDrop();
            case Script.OP_SWAP:
                return executeSwap();
            case Script.OP_OVER:
                return executeOver();
            case Script.OP_ROT:
                return executeRot();
            case Script.OP_TUCK:
                return executeTuck();
            case Script.OP_PICK:
                return executePick();
            case Script.OP_ROLL:
                return executeRoll();
            case Script.OP_TOALTSTACK:
                return executeToAltStack();
            case Script.OP_FROMALTSTACK:
                return executeFromAltStack();
            case Script.OP_2DROP:
                return execute2Drop();
            case Script.OP_2DUP:
                return execute2Dup();
            case Script.OP_3DUP:
                return execute3Dup();
            case Script.OP_2OVER:
                return execute2Over();
            case Script.OP_2ROT:
                return execute2Rot();
            case Script.OP_2SWAP:
                return execute2Swap();
            case Script.OP_IFDUP:
                return executeIfDup();
            case Script.OP_DEPTH:
                return executeDepth();
            case Script.OP_NIP:
                return executeNip();

            // 位逻辑操作
            case Script.OP_EQUAL:
                return executeEqual();
            case Script.OP_EQUALVERIFY:
                if (executeEqual()) {
                    byte[] result = pop();
                    return castToBool(result);
                }
                return false;

            // 数值操作
            case Script.OP_1ADD:
                return execute1Add();
            case Script.OP_1SUB:
                return execute1Sub();
            case Script.OP_NEGATE:
                return executeNegate();
            case Script.OP_ABS:
                return executeAbs();
            case Script.OP_NOT:
                return executeNot();
            case Script.OP_0NOTEQUAL:
                return execute0NotEqual();
            case Script.OP_ADD:
                return executeAdd();
            case Script.OP_SUB:
                return executeSub();
            case Script.OP_MUL:
                return executeMul();
            case Script.OP_DIV:
                return executeDiv();
            case Script.OP_MOD:
                return executeMod();
            case Script.OP_LSHIFT:
                return executeLShift();
            case Script.OP_RSHIFT:
                return executeRShift();
            case Script.OP_BOOLAND:
                return executeBoolAnd();
            case Script.OP_BOOLOR:
                return executeBoolOr();
            case Script.OP_NUMEQUAL:
                return executeNumEqual();
            case Script.OP_NUMEQUALVERIFY:
                if (executeNumEqual()) {
                    byte[] result = pop();
                    return castToBool(result);
                }
                return false;
            case Script.OP_NUMNOTEQUAL:
                return executeNumNotEqual();
            case Script.OP_LESSTHAN:
                return executeLessThan();
            case Script.OP_GREATERTHAN:
                return executeGreaterThan();
            case Script.OP_LESSTHANOREQUAL:
                return executeLessThanOrEqual();
            case Script.OP_GREATERTHANOREQUAL:
                return executeGreaterThanOrEqual();
            case Script.OP_MIN:
                return executeMin();
            case Script.OP_MAX:
                return executeMax();
            case Script.OP_WITHIN:
                return executeWithin();

            // 加密操作
            case Script.OP_RIPEMD160:
                return executeRipemd160();
            case Script.OP_SHA256:
                return executeSha256();
            case Script.OP_HASH160:
                return executeHash160();
            case Script.OP_HASH256:
                return executeHash256();
            case Script.OP_CHECKSIG:
                return executeCheckSig(tx, inputIndex);
            case Script.OP_CHECKSIGVERIFY:
                if (executeCheckSig(tx, inputIndex)) {
                    byte[] result = pop();
                    return castToBool(result);
                }
                return false;
            case Script.OP_CHECKMULTISIG:
                return executeCheckMultiSig(tx, inputIndex);
            case Script.OP_CHECKMULTISIGVERIFY:
                if (executeCheckMultiSig(tx, inputIndex)) {
                    byte[] result = pop();
                    return castToBool(result);
                }
                return false;

            // 其他操作
            case Script.OP_SIZE:
                return executeSize();
            case Script.OP_CODESEPARATOR:
                // 记录代码分隔位置
                // 简化实现：记录当前cursor
                break;
            case Script.OP_CHECKLOCKTIMEVERIFY:
                return executeCheckLockTimeVerify(tx, inputIndex);
            case Script.OP_CHECKSEQUENCEVERIFY:
                return executeCheckSequenceVerify(tx, inputIndex);

            case Script.OP_NOP:
                // 无操作
                break;

            default:
                throw new ScriptExecutionException("不支持的操作码: 0x" +
                        Integer.toHexString(opCode));
        }

        return true;
    }

    // ==================== 栈操作实现 ====================

    private boolean executeDup() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_DUP");
        }
        byte[] top = stack.peek();
        push(top.clone());
        return true;
    }

    private boolean executeDrop() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_DROP");
        }
        pop();
        return true;
    }

    private boolean executeSwap() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_SWAP");
        }
        byte[] x1 = pop();
        byte[] x2 = pop();
        push(x1);
        push(x2);
        return true;
    }

    private boolean executeOver() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_OVER");
        }
        byte[] x1 = pop();
        byte[] x2 = stack.peek();
        push(x1);
        push(x2.clone());
        return true;
    }

    private boolean executeRot() throws ScriptExecutionException {
        if (stack.size() < 3) {
            throw new ScriptExecutionException("栈下溢: OP_ROT");
        }
        byte[] x1 = pop();  // 栈顶
        byte[] x2 = pop();  // 次栈顶
        byte[] x3 = pop();  // 第三
        push(x2);
        push(x1);
        push(x3);
        return true;
    }

    private boolean executeTuck() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_TUCK");
        }
        byte[] x1 = pop();
        byte[] x2 = pop();
        push(x1);
        push(x2);
        push(x1.clone());
        return true;
    }

    private boolean executePick() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_PICK");
        }
        byte[] nBytes = pop();
        int n = decodeNumber(nBytes).intValueExact();
        if (n < 0 || stack.size() <= n) {
            throw new ScriptExecutionException("无效的索引: OP_PICK");
        }
        Iterator<byte[]> iterator = stack.iterator();
        byte[] element = null;
        for (int i = 0; i <= n; i++) {
            element = iterator.next();
        }
        push(element.clone());
        return true;
    }

    private boolean executeRoll() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_ROLL");
        }
        byte[] nBytes = pop();
        int n = decodeNumber(nBytes).intValueExact();
        if (n < 0 || stack.size() <= n) {
            throw new ScriptExecutionException("无效的索引: OP_ROLL");
        }
        if (n == 0) {
            return true;  // 0表示复制栈顶
        }
        Iterator<byte[]> iterator = stack.iterator();
        byte[] element = null;
        for (int i = 0; i <= n; i++) {
            element = iterator.next();
        }
        push(element.clone());
        return true;
    }

    private boolean executeToAltStack() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_TOALTSTACK");
        }
        altStack.push(pop());
        return true;
    }

    private boolean executeFromAltStack() throws ScriptExecutionException {
        if (altStack.isEmpty()) {
            throw new ScriptExecutionException("备用栈下溢: OP_FROMALTSTACK");
        }
        push(altStack.pop());
        return true;
    }

    private boolean execute2Drop() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_2DROP");
        }
        pop();
        pop();
        return true;
    }

    private boolean execute2Dup() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_2DUP");
        }
        byte[] x1 = get(1);
        byte[] x2 = get(0);
        push(x2.clone());
        push(x1.clone());
        return true;
    }

    private boolean execute3Dup() throws ScriptExecutionException {
        if (stack.size() < 3) {
            throw new ScriptExecutionException("栈下溢: OP_3DUP");
        }
        byte[] x1 = get(2);
        byte[] x2 = get(1);
        byte[] x3 = get(0);
        push(x3.clone());
        push(x2.clone());
        push(x1.clone());
        return true;
    }

    private boolean execute2Over() throws ScriptExecutionException {
        if (stack.size() < 4) {
            throw new ScriptExecutionException("栈下溢: OP_2OVER");
        }
        byte[] x1 = get(3);
        byte[] x2 = get(2);
        push(x2.clone());
        push(x1.clone());
        return true;
    }

    private boolean execute2Rot() throws ScriptExecutionException {
        if (stack.size() < 6) {
            throw new ScriptExecutionException("栈下溢: OP_2ROT");
        }
        byte[] x1 = pop();  // 栈顶
        byte[] x2 = pop();  // 2
        byte[] x3 = pop();  // 3
        byte[] x4 = pop();  // 4
        byte[] x5 = pop();  // 5
        byte[] x6 = pop();  // 6
        push(x4);
        push(x3);
        push(x2);
        push(x1);
        push(x6);
        push(x5);
        return true;
    }

    private boolean execute2Swap() throws ScriptExecutionException {
        if (stack.size() < 4) {
            throw new ScriptExecutionException("栈下溢: OP_2SWAP");
        }
        byte[] x1 = pop();
        byte[] x2 = pop();
        byte[] x3 = pop();
        byte[] x4 = pop();
        push(x2);
        push(x1);
        push(x4);
        push(x3);
        return true;
    }

    private boolean executeIfDup() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_IFDUP");
        }
        byte[] top = stack.peek();
        if (castToBool(top)) {
            push(top.clone());
        }
        return true;
    }

    private boolean executeDepth() {
        push(encodeNumber(stack.size()));
        return true;
    }

    private boolean executeNip() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_NIP");
        }
        byte[] top = pop();
        pop();
        push(top);
        return true;
    }

    // ==================== 位逻辑操作实现 ====================

    private boolean executeEqual() throws ScriptExecutionException {
        log.info("执行是否相等");
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_EQUAL");
        }
        byte[] x1 = pop();
        byte[] x2 = pop();
        push(encodeNumber(Arrays.equals(x1, x2) ? 1 : 0));
        return true;
    }

    // ==================== 数值操作实现 ====================

    private boolean execute1Add() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_1ADD");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.add(BigInteger.ONE)));
        return true;
    }

    private boolean execute1Sub() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_1SUB");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.subtract(BigInteger.ONE)));
        return true;
    }

    private boolean executeNegate() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_NEGATE");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.negate()));
        return true;
    }

    private boolean executeAbs() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_ABS");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.abs()));
        return true;
    }

    private boolean executeNot() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_NOT");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.equals(BigInteger.ZERO) ? 1 : 0));
        return true;
    }

    private boolean execute0NotEqual() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_0NOTEQUAL");
        }
        byte[] num = pop();
        BigInteger n = decodeNumber(num);
        push(encodeNumber(n.equals(BigInteger.ZERO) ? 0 : 1));
        return true;
    }

    private boolean executeAdd() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_ADD");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(a.add(b)));
        return true;
    }

    private boolean executeSub() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_SUB");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(b.subtract(a)));
        return true;
    }

    private boolean executeMul() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_MUL");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(a.multiply(b)));
        return true;
    }

    private boolean executeDiv() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_DIV");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        if (a.equals(BigInteger.ZERO)) {
            throw new ScriptExecutionException("除零错误: OP_DIV");
        }
        push(encodeNumber(b.divide(a)));
        return true;
    }

    private boolean executeMod() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_MOD");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        if (a.equals(BigInteger.ZERO)) {
            throw new ScriptExecutionException("模零错误: OP_MOD");
        }
        push(encodeNumber(b.mod(a.abs())));
        return true;
    }

    private boolean executeLShift() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_LSHIFT");
        }
        byte[] shiftBytes = pop();
        byte[] numBytes = pop();
        BigInteger shift = decodeNumber(shiftBytes);
        BigInteger num = decodeNumber(numBytes);
        if (shift.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ScriptExecutionException("移位过大: OP_LSHIFT");
        }
        push(encodeNumber(num.shiftLeft(shift.intValue())));
        return true;
    }

    private boolean executeRShift() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_RSHIFT");
        }
        byte[] shiftBytes = pop();
        byte[] numBytes = pop();
        BigInteger shift = decodeNumber(shiftBytes);
        BigInteger num = decodeNumber(numBytes);
        if (shift.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ScriptExecutionException("移位过大: OP_RSHIFT");
        }
        push(encodeNumber(num.shiftRight(shift.intValue())));
        return true;
    }

    private boolean executeBoolAnd() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_BOOLAND");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        boolean a = castToBool(aBytes);
        boolean b = castToBool(bBytes);
        push(encodeNumber(a && b ? 1 : 0));
        return true;
    }

    private boolean executeBoolOr() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_BOOLOR");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        boolean a = castToBool(aBytes);
        boolean b = castToBool(bBytes);
        push(encodeNumber(a || b ? 1 : 0));
        return true;
    }

    private boolean executeNumEqual() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_NUMEQUAL");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(a.equals(b) ? 1 : 0));
        return true;
    }

    private boolean executeNumNotEqual() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_NUMNOTEQUAL");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(!a.equals(b) ? 1 : 0));
        return true;
    }

    private boolean executeLessThan() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_LESSTHAN");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(b.compareTo(a) < 0 ? 1 : 0));
        return true;
    }

    private boolean executeGreaterThan() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_GREATERTHAN");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(b.compareTo(a) > 0 ? 1 : 0));
        return true;
    }

    private boolean executeLessThanOrEqual() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_LESSTHANOREQUAL");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(b.compareTo(a) <= 0 ? 1 : 0));
        return true;
    }

    private boolean executeGreaterThanOrEqual() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_GREATERTHANOREQUAL");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(b.compareTo(a) >= 0 ? 1 : 0));
        return true;
    }

    private boolean executeMin() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_MIN");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(a.compareTo(b) < 0 ? a : b));
        return true;
    }

    private boolean executeMax() throws ScriptExecutionException {
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_MAX");
        }
        byte[] aBytes = pop();
        byte[] bBytes = pop();
        BigInteger a = decodeNumber(aBytes);
        BigInteger b = decodeNumber(bBytes);
        push(encodeNumber(a.compareTo(b) > 0 ? a : b));
        return true;
    }

    private boolean executeWithin() throws ScriptExecutionException {
        if (stack.size() < 3) {
            throw new ScriptExecutionException("栈下溢: OP_WITHIN");
        }
        byte[] maxBytes = pop();
        byte[] minBytes = pop();
        byte[] xBytes = pop();
        BigInteger max = decodeNumber(maxBytes);
        BigInteger min = decodeNumber(minBytes);
        BigInteger x = decodeNumber(xBytes);
        boolean result = x.compareTo(min) >= 0 && x.compareTo(max) < 0;
        push(encodeNumber(result ? 1 : 0));
        return true;
    }

    // ==================== 加密操作实现 ====================

    private boolean executeRipemd160() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_RIPEMD160");
        }
        byte[] data = pop();
        byte[] hash = Sha.applyRIPEMD160(data);
        push(hash);
        return true;
    }

    private boolean executeSha256() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_SHA256");
        }
        byte[] data = pop();
        byte[] hash = Sha.applySHA256(data);
        push(hash);
        return true;
    }

    private boolean executeHash160() throws ScriptExecutionException {
        log.info("对值进行Hash160");
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_HASH160");
        }
        byte[] data = pop();
        byte[] hash = Sha.applyRIPEMD160(Sha.applySHA256(data));
        push(hash);
        return true;
    }

    private boolean executeHash256() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_HASH256");
        }
        byte[] data = pop();
        byte[] hash = Sha.applySHA256(data);
        push(hash);
        return true;
    }

    private boolean executeSize() throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_SIZE");
        }
        byte[] data = stack.peek();
        push(encodeNumber(data.length));
        return true;
    }

    private boolean executeCheckSig(Transaction tx, int inputIndex) throws ScriptExecutionException {
        log.info("检查签名");
        if (stack.size() < 2) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKSIG");
        }
        // 弹出公钥和签名
        byte[] pubKeyBytes = pop();//[32字节]
        byte[] signatureBytes = pop();//[64字节][1字节类型]
        log.info("弹出公钥{} 长度{}",bytesToHex(pubKeyBytes),pubKeyBytes.length);
        log.info("弹出签名{} 长度{}",bytesToHex(signatureBytes),signatureBytes.length);
        try {
            byte[] signature = Arrays.copyOfRange(signatureBytes, 0, signatureBytes.length - 1);
            log.info("签名{} 长度{}",bytesToHex(signature),signature.length);
            //签名类型
            byte signatureType = signatureBytes[signatureBytes.length - 1];
            log.info("签名类型{}",signatureType);

            // 计算签名哈希
            byte[] sigHashBytes = tx.calculateSigHash(inputIndex);

            // 验证签名
            boolean result = Ed25519Signer.fastVerify(pubKeyBytes,sigHashBytes, signature);
            push(encodeNumber(result ? 1 : 0));
            return true;
        } catch (Exception e) {
            log.error("签名验证失败: ", e);
            push(encodeNumber(0));
            return true;  // 验证失败但不终止执行
        }
    }

    private boolean executeCheckMultiSig(Transaction tx, int inputIndex) throws ScriptExecutionException {
        if (stack.size() < 1) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKMULTISIG");
        }

        // 获取公钥数量
        byte[] nBytes = pop();
        int n = decodeNumber(nBytes).intValueExact();
        if (n < 0 || n > 20) {  // 比特币限制最多20个公钥
            throw new ScriptExecutionException("公钥数量无效: " + n);
        }

        // 获取公钥
        List<byte[]> pubKeys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (stack.isEmpty()) {
                throw new ScriptExecutionException("栈下溢: 公钥数量不足");
            }
            pubKeys.add(pop());
        }

        // 获取签名数量
        byte[] mBytes = pop();
        int m = decodeNumber(mBytes).intValueExact();
        if (m < 0 || m > n) {
            throw new ScriptExecutionException("签名数量无效: " + m);
        }

        // 获取签名
        List<byte[]> signatures = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (stack.isEmpty()) {
                throw new ScriptExecutionException("栈下溢: 签名数量不足");
            }
            signatures.add(pop());
        }

        // 弹出额外的无用字节（由于历史原因）
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: 缺少额外字节");
        }
        pop();

        // 验证签名
        int successCount = 0;
        int sigIndex = 0;
        int pubKeyIndex = 0;

        while (sigIndex < m && pubKeyIndex < n) {
            byte[] sigBytes = signatures.get(sigIndex);
            byte[] pubKey = pubKeys.get(pubKeyIndex);

            try {
                // 分离签名和哈希类型
                int sigHash = sigBytes[sigBytes.length - 1] & 0xFF;
                byte[] signature = Arrays.copyOfRange(sigBytes, 0, sigBytes.length - 1);

                // 计算签名哈希
                byte[] sigHashBytes = tx.calculateSigHash(inputIndex);

                // 验证签名
                if (Ed25519Signer.fastVerify(pubKey,sigHashBytes, signature)) {
                    successCount++;
                    sigIndex++;
                }
                pubKeyIndex++;

            } catch (Exception e) {
                pubKeyIndex++;
            }
        }

        boolean result = (successCount == m);
        push(encodeNumber(result ? 1 : 0));
        return true;
    }

    // ==================== 流程控制实现 ====================

    private boolean executeIf(int opCode) throws ScriptExecutionException {
        if (!exec) {
            // 当前不执行，跳过整个块
            conditionStack.push(false);
            return true;
        }

        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_IF/OP_NOTIF");
        }

        byte[] condition = pop();
        boolean result = castToBool(condition);
        if (opCode == Script.OP_NOTIF) {
            result = !result;
        }

        conditionStack.push(result);
        exec = result;
        return true;
    }

    private boolean executeElse() {
        if (conditionStack.isEmpty()) {
            return false;  // OP_ELSE没有对应的OP_IF
        }

        boolean lastCondition = conditionStack.pop();
        boolean newCondition = !lastCondition && exec;
        conditionStack.push(newCondition);
        exec = newCondition;
        return true;
    }

    private boolean executeEndif() {
        if (conditionStack.isEmpty()) {
            return false;  // OP_ENDIF没有对应的OP_IF
        }

        conditionStack.pop();
        if (conditionStack.isEmpty()) {
            exec = true;  // 回到顶层
        } else {
            // 恢复上一层的执行状态
            exec = conditionStack.peek();
        }
        return true;
    }

    // ==================== 时间锁定实现 ====================

    private boolean executeCheckLockTimeVerify(Transaction tx, int inputIndex)
            throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKLOCKTIMEVERIFY");
        }

        byte[] lockTimeBytes = pop();
        long lockTime = decodeNumber(lockTimeBytes).longValueExact();

        // 获取交易锁定时间
        long txLockTime = tx.getLockTime();

        // 检查锁定时间
        boolean result = verifyLockTime(txLockTime, lockTime, tx.getInputs().get(inputIndex));
        push(encodeNumber(result ? 1 : 0));
        return true;
    }

    private boolean executeCheckSequenceVerify(Transaction tx, int inputIndex)
            throws ScriptExecutionException {
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKSEQUENCEVERIFY");
        }

        byte[] sequenceBytes = pop();
        long sequence = decodeNumber(sequenceBytes).longValueExact();

        // 获取输入序列号
        long inputSequence = tx.getInputs().get(inputIndex).getSequence();

        // 检查序列号
        boolean result = verifySequence(inputSequence, sequence);
        push(encodeNumber(result ? 1 : 0));
        return true;
    }

    // ==================== 工具方法 ====================

    private void push(byte[] data) {
        stack.push(data);
    }

    private byte[] pop() {
        return stack.pop();
    }

    private byte[] get(int index) {
        Iterator<byte[]> iterator = stack.iterator();
        for (int i = 0; i < index; i++) {
            iterator.next();
        }
        return iterator.next();
    }

    private static boolean castToBool(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        for (int i = 0; i < data.length; i++) {
            if (data[i] != 0) {
                if (i == data.length - 1 && data[i] == 0x80) {
                    return false;  // 负零
                }
                return true;
            }
        }
        return false;
    }

    private static byte[] encodeNumber(long n) {
        return encodeNumber(BigInteger.valueOf(n));
    }

    private static byte[] encodeNumber(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return new byte[0];
        }

        boolean negative = n.compareTo(BigInteger.ZERO) < 0;
        BigInteger absN = n.abs();

        // 转换为小端字节数组
        byte[] bytes = absN.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 1)
                .order(ByteOrder.LITTLE_ENDIAN);

        // 反转字节序
        for (int i = bytes.length - 1; i >= 0; i--) {
            buffer.put(bytes[i]);
        }

        // 添加符号位
        if ((buffer.get(buffer.position() - 1) & 0x80) != 0) {
            buffer.put((byte)(negative ? 0x80 : 0x00));
        } else if (negative) {
            buffer.put(buffer.position() - 1, (byte)(buffer.get(buffer.position() - 1) | 0x80));
        }

        byte[] result = new byte[buffer.position()];
        buffer.rewind();
        buffer.get(result);
        return result;
    }

    private static BigInteger decodeNumber(byte[] data) {
        if (data == null || data.length == 0) {
            return BigInteger.ZERO;
        }

        // 复制并反转字节序
        byte[] reversed = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            reversed[data.length - 1 - i] = data[i];
        }

        // 检查符号位
        boolean negative = (reversed[0] & 0x80) != 0;
        if (negative) {
            reversed[0] &= 0x7F;  // 清除符号位
        }

        BigInteger result = new BigInteger(1, reversed);
        if (negative) {
            result = result.negate();
        }

        return result;
    }

    private static boolean isConditionalOp(Script.ScriptElement element) {
        if (!element.isOpCode()) {
            return false;
        }
        int opCode = element.getOpCode();
        return opCode == Script.OP_IF ||
                opCode == Script.OP_NOTIF ||
                opCode == Script.OP_ELSE ||
                opCode == Script.OP_ENDIF;
    }

    private boolean verifyLockTime(long txLockTime, long lockTime, TxInput input) {
        // 实现锁定时间验证逻辑
        // 这里简化实现，实际需要检查锁定时间类型和序列号
        return txLockTime >= lockTime;
    }

    private boolean verifySequence(long inputSequence, long sequence) {
        // 实现序列号验证逻辑
        // 这里简化实现
        return (inputSequence & 0xFFFF) >= (sequence & 0xFFFF);
    }

    // ==================== 公共方法 ====================

    /**
     * 验证P2PKH脚本
     */
    public static boolean verifyP2PKH(byte[] signature, byte[] pubKey, byte[] pubKeyHash,
                                      Transaction tx, int inputIndex, long inputValue) {
        try {
            // 创建解锁脚本
            Script unlockingScript = new Script();
            unlockingScript.addData(signature);
            unlockingScript.addData(pubKey);

            // 创建锁定脚本
            Script lockingScript = new Script();
            lockingScript.addOpCode(Script.OP_DUP);
            lockingScript.addOpCode(Script.OP_HASH160);
            lockingScript.addData(pubKeyHash);
            lockingScript.addOpCode(Script.OP_EQUALVERIFY);
            lockingScript.addOpCode(Script.OP_CHECKSIG);

            // 合并脚本
            Script combinedScript = combineScripts(unlockingScript, lockingScript);

            // 执行脚本
            ScriptExecutor executor = new ScriptExecutor();
            return executor.execute(combinedScript, tx, inputIndex);

        } catch (Exception e) {
            log.error("P2PKH验证失败: ", e);
            return false;
        }
    }

    /**
     * 验证P2SH脚本
     */
    public static boolean verifyP2SH(byte[] redeemScript, byte[][] signatures,
                                     byte[][] pubKeys, Transaction tx,
                                     int inputIndex, long inputValue) {
        try {
            // 创建解锁脚本
            Script unlockingScript = new Script();
            unlockingScript.addOpCode(Script.OP_0);  // 多签的额外字节
            for (byte[] signature : signatures) {
                unlockingScript.addData(signature);
            }
            unlockingScript.addData(redeemScript);

            // 创建锁定脚本
            Script lockingScript = new Script();
            lockingScript.addOpCode(Script.OP_HASH160);
            lockingScript.addData(Sha.applyRIPEMD160(redeemScript));
            lockingScript.addOpCode(Script.OP_EQUAL);

            // 合并脚本
            Script combinedScript = combineScripts(unlockingScript, lockingScript);

            // 执行脚本
            ScriptExecutor executor = new ScriptExecutor();
            return executor.execute(combinedScript, tx, inputIndex);

        } catch (Exception e) {
            log.error("P2SH验证失败: ", e);
            return false;
        }
    }

    /**
     * 验证多签脚本
     */
    public static boolean verifyMultiSig(int m, byte[][] pubKeys, byte[][] signatures,
                                         Transaction tx, int inputIndex, long inputValue) {
        try {
            // 创建解锁脚本
            Script unlockingScript = new Script();
            unlockingScript.addOpCode(Script.OP_0);  // 额外的无用字节
            for (byte[] signature : signatures) {
                unlockingScript.addData(signature);
            }

            // 创建锁定脚本
            Script lockingScript = new Script();
            lockingScript.addSmallInteger(m);
            for (byte[] pubKey : pubKeys) {
                lockingScript.addData(pubKey);
            }
            lockingScript.addSmallInteger(pubKeys.length);
            lockingScript.addOpCode(Script.OP_CHECKMULTISIG);

            // 合并脚本
            Script combinedScript = combineScripts(unlockingScript, lockingScript);

            // 执行脚本
            ScriptExecutor executor = new ScriptExecutor();
            return executor.execute(combinedScript, tx, inputIndex);

        } catch (Exception e) {
            log.error("多签验证失败: ", e);
            return false;
        }
    }

    /**
     * 合并解锁脚本和锁定脚本
     */
    public static Script combineScripts(Script unlockingScript, Script lockingScript) {
        List<Script.ScriptElement> combined = new ArrayList<>();
        combined.addAll(unlockingScript.getElements());
        combined.addAll(lockingScript.getElements());
        return new Script(combined);
    }

    /**
     * 获取栈内容（用于调试）
     */
    public List<byte[]> getStack() {
        return new ArrayList<>(stack);
    }

    /**
     * 获取备用栈内容（用于调试）
     */
    public List<byte[]> getAltStack() {
        return new ArrayList<>(altStack);
    }

    public boolean isVerified() {
        return verified;
    }



    //{
    //    "txId": "7cf155bd127aed86c6208b2af0afa20007af717728215613901a2e04a11c993c",
    //    "version": 1,
    //    "lockTime": 0,
    //    "inputs": [
    //        {
    //            "txId": "06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd",
    //            "index": 0,
    //            "sequence": "4294967295",
    //            "scriptSig": "45d5a3aaa5c4eb767f82785b35da1e16f968be6029df82cb47e763564dc9e0c0e2871d9946065a228972f981fb0fdc76759d16626ee1b27b9964fe8156e3b40f01dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1",
    //            "output": {
    //                "value": 5000000000,
    //                "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //            }
    //        },
    //        {
    //            "txId": "e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d",
    //            "index": 0,
    //            "sequence": "4294967295",
    //            "scriptSig": "19954135d76a96e408dd11a3ae8814a5ff1fc816c9fc0996f50534d3e76c5aad42d046a125a4d9a379c28a2b77e3f2312ff39c927e7f792ac5c30e81c3df490101dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1",
    //            "output": {
    //                "value": 5000000000,
    //                "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //            }
    //        }
    //    ],
    //    "outputs": [
    //        {
    //            "value": 5000000000,
    //            "scriptPubKey": "76934d8111b97796af8d22443628399ebb612124"
    //        },
    //        {
    //            "value": 4999995780,
    //            "scriptPubKey": "a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"
    //        }
    //    ],
    //    "status": 2130303778818,
    //    "statusStr": "交易池 | 已验证 | 签名验证 | 脚本验证 | 输入验证 | 输出验证"
    //}

    // ========== 新增main方法 ==========
    public static void main(String[] args) {
        try {

            // ========== 步骤1：生成Ed25519密钥对 ==========
            log.info("===== 1. 生成Ed25519密钥对 =====");

            byte[] privateKey = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
            byte[] publicKey = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");
            byte[] pubKeyHash = Sha.applyRIPEMD160(Sha.applySHA256(publicKey)); // 公钥哈希 = RIPEMD160(SHA256(公钥))

            log.info("私钥: {}", SerializeUtils.bytesToHex(privateKey));
            log.info("公钥: {}", SerializeUtils.bytesToHex(publicKey));
            log.info("公钥哈希: {}", SerializeUtils.bytesToHex(pubKeyHash));

            // ========== 步骤2：创建测试交易 ==========
            log.info("\n===== 2. 创建测试交易 =====");
            Transaction tx = new Transaction();

            // 添加交易输入
            TxInput txInput1 = new TxInput();
            txInput1.setTxId(hexToBytes("06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd")); // 前序交易哈希（测试用空值）
            txInput1.setIndex(0);
            txInput1.setSequence(0xFFFFFFFFL);

            TxInput txInput2 = new TxInput();
            txInput2.setTxId(hexToBytes("e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d")); // 前序交易哈希（测试用空值）
            txInput2.setIndex(0);
            txInput2.setSequence(0xFFFFFFFFL);

            ArrayList<TxInput> txInputs = new ArrayList<>();
            txInputs.add(txInput1);
            txInputs.add(txInput2);
            tx.setInputs(txInputs);


            // 添加交易输出
            TxOutput txOutput1 = new TxOutput();
            txOutput1.setValue(5000000000L); // 输出金额（单位：satoshi）
            txOutput1.setScriptPubKey(hexToBytes("76934d8111b97796af8d22443628399ebb612124")); // 锁定脚本后续构造
            TxOutput txOutput2 = new TxOutput();
            txOutput2.setValue(4999995780L); // 输出金额（单位：satoshi）
            txOutput2.setScriptPubKey(hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56")); // 锁定脚本后续构造
            ArrayList<TxOutput> txOutputs = new ArrayList<>();
            txOutputs.add(txOutput1);
            txOutputs.add(txOutput2);
            tx.setOutputs(txOutputs);
            tx.setVersion(1);
            tx.setLockTime(0);


            //根据模拟交易构建UTXOMap
            Map<String, UTXO> utxoMap = new HashMap<>();
            String txId1 = "06071a12ea4acf1b977fdf0c13c4adc7d65eb6cc5bb6f931fcaddb15e73df1cd";
            int index1 = 0;
            long value1 = 5000000000L; // 注意加L避免整数溢出（50亿satoshi）
            byte[] scriptPubKey1 = hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56");
            UTXO utxo1 = new UTXO();
            utxo1.setTxId(hexToBytes(txId1));          // 前序交易ID
            utxo1.setIndex(index1);        // 前序交易输出索引
            utxo1.setValue(value1);        // 输出金额（satoshi）
            utxo1.setScript(scriptPubKey1); // 锁定脚本
           // UTXO唯一标识：交易ID + 输出索引（核心键规则）
            byte[] key = UTXO.getKey(hexToBytes(txId1), index1);
            utxoMap.put(bytesToHex(key), utxo1);

            // 2. 构建第二个UTXO（对应模拟交易第二个输入的前序输出）
            // 注意：原JSON中该txId疑似少1位，按原始内容处理
            String txId2 = "e9e11fb89505f0efb292316cb54d504d3ff9a26777c997c6469d7ae097b8fb2d";
            int index2 = 0;
            long value2 = 5000000000L;
            byte[] scriptPubKey2 = hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56");
            UTXO utxo2 = new UTXO();
            utxo2.setTxId(hexToBytes(txId2));
            utxo2.setIndex(index2);
            utxo2.setValue(value2);
            utxo2.setScript(scriptPubKey2);
            byte[] utxoKey2 = UTXO.getKey(hexToBytes(txId2), index2);
            utxoMap.put(bytesToHex(utxoKey2), utxo2);
            tx.setUtxoMap(utxoMap);


            // ========== 步骤3：签名交易 ==========
            log.info("\n===== 3. 对交易进行签名 =====");
            byte[] sigHash = tx.prepareDataForSigning(0, hexToBytes("a57f11a1cbb1d0a7841b5b1c374fbc46f42f8c56"), SIGHASH_ALL);
            byte[] signature = Ed25519Signer.fastSign(privateKey, Sha.applySHA256(sigHash));



            log.info("签名哈希: {}", SerializeUtils.bytesToHex(sigHash));
            log.info("交易签名: {}", SerializeUtils.bytesToHex(signature));

            //实现在签名signature的后面加上1字节的SIGHASH_ALL
            byte[] signatureWithHashType = new byte[signature.length + 1];
            System.arraycopy(signature, 0, signatureWithHashType, 0, signature.length);
            signatureWithHashType[signature.length] = SIGHASH_ALL; // 追加0x01


            // ========== 步骤4：构造P2PKH脚本 ==========
            log.info("\n===== 4. 构造P2PKH解锁/锁定脚本 =====");
            // 解锁脚本：[签名] [公钥]
            Script unlockingScript = new Script();
            unlockingScript.addData(signatureWithHashType);
            unlockingScript.addData(publicKey);

            // 锁定脚本：OP_DUP OP_HASH160 [公钥哈希] OP_EQUALVERIFY OP_CHECKSIG
            Script lockingScript = new Script();
            lockingScript.addOpCode(Script.OP_DUP);
            lockingScript.addOpCode(Script.OP_HASH160);
            lockingScript.addData(pubKeyHash);
            lockingScript.addOpCode(Script.OP_EQUALVERIFY);
            lockingScript.addOpCode(Script.OP_CHECKSIG);

            log.info("解锁脚本: {}", unlockingScript.toString());
            log.info("锁定脚本: {}", lockingScript.toString());
            log.info("解锁脚本十六进制: {}", unlockingScript.toHex());
            log.info("锁定脚本十六进制: {}", lockingScript.toHex());

            // ========== 步骤5：合并脚本并执行验证 ==========
            log.info("\n===== 5. 执行脚本验证 =====");
            // 合并解锁脚本 + 锁定脚本
            Script combinedScript = ScriptExecutor.combineScripts(unlockingScript, lockingScript);
            log.info("合并后脚本: {}", combinedScript.toString());

            // 创建脚本执行器并验证
            ScriptExecutor executor = new ScriptExecutor();
            boolean verifyResult = executor.execute(
                    combinedScript,
                    tx,
                    0
            );
            //验证结果
            log.info("验证结果: {}", verifyResult);

        } catch (Exception e) {
            log.error("脚本验证过程出错: ", e);
        }
    }
}

