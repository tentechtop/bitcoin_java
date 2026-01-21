package com.bit.coin;

import com.bit.coin.utils.Ed25519Signer;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;

import static com.bit.coin.utils.SerializeUtils.hexToBytes;

/**
 * 简化的以太坊虚拟机（EVM）实现
 * 包含ERC20代币和Ed25519签名验证功能
 */
public class SimpleEVM {

    // 操作码常量
    public static class OpCode {
        public static final int STOP = 0x00;
        public static final int ADD = 0x01;
        public static final int MUL = 0x02;
        public static final int SUB = 0x03;
        public static final int DIV = 0x04;
        public static final int SDIV = 0x05;
        public static final int MOD = 0x06;
        public static final int SMOD = 0x07;
        public static final int ADDMOD = 0x08;
        public static final int MULMOD = 0x09;
        public static final int EXP = 0x0A;
        public static final int LT = 0x10;
        public static final int GT = 0x11;
        public static final int EQ = 0x14;
        public static final int ISZERO = 0x15;
        public static final int AND = 0x16;
        public static final int OR = 0x17;
        public static final int XOR = 0x18;
        public static final int NOT = 0x19;
        public static final int BYTE = 0x1A;
        public static final int PUSH1 = 0x60;
        public static final int PUSH32 = 0x7F;
        public static final int DUP1 = 0x80;
        public static final int DUP16 = 0x8F;
        public static final int SWAP1 = 0x90;
        public static final int SWAP16 = 0x9F;
        public static final int MSTORE = 0x52;
        public static final int MLOAD = 0x51;
        public static final int MSTORE8 = 0x53;
        public static final int SLOAD = 0x54;
        public static final int SSTORE = 0x55;
        public static final int JUMP = 0x56;
        public static final int JUMPI = 0x57;
        public static final int PC = 0x58;
        public static final int MSIZE = 0x59;
        public static final int GAS = 0x5A;
        public static final int JUMPDEST = 0x5B;
        public static final int RETURN = 0xF3;
        public static final int REVERT = 0xFD;
        public static final int INVALID = 0xFE;
        public static final int SELFDESTRUCT = 0xFF;
        public static final int CALLER = 0x33;
        public static final int ORIGIN = 0x32;
        public static final int CALLDATALOAD = 0x35;
        public static final int CALLDATASIZE = 0x36;
        public static final int SHA3 = 0x20;
        public static final int CALLVALUE = 0x34;
        public static final int BALANCE = 0x31;
        public static final int ADDRESS = 0x30;
        public static final int ED25519VERIFY = 0xFA; // 自定义操作码：Ed25519签名验证
    }

    /**
     * EVM执行上下文
     */
    public static class ExecutionContext {
        public Stack<BigInteger> stack = new Stack<>();
        public Map<BigInteger, BigInteger> memory = new HashMap<>();
        public Map<BigInteger, BigInteger> storage = new HashMap<>();
        public byte[] code;
        public byte[] callData;
        public int pc = 0; // 程序计数器
        public BigInteger gas = BigInteger.valueOf(1000000);
        public boolean stopped = false;
        public byte[] returnData;
        public BigInteger caller = BigInteger.ZERO; // 调用者地址
        public BigInteger callValue = BigInteger.ZERO; // 调用时转移的ETH
        public BigInteger address = BigInteger.ONE; // 合约地址

        public ExecutionContext(byte[] code) {
            this.code = code;
        }

        public ExecutionContext(byte[] code, byte[] callData, BigInteger caller, BigInteger callValue) {
            this.code = code;
            this.callData = callData;
            this.caller = caller;
            this.callValue = callValue;
        }

        public int readByte() {
            if (pc >= code.length) {
                return 0;
            }
            return code[pc++] & 0xFF;
        }

        public BigInteger readBytes(int n) {
            BigInteger result = BigInteger.ZERO;
            for (int i = 0; i < n; i++) {
                int b = readByte();
                result = result.shiftLeft(8).or(BigInteger.valueOf(b));
            }
            return result;
        }
    }

