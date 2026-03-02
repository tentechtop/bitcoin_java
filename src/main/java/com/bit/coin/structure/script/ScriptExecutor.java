package com.bit.coin.structure.script;

import com.bit.coin.structure.block.BlockHeader;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TxInput;
import com.bit.coin.structure.tx.TxOutput;
import com.bit.coin.structure.tx.UTXO;
import com.bit.coin.utils.Ed25519Signer;
import com.bit.coin.utils.SerializeUtils;
import com.bit.coin.utils.Sha;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

import static com.bit.coin.structure.tx.Transaction.SIGHASH_ALL;
import static com.bit.coin.utils.SerializeUtils.bytesToHex;
import static com.bit.coin.utils.SerializeUtils.hexToBytes;
import static org.apache.logging.log4j.ThreadContext.peek;

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
            
            if (stack.isEmpty()) {
                verified = false;
            } else {
                byte[] result = stack.peek();
                verified = castToBool(result); 
            }
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
    public boolean executeOpCode(int opCode, Transaction tx, int inputIndex) throws ScriptExecutionException {
        // 处理OP_PUSHBYTES_1~75（数据推送操作码）
        if (opCode >= 0x01 && opCode <= 0x4b) {
            if (cursor + opCode > script.getElements().size()) {
                throw new ScriptExecutionException("OP_PUSHBYTES_" + opCode + " 数据不足");
            }
            // 拼接后续n字节数据
            ByteBuffer buffer = ByteBuffer.allocate(opCode);
            for (int i = 0; i < opCode; i++) {
                Script.ScriptElement dataElement = script.getElements().get(cursor++);
                if (dataElement.isOpCode()) {
                    throw new ScriptExecutionException("OP_PUSHBYTES_" + opCode + " 后续不是数据");
                }
                buffer.put(dataElement.getData());
            }
            push(buffer.array());
            return true;
        }

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
                log.warn("执行OP_RETURN，脚本立即终止并失败");
                verified = false; // 强制标记验证失败
                return false; // 终止脚本执行流程
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
        byte[] c = pop();
        byte[] b = pop();
        byte[] a = pop();
        push(b);
        push(c);
        push(a);
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
        // 规则1：栈不能为空（需要弹出n）
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_PICK - 无可用数值n");
        }
        // 步骤1：弹出栈顶的n并解码（小端→整数）
        byte[] nBytes = pop();
        BigInteger nBig = decodeNumber(nBytes);
        // 规则2：n必须是非负整数且不超过Integer范围（比特币限制）
        if (nBig.signum() < 0 || nBig.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ScriptExecutionException("无效的索引: OP_PICK - n必须是非负整数且≤" + Integer.MAX_VALUE);
        }
        int n = nBig.intValueExact();
        // 规则3：n必须小于当前栈大小（弹出n后的栈）
        if (stack.size() <= n) {
            throw new ScriptExecutionException(
                    String.format("无效的索引: OP_PICK - n=%d，栈大小=%d（需n < 栈大小）", n, stack.size())
            );
        }
        // 步骤2：获取栈底→栈顶顺序的第n个元素（修复迭代器反向问题）
        // ArrayDeque的iterator是栈顶→栈底，先转为列表并反转得到栈底→栈顶
        List<byte[]> stackList = new ArrayList<>(stack);
        Collections.reverse(stackList); // 现在stackList[0]是栈底，stackList[n]是目标元素
        byte[] targetElement = stackList.get(n).clone(); // 复制元素（避免引用共享）
        // 步骤3：将目标元素压入栈顶
        push(targetElement);
        log.debug("OP_PICK执行成功 - 复制栈底索引{}的元素到栈顶，当前栈大小:{}", n, stack.size());
        return true;
    }

    private boolean executeRoll() throws ScriptExecutionException {
        // 规则1：栈不能为空（需要弹出n）
        if (stack.isEmpty()) {
            throw new ScriptExecutionException("栈下溢: OP_ROLL - 无可用数值n");
        }
        // 步骤1：弹出栈顶的n并解码
        byte[] nBytes = pop();
        BigInteger nBig = decodeNumber(nBytes);
        if (nBig.signum() < 0 || nBig.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new ScriptExecutionException("无效的索引: OP_ROLL - n必须是非负整数且≤" + Integer.MAX_VALUE);
        }
        int n = nBig.intValueExact();
        // 规则2：n必须小于当前栈大小（弹出n后的栈）
        if (stack.size() <= n) {
            throw new ScriptExecutionException(
                    String.format("无效的索引: OP_ROLL - n=%d，栈大小=%d（需n < 栈大小）", n, stack.size())
            );
        }
        // 步骤2：获取栈底→栈顶顺序的元素，并移除原位置元素
        List<byte[]> stackList = new ArrayList<>(stack);
        Collections.reverse(stackList); // 转为栈底→栈顶顺序
        byte[] targetElement = stackList.remove(n); // 移除第n个元素
        // 恢复栈为 ArrayDeque（栈顶→栈底）
        stack.clear();
        Collections.reverse(stackList); // 转回栈顶→栈底
        stack.addAll(stackList);

        // 步骤3：将移除的元素压入栈顶
        push(targetElement);
        log.debug("OP_ROLL执行成功 - 移动栈底索引{}的元素到栈顶，当前栈大小:{}", n, stack.size());
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
        // 栈：[a,b,c,d,e,f] (f是栈顶) → 2ROT后 → [c,d,e,f,a,b]
        byte[] f = pop();
        byte[] e = pop();
        byte[] d = pop();
        byte[] c = pop();
        byte[] b = pop();
        byte[] a = pop();
        push(d);
        push(c);
        push(f);
        push(e);
        push(b);
        push(a);
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

        // 核心修复：ELSE块执行状态 = OP_IF条件的相反值
        boolean ifCondition = conditionStack.peek(); // 取出OP_IF的条件结果
        exec = !ifCondition; // 切换为相反的执行状态
        return true;
    }

    private boolean executeEndif() {
        if (conditionStack.isEmpty()) {
            return false;  // OP_ENDIF没有对应的OP_IF
        }

        conditionStack.pop();
        // 恢复上一层的执行状态（无上层则恢复为true）
        exec = conditionStack.isEmpty() || conditionStack.peek();
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

    public void push(byte[] data) {
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

    public static boolean castToBool(byte[] data) {
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

    public static byte[] encodeNumber(BigInteger n) {
        if (n.equals(BigInteger.ZERO)) {
            return new byte[0];
        }

        boolean negative = n.compareTo(BigInteger.ZERO) < 0;
        BigInteger absN = n.abs();

        // 转换为大端字节数组（去除前导零）
        byte[] bytes = absN.toByteArray();
        int start = 0;
        while (start < bytes.length && bytes[start] == 0) {
            start++;
        }
        byte[] trimmed = new byte[bytes.length - start];
        System.arraycopy(bytes, start, trimmed, 0, trimmed.length);

        // 小端化处理
        byte[] littleEndian = new byte[trimmed.length];
        for (int i = 0; i < trimmed.length; i++) {
            littleEndian[i] = trimmed[trimmed.length - 1 - i];
        }

        // 处理符号位和最高位
        List<Byte> resultList = new ArrayList<>();
        for (byte b : littleEndian) {
            resultList.add(b);
        }

        // 检查最高位是否为1，若是则添加空字节
        if ((littleEndian[littleEndian.length - 1] & 0x80) != 0) {
            resultList.add((byte) 0x00);
        }

        // 处理负数：设置最高位为1
        if (negative) {
            int lastIdx = resultList.size() - 1;
            byte last = resultList.get(lastIdx);
            resultList.set(lastIdx, (byte) (last | 0x80));
        }

        // 转换为数组
        byte[] result = new byte[resultList.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = resultList.get(i);
        }
        return result;
    }

    public static BigInteger decodeNumber(byte[] data) {
        if (data == null || data.length == 0) {
            return BigInteger.ZERO;
        }

        // 检查符号位
        boolean negative = (data[data.length - 1] & 0x80) != 0;
        byte[] temp = Arrays.copyOf(data, data.length);

        // 清除符号位
        if (negative) {
            temp[temp.length - 1] &= 0x7F;
        }

        // 小端转大端
        byte[] bigEndian = BlockHeader.reverseBytes(temp);

        // 去除末尾的零（小端转大端后的前导零）
        int nonZeroStart = 0;
        while (nonZeroStart < bigEndian.length && bigEndian[nonZeroStart] == 0) {
            nonZeroStart++;
        }
        byte[] trimmed = new byte[bigEndian.length - nonZeroStart];
        if (trimmed.length == 0) {
            return BigInteger.ZERO;
        }
        System.arraycopy(bigEndian, nonZeroStart, trimmed, 0, trimmed.length);

        BigInteger result = new BigInteger(1, trimmed);
        return negative ? result.negate() : result;
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
        // 规则1：sequence=0xFFFFFFFF → CLTV 直接失效（返回true）
        if (input.getSequence() == 0xFFFFFFFFL) {
            return true;
        }
        // 规则2：锁定时间为0 → 无效（返回false）
        if (lockTime == 0 || txLockTime == 0) {
            return false;
        }
        // 规则3：类型必须一致（区块高度 <500000000，时间戳 ≥500000000）
        boolean isScriptBlockHeight = lockTime < 500000000L;
        boolean isTxBlockHeight = txLockTime < 500000000L;
        if (isScriptBlockHeight != isTxBlockHeight) {
            return false;
        }
        // 规则4：交易锁定时间 ≥ 脚本锁定时间
        return txLockTime >= lockTime;
    }



    private boolean verifySequence(long inputSequence, long sequence) {
        // 高2位：类型标志（0=区块高度，1=时间戳）
        long scriptType = (sequence >> 30) & 0x03; // 提取脚本值的类型标志
        long inputType = (inputSequence >> 30) & 0x03; // 提取输入sequence的类型标志

        // 低30位：实际的数值部分
        long scriptValue = sequence & 0x3FFFFFFF; // 仅保留低30位
        long inputValue = inputSequence & 0x3FFFFFFF; // 仅保留低30位

        // 规则1：类型必须完全匹配
        if (scriptType != inputType) {
            return false;
        }
        // 规则2：输入数值 ≥ 脚本数值
        return inputValue >= scriptValue;
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
     * 栈内容（栈底→栈顶）
     */
    public List<byte[]> getStack() {
        List<byte[]> stackList = new ArrayList<>(stack);
        // ArrayDeque的迭代器顺序是栈顶→栈底，反转后变为栈底→栈顶
        Collections.reverse(stackList);
        return stackList;
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
}

