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
            byte[] sigHashBytes = tx.calculateSigHash(inputIndex,signatureType);

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
        log.info("执行多签（支持乱序匹配版本）");
        // ========== 步骤1: 弹出总公钥数量 n (OP_n) ==========
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKMULTISIG - 缺少总公钥数量n");
        }
        byte[] nBytes = pop();
        int n = decodeNumber(nBytes).intValueExact();
        log.info("读取总公钥数量n: {}", n);

        // 校验n的合法范围（比特币规范0-20）
        if (n < 0 || n > 20) {
            throw new ScriptExecutionException("总公钥数量无效: " + n + " (合法范围0-20)");
        }

        // ========== 步骤2: 弹出 n 个公钥（栈是LIFO，弹出顺序是pubn→pub1，需反转） ==========
        List<byte[]> pubKeys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (stack.isEmpty()) {
                throw new ScriptExecutionException("栈下溢: OP_CHECKMULTISIG - 公钥数量不足，预期" + n + "个，已读取" + i + "个");
            }
            pubKeys.add(pop());
            //打印公钥
            log.info("读取公钥{} 长度{}", bytesToHex(pubKeys.get(i)), pubKeys.get(i).length);
        }

        // ========== 步骤3: 弹出需要的签名数量 m (OP_m) ==========
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_CHECKMULTISIG - 缺少需要的签名数量m");
        }
        byte[] mBytes = pop();
        int m = decodeNumber(mBytes).intValueExact();
        log.info("读取需要的签名数量m: {}", m);

        // 校验m的合法性
        if (m < 0 || m > n || m > 20) {
            throw new ScriptExecutionException(
                    String.format("签名数量无效: m=%d (需满足 0≤m≤n=%d 且 m≤20)", m, n)
            );
        }

        // ========== 步骤4: 弹出 m 个签名（栈是LIFO，弹出顺序是sigm→sig1，需反转） ==========
        List<byte[]> signatures = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (stack.isEmpty()) {
                throw new ScriptExecutionException("栈下溢: OP_CHECKMULTISIG - 签名数量不足，预期" + m + "个，已读取" + i + "个");
            }
            signatures.add(pop());
            //打印签名
            log.info("读取签名{} 长度{}", bytesToHex(signatures.get(i)), signatures.get(i).length);
        }

        // ========== 步骤5: 签名验证核心逻辑（支持乱序匹配） ==========
        int successCount = 0;
        // 记录已使用的公钥索引，避免一个公钥验证多个签名
        boolean[] usedPubKeys = new boolean[n];

        // 遍历每个签名，尝试为每个签名找到一个未使用的匹配公钥
        for (int sigIdx = 0; sigIdx < m; sigIdx++) {
            byte[] sigBytes = signatures.get(sigIdx);
            boolean sigMatched = false; // 标记当前签名是否找到匹配的公钥

            // 遍历所有未使用的公钥，寻找匹配
            for (int pubIdx = 0; pubIdx < n; pubIdx++) {
                if (usedPubKeys[pubIdx]) {
                    continue; // 跳过已使用的公钥，避免重复验证
                }

                byte[] pubKey = pubKeys.get(pubIdx);
                log.debug("验证签名{} vs 公钥{} (未使用)", sigIdx + 1, pubIdx + 1);

                try {
                    // 分离签名内容和哈希类型（最后1字节是SIGHASH类型）
                    if (sigBytes.length < 1) {
                        log.debug("签名{}为空，跳过匹配公钥{}", sigIdx + 1, pubIdx + 1);
                        continue;
                    }
                    byte[] signature = Arrays.copyOfRange(sigBytes, 0, sigBytes.length - 1);
                    byte sigHashType = sigBytes[sigBytes.length - 1];
                    log.debug("签名{}哈希类型: 0x{}", sigIdx + 1, Integer.toHexString(sigHashType));

                    // 计算签名哈希（交易的签名摘要）
                    byte[] sigHash = tx.calculateSigHash(inputIndex,sigHashType);
                    log.info("签名数据{}",bytesToHex(sigHash));

                    // Ed25519签名验证
                    if (Ed25519Signer.fastVerify(pubKey, sigHash, signature)) {
                        successCount++;
                        usedPubKeys[pubIdx] = true; // 标记该公钥已使用
                        sigMatched = true;
                        log.debug("签名{}与公钥{}匹配成功，已验证{}个签名", sigIdx + 1, pubIdx + 1, successCount);
                        break; // 找到匹配的公钥，无需继续遍历其他公钥
                    }
                } catch (Exception e) {
                    log.warn("签名{}验证公钥{}时异常: {}", sigIdx + 1, pubIdx + 1, e.getMessage());
                }
            }

            // 如果当前签名没有找到任何匹配的公钥，直接终止后续验证（无法满足m个签名要求）
            if (!sigMatched) {
                log.debug("签名{}未找到匹配的公钥，多签验证提前终止", sigIdx + 1);
                break;
            }
        }

        // ========== 步骤6: 验证结果判断 & 入栈 ==========
        boolean verifyResult = successCount == m;
        push(encodeNumber(verifyResult ? 1 : 0));
        log.info("多签验证完成 - 成功验证{}个签名 / 需要{}个签名 → 结果: {}", successCount, m, verifyResult ? "通过" : "失败");

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


    public static void main(String[] args) {
        try {
            byte[] privateKey1 = hexToBytes("1ed02d6a330a20ae961a2bd921e80b9373874dd248d354e7c2410f67cef5cbc8");
            byte[] publicKey1 = hexToBytes("dd5dbac2d2e1a7a9b849adc1dc89acb44c9f9518259933d031950cf2f1e17cd1");

            byte[] privateKey2 = hexToBytes("fafe8913e58be4b29cd914317ca4bdd2e5ccc62d1fb7e25d180b1dc19b590c1f");
            byte[] publicKey2 = hexToBytes("eacdc6e4aec120c247cd35959e6e875e0a35add4cf80e9f05e6d5aa29d511f53");

            byte[] privateKey3 = hexToBytes("ac58a008c9e6c89d884b4611415750bba6b50d40da5e28cc087b590fc8a207c3");
            byte[] publicKey3 = hexToBytes("f7908979baf0c46cc37cba0d0348bf6baa99098b4e8943b0860fa9edf5e6e092");



            //谁能提供一个脚本，它的哈希值等于我指定的值，并且这个脚本能被成功执行，钱就归谁

            //测试多签 OP_2 <公钥1> <公钥2> <公钥3> OP_3 OP_CHECKMULTISIG



            //收款方创建脚本 并提供脚本的Hash160 byte[] 20字节 -> scriptHash

            //付款方 创建锁定脚本 OP_HASH160 <scriptHash> OP_EQUAL

            //收款方使用这笔钱要提供: 原始的赎回脚本（包含复杂的解锁条件，如多个公钥和签名要求）。

            Script redeemScript = new Script();
            redeemScript.addOpCode(Script.OP_2);  // 需要2个签名
            redeemScript.addData(publicKey1);     // 公钥1
            redeemScript.addData(publicKey2);     // 公钥2
            redeemScript.addData(publicKey3);     // 公钥3
            redeemScript.addOpCode(Script.OP_3);  // 总共3个公钥
            redeemScript.addOpCode(Script.OP_CHECKMULTISIG);  // 多重签名检查

            String redeemScriptHex = redeemScript.toHex();
            log.info("赎回脚本: {}", redeemScript.toString());
            log.info("赎回脚本长度: {} 字节", redeemScriptHex.length() / 2);
            log.info("赎回脚本十六进制: {}\n", redeemScriptHex);

            byte[] redeemScriptHash = Sha.applyRIPEMD160(Sha.applySHA256(redeemScript.serialize()));
            log.info("赎回脚本哈希 (20字节): {}", bytesToHex(redeemScriptHash));

            Script p2shLockingScript = new Script();
            p2shLockingScript.addOpCode(Script.OP_HASH160);
            p2shLockingScript.addData(redeemScriptHash);  // 20字节的脚本哈希
            p2shLockingScript.addOpCode(Script.OP_EQUAL);
            log.info("P2SH锁定脚本: {}", p2shLockingScript.toString());
            log.info("P2SH锁定脚本十六进制: {}\n", p2shLockingScript.toHex());

            String previousTxId = "a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef123456";
            int previousOutputIndex = 0;
            long utxoValue = 100000000L;  // 1 BTC = 100,000,000 satoshis

            UTXO utxo = new UTXO();
            utxo.setTxId(hexToBytes(previousTxId));
            utxo.setIndex(previousOutputIndex);
            utxo.setValue(utxoValue);
            utxo.setScript(p2shLockingScript.serialize());  // 锁定脚本是P2SH脚本
            log.info("UTXO交易ID: {}", previousTxId);
            log.info("UTXO输出索引: {}", previousOutputIndex);
            log.info("UTXO金额: {} satoshis ({} BTC)", utxoValue, utxoValue / 100000000.0);
            log.info("UTXO锁定脚本: {}\n", p2shLockingScript.toString());

            // ========== 6. 创建花费交易 ==========
            log.info("======= 6. 创建花费交易 =======");
            Transaction spendingTx = new Transaction();
            spendingTx.setVersion(1);
            spendingTx.setLockTime(0);
            // 添加交易输入（引用UTXO）
            TxInput txInput = new TxInput();
            txInput.setTxId(hexToBytes(previousTxId));
            txInput.setIndex(previousOutputIndex);
            txInput.setSequence(0xFFFFFFFFL);
            // 解锁脚本稍后设置
            ArrayList<TxInput> inputs = new ArrayList<>();
            inputs.add(txInput);
            spendingTx.setInputs(inputs);
            // 添加交易输出（发送到新地址）
            TxOutput txOutput = new TxOutput();
            txOutput.setValue(utxoValue - 1000);  // 减去手续费

            // 假设发送到一个简单的P2PKH地址
            byte[] recipientPubKeyHash = hexToBytes("76934d8111b97796af8d22443628399ebb612124");
            Script recipientScript = new Script();
            recipientScript.addOpCode(Script.OP_DUP);
            recipientScript.addOpCode(Script.OP_HASH160);
            recipientScript.addData(recipientPubKeyHash);
            recipientScript.addOpCode(Script.OP_EQUALVERIFY);
            recipientScript.addOpCode(Script.OP_CHECKSIG);
            txOutput.setScriptPubKey(recipientScript.serialize());

            ArrayList<TxOutput> outputs = new ArrayList<>();
            outputs.add(txOutput);
            spendingTx.setOutputs(outputs);

            // 设置UTXO映射（用于签名）
            Map<String, UTXO> utxoMap2 = new HashMap<>();
            byte[] utxoKey = UTXO.getKey(hexToBytes(previousTxId), previousOutputIndex);
            utxoMap2.put(bytesToHex(utxoKey), utxo);
            spendingTx.setUtxoMap(utxoMap2);
            log.info("花费交易创建成功");
            log.info("输入数量: 1");
            log.info("输出数量: 1");
            log.info("手续费: 1000 satoshis\n");
            // ========== 7. 创建签名 ==========
            log.info("======= 7. 创建多重签名 =======");

            // 对于P2SH，签名时需要将锁定脚本替换为赎回脚本
            // 准备签名数据
            byte[] serialize = redeemScript.serialize();
            log.info("脚本数据{}",bytesToHex(serialize));
            log.info("脚本数据Hash160{}",bytesToHex(Sha.applyRIPEMD160(Sha.applySHA256(serialize))));

            byte[] sigHash2 = spendingTx.prepareDataForSigning(
                    0,  // 输入索引
                    p2shLockingScript.serialize(),  // 使用P2SH锁定脚本
                    SIGHASH_ALL
            );
            log.info("签名哈希: {}", bytesToHex(sigHash2).substring(0, 20) + "...");
            // 使用私钥1和私钥2进行签名（2-of-3）
            byte[] sigHashDigest = Sha.applySHA256(sigHash2);
            log.info("原始签名数据{}",bytesToHex(sigHashDigest));

            byte[] bytes = spendingTx.calculateSigHash(0,SIGHASH_ALL);
            log.info("原始签名数据2{}",bytesToHex(bytes));

            // 第一个签名
            byte[] signature1 = Ed25519Signer.fastSign(privateKey1, sigHashDigest);
            byte[] signatureWithHashType1 = new byte[signature1.length + 1];
            System.arraycopy(signature1, 0, signatureWithHashType1, 0, signature1.length);
            signatureWithHashType1[signature1.length] = SIGHASH_ALL;


            // 第二个签名
            byte[] signature2 = Ed25519Signer.fastSign(privateKey2, sigHashDigest);
            byte[] signatureWithHashType2 = new byte[signature2.length + 1];
            System.arraycopy(signature2, 0, signatureWithHashType2, 0, signature2.length);
            signatureWithHashType2[signature2.length] = SIGHASH_ALL;

            log.info("签名1 (使用私钥1): {}...", bytesToHex(signature1).substring(0, 20));
            log.info("签名2 (使用私钥2): {}...\n", bytesToHex(signature2).substring(0, 20));

            // ========== 8. 构造P2SH解锁脚本 ==========
            log.info("======= 8. 构造P2SH解锁脚本 =======");

            // 解锁脚本格式: [OP_0] [signature1] [signature2] [redeemScript]
            Script unlockingScript2 = new Script();

            // 添加两个签名（顺序必须与赎回脚本中的公钥顺序一致）
            unlockingScript2.addData(signatureWithHashType1);  // 对应公钥1
            unlockingScript2.addData(signatureWithHashType2);  // 对应公钥2
            // 添加完整的赎回脚本
            unlockingScript2.addData(redeemScript.serialize());

            log.info("解锁脚本: {}", unlockingScript2.toString());
            log.info("解锁脚本十六进制: {}\n", unlockingScript2.toHex());
            // 将解锁脚本设置到交易输入中
            txInput.setScriptSig(unlockingScript2.serialize());
            // ========== 9. 执行脚本验证 ==========
            log.info("======= 9. 执行脚本验证 =======");

            // 创建脚本执行器
            ScriptExecutor executor1 = new ScriptExecutor();


            Script combinedP2SHScript = ScriptExecutor.combineScripts(unlockingScript2, p2shLockingScript);


            // 执行验证
            boolean verificationResult = executor1.execute(
                    combinedP2SHScript,
                    spendingTx,
                    0
            );

            if (verificationResult) {
                log.info("✅ 验证成功！2-of-3多重签名P2SH交易有效。");
            } else {
                log.info("❌ 验证失败！交易无效。");
            }
            // ========== 第二步：执行赎回脚本的多签逻辑 ==========

            List<Script.ScriptElement> unlockElements = new ArrayList<>(unlockingScript2.getElements());
            if (unlockElements.isEmpty()) {
                log.error("解锁脚本为空");
            }
            unlockElements.remove(unlockElements.size() - 1); // 移除最后一个元素（赎回脚本）
            Script unlockWithoutRedeem = new Script(unlockElements);

            Script combinedRedeemScript = combineScripts(unlockWithoutRedeem, redeemScript);
            // 4. 执行赎回脚本（真正的多签验证）
            ScriptExecutor redeemExecutor = new ScriptExecutor();
            boolean redeemVerify = redeemExecutor.execute(combinedRedeemScript, spendingTx, 0);
            if (!redeemVerify) {
                log.error("P2SH赎回脚本执行失败（多签验证不通过）");
            }else {
                log.info("✅ P2SH赎回脚本执行成功（多签验证通过）");
            }

        } catch (Exception e) {
            log.error("脚本验证过程出错: ", e);
        }
    }
}