    /**
     * EVM执行结果
     */
    public static class ExecutionResult {
        public boolean success;
        public byte[] returnData;
        public BigInteger gasUsed;
        public String error;
        public Map<BigInteger, BigInteger> storage;
        public BigInteger stackResult; // 栈顶结果（如果有）

        public ExecutionResult(boolean success, byte[] returnData,
                               BigInteger gasUsed, String error,
                               Map<BigInteger, BigInteger> storage, BigInteger stackResult) {
            this.success = success;
            this.returnData = returnData;
            this.gasUsed = gasUsed;
            this.error = error;
            this.storage = storage;
            this.stackResult = stackResult;
        }

        public ExecutionResult(boolean success, byte[] returnData,
                               BigInteger gasUsed, String error,
                               Map<BigInteger, BigInteger> storage) {
            this(success, returnData, gasUsed, error, storage, null);
        }
    }

    /**
     * ERC20代币元数据
     */
    public static class ERC20Metadata {
        public String name;
        public String symbol;
        public int decimals;
        public BigInteger totalSupply;
        public BigInteger creator; // 创建者地址

        public ERC20Metadata(String name, String symbol, int decimals,
                             BigInteger totalSupply, BigInteger creator) {
            this.name = name;
            this.symbol = symbol;
            this.decimals = decimals;
            this.totalSupply = totalSupply;
            this.creator = creator;
        }
    }


    /**
     * EVM实现
     */
    public static class EVM {
        private Map<Integer, Operation> operations = new HashMap<>();
        private Map<BigInteger, ERC20Metadata> tokens = new HashMap<>(); // 合约地址 -> 代币元数据
        private Map<BigInteger, Map<BigInteger, BigInteger>> tokenStorage = new HashMap<>(); // 合约地址 -> 存储
        private BigInteger nextContractAddress = BigInteger.valueOf(1000);

        public EVM() {
            initializeOperations();
        }

        private void initializeOperations() {
            // 算术操作
            operations.put(OpCode.ADD, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                BigInteger result = a.add(b);
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.MUL, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                BigInteger result = a.multiply(b);
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(5));
            });

            operations.put(OpCode.SUB, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                BigInteger result = b.subtract(a); // 注意：应该是 b - a
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.DIV, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                if (a.equals(BigInteger.ZERO)) { // 注意：应该是除数不能为0
                    ctx.stack.push(BigInteger.ZERO);
                } else {
                    ctx.stack.push(b.divide(a));
                }
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(5));
            });

            // 栈操作
            operations.put(OpCode.PUSH1, ctx -> {
                BigInteger value = ctx.readBytes(1);
                ctx.stack.push(value);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.DUP1, ctx -> {
                if (ctx.stack.isEmpty()) {
                    ctx.stopped = true;
                    return;
                }
                ctx.stack.push(ctx.stack.peek());
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.SWAP1, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                ctx.stack.push(a);
                ctx.stack.push(b);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            // 比较操作
            operations.put(OpCode.LT, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();  // 栈顶元素（后压入的数）
                BigInteger b = ctx.stack.pop();  // 栈底元素（先压入的数）
                // 修正逻辑：判断 a < b → 即 "后压入的数 < 先压入的数"
                BigInteger result = (a.compareTo(b) < 0) ? BigInteger.ONE : BigInteger.ZERO;
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });


            operations.put(OpCode.GT, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();  // 栈顶元素（后压入的数）
                BigInteger b = ctx.stack.pop();  // 栈底元素（先压入的数）
                // 修正逻辑：判断 a > b → 即 "后压入的数 > 先压入的数"
                BigInteger result = (a.compareTo(b) > 0) ? BigInteger.ONE : BigInteger.ZERO;
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.EQ, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                BigInteger result = b.equals(a) ? BigInteger.ONE : BigInteger.ZERO;
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.ISZERO, ctx -> {
                if (ctx.stack.isEmpty()) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger result = a.equals(BigInteger.ZERO) ? BigInteger.ONE : BigInteger.ZERO;
                ctx.stack.push(result);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            // 逻辑操作
            operations.put(OpCode.AND, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                ctx.stack.push(a.and(b));
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.OR, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                ctx.stack.push(a.or(b));
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.XOR, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger a = ctx.stack.pop();
                BigInteger b = ctx.stack.pop();
                ctx.stack.push(a.xor(b));
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            // 内存操作
            operations.put(OpCode.MSTORE, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger offset = ctx.stack.pop();
                BigInteger value = ctx.stack.pop();
                ctx.memory.put(offset, value);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            operations.put(OpCode.MLOAD, ctx -> {
                if (ctx.stack.isEmpty()) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger offset = ctx.stack.pop();
                BigInteger value = ctx.memory.getOrDefault(offset, BigInteger.ZERO);
                ctx.stack.push(value);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(3));
            });

            // 存储操作
            operations.put(OpCode.SSTORE, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger key = ctx.stack.pop();
                BigInteger value = ctx.stack.pop();
                ctx.storage.put(key, value);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(20000));
            });

            operations.put(OpCode.SLOAD, ctx -> {
                if (ctx.stack.isEmpty()) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger key = ctx.stack.pop();
                BigInteger value = ctx.storage.getOrDefault(key, BigInteger.ZERO);
                ctx.stack.push(value);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(200));
            });

            // 控制流操作
            operations.put(OpCode.JUMP, ctx -> {
                if (ctx.stack.isEmpty()) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger dest = ctx.stack.pop();
                ctx.pc = dest.intValue();
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(8));
            });

            operations.put(OpCode.JUMPI, ctx -> {
                if (ctx.stack.size() < 2) {
                    ctx.stopped = true;
                    return;
                }
                BigInteger dest = ctx.stack.pop();
                BigInteger condition = ctx.stack.pop();
                if (!condition.equals(BigInteger.ZERO)) {
                    ctx.pc = dest.intValue();
                }
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(10));
            });

            operations.put(OpCode.JUMPDEST, ctx -> {
                // JUMPDEST 不做任何操作，只是标记跳转目标
                ctx.gas = ctx.gas.subtract(BigInteger.ONE);
            });

            // 地址和余额操作
            operations.put(OpCode.CALLER, ctx -> {
                ctx.stack.push(ctx.caller);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(2));
            });

            operations.put(OpCode.ADDRESS, ctx -> {
                ctx.stack.push(ctx.address);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(2));
            });

            operations.put(OpCode.CALLVALUE, ctx -> {
                ctx.stack.push(ctx.callValue);
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(2));
            });

            // Ed25519签名验证操作
            operations.put(OpCode.ED25519VERIFY, ctx -> {
                if (ctx.stack.size() < 4) {
                    ctx.stopped = true;
                    return;
                }

                // 从栈中弹出参数
                BigInteger messageLength = ctx.stack.pop();
                BigInteger sigOffset = ctx.stack.pop();
                BigInteger msgOffset = ctx.stack.pop();
                BigInteger pubKeyOffset = ctx.stack.pop();

                // 从内存中读取数据
                byte[] publicKey = readMemoryBytes(ctx, pubKeyOffset.intValue(), 32);
                byte[] message = readMemoryBytes(ctx, msgOffset.intValue(), messageLength.intValue());
                byte[] signature = readMemoryBytes(ctx, sigOffset.intValue(), 64);

                // 执行Ed25519验证
                boolean isValid = Ed25519Signer.fastVerify(message, signature, publicKey);

                // 将结果压入栈中
                ctx.stack.push(isValid ? BigInteger.ONE : BigInteger.ZERO);

                // 消耗gas
                ctx.gas = ctx.gas.subtract(BigInteger.valueOf(2000));
            });

            // 停止和返回
            operations.put(OpCode.STOP, ctx -> {
                ctx.stopped = true;
                ctx.gas = ctx.gas.subtract(BigInteger.ZERO);
            });

            operations.put(OpCode.RETURN, ctx -> {
                ctx.stopped = true;
                ctx.returnData = new byte[0];
                ctx.gas = ctx.gas.subtract(BigInteger.ZERO);
            });

            operations.put(OpCode.REVERT, ctx -> {
                ctx.stopped = true;
                ctx.returnData = new byte[0];
                ctx.gas = ctx.gas.subtract(BigInteger.ZERO);
            });
        }

        /**
         * 从内存中读取字节数组
         */
        private byte[] readMemoryBytes(ExecutionContext ctx, int offset, int length) {
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                BigInteger value = ctx.memory.getOrDefault(BigInteger.valueOf(offset + i), BigInteger.ZERO);
                result[i] = value.byteValue();
            }
            return result;
        }

        /**
         * 将字节数组写入内存
         */
        private void writeMemoryBytes(ExecutionContext ctx, int offset, byte[] data) {
            for (int i = 0; i < data.length; i++) {
                ctx.memory.put(BigInteger.valueOf(offset + i), BigInteger.valueOf(data[i] & 0xFF));
            }
        }

        /**
         * 执行字节码
         */
        public ExecutionResult execute(byte[] code) {
            return execute(code, null, BigInteger.ZERO, BigInteger.ZERO);
        }

        public ExecutionResult execute(byte[] code, byte[] callData,
                                       BigInteger caller, BigInteger callValue) {
            ExecutionContext ctx = new ExecutionContext(code, callData, caller, callValue);
            BigInteger initialGas = ctx.gas;
            BigInteger stackResult = null;

            try {
                while (!ctx.stopped && ctx.pc < code.length && ctx.gas.compareTo(BigInteger.ZERO) > 0) {
                    int opcode = ctx.readByte();

                    Operation operation = operations.get(opcode);
                    if (operation != null) {
                        operation.execute(ctx);
                    } else {
                        // 未知操作码
                        return new ExecutionResult(false, new byte[0],
                                initialGas.subtract(ctx.gas), "Unknown opcode: 0x" +
                                Integer.toHexString(opcode), ctx.storage);
                    }

                    // 如果栈不为空，记录栈顶值
                    if (!ctx.stack.isEmpty()) {
                        stackResult = ctx.stack.peek();
                    }
                }

                if (ctx.gas.compareTo(BigInteger.ZERO) <= 0) {
                    return new ExecutionResult(false, new byte[0],
                            initialGas, "Out of gas", ctx.storage, stackResult);
                }

                return new ExecutionResult(true, ctx.returnData != null ?
                        ctx.returnData : new byte[0],
                        initialGas.subtract(ctx.gas), null, ctx.storage, stackResult);

            } catch (Exception e) {
                return new ExecutionResult(false, new byte[0],
                        initialGas.subtract(ctx.gas), e.getMessage(), ctx.storage, stackResult);
            }
        }

        /**
         * 部署ERC20代币合约
         */
        public BigInteger deployERC20(String name, String symbol, int decimals,
                                      BigInteger totalSupply, BigInteger creator) {
            BigInteger contractAddress = generateContractAddress();

            // 创建代币元数据
            ERC20Metadata metadata = new ERC20Metadata(name, symbol,
                    decimals, totalSupply, creator);
            tokens.put(contractAddress, metadata);

            // 初始化存储
            Map<BigInteger, BigInteger> storage = new HashMap<>();

            // 存储布局 (简化版本)
            // slot 0: 总供应量
            storage.put(BigInteger.ZERO, totalSupply);
            // slot 1: 小数位数
            storage.put(BigInteger.ONE, BigInteger.valueOf(decimals));
            // slot 2: 代币名称长度
            storage.put(BigInteger.valueOf(2), BigInteger.valueOf(name.length()));
            // slot 3: 代币符号长度
            storage.put(BigInteger.valueOf(3), BigInteger.valueOf(symbol.length()));

            // 将总供应量分配给创建者
            // 使用创建者地址的哈希值作为key
            BigInteger creatorBalanceKey = getBalanceKey(creator);
            storage.put(creatorBalanceKey, totalSupply);

            // 记录元数据
            storage.put(BigInteger.valueOf(1000), BigInteger.valueOf(name.hashCode() & 0x7FFFFFFF)); // 避免负数
            storage.put(BigInteger.valueOf(1001), BigInteger.valueOf(symbol.hashCode() & 0x7FFFFFFF));

            tokenStorage.put(contractAddress, storage);

            return contractAddress;
        }

        /**
         * 调用ERC20合约的transfer函数
         */
        public ExecutionResult transfer(BigInteger contractAddress, BigInteger from,
                                        BigInteger to, BigInteger amount) {
            Map<BigInteger, BigInteger> storage = tokenStorage.get(contractAddress);
            if (storage == null) {
                return new ExecutionResult(false, new byte[0], BigInteger.ZERO,
                        "Contract not found", new HashMap<>());
            }

            // 检查余额
            BigInteger fromBalance = storage.getOrDefault(getBalanceKey(from), BigInteger.ZERO);
            if (fromBalance.compareTo(amount) < 0) {
                return new ExecutionResult(false, new byte[0], BigInteger.valueOf(21000),
                        "Insufficient balance", storage);
            }

            // 执行转账
            BigInteger newFromBalance = fromBalance.subtract(amount);
            BigInteger toBalance = storage.getOrDefault(getBalanceKey(to), BigInteger.ZERO);
            BigInteger newToBalance = toBalance.add(amount);

            storage.put(getBalanceKey(from), newFromBalance);
            storage.put(getBalanceKey(to), newToBalance);

            return new ExecutionResult(true, new byte[0], BigInteger.valueOf(21000),
                    null, storage);
        }

        /**
         * 查询代币余额
         */
        public BigInteger balanceOf(BigInteger contractAddress, BigInteger account) {
            Map<BigInteger, BigInteger> storage = tokenStorage.get(contractAddress);
            if (storage == null) {
                return BigInteger.ZERO;
            }
            return storage.getOrDefault(getBalanceKey(account), BigInteger.ZERO);
        }

        /**
         * 查询代币信息
         */
        public ERC20Metadata getTokenInfo(BigInteger contractAddress) {
            return tokens.get(contractAddress);
        }

        /**
         * 生成合约地址
         */
        private BigInteger generateContractAddress() {
            return nextContractAddress = nextContractAddress.add(BigInteger.ONE);
        }

        /**
         * 获取余额存储key
         */
        private BigInteger getBalanceKey(BigInteger account) {
            // 使用固定的偏移量 + 账户地址
            return BigInteger.valueOf(100).add(account.mod(BigInteger.valueOf(1000000)));
        }

        /**
         * 获取合约存储
         */
        public Map<BigInteger, BigInteger> getContractStorage(BigInteger contractAddress) {
            return tokenStorage.get(contractAddress);
        }

        /**
         * 获取所有代币合约
         */
        public List<BigInteger> getAllTokenContracts() {
            return new ArrayList<>(tokens.keySet());
        }


    }

    /**
     * 操作接口
     */
    private interface Operation {
        void execute(ExecutionContext ctx);
    }

    /**
     * 字节码汇编器
     */
    public static class Assembler {
        private List<Byte> code = new ArrayList<>();

        public Assembler push(BigInteger value) {
            int byteCount = (value.bitLength() + 7) / 8;
            if (byteCount == 0) byteCount = 1;
            if (byteCount > 32) byteCount = 32;

            int opcode = OpCode.PUSH1 + byteCount - 1;
            code.add((byte) opcode);

            byte[] bytes = value.toByteArray();
            // 确保是正数表示
            if (bytes[0] == 0 && bytes.length > 1) {
                byte[] temp = new byte[bytes.length - 1];
                System.arraycopy(bytes, 1, temp, 0, temp.length);
                bytes = temp;
            }

            // 填充到指定长度
            byte[] padded = new byte[byteCount];
            int start = byteCount - bytes.length;
            for (int i = 0; i < bytes.length; i++) {
                padded[start + i] = bytes[i];
            }

            for (byte b : padded) {
                code.add(b);
            }
            return this;
        }

        public Assembler add() {
            code.add((byte) OpCode.ADD);
            return this;
        }

        public Assembler mul() {
            code.add((byte) OpCode.MUL);
            return this;
        }

        public Assembler sub() {
            code.add((byte) OpCode.SUB);
            return this;
        }

        public Assembler div() {
            code.add((byte) OpCode.DIV);
            return this;
        }

        public Assembler dup1() {
            code.add((byte) OpCode.DUP1);
            return this;
        }

        public Assembler swap1() {
            code.add((byte) OpCode.SWAP1);
            return this;
        }

        public Assembler lt() {
            code.add((byte) OpCode.LT);
            return this;
        }

        public Assembler gt() {
            code.add((byte) OpCode.GT);
            return this;
        }

        public Assembler eq() {
            code.add((byte) OpCode.EQ);
            return this;
        }

        public Assembler isZero() {
            code.add((byte) OpCode.ISZERO);
            return this;
        }

        public Assembler and() {
            code.add((byte) OpCode.AND);
            return this;
        }

        public Assembler or() {
            code.add((byte) OpCode.OR);
            return this;
        }

        public Assembler xor() {
            code.add((byte) OpCode.XOR);
            return this;
        }

        public Assembler sstore() {
            code.add((byte) OpCode.SSTORE);
            return this;
        }

        public Assembler sload() {
            code.add((byte) OpCode.SLOAD);
            return this;
        }

        public Assembler mstore() {
            code.add((byte) OpCode.MSTORE);
            return this;
        }

        public Assembler mload() {
            code.add((byte) OpCode.MLOAD);
            return this;
        }

        public Assembler jump() {
            code.add((byte) OpCode.JUMP);
            return this;
        }

        public Assembler jumpi() {
            code.add((byte) OpCode.JUMPI);
            return this;
        }

        public Assembler jumpdest() {
            code.add((byte) OpCode.JUMPDEST);
            return this;
        }

        public Assembler caller() {
            code.add((byte) OpCode.CALLER);
            return this;
        }

        public Assembler address() {
            code.add((byte) OpCode.ADDRESS);
            return this;
        }

        public Assembler callvalue() {
            code.add((byte) OpCode.CALLVALUE);
            return this;
        }

        public Assembler stop() {
            code.add((byte) OpCode.STOP);
            return this;
        }

        public Assembler revert() {
            code.add((byte) OpCode.REVERT);
            return this;
        }

        public Assembler returndatasize() {
            // 简化的RETURNDATASIZE实现
            code.add((byte) 0x3d);
            return this;
        }

        public Assembler _return() {
            code.add((byte) OpCode.RETURN);
            return this;
        }

        public Assembler ed25519verify() {
            code.add((byte) OpCode.ED25519VERIFY);
            return this;
        }

        public byte[] toByteArray() {
            byte[] result = new byte[code.size()];
            for (int i = 0; i < code.size(); i++) {
                result[i] = code.get(i);
            }
            return result;
        }
    }

    /**
     * 使用示例
     */
    public static void main(String[] args) {
        System.out.println("=== 简化的EVM实现 - ERC20代币和Ed25519签名验证 ===\n");

        // 示例1: 简单的算术运算
        System.out.println("示例1: 计算 ((5 + 3) * 2) - 4");
        Assembler asm1 = new Assembler();
        // 正确的顺序: 先计算5+3=8，然后乘以2=16，然后减去4=12
        byte[] code1 = asm1
                .push(BigInteger.valueOf(5))
                .push(BigInteger.valueOf(3))
                .add()           // 5 + 3 = 8
                .push(BigInteger.valueOf(2))
                .mul()           // 8 * 2 = 16
                .push(BigInteger.valueOf(4))
                .sub()           // 16 - 4 = 12
                .stop()
                .toByteArray();

        EVM evm1 = new EVM();
        ExecutionResult result1 = evm1.execute(code1);
        System.out.println("成功: " + result1.success);
        System.out.println("Gas消耗: " + result1.gasUsed);
        System.out.println("栈顶结果: " + result1.stackResult + " (应为12)");

        // 示例2: 条件判断 - 修正版本
        System.out.println("\n示例2: 条件判断 (7 > 5) AND (3 == 3)");
        // 问题分析: 之前的AND操作是位运算，但这里我们需要逻辑AND
        // 修正: 先比较7>5得到1，比较3==3得到1，然后使用AND得到1
        Assembler asm2 = new Assembler();
        byte[] code2 = asm2
                .push(BigInteger.valueOf(5))
                .push(BigInteger.valueOf(7))
                .gt()            // 7 > 5 = 1 (true)
                .push(BigInteger.valueOf(3))
                .push(BigInteger.valueOf(3))
                .eq()            // 3 == 3 = 1 (true)
                .and()           // 1 AND 1 = 1
                .stop()
                .toByteArray();

        ExecutionResult result2 = evm1.execute(code2);
        System.out.println("成功: " + result2.success);
        System.out.println("Gas消耗: " + result2.gasUsed);
        System.out.println("栈顶结果: " + result2.stackResult + " (应为1)");

        // 示例3: 部署ERC20代币
        System.out.println("\n示例3: 部署ERC20代币");
        EVM evm3 = new EVM();

        // 部署一个名为"MyToken"的代币
        BigInteger creator = BigInteger.valueOf(123456);
        BigInteger totalSupply = BigInteger.valueOf(1000000).multiply(BigInteger.TEN.pow(18));
        BigInteger contractAddress = evm3.deployERC20(
                "MyToken", "MTK", 18, totalSupply, creator
        );

        System.out.println("合约地址: " + contractAddress);
        ERC20Metadata tokenInfo = evm3.getTokenInfo(contractAddress);
        System.out.println("代币名称: " + tokenInfo.name);
        System.out.println("代币符号: " + tokenInfo.symbol);
        System.out.println("小数位数: " + tokenInfo.decimals);
        System.out.println("代币总供应量: " + tokenInfo.totalSupply);
        System.out.println("创建者余额: " +
                evm3.balanceOf(contractAddress, creator));

        // 示例4: ERC20代币转账
        System.out.println("\n示例4: ERC20代币转账");
        BigInteger alice = BigInteger.valueOf(123456);
        BigInteger bob = BigInteger.valueOf(654321);
        BigInteger transferAmount = BigInteger.valueOf(100).multiply(BigInteger.TEN.pow(18));

        System.out.println("转账前 - Alice余额: " +
                evm3.balanceOf(contractAddress, alice));
        System.out.println("转账前 - Bob余额: " +
                evm3.balanceOf(contractAddress, bob));

        // 从Alice转账给Bob
        ExecutionResult transferResult = evm3.transfer(contractAddress, alice, bob, transferAmount);
        System.out.println("\n转账结果:");
        System.out.println("成功: " + transferResult.success);
        System.out.println("Gas消耗: " + transferResult.gasUsed);
        if (transferResult.error != null) {
            System.out.println("错误信息: " + transferResult.error);
        }

        System.out.println("\n转账后 - Alice余额: " +
                evm3.balanceOf(contractAddress, alice));
        System.out.println("转账后 - Bob余额: " +
                evm3.balanceOf(contractAddress, bob));

        // 验证余额计算是否正确
        BigInteger expectedAliceBalance = totalSupply.subtract(transferAmount);
        BigInteger actualAliceBalance = evm3.balanceOf(contractAddress, alice);
        System.out.println("Alice预期余额: " + expectedAliceBalance);
        System.out.println("Alice实际余额: " + actualAliceBalance);
        System.out.println("Alice余额是否正确: " + expectedAliceBalance.equals(actualAliceBalance));

        // 示例5: 余额不足的转账
        System.out.println("\n示例5: 尝试转账超过余额");
        BigInteger tooMuch = totalSupply.add(BigInteger.ONE);
        ExecutionResult failResult = evm3.transfer(contractAddress, alice, bob, tooMuch);
        System.out.println("转账成功: " + failResult.success);
        if (!failResult.success) {
            System.out.println("错误信息: " + failResult.error);
        }

        // 示例6: 执行条件跳转字节码
        System.out.println("\n示例6: 执行条件跳转字节码");
        Assembler asm6 = new Assembler();
        // 实现: if (5 > 3) return 1 else return 0
        byte[] code6 = asm6
                .push(BigInteger.valueOf(3))
                .push(BigInteger.valueOf(5))
                .gt()            // 5 > 3 = 1
                .push(BigInteger.valueOf(20)) // 跳转目标位置
                .jumpi()         // 如果为1，跳转到位置20
                .push(BigInteger.ZERO) // false分支
                .push(BigInteger.ZERO)
                .mstore()
                .push(BigInteger.valueOf(32))
                .push(BigInteger.ZERO)
                ._return()
                .push(BigInteger.valueOf(20))
                .jumpdest()      // 位置20: true分支
                .push(BigInteger.ONE) // true分支
                .push(BigInteger.ZERO)
                .mstore()
                .push(BigInteger.valueOf(32))
                .push(BigInteger.ZERO)
                ._return()
                .toByteArray();

        ExecutionResult result6 = evm1.execute(code6);
        System.out.println("条件跳转执行成功: " + result6.success);
        System.out.println("Gas消耗: " + result6.gasUsed);

        // 示例7: Ed25519签名验证
        System.out.println("\n示例7: Ed25519签名验证");
        try {
            // 创建测试数据
            byte[] publicKey = hexToBytes("291b960b5ca8591653bea10d11afd883b240616a02ba49acc82be4fcd3aa5dc3");
            byte[] message = "Hello, World!".getBytes();


            byte[] signature = Ed25519Signer.fastSign(hexToBytes("46405ee67af3e85c64ff84c6a6df9a3ac4739e71a2147cb02f4e36b70127487d"),message);


            // 执行验证
            boolean isValid = Ed25519Signer.fastVerify(publicKey, message, signature);
            System.out.println("签名验证结果: " + (isValid ? "通过" : "失败"));



        } catch (Exception e) {
            System.out.println("签名验证错误: " + e.getMessage());
        }

        // 示例8: 在EVM中执行Ed25519验证字节码
        System.out.println("\n示例8: 在EVM中执行Ed25519验证");
        Assembler asm8 = new Assembler();

        // 准备测试数据
        byte[] testPublicKey = hexToBytes("291b960b5ca8591653bea10d11afd883b240616a02ba49acc82be4fcd3aa5dc3");
        byte[] testMessage = "Test message".getBytes();
        byte[] testSignature = Ed25519Signer.fastSign(hexToBytes("46405ee67af3e85c64ff84c6a6df9a3ac4739e71a2147cb02f4e36b70127487d"),testMessage);


        System.out.println("\n=== 演示完成 ===");

        // 总结
        System.out.println("\n=== 总结 ===");
        System.out.println("1. 算术运算: " + (result1.stackResult.equals(BigInteger.valueOf(12)) ? "✓" : "✗"));
        System.out.println("2. 条件判断: " + (result2.stackResult.equals(BigInteger.ONE) ? "✓" : "✗"));
        System.out.println("3. ERC20部署: " + (contractAddress != null ? "✓" : "✗"));
        System.out.println("4. 转账功能: " + (transferResult.success ? "✓" : "✗"));
        System.out.println("5. 余额检查: " + (!failResult.success ? "✓" : "✗"));
        System.out.println("6. 条件跳转: " + (result6.success ? "✓" : "✗"));

    }
}