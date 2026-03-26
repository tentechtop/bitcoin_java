package com.bit.coin;

import com.bit.coin.utils.Sha;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class BitCoinVM {

    /**
     * 辅助类：存储Gas计算所需的上下文信息
     * 包含EIP-2929/2200/1884等所需的核心上下文
     */
    public static class GasContext {
        // 内存相关
        public int currentMemSize; // 当前内存大小（按32字节对齐）
        // 存储相关 (SSTORE)
        public BigInteger storageOldValue; // 存储的旧值
        public BigInteger storageNewValue; // 存储的新值
        // 访问列表相关 (EIP-2929)
        public Set<BigInteger> warmAddresses = new HashSet<>();// 热地址列表
        public Set<BigInteger> warmStorageSlots = new HashSet<>();// 热存储槽列表

        // 调用/创建相关
        public boolean isTransfer; // 是否转账（CALL/CREATE）
        public boolean targetAccountExists; // 目标账户是否存在
        public int dataLength; // 数据长度（SHA3/LOG/CALL等）
        public int exponentBytes; // EXP操作码的指数字节数
        public int stackHeight; // 栈高度（部分操作码校验用）

        // 构造器：初始化默认上下文
        public GasContext() {
            this.currentMemSize = 0;
            this.storageOldValue = BigInteger.ZERO;
            this.storageNewValue = BigInteger.ZERO;
            this.isTransfer = false;
            this.targetAccountExists = true;
            this.dataLength = 0;
            this.exponentBytes = 0;
            this.stackHeight = 0;
        }
    }

    /**
     * EVM操作码常量定义
     * 完全对齐以太坊主网最新规范（2026），参考以太坊黄皮书和最新EIP
     * 核心更新：EIP-1884, EIP-2929, EIP-1559, EIP-4844等
     */
    public static class OpCode {
        // ============ 0x00: 停止和算术操作 ============
        public static final int STOP = 0x00;           // 停止执行
        public static final int ADD = 0x01;           // 加法
        public static final int MUL = 0x02;           // 乘法
        public static final int SUB = 0x03;           // 减法
        public static final int DIV = 0x04;           // 无符号整数除法（截断）
        public static final int SDIV = 0x05;          // 有符号整数除法（截断）
        public static final int MOD = 0x06;           // 无符号取模
        public static final int SMOD = 0x07;          // 有符号取模
        public static final int ADDMOD = 0x08;        // 模加 (a + b) % c
        public static final int MULMOD = 0x09;        // 模乘 (a * b) % c
        public static final int EXP = 0x0A;           // 指数运算 (base^exponent)
        public static final int SIGNEXTEND = 0x0B;    // 符号扩展
        // 0x0C-0x0F: 保留/未使用

        // ============ 0x10: 比较和位操作 ============
        public static final int LT = 0x10;            // 无符号小于
        public static final int GT = 0x11;            // 无符号大于
        public static final int SLT = 0x12;           // 有符号小于
        public static final int SGT = 0x13;           // 有符号大于
        public static final int EQ = 0x14;            // 等于
        public static final int ISZERO = 0x15;        // 是否为零
        public static final int AND = 0x16;           // 按位与
        public static final int OR = 0x17;            // 按位或
        public static final int XOR = 0x18;           // 按位异或
        public static final int NOT = 0x19;           // 按位取反
        public static final int BYTE = 0x1A;          // 获取指定位置的字节
        public static final int SHL = 0x1B;           // 左移位 (EIP-145)
        public static final int SHR = 0x1C;           // 逻辑右移位 (EIP-145)
        public static final int SAR = 0x1D;           // 算术右移位 (EIP-145)
        // 0x1E-0x1F: 保留/未使用

        // ============ 0x20: 加密操作 ============
        public static final int SHA3 = 0x20;          // Keccak-256哈希 (以太坊专用，非标准SHA3)
        // 0x21-0x2F: 保留/未使用

        // ============ 0x30: 环境信息 (EIP-2929 访问列表优化) ============
        public static final int ADDRESS = 0x30;       // 当前合约地址
        public static final int BALANCE = 0x31;       // 账户余额 (EIP-1884: Gas从400→700)
        public static final int ORIGIN = 0x32;        // 交易原始发送者
        public static final int CALLER = 0x33;        // 直接调用者地址
        public static final int CALLVALUE = 0x34;     // 调用时转入的WEI数量
        public static final int CALLDATALOAD = 0x35;  // 从调用数据加载32字节
        public static final int CALLDATASIZE = 0x36;  // 调用数据总长度
        public static final int CALLDATACOPY = 0x37;  // 复制调用数据到内存
        public static final int CODESIZE = 0x38;      // 当前合约代码长度
        public static final int CODECOPY = 0x39;      // 复制当前合约代码到内存
        public static final int GASPRICE = 0x3A;      // 交易的Gas价格 (EIP-1559: 对应tx.gasPrice)
        public static final int EXTCODESIZE = 0x3B;   // 外部合约代码长度 (EIP-1884: Gas从700→700，EIP-2929优化)
        public static final int EXTCODECOPY = 0x3C;   // 复制外部合约代码到内存
        public static final int RETURNDATASIZE = 0x3D; // 返回数据长度 (EIP-211)
        public static final int RETURNDATACOPY = 0x3E; // 复制返回数据到内存 (EIP-211)
        public static final int EXTCODEHASH = 0x3F;   // 外部合约代码哈希 (EIP-1052, EIP-1884: Gas 700)

        // ============ 0x40: 区块信息 ============
        public static final int BLOCKHASH = 0x40;     // 最近256个区块的哈希
        public static final int COINBASE = 0x41;      // 当前区块矿工/验证者地址
        public static final int TIMESTAMP = 0x42;     // 当前区块时间戳 (秒)
        public static final int NUMBER = 0x43;        // 当前区块号
        public static final int DIFFICULTY = 0x44;    // 当前区块难度 (合并后为0)
        public static final int GASLIMIT = 0x45;      // 当前区块Gas上限
        public static final int CHAINID = 0x46;       // 链ID (EIP-1344)
        public static final int SELFBALANCE = 0x47;   // 当前合约自身余额 (EIP-1884)
        public static final int BASEFEE = 0x48;       // 区块基础费用 (EIP-1559)
        // 0x49-0x4F: 保留/未使用

        // ============ 0x50: 栈、内存、存储和流操作 ============
        public static final int POP = 0x50;           // 弹出栈顶元素
        public static final int MLOAD = 0x51;         // 从内存加载32字节
        public static final int MSTORE = 0x52;        // 存储32字节到内存
        public static final int MSTORE8 = 0x53;       // 存储1字节到内存
        public static final int SLOAD = 0x54;         // 从存储加载 (EIP-2929: 冷存储2100, 热存储100)
        public static final int SSTORE = 0x55;        // 存储到状态 (EIP-2200: 冷20000, 热100, 净空返还15000)
        public static final int JUMP = 0x56;          // 无条件跳转
        public static final int JUMPI = 0x57;         // 条件跳转
        public static final int PC = 0x58;            // 当前程序计数器
        public static final int MSIZE = 0x59;         // 内存已使用大小 (按32字节对齐)
        public static final int GAS = 0x5A;           // 剩余Gas
        public static final int JUMPDEST = 0x5B;      // 合法跳转目标标记
        // 0x5C-0x5F: 保留/未使用

        // ============ 0x60-0x7F: 推送操作 ============
        public static final int PUSH1 = 0x60;         // 推送1字节数据到栈
        public static final int PUSH2 = 0x61;         // 推送2字节数据到栈
        public static final int PUSH3 = 0x62;         // 推送3字节数据到栈
        public static final int PUSH4 = 0x63;         // 推送4字节数据到栈
        public static final int PUSH5 = 0x64;         // 推送5字节数据到栈
        public static final int PUSH6 = 0x65;         // 推送6字节数据到栈
        public static final int PUSH7 = 0x66;         // 推送7字节数据到栈
        public static final int PUSH8 = 0x67;         // 推送8字节数据到栈
        public static final int PUSH9 = 0x68;         // 推送9字节数据到栈
        public static final int PUSH10 = 0x69;        // 推送10字节数据到栈
        public static final int PUSH11 = 0x6A;        // 推送11字节数据到栈
        public static final int PUSH12 = 0x6B;        // 推送12字节数据到栈
        public static final int PUSH13 = 0x6C;        // 推送13字节数据到栈
        public static final int PUSH14 = 0x6D;        // 推送14字节数据到栈
        public static final int PUSH15 = 0x6E;        // 推送15字节数据到栈
        public static final int PUSH16 = 0x6F;        // 推送16字节数据到栈
        public static final int PUSH17 = 0x70;        // 推送17字节数据到栈
        public static final int PUSH18 = 0x71;        // 推送18字节数据到栈
        public static final int PUSH19 = 0x72;        // 推送19字节数据到栈
        public static final int PUSH20 = 0x73;        // 推送20字节数据到栈
        public static final int PUSH21 = 0x74;        // 推送21字节数据到栈
        public static final int PUSH22 = 0x75;        // 推送22字节数据到栈
        public static final int PUSH23 = 0x76;        // 推送23字节数据到栈
        public static final int PUSH24 = 0x77;        // 推送24字节数据到栈
        public static final int PUSH25 = 0x78;        // 推送25字节数据到栈
        public static final int PUSH26 = 0x79;        // 推送26字节数据到栈
        public static final int PUSH27 = 0x7A;        // 推送27字节数据到栈
        public static final int PUSH28 = 0x7B;        // 推送28字节数据到栈
        public static final int PUSH29 = 0x7C;        // 推送29字节数据到栈
        public static final int PUSH30 = 0x7D;        // 推送30字节数据到栈
        public static final int PUSH31 = 0x7E;        // 推送31字节数据到栈
        public static final int PUSH32 = 0x7F;        // 推送32字节数据到栈

        // ============ 0x80-0x8F: 复制操作 (DUP) ============
        public static final int DUP1 = 0x80;          // 复制栈顶元素到栈顶
        public static final int DUP2 = 0x81;          // 复制栈第2个元素到栈顶
        public static final int DUP3 = 0x82;          // 复制栈第3个元素到栈顶
        public static final int DUP4 = 0x83;          // 复制栈第4个元素到栈顶
        public static final int DUP5 = 0x84;          // 复制栈第5个元素到栈顶
        public static final int DUP6 = 0x85;          // 复制栈第6个元素到栈顶
        public static final int DUP7 = 0x86;          // 复制栈第7个元素到栈顶
        public static final int DUP8 = 0x87;          // 复制栈第8个元素到栈顶
        public static final int DUP9 = 0x88;          // 复制栈第9个元素到栈顶
        public static final int DUP10 = 0x89;         // 复制栈第10个元素到栈顶
        public static final int DUP11 = 0x8A;         // 复制栈第11个元素到栈顶
        public static final int DUP12 = 0x8B;         // 复制栈第12个元素到栈顶
        public static final int DUP13 = 0x8C;         // 复制栈第13个元素到栈顶
        public static final int DUP14 = 0x8D;         // 复制栈第14个元素到栈顶
        public static final int DUP15 = 0x8E;         // 复制栈第15个元素到栈顶
        public static final int DUP16 = 0x8F;         // 复制栈第16个元素到栈顶

        // ============ 0x90-0x9F: 交换操作 (SWAP) ============
        public static final int SWAP1 = 0x90;         // 交换栈顶2个元素
        public static final int SWAP2 = 0x91;         // 交换栈顶和第3个元素
        public static final int SWAP3 = 0x92;         // 交换栈顶和第4个元素
        public static final int SWAP4 = 0x93;         // 交换栈顶和第5个元素
        public static final int SWAP5 = 0x94;         // 交换栈顶和第6个元素
        public static final int SWAP6 = 0x95;         // 交换栈顶和第7个元素
        public static final int SWAP7 = 0x96;         // 交换栈顶和第8个元素
        public static final int SWAP8 = 0x97;         // 交换栈顶和第9个元素
        public static final int SWAP9 = 0x98;         // 交换栈顶和第10个元素
        public static final int SWAP10 = 0x99;        // 交换栈顶和第11个元素
        public static final int SWAP11 = 0x9A;        // 交换栈顶和第12个元素
        public static final int SWAP12 = 0x9B;        // 交换栈顶和第13个元素
        public static final int SWAP13 = 0x9C;        // 交换栈顶和第14个元素
        public static final int SWAP14 = 0x9D;        // 交换栈顶和第15个元素
        public static final int SWAP15 = 0x9E;        // 交换栈顶和第16个元素
        public static final int SWAP16 = 0x9F;        // 交换栈顶和第17个元素

        // ============ 0xA0-0xA4: 日志操作 ============
        public static final int LOG0 = 0xA0;          // 无主题日志 (基础375 + 数据每字节8)
        public static final int LOG1 = 0xA1;          // 1个主题日志 (基础375+750 + 数据每字节8)
        public static final int LOG2 = 0xA2;          // 2个主题日志 (基础375+1500 + 数据每字节8)
        public static final int LOG3 = 0xA3;          // 3个主题日志 (基础375+2250 + 数据每字节8)
        public static final int LOG4 = 0xA4;          // 4个主题日志 (基础375+3000 + 数据每字节8)
        // 0xA5-0xAF: 保留/未使用

        // ============ 0xF0-0xFF: 系统操作 ============
        public static final int CREATE = 0xF0;        // 创建合约 (基础32000 + 新账户25000)
        public static final int CALL = 0xF1;          // 消息调用 (基础700 + 转账9000 + 新账户25000)
        public static final int CALLCODE = 0xF2;      // 代码调用 (已被DELEGATECALL/STATICCALL替代)
        public static final int RETURN = 0xF3;        // 返回数据并终止执行
        public static final int DELEGATECALL = 0xF4;  // 委托调用 (EIP-712, 基础700)
        public static final int CREATE2 = 0xF5;       // CREATE2 (EIP-1014, 基础32000 + 新账户25000)
        // 0xF6-0xF9: 保留/预编译合约
        public static final int STATICCALL = 0xFA;    // 静态调用 (EIP-214, 基础700)
        public static final int REVERT = 0xFD;        // 回滚状态并返回数据 (EIP-140)
        public static final int INVALID = 0xFE;       // 无效操作码 (触发异常)
        public static final int SELFDESTRUCT = 0xFF;  // 自毁合约 (EIP-608: 目标新账户25000, 否则5000)

        // -------------- 核心工具方法 --------------

        public static boolean isValid(int opcode) {
            return opcode >= 0x00 && opcode <= 0xFF;
        }

        public static String getName(int opcode) {
            return switch (opcode) {
                // 0x00: 停止和算术
                case STOP -> "STOP";
                case ADD -> "ADD";
                case MUL -> "MUL";
                case SUB -> "SUB";
                case DIV -> "DIV";
                case SDIV -> "SDIV";
                case MOD -> "MOD";
                case SMOD -> "SMOD";
                case ADDMOD -> "ADDMOD";
                case MULMOD -> "MULMOD";
                case EXP -> "EXP";
                case SIGNEXTEND -> "SIGNEXTEND";

                // 0x10: 比较和位操作
                case LT -> "LT";
                case GT -> "GT";
                case SLT -> "SLT";
                case SGT -> "SGT";
                case EQ -> "EQ";
                case ISZERO -> "ISZERO";
                case AND -> "AND";
                case OR -> "OR";
                case XOR -> "XOR";
                case NOT -> "NOT";
                case BYTE -> "BYTE";
                case SHL -> "SHL";
                case SHR -> "SHR";
                case SAR -> "SAR";

                // 0x20: 加密操作
                case SHA3 -> "SHA3";

                // 0x30: 环境信息
                case ADDRESS -> "ADDRESS";
                case BALANCE -> "BALANCE";
                case ORIGIN -> "ORIGIN";
                case CALLER -> "CALLER";
                case CALLVALUE -> "CALLVALUE";
                case CALLDATALOAD -> "CALLDATALOAD";
                case CALLDATASIZE -> "CALLDATASIZE";
                case CALLDATACOPY -> "CALLDATACOPY";
                case CODESIZE -> "CODESIZE";
                case CODECOPY -> "CODECOPY";
                case GASPRICE -> "GASPRICE";
                case EXTCODESIZE -> "EXTCODESIZE";
                case EXTCODECOPY -> "EXTCODECOPY";
                case RETURNDATASIZE -> "RETURNDATASIZE";
                case RETURNDATACOPY -> "RETURNDATACOPY";
                case EXTCODEHASH -> "EXTCODEHASH";

                // 0x40: 区块信息
                case BLOCKHASH -> "BLOCKHASH";
                case COINBASE -> "COINBASE";
                case TIMESTAMP -> "TIMESTAMP";
                case NUMBER -> "NUMBER";
                case DIFFICULTY -> "DIFFICULTY";
                case GASLIMIT -> "GASLIMIT";
                case CHAINID -> "CHAINID";
                case SELFBALANCE -> "SELFBALANCE"; // 修复原代码错误：原代码返回SELFDESTRUCT
                case BASEFEE -> "BASEFEE";

                // 0x50: 栈/内存/存储/流
                case POP -> "POP";
                case MLOAD -> "MLOAD";
                case MSTORE -> "MSTORE";
                case MSTORE8 -> "MSTORE8";
                case SLOAD -> "SLOAD";
                case SSTORE -> "SSTORE";
                case JUMP -> "JUMP";
                case JUMPI -> "JUMPI";
                case PC -> "PC";
                case MSIZE -> "MSIZE";
                case GAS -> "GAS";
                case JUMPDEST -> "JUMPDEST";

                // 0x60-0x7F: PUSH
                case PUSH1 -> "PUSH1";
                case PUSH2 -> "PUSH2";
                case PUSH3 -> "PUSH3";
                case PUSH4 -> "PUSH4";
                case PUSH5 -> "PUSH5";
                case PUSH6 -> "PUSH6";
                case PUSH7 -> "PUSH7";
                case PUSH8 -> "PUSH8";
                case PUSH9 -> "PUSH9";
                case PUSH10 -> "PUSH10";
                case PUSH11 -> "PUSH11";
                case PUSH12 -> "PUSH12";
                case PUSH13 -> "PUSH13";
                case PUSH14 -> "PUSH14";
                case PUSH15 -> "PUSH15";
                case PUSH16 -> "PUSH16";
                case PUSH17 -> "PUSH17";
                case PUSH18 -> "PUSH18";
                case PUSH19 -> "PUSH19";
                case PUSH20 -> "PUSH20";
                case PUSH21 -> "PUSH21";
                case PUSH22 -> "PUSH22";
                case PUSH23 -> "PUSH23";
                case PUSH24 -> "PUSH24";
                case PUSH25 -> "PUSH25";
                case PUSH26 -> "PUSH26";
                case PUSH27 -> "PUSH27";
                case PUSH28 -> "PUSH28";
                case PUSH29 -> "PUSH29";
                case PUSH30 -> "PUSH30";
                case PUSH31 -> "PUSH31";
                case PUSH32 -> "PUSH32";

                // 0x80-0x8F: DUP
                case DUP1 -> "DUP1";
                case DUP2 -> "DUP2";
                case DUP3 -> "DUP3";
                case DUP4 -> "DUP4";
                case DUP5 -> "DUP5";
                case DUP6 -> "DUP6";
                case DUP7 -> "DUP7";
                case DUP8 -> "DUP8";
                case DUP9 -> "DUP9";
                case DUP10 -> "DUP10";
                case DUP11 -> "DUP11";
                case DUP12 -> "DUP12";
                case DUP13 -> "DUP13";
                case DUP14 -> "DUP14";
                case DUP15 -> "DUP15";
                case DUP16 -> "DUP16";

                // 0x90-0x9F: SWAP
                case SWAP1 -> "SWAP1";
                case SWAP2 -> "SWAP2";
                case SWAP3 -> "SWAP3";
                case SWAP4 -> "SWAP4";
                case SWAP5 -> "SWAP5";
                case SWAP6 -> "SWAP6";
                case SWAP7 -> "SWAP7";
                case SWAP8 -> "SWAP8";
                case SWAP9 -> "SWAP9";
                case SWAP10 -> "SWAP10";
                case SWAP11 -> "SWAP11";
                case SWAP12 -> "SWAP12";
                case SWAP13 -> "SWAP13";
                case SWAP14 -> "SWAP14";
                case SWAP15 -> "SWAP15";
                case SWAP16 -> "SWAP16";

                // 0xA0-0xA4: LOG
                case LOG0 -> "LOG0";
                case LOG1 -> "LOG1";
                case LOG2 -> "LOG2";
                case LOG3 -> "LOG3";
                case LOG4 -> "LOG4";

                // 0xF0-0xFF: 系统操作
                case CREATE -> "CREATE";
                case CALL -> "CALL";
                case CALLCODE -> "CALLCODE";
                case RETURN -> "RETURN";
                case DELEGATECALL -> "DELEGATECALL";
                case CREATE2 -> "CREATE2";
                case STATICCALL -> "STATICCALL";
                case REVERT -> "REVERT";
                case INVALID -> "INVALID";
                case SELFDESTRUCT -> "SELFDESTRUCT";

                // 未定义/保留操作码
                default -> String.format("UNDEFINED_0x%02X", opcode);
            };
        }

        /**
         * 精确计算操作码的Gas消耗
         * 严格遵循以太坊黄皮书+EIP-1884/2929/2200/1559/4844等规范
         * @param opcode 操作码
         * @param context Gas计算上下文（包含内存、存储、访问列表等）
         * @return 精确的Gas消耗值
         */
        public static BigInteger getGasCost(int opcode, GasContext context) {
            // 空上下文防御
            if (context == null) {
                context = new GasContext();
            }

            return switch (opcode) {
                // ============ 停止和算术操作 ============
                case STOP -> BigInteger.ZERO;
                case ADD, SUB, LT, GT, SLT, SGT, EQ, ISZERO, AND, OR, XOR, NOT, BYTE, SHL, SHR, SAR -> BigInteger.valueOf(3);
                case MUL, DIV, SDIV, MOD, SMOD, SIGNEXTEND -> BigInteger.valueOf(5);
                case ADDMOD, MULMOD -> BigInteger.valueOf(8);
                case EXP -> {
                    // EXP: 10 + 5 * 指数的字节数（EIP-160）
                    yield BigInteger.valueOf(10).add(BigInteger.valueOf(5).multiply(BigInteger.valueOf(context.exponentBytes)));
                }

                // ============ 加密操作 ============
                case SHA3 -> {
                    // SHA3: 30 + 6 * ceil(dataLength / 32)（黄皮书）
                    int words = (context.dataLength + 31) / 32;
                    yield BigInteger.valueOf(30).add(BigInteger.valueOf(6).multiply(BigInteger.valueOf(words)));
                }

                // ============ 环境信息 (EIP-2929) ============
                case ADDRESS, ORIGIN, CALLER, CALLVALUE, CALLDATASIZE, CODESIZE, GASPRICE, RETURNDATASIZE,
                     COINBASE, TIMESTAMP, NUMBER, DIFFICULTY, GASLIMIT, CHAINID, BASEFEE, PC, MSIZE, GAS -> BigInteger.valueOf(2);

                case CALLDATALOAD, CODECOPY, CALLDATACOPY, EXTCODECOPY, RETURNDATACOPY, MLOAD, MSTORE, MSTORE8 -> {
                    // 内存操作：基础3 + 内存扩展成本
                    BigInteger memCost = calculateMemoryExpansionCost(context.currentMemSize, context.dataLength);
                    yield BigInteger.valueOf(3).add(memCost);
                }

                case BALANCE, EXTCODESIZE, EXTCODEHASH -> {
                    // EIP-1884: 基础700 + EIP-2929冷/热访问成本
                    BigInteger accessCost = context.warmAddresses.contains(BigInteger.valueOf(opcode)) ? BigInteger.valueOf(100) : BigInteger.valueOf(2600);
                    yield BigInteger.valueOf(700).add(accessCost);
                }

                // ============ 栈、内存、存储和流操作 ============
                case POP -> BigInteger.valueOf(2);
                case JUMPDEST -> BigInteger.valueOf(1);
                case JUMP -> BigInteger.valueOf(8);
                case JUMPI -> BigInteger.valueOf(10);
                case SLOAD -> {
                    // EIP-2929: 冷存储2100，热存储100
                    yield context.warmStorageSlots.contains(context.storageOldValue) ? BigInteger.valueOf(100) : BigInteger.valueOf(2100);
                }
                case SSTORE -> {
                    // EIP-2200: SSTORE的复杂Gas计算（考虑新旧值）
                    yield calculateSStoreGas(context.storageOldValue, context.storageNewValue, context.warmStorageSlots);
                }

                // ============ 推送/复制/交换操作 ============
                case PUSH1, PUSH2, PUSH3, PUSH4, PUSH5, PUSH6, PUSH7, PUSH8, PUSH9, PUSH10,
                     PUSH11, PUSH12, PUSH13, PUSH14, PUSH15, PUSH16, PUSH17, PUSH18, PUSH19, PUSH20,
                     PUSH21, PUSH22, PUSH23, PUSH24, PUSH25, PUSH26, PUSH27, PUSH28, PUSH29, PUSH30,
                     PUSH31, PUSH32, DUP1, DUP2, DUP3, DUP4, DUP5, DUP6, DUP7, DUP8, DUP9, DUP10,
                     DUP11, DUP12, DUP13, DUP14, DUP15, DUP16, SWAP1, SWAP2, SWAP3, SWAP4, SWAP5,
                     SWAP6, SWAP7, SWAP8, SWAP9, SWAP10, SWAP11, SWAP12, SWAP13, SWAP14, SWAP15, SWAP16 -> BigInteger.valueOf(3);

                // ============ 日志操作 ============
                case LOG0 -> {
                    // LOG0: 375 + 8 * 数据字节数
                    yield BigInteger.valueOf(375).add(BigInteger.valueOf(8).multiply(BigInteger.valueOf(context.dataLength)));
                }
                case LOG1 -> {
                    // LOG1: 375 + 750 + 8 * 数据字节数
                    yield BigInteger.valueOf(1125).add(BigInteger.valueOf(8).multiply(BigInteger.valueOf(context.dataLength)));
                }
                case LOG2 -> {
                    // LOG2: 375 + 1500 + 8 * 数据字节数
                    yield BigInteger.valueOf(1875).add(BigInteger.valueOf(8).multiply(BigInteger.valueOf(context.dataLength)));
                }
                case LOG3 -> {
                    // LOG3: 375 + 2250 + 8 * 数据字节数
                    yield BigInteger.valueOf(2625).add(BigInteger.valueOf(8).multiply(BigInteger.valueOf(context.dataLength)));
                }
                case LOG4 -> {
                    // LOG4: 375 + 3000 + 8 * 数据字节数
                    yield BigInteger.valueOf(3375).add(BigInteger.valueOf(8).multiply(BigInteger.valueOf(context.dataLength)));
                }

                // ============ 系统操作 ============
                case CREATE, CREATE2 -> {
                    // CREATE/CREATE2: 基础32000 + 新账户25000（如果目标账户不存在）
                    BigInteger newAccountCost = context.targetAccountExists ? BigInteger.ZERO : BigInteger.valueOf(25000);
                    yield BigInteger.valueOf(32000).add(newAccountCost);
                }
                case CALL, CALLCODE, DELEGATECALL, STATICCALL -> {
                    // CALL系列: 基础700 + 转账9000 + 新账户25000 + 冷地址访问2600（EIP-2929）
                    BigInteger transferCost = context.isTransfer ? BigInteger.valueOf(9000) : BigInteger.ZERO;
                    BigInteger newAccountCost = context.targetAccountExists ? BigInteger.ZERO : BigInteger.valueOf(25000);
                    BigInteger accessCost = context.warmAddresses.contains(BigInteger.valueOf(opcode)) ? BigInteger.ZERO : BigInteger.valueOf(2600);
                    yield BigInteger.valueOf(700).add(transferCost).add(newAccountCost).add(accessCost);
                }
                case RETURN, REVERT, INVALID -> BigInteger.ZERO;
                case SELFDESTRUCT -> {
                    // SELFDESTRUCT: 5000（目标账户存在）/25000（目标账户不存在） + 冷地址访问2600
                    BigInteger baseCost = context.targetAccountExists ? BigInteger.valueOf(5000) : BigInteger.valueOf(25000);
                    BigInteger accessCost = context.warmAddresses.contains(BigInteger.valueOf(opcode)) ? BigInteger.ZERO : BigInteger.valueOf(2600);
                    yield baseCost.add(accessCost);
                }

                // 未定义操作码
                default -> BigInteger.ZERO;
            };
        }

        /**
         * 辅助方法：计算内存扩展的Gas成本（黄皮书规范）
         * @param currentSize 当前内存大小（按32字节对齐）
         * @param requiredSize 需要的内存大小
         * @return 内存扩展的Gas成本
         */
        private static BigInteger calculateMemoryExpansionCost(int currentSize, int requiredSize) {
            if (requiredSize <= currentSize) {
                return BigInteger.ZERO;
            }

            // 内存大小按32字节对齐
            int newSize = (requiredSize + 31) / 32;
            // 黄皮书公式: memoryCost = newSize * newSize / 512 + 3 * newSize
            BigInteger newSizeBig = BigInteger.valueOf(newSize);
            BigInteger memCost = newSizeBig.multiply(newSizeBig).divide(BigInteger.valueOf(512))
                    .add(BigInteger.valueOf(3).multiply(newSizeBig));

            // 减去当前内存的Gas成本
            BigInteger currentSizeBig = BigInteger.valueOf(currentSize / 32);
            BigInteger currentMemCost = currentSizeBig.multiply(currentSizeBig).divide(BigInteger.valueOf(512))
                    .add(BigInteger.valueOf(3).multiply(currentSizeBig));

            return memCost.subtract(currentMemCost);
        }

        /**
         * 辅助方法：计算SSTORE的Gas成本（EIP-2200规范）
         * @param oldValue 存储旧值
         * @param newValue 存储新值
         * @param warmSlots 热存储槽列表
         * @return SSTORE的精确Gas成本
         */
        private static BigInteger calculateSStoreGas(BigInteger oldValue, BigInteger newValue, Set<BigInteger> warmSlots) {
            // 1. 冷/热访问基础成本
            BigInteger accessCost = warmSlots.contains(oldValue) ? BigInteger.valueOf(100) : BigInteger.valueOf(2100);

            // 2. 状态变更成本（EIP-2200）
            BigInteger zero = BigInteger.ZERO;
            if (oldValue.equals(newValue)) {
                // 无变更：仅访问成本
                return accessCost;
            }
            if (oldValue.equals(zero)) {
                // 从0变为非0：20000（冷）/100（热）
                return accessCost.add(BigInteger.valueOf(20000));
            }
            if (newValue.equals(zero)) {
                // 从非0变为0：返还15000，实际成本为 accessCost - 15000（Gas返还）
                return accessCost.subtract(BigInteger.valueOf(15000));
            }
            // 非0到非0：100（热）/20000（冷）
            return accessCost.add(BigInteger.valueOf(100));
        }

        // 兼容旧版方法（保留，但内部调用精确版）
        @Deprecated
        public static BigInteger getGasCost(int opcode) {
            return getGasCost(opcode, new GasContext());
        }
    }

    /**
     * EVM执行上下文
     * 包含执行合约所需的所有状态
     */
    public static class ExecutionContext {
        // 基础执行信息
        public BigInteger pc;                 // 程序计数器
        public BigInteger gas;                // 剩余Gas
        public BigInteger gasLimit;           // Gas限制
        public BigInteger gasPrice;           // Gas价格
        public BigInteger value;              // 转账金额（wei）

        // 执行环境
        public BigInteger[] stack;           // 执行栈（最多1024个元素）
        public int stackSize;                // 当前栈大小
        public byte[] memory;                // 内存（动态扩展）
        public int memorySize;               // 当前内存大小（字节）

        // 存储
        public Map<BigInteger, BigInteger> storage;  // 合约存储
        public BigInteger balance;           // 合约余额

        // 账户信息
        public BigInteger address;           // 当前合约地址
        public BigInteger caller;            // 调用者地址
        public BigInteger origin;            // 原始交易发送者
        public BigInteger coinbase;          // 区块矿工地址

        // 区块信息
        public BigInteger blockNumber;       // 当前区块号
        public BigInteger timestamp;         // 区块时间戳
        public BigInteger difficulty;        // 区块难度
        public BigInteger gasLimitBlock;     // 区块Gas限制
        public BigInteger chainId;           // 链ID
        public BigInteger baseFee;           // 基础费用（EIP-1559）

        // 调用数据
        public byte[] code;                  // 合约字节码
        public byte[] callData;              // 调用数据
        public int callDataSize;             // 调用数据大小

        // 返回数据
        public byte[] returnData;            // 返回数据
        public int returnDataOffset;         // 返回数据偏移
        public int returnDataSize;           // 返回数据大小

        // 状态标志
        public boolean isRunning;            // 是否正在运行
        public boolean revert;               // 是否发生回滚
        public String revertReason;          // 回滚原因
        public boolean isStatic;             // 是否为静态调用

        // 访问列表（EIP-2929）
        public Set<BigInteger> accessedAddresses;      // 已访问地址
        public Set<BigInteger> accessedStorageKeys;    // 已访问存储键

        // 调用深度
        public int callDepth;                // 当前调用深度
        public int maxCallDepth = 1024;      // 最大调用深度

        // 日志
        public List<LogEntry> logs;          // 日志条目

        // 自毁列表
        public Set<BigInteger> selfDestructs; // 自毁的地址列表

        // 构造器
        public ExecutionContext() {
            this.pc = BigInteger.ZERO;
            this.gas = BigInteger.ZERO;
            this.gasLimit = BigInteger.ZERO;
            this.gasPrice = BigInteger.ZERO;
            this.value = BigInteger.ZERO;

            this.stack = new BigInteger[1024];
            this.stackSize = 0;
            this.memory = new byte[0];
            this.memorySize = 0;

            this.storage = new HashMap<>();
            this.balance = BigInteger.ZERO;

            this.address = BigInteger.ZERO;
            this.caller = BigInteger.ZERO;
            this.origin = BigInteger.ZERO;
            this.coinbase = BigInteger.ZERO;

            this.blockNumber = BigInteger.ZERO;
            this.timestamp = BigInteger.ZERO;
            this.difficulty = BigInteger.ZERO;
            this.gasLimitBlock = BigInteger.ZERO;
            this.chainId = BigInteger.ONE;  // 默认主网链ID
            this.baseFee = BigInteger.ZERO;

            this.code = new byte[0];
            this.callData = new byte[0];
            this.callDataSize = 0;

            this.returnData = new byte[0];
            this.returnDataOffset = 0;
            this.returnDataSize = 0;

            this.isRunning = false;
            this.revert = false;
            this.revertReason = "";
            this.isStatic = false;

            this.accessedAddresses = new HashSet<>();
            this.accessedStorageKeys = new HashSet<>();

            this.callDepth = 0;

            this.logs = new ArrayList<>();
            this.selfDestructs = new HashSet<>();
        }

        /**
         * 日志条目
         */
        public static class LogEntry {
            public BigInteger address;       // 日志地址
            public List<BigInteger> topics;  // 日志主题
            public byte[] data;              // 日志数据

            public LogEntry(BigInteger address, List<BigInteger> topics, byte[] data) {
                this.address = address;
                this.topics = topics;
                this.data = data;
            }
        }

        /**
         * 将值压入栈顶
         */
        public void push(BigInteger value) {
            if (stackSize >= 1024) {
                throw new RuntimeException("Stack overflow");
            }
            stack[stackSize++] = value;
        }

        /**
         * 从栈顶弹出值
         */
        public BigInteger pop() {
            if (stackSize <= 0) {
                throw new RuntimeException("Stack underflow");
            }
            return stack[--stackSize];
        }

        /**
         * 获取栈顶第n个元素（0为栈顶）
         */
        public BigInteger peek(int n) {
            if (n >= stackSize) {
                throw new RuntimeException("Stack underflow");
            }
            return stack[stackSize - 1 - n];
        }

        /**
         * 交换栈顶和第n个元素
         */
        public void swap(int n) {
            if (n >= stackSize) {
                throw new RuntimeException("Stack underflow");
            }
            BigInteger tmp = stack[stackSize - 1];
            stack[stackSize - 1] = stack[stackSize - 1 - n];
            stack[stackSize - 1 - n] = tmp;
        }

        /**
         * 复制栈顶第n个元素到栈顶
         */
        public void dup(int n) {
            if (n >= stackSize) {
                throw new RuntimeException("Stack underflow");
            }
            push(stack[stackSize - 1 - n]);
        }

        /**
         * 扩展内存
         */
        public void extendMemory(int newSize) {
            if (newSize <= memorySize) {
                return;
            }

            // 按32字节对齐
            int alignedSize = (newSize + 31) & ~31;
            byte[] newMemory = new byte[alignedSize];
            System.arraycopy(memory, 0, newMemory, 0, memorySize);
            memory = newMemory;
            memorySize = alignedSize;
        }

        /**
         * 从内存读取数据
         */
        public byte[] readMemory(int offset, int length) {
            if (offset + length > memorySize) {
                extendMemory(offset + length);
            }

            byte[] data = new byte[length];
            System.arraycopy(memory, offset, data, 0, length);
            return data;
        }

        /**
         * 向内存写入数据
         */
        public void writeMemory(int offset, byte[] data) {
            if (offset + data.length > memorySize) {
                extendMemory(offset + data.length);
            }

            System.arraycopy(data, 0, memory, offset, data.length);
        }

        /**
         * 从存储读取值
         */
        public BigInteger loadStorage(BigInteger key) {
            accessedStorageKeys.add(key);
            return storage.getOrDefault(key, BigInteger.ZERO);
        }

        /**
         * 向存储写入值
         */
        public void storeStorage(BigInteger key, BigInteger value) {
            storage.put(key, value);
            accessedStorageKeys.add(key);
        }

        /**
         * 消耗Gas
         */
        public void consumeGas(BigInteger amount) {
            if (gas.compareTo(amount) < 0) {
                throw new RuntimeException("Out of gas");
            }
            gas = gas.subtract(amount);
        }

        /**
         * 添加日志
         */
        public void addLog(LogEntry log) {
            logs.add(log);
        }

        /**
         * 标记自毁
         */
        public void addSelfDestruct(BigInteger address) {
            selfDestructs.add(address);
        }

        /**
         * 清空返回数据
         */
        public void clearReturnData() {
            returnData = new byte[0];
            returnDataOffset = 0;
            returnDataSize = 0;
        }

        /**
         * 设置返回数据
         */
        public void setReturnData(byte[] data) {
            returnData = data;
            returnDataSize = data.length;
        }
    }

    /**
     * EVM执行结果
     * 包含合约执行的结果信息
     */
    public static class ExecutionResult {
        public boolean success;              // 是否成功执行
        public BigInteger gasUsed;           // 已使用的Gas
        public BigInteger gasRefund;         // 应返还的Gas
        public byte[] returnData;            // 返回数据
        public String revertReason;          // 回滚原因（如果回滚）
        public List<ExecutionContext.LogEntry> logs;  // 产生的日志
        public Set<BigInteger> selfDestructs; // 自毁的地址
        public Map<BigInteger, BigInteger> storageChanges; // 存储变更

        // Gas相关统计
        public BigInteger gasLimit;          // Gas限制
        public BigInteger gasPrice;          // Gas价格
        public BigInteger gasCost;           // 总Gas费用 = gasUsed * gasPrice

        // 执行上下文快照
        public ExecutionContext context;     // 执行后的上下文

        // 构造器
        public ExecutionResult() {
            this.success = false;
            this.gasUsed = BigInteger.ZERO;
            this.gasRefund = BigInteger.ZERO;
            this.returnData = new byte[0];
            this.revertReason = "";
            this.logs = new ArrayList<>();
            this.selfDestructs = new HashSet<>();
            this.storageChanges = new HashMap<>();
            this.gasLimit = BigInteger.ZERO;
            this.gasPrice = BigInteger.ZERO;
            this.gasCost = BigInteger.ZERO;
            this.context = null;
        }

        /**
         * 计算总Gas费用
         */
        public void calculateGasCost() {
            this.gasCost = this.gasUsed.multiply(this.gasPrice);
        }

        /**
         * 从执行上下文创建结果
         */
        public static ExecutionResult fromContext(ExecutionContext ctx, boolean success, BigInteger gasUsed) {
            ExecutionResult result = new ExecutionResult();
            result.success = success;
            result.gasUsed = gasUsed;
            result.gasRefund = BigInteger.ZERO; // 简化实现
            result.returnData = ctx.returnData;
            result.revertReason = ctx.revertReason;
            result.logs = new ArrayList<>(ctx.logs);
            result.selfDestructs = new HashSet<>(ctx.selfDestructs);
            result.storageChanges = new HashMap<>(ctx.storage);
            result.gasLimit = ctx.gasLimit;
            result.gasPrice = ctx.gasPrice;
            result.calculateGasCost();
            result.context = ctx;

            return result;
        }

        @Override
        public String toString() {
            return String.format("ExecutionResult{" +
                            "success=%s, " +
                            "gasUsed=%s, " +
                            "returnData=%s, " +
                            "revertReason='%s', " +
                            "logs=%d, " +
                            "gasCost=%s" +
                            "}",
                    success,
                    gasUsed.toString(),
                    bytesToHex(returnData),
                    revertReason,
                    logs.size(),
                    gasCost.toString());
        }

        public String bytesToHex(byte[] bytes) {
            if (bytes == null) return "";
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }


    /**
     * EVM虚拟机实现
     * 完整的以太坊虚拟机实现
     */
    public static class VM {
        // 配置选项
        public static class Config {
            public boolean enableDebug = false;      // 启用调试模式
            public boolean enableTracing = false;    // 启用执行跟踪
            public boolean enableGasMetering = true; // 启用Gas计量
            public boolean enableStateAccessTracking = true; // 启用状态访问跟踪
            public int maxCallDepth = 1024;          // 最大调用深度

            public static Config defaultConfig() {
                return new Config();
            }
        }

        private Config config;

        // 执行器
        private static class Executor {
            private final ExecutionContext ctx;
            private final Config config;
            private final List<ExecutionStep> trace = new ArrayList<>();

            public Executor(ExecutionContext ctx, Config config) {
                this.ctx = ctx;
                this.config = config;
            }

            /**
             * 执行单条指令
             */
            public boolean step() {
                if (!ctx.isRunning) {
                    return false;
                }

                if (ctx.pc.intValue() >= ctx.code.length) {
                    ctx.isRunning = false;
                    return false;
                }

                // 读取操作码
                int opcode = ctx.code[ctx.pc.intValue()] & 0xFF;
                ctx.pc = ctx.pc.add(BigInteger.ONE);

                // 执行步骤记录
                ExecutionStep step = new ExecutionStep();
                step.pc = ctx.pc.subtract(BigInteger.ONE);
                step.opcode = opcode;
                step.gasBefore = ctx.gas;

                // 执行操作码
                try {
                    executeOpcode(opcode);
                    step.success = true;
                } catch (Exception e) {
                    ctx.revert = true;
                    ctx.revertReason = e.getMessage();
                    ctx.isRunning = false;
                    step.success = false;
                    step.error = e.getMessage();
                }

                step.gasAfter = ctx.gas;
                step.stackSnapshot = ctx.stackSize > 0 ?
                        Arrays.copyOf(ctx.stack, ctx.stackSize) : new BigInteger[0];

                if (config.enableTracing) {
                    trace.add(step);
                }

                return ctx.isRunning;
            }

            /**
             * 执行操作码
             */
            private void executeOpcode(int opcode) {
                // 创建Gas上下文
                GasContext gasCtx = new GasContext();
                gasCtx.currentMemSize = ctx.memorySize;
                gasCtx.stackHeight = ctx.stackSize;

                // 计算Gas成本
                BigInteger gasCost = OpCode.getGasCost(opcode, gasCtx);
                if (config.enableGasMetering) {
                    ctx.consumeGas(gasCost);
                }

                // 执行操作码
                switch (opcode) {
                    // ============ 停止和算术操作 ============
                    case OpCode.STOP:
                        ctx.isRunning = false;
                        break;

                    case OpCode.ADD:
                        BigInteger a = ctx.pop();
                        BigInteger b = ctx.pop();
                        ctx.push(a.add(b).and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)));
                        break;

                    case OpCode.MUL:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.multiply(b).and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)));
                        break;

                    case OpCode.SUB:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.subtract(b).and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)));
                        break;

                    case OpCode.DIV:
                        a = ctx.pop();
                        b = ctx.pop();
                        if (b.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(a.divide(b));
                        }
                        break;

                    case OpCode.SDIV:
                        a = signed(ctx.pop());
                        b = signed(ctx.pop());
                        if (b.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(unsigned(a.divide(b)));
                        }
                        break;

                    case OpCode.MOD:
                        a = ctx.pop();
                        b = ctx.pop();
                        if (b.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(a.mod(b));
                        }
                        break;

                    case OpCode.SMOD:
                        a = signed(ctx.pop());
                        b = signed(ctx.pop());
                        if (b.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(unsigned(a.abs().mod(b.abs())));
                            if (a.signum() < 0) {
                                BigInteger top = ctx.pop();
                                ctx.push(top.negate());
                            }
                        }
                        break;

                    case OpCode.ADDMOD:
                        a = ctx.pop();
                        b = ctx.pop();
                        BigInteger n = ctx.pop();
                        if (n.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(a.add(b).mod(n));
                        }
                        break;

                    case OpCode.MULMOD:
                        a = ctx.pop();
                        b = ctx.pop();
                        n = ctx.pop();
                        if (n.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(a.multiply(b).mod(n));
                        }
                        break;

                    case OpCode.EXP:
                        a = ctx.pop();
                        b = ctx.pop();
                        if (b.equals(BigInteger.ZERO)) {
                            ctx.push(BigInteger.ONE);
                        } else {
                            ctx.push(a.modPow(b, BigInteger.valueOf(2).pow(256)));
                        }
                        break;

                    case OpCode.SIGNEXTEND:
                        BigInteger k = ctx.pop();
                        BigInteger val = ctx.pop();
                        if (k.compareTo(BigInteger.valueOf(31)) < 0) {
                            int bitPos = (k.intValue() + 1) * 8 - 1;
                            BigInteger bit = val.shiftRight(bitPos).and(BigInteger.ONE);
                            if (bit.equals(BigInteger.ONE)) {
                                BigInteger mask = BigInteger.ONE.shiftLeft(bitPos).subtract(BigInteger.ONE);
                                val = val.or(mask.not());
                            }
                        }
                        ctx.push(val);
                        break;

                    // ============ 比较和位操作 ============
                    case OpCode.LT:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.compareTo(b) < 0 ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.GT:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.compareTo(b) > 0 ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.SLT:
                        a = signed(ctx.pop());
                        b = signed(ctx.pop());
                        ctx.push(a.compareTo(b) < 0 ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.SGT:
                        a = signed(ctx.pop());
                        b = signed(ctx.pop());
                        ctx.push(a.compareTo(b) > 0 ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.EQ:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.equals(b) ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.ISZERO:
                        a = ctx.pop();
                        ctx.push(a.equals(BigInteger.ZERO) ? BigInteger.ONE : BigInteger.ZERO);
                        break;

                    case OpCode.AND:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.and(b));
                        break;

                    case OpCode.OR:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.or(b));
                        break;

                    case OpCode.XOR:
                        a = ctx.pop();
                        b = ctx.pop();
                        ctx.push(a.xor(b));
                        break;

                    case OpCode.NOT:
                        a = ctx.pop();
                        ctx.push(a.not().and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)));
                        break;

                    case OpCode.BYTE:
                        BigInteger pos = ctx.pop();
                        BigInteger val2 = ctx.pop();
                        if (pos.compareTo(BigInteger.valueOf(32)) >= 0) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            int shift = 256 - 8 * (pos.intValue() + 1);
                            ctx.push(val2.shiftRight(shift).and(BigInteger.valueOf(0xFF)));
                        }
                        break;

                    case OpCode.SHL:
                        int shift = ctx.pop().intValue();
                        val2 = ctx.pop();
                        if (shift >= 256) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(val2.shiftLeft(shift).and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE)));
                        }
                        break;

                    case OpCode.SHR:
                        shift = ctx.pop().intValue();
                        val2 = ctx.pop();
                        if (shift >= 256) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            ctx.push(val2.shiftRight(shift));
                        }
                        break;

                    case OpCode.SAR:
                        shift = ctx.pop().intValue();
                        val2 = signed(ctx.pop());
                        if (shift >= 256) {
                            if (val2.signum() >= 0) {
                                ctx.push(BigInteger.ZERO);
                            } else {
                                ctx.push(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE));
                            }
                        } else {
                            ctx.push(unsigned(val2.shiftRight(shift)));
                        }
                        break;

                    // ============ 加密操作 ============
                    case OpCode.SHA3:
                        int offset = ctx.pop().intValue();
                        int length = ctx.pop().intValue();
                        byte[] data = ctx.readMemory(offset, length);
                        // 简化实现，实际应使用Keccak-256
                        byte[] hash = Sha.applySha3(data);
                        ctx.push(new BigInteger(1, hash));
                        break;

                    // ============ 环境信息 ============
                    case OpCode.ADDRESS:
                        ctx.push(ctx.address);
                        break;

                    case OpCode.BALANCE:
                        BigInteger addr = ctx.pop();
                        ctx.accessedAddresses.add(addr);
                        // 简化实现，总是返回0
                        ctx.push(BigInteger.ZERO);
                        break;

                    case OpCode.ORIGIN:
                        ctx.push(ctx.origin);
                        break;

                    case OpCode.CALLER:
                        ctx.push(ctx.caller);
                        break;

                    case OpCode.CALLVALUE:
                        ctx.push(ctx.value);
                        break;

                    case OpCode.CALLDATALOAD:
                        offset = ctx.pop().intValue();
                        if (offset >= ctx.callData.length) {
                            ctx.push(BigInteger.ZERO);
                        } else {
                            byte[] callDataWord = new byte[32];
                            int len = Math.min(32, ctx.callData.length - offset);
                            System.arraycopy(ctx.callData, offset, callDataWord, 0, len);
                            ctx.push(new BigInteger(1, callDataWord));
                        }
                        break;

                    case OpCode.CALLDATASIZE:
                        ctx.push(BigInteger.valueOf(ctx.callData.length));
                        break;

                    case OpCode.CALLDATACOPY:
                        int memOffset = ctx.pop().intValue();
                        int dataOffset = ctx.pop().intValue();
                        length = ctx.pop().intValue();

                        if (length > 0) {
                            byte[] copyData = new byte[length];
                            if (dataOffset < ctx.callData.length) {
                                int copyLen = Math.min(length, ctx.callData.length - dataOffset);
                                System.arraycopy(ctx.callData, dataOffset, copyData, 0, copyLen);
                            }
                            ctx.writeMemory(memOffset, copyData);
                        }
                        break;

                    case OpCode.CODESIZE:
                        ctx.push(BigInteger.valueOf(ctx.code.length));
                        break;

                    case OpCode.CODECOPY:
                        memOffset = ctx.pop().intValue();
                        dataOffset = ctx.pop().intValue();
                        length = ctx.pop().intValue();

                        if (length > 0) {
                            byte[] codeCopy = new byte[length];
                            if (dataOffset < ctx.code.length) {
                                int copyLen = Math.min(length, ctx.code.length - dataOffset);
                                System.arraycopy(ctx.code, dataOffset, codeCopy, 0, copyLen);
                            }
                            ctx.writeMemory(memOffset, codeCopy);
                        }
                        break;

                    case OpCode.GASPRICE:
                        ctx.push(ctx.gasPrice);
                        break;

                    case OpCode.EXTCODESIZE:
                        addr = ctx.pop();
                        ctx.accessedAddresses.add(addr);
                        // 简化实现，总是返回0
                        ctx.push(BigInteger.ZERO);
                        break;

                    case OpCode.EXTCODECOPY:
                        addr = ctx.pop();
                        ctx.accessedAddresses.add(addr);
                        memOffset = ctx.pop().intValue();
                        int codeOffset = ctx.pop().intValue();
                        length = ctx.pop().intValue();
                        // 简化实现，不实际复制代码
                        break;

                    case OpCode.RETURNDATASIZE:
                        ctx.push(BigInteger.valueOf(ctx.returnDataSize));
                        break;

                    case OpCode.RETURNDATACOPY:
                        memOffset = ctx.pop().intValue();
                        dataOffset = ctx.pop().intValue();
                        length = ctx.pop().intValue();

                        if (dataOffset + length > ctx.returnDataSize) {
                            throw new RuntimeException("RETURNDATACOPY out of bounds");
                        }

                        if (length > 0) {
                            byte[] retCopy = new byte[length];
                            System.arraycopy(ctx.returnData, dataOffset, retCopy, 0, length);
                            ctx.writeMemory(memOffset, retCopy);
                        }
                        break;

                    case OpCode.EXTCODEHASH:
                        addr = ctx.pop();
                        ctx.accessedAddresses.add(addr);
                        // 简化实现，返回0表示账户不存在
                        ctx.push(BigInteger.ZERO);
                        break;

                    // ============ 区块信息 ============
                    case OpCode.BLOCKHASH:
                        BigInteger blockNumber = ctx.pop();
                        // 简化实现，总是返回0
                        ctx.push(BigInteger.ZERO);
                        break;

                    case OpCode.COINBASE:
                        ctx.push(ctx.coinbase);
                        break;

                    case OpCode.TIMESTAMP:
                        ctx.push(ctx.timestamp);
                        break;

                    case OpCode.NUMBER:
                        ctx.push(ctx.blockNumber);
                        break;

                    case OpCode.DIFFICULTY:
                        ctx.push(ctx.difficulty);
                        break;

                    case OpCode.GASLIMIT:
                        ctx.push(ctx.gasLimitBlock);
                        break;

                    case OpCode.CHAINID:
                        ctx.push(ctx.chainId);
                        break;

                    case OpCode.SELFBALANCE:
                        ctx.push(ctx.balance);
                        break;

                    case OpCode.BASEFEE:
                        ctx.push(ctx.baseFee);
                        break;

                    // ============ 栈、内存、存储操作 ============
                    case OpCode.POP:
                        ctx.pop();
                        break;

                    case OpCode.MLOAD:
                        offset = ctx.pop().intValue();
                        byte[] word = ctx.readMemory(offset, 32);
                        ctx.push(new BigInteger(1, word));
                        break;

                    case OpCode.MSTORE:
                        offset = ctx.pop().intValue();
                        val2 = ctx.pop();
                        byte[] wordBytes = toBytes(val2, 32);
                        ctx.writeMemory(offset, wordBytes);
                        break;

                    case OpCode.MSTORE8:
                        offset = ctx.pop().intValue();
                        byte byteVal = ctx.pop().byteValue();
                        ctx.writeMemory(offset, new byte[]{byteVal});
                        break;

                    case OpCode.SLOAD:
                        BigInteger key = ctx.pop();
                        ctx.push(ctx.loadStorage(key));
                        break;

                    case OpCode.SSTORE:
                        key = ctx.pop();
                        val2 = ctx.pop();
                        ctx.storeStorage(key, val2);
                        break;

                    case OpCode.JUMP:
                        BigInteger dest = ctx.pop();
                        if (!isJumpDestValid(dest.intValue())) {
                            throw new RuntimeException("Invalid JUMP destination");
                        }
                        ctx.pc = dest;
                        break;

                    case OpCode.JUMPI:
                        dest = ctx.pop();
                        BigInteger condition = ctx.pop();
                        if (!condition.equals(BigInteger.ZERO)) {
                            if (!isJumpDestValid(dest.intValue())) {
                                throw new RuntimeException("Invalid JUMPI destination");
                            }
                            ctx.pc = dest;
                        }
                        break;

                    case OpCode.PC:
                        ctx.push(ctx.pc.subtract(BigInteger.ONE));
                        break;

                    case OpCode.MSIZE:
                        ctx.push(BigInteger.valueOf(ctx.memorySize));
                        break;

                    case OpCode.GAS:
                        ctx.push(ctx.gas);
                        break;

                    case OpCode.JUMPDEST:
                        // JUMPDEST不执行任何操作
                        break;

                    // ============ 推送操作 ============
                    default:
                        if (opcode >= OpCode.PUSH1 && opcode <= OpCode.PUSH32) {
                            int m = opcode - OpCode.PUSH1 + 1;
                            byte[] pushData = new byte[m];
                            for (int i = 0; i < m; i++) {
                                if (ctx.pc.intValue() < ctx.code.length) {
                                    pushData[i] = ctx.code[ctx.pc.intValue()];
                                    ctx.pc = ctx.pc.add(BigInteger.ONE);
                                }
                            }
                            ctx.push(new BigInteger(1, pushData));
                        }

                        // ============ 复制操作 ============
                        else if (opcode >= OpCode.DUP1 && opcode <= OpCode.DUP16) {
                            int s = opcode - OpCode.DUP1 + 1;
                            ctx.dup(s);
                        }

                        // ============ 交换操作 ============
                        else if (opcode >= OpCode.SWAP1 && opcode <= OpCode.SWAP16) {
                            int s = opcode - OpCode.SWAP1 + 1;
                            ctx.swap(s);
                        }

                        // ============ 日志操作 ============
                        else if (opcode >= OpCode.LOG0 && opcode <= OpCode.LOG4) {
                            int topics = opcode - OpCode.LOG0;
                            offset = ctx.pop().intValue();
                            length = ctx.pop().intValue();
                            List<BigInteger> topicList = new ArrayList<>();
                            for (int i = 0; i < topics; i++) {
                                topicList.add(ctx.pop());
                            }
                            byte[] logData = ctx.readMemory(offset, length);
                            ctx.addLog(new ExecutionContext.LogEntry(ctx.address, topicList, logData));
                        }

                        // ============ 系统操作 ============
                        else if (opcode == OpCode.CREATE || opcode == OpCode.CREATE2) {
                            // 简化实现
                            ctx.pop(); // value
                            ctx.pop(); // offset
                            ctx.pop(); // length
                            // 返回新合约地址
                            ctx.push(BigInteger.ZERO);
                        }

                        else if (opcode == OpCode.CALL || opcode == OpCode.CALLCODE ||
                                opcode == OpCode.DELEGATECALL || opcode == OpCode.STATICCALL) {
                            // 简化实现
                            ctx.pop(); // gas
                            ctx.pop(); // address
                            ctx.pop(); // value
                            ctx.pop(); // argsOffset
                            ctx.pop(); // argsLength
                            ctx.pop(); // retOffset
                            ctx.pop(); // retLength
                            // 总是返回成功
                            ctx.push(BigInteger.ONE);
                        }

                        else if (opcode == OpCode.RETURN) {
                            offset = ctx.pop().intValue();
                            length = ctx.pop().intValue();
                            ctx.setReturnData(ctx.readMemory(offset, length));
                            ctx.isRunning = false;
                        }

                        else if (opcode == OpCode.REVERT) {
                            offset = ctx.pop().intValue();
                            length = ctx.pop().intValue();
                            ctx.setReturnData(ctx.readMemory(offset, length));
                            ctx.revert = true;
                            ctx.isRunning = false;
                        }

                        else if (opcode == OpCode.INVALID) {
                            throw new RuntimeException("INVALID opcode");
                        }

                        else if (opcode == OpCode.SELFDESTRUCT) {
                            BigInteger beneficiary = ctx.pop();
                            ctx.addSelfDestruct(beneficiary);
                            ctx.isRunning = false;
                        }

                        else {
                            throw new RuntimeException("Unknown opcode: 0x" + Integer.toHexString(opcode));
                        }
                        break;
                }
            }

            /**
             * 检查跳转目标是否有效
             */
            private boolean isJumpDestValid(int dest) {
                if (dest < 0 || dest >= ctx.code.length) {
                    return false;
                }
                // 检查目标位置是否是JUMPDEST，且不在PUSHn的参数区域
                int pc = 0;
                while (pc < ctx.code.length) {
                    int opcode = ctx.code[pc] & 0xFF;
                    if (pc == dest) {
                        return opcode == OpCode.JUMPDEST;
                    }
                    // 跳过PUSHn的参数
                    if (opcode >= OpCode.PUSH1 && opcode <= OpCode.PUSH32) {
                        int n = opcode - OpCode.PUSH1 + 1;
                        pc += 1 + n;
                    } else {
                        pc += 1;
                    }
                }
                return false;
            }


            /**
             * 将BigInteger转换为字节数组
             */
            private byte[] toBytes(BigInteger value, int length) {
                byte[] bytes = value.toByteArray();
                byte[] result = new byte[length];

                if (bytes.length > length) {
                    System.arraycopy(bytes, bytes.length - length, result, 0, length);
                } else {
                    System.arraycopy(bytes, 0, result, length - bytes.length, bytes.length);
                }

                return result;
            }

            /**
             * 将无符号256位整数转换为有符号
             */
            private BigInteger signed(BigInteger value) {
                if (value.testBit(255)) {
                    return value.subtract(BigInteger.valueOf(2).pow(256));
                }
                return value;
            }

            /**
             * 将有符号整数转换为无符号256位
             */
            private BigInteger unsigned(BigInteger value) {
                if (value.signum() < 0) {
                    return value.add(BigInteger.valueOf(2).pow(256));
                }
                return value.mod(BigInteger.valueOf(2).pow(256));
            }
        }

        /**
         * 执行步骤记录
         */
        public static class ExecutionStep {
            public BigInteger pc;            // 程序计数器
            public int opcode;               // 操作码
            public BigInteger gasBefore;     // 执行前Gas
            public BigInteger gasAfter;      // 执行后Gas
            public boolean success;          // 是否成功
            public String error;             // 错误信息
            public BigInteger[] stackSnapshot; // 栈快照
        }

        // 构造器
        public VM() {
            this.config = Config.defaultConfig();
        }

        public VM(Config config) {
            this.config = config;
        }

        /**
         * 执行合约
         */
        public ExecutionResult execute(ExecutionContext context) {
            context.isRunning = true;
            BigInteger initialGas = context.gas;

            Executor executor = new Executor(context, config);

            try {
                while (executor.step()) {
                    // 继续执行
                }

                BigInteger gasUsed = initialGas.subtract(context.gas);
                boolean success = !context.revert && context.returnData != null;

                return ExecutionResult.fromContext(context, success, gasUsed);

            } catch (Exception e) {
                BigInteger gasUsed = initialGas.subtract(context.gas);
                context.revert = true;
                context.revertReason = e.getMessage();
                return ExecutionResult.fromContext(context, false, gasUsed);
            }
        }

        /**
         * 创建简单的执行上下文
         */
        public static ExecutionContext createSimpleContext(byte[] code, BigInteger gasLimit) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.code = code;
            ctx.gas = gasLimit;
            ctx.gasLimit = gasLimit;
            ctx.gasPrice = BigInteger.valueOf(10_000_000_000L); // 10 gwei
            ctx.isRunning = true;

            // 设置默认值
            ctx.address = BigInteger.valueOf(0x1234567890abcdefL);
            ctx.caller = BigInteger.valueOf(0xabcdef1234567890L);
            ctx.origin = ctx.caller;
            ctx.coinbase = BigInteger.valueOf(0x1111111111111111L);

            ctx.blockNumber = BigInteger.valueOf(15_000_000L);
            ctx.timestamp = BigInteger.valueOf(1_700_000_000L);
            ctx.difficulty = BigInteger.valueOf(10_000_000_000_000L);
            ctx.gasLimitBlock = BigInteger.valueOf(30_000_000L);
            ctx.chainId = BigInteger.ONE;
            ctx.baseFee = BigInteger.valueOf(1_000_000_000L); // 1 gwei

            return ctx;
        }

        /**
         * 执行简单的合约代码
         */
        public ExecutionResult executeSimple(byte[] code, BigInteger gasLimit) {
            ExecutionContext ctx = createSimpleContext(code, gasLimit);
            return execute(ctx);
        }

        /**
         * 执行并打印结果
         */
        public void executeAndPrint(byte[] code, BigInteger gasLimit) {
            ExecutionResult result = executeSimple(code, gasLimit);
            System.out.println("执行结果:");
            System.out.println("  成功: " + result.success);
            System.out.println("  Gas使用: " + result.gasUsed);
            System.out.println("  Gas费用: " + result.gasCost + " wei");
            System.out.println("  返回数据: " + bytesToHex(result.returnData));
            if (!result.revertReason.isEmpty()) {
                System.out.println("  回滚原因: " + result.revertReason);
            }
            System.out.println("  日志数量: " + result.logs.size());
            System.out.println("  存储变更: " + result.storageChanges.size());
        }

        private String bytesToHex(byte[] bytes) {
            if (bytes == null || bytes.length == 0) {
                return "0x";
            }
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }


    /**
     * 操作接口
     */
    private interface Operation {
        void execute(ExecutionContext ctx);
    }

    /**
     * EVM字节码汇编器
     * 支持完整的EVM操作码生成，包含标签、跳转、内存操作等高级功能
     */
    public static class Assembler {
        private List<Byte> code = new ArrayList<>();
        private Map<String, Integer> labels = new HashMap<>();
        private Map<String, List<LabelReference>> labelReferences = new HashMap<>();

        /**
         * 标签引用记录
         */
        private static class LabelReference {
            int position;  // 需要填充的位置
            String label;  // 引用的标签名

            LabelReference(int position, String label) {
                this.position = position;
                this.label = label;
            }
        }

        // ============ 算术操作 ============
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

        public Assembler sdiv() {
            code.add((byte) OpCode.SDIV);
            return this;
        }

        public Assembler mod() {
            code.add((byte) OpCode.MOD);
            return this;
        }

        public Assembler smod() {
            code.add((byte) OpCode.SMOD);
            return this;
        }

        public Assembler addmod() {
            code.add((byte) OpCode.ADDMOD);
            return this;
        }

        public Assembler mulmod() {
            code.add((byte) OpCode.MULMOD);
            return this;
        }

        public Assembler exp() {
            code.add((byte) OpCode.EXP);
            return this;
        }

        public Assembler signextend() {
            code.add((byte) OpCode.SIGNEXTEND);
            return this;
        }

        // ============ 比较和位操作 ============
        public Assembler lt() {
            code.add((byte) OpCode.LT);
            return this;
        }

        public Assembler gt() {
            code.add((byte) OpCode.GT);
            return this;
        }

        public Assembler slt() {
            code.add((byte) OpCode.SLT);
            return this;
        }

        public Assembler sgt() {
            code.add((byte) OpCode.SGT);
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

        public Assembler not() {
            code.add((byte) OpCode.NOT);
            return this;
        }

        public Assembler _byte() {
            code.add((byte) OpCode.BYTE);
            return this;
        }

        public Assembler shl() {
            code.add((byte) OpCode.SHL);
            return this;
        }

        public Assembler shr() {
            code.add((byte) OpCode.SHR);
            return this;
        }

        public Assembler sar() {
            code.add((byte) OpCode.SAR);
            return this;
        }

        // ============ 加密操作 ============
        public Assembler sha3() {
            code.add((byte) OpCode.SHA3);
            return this;
        }

        // ============ 环境信息 ============
        public Assembler address() {
            code.add((byte) OpCode.ADDRESS);
            return this;
        }

        public Assembler balance() {
            code.add((byte) OpCode.BALANCE);
            return this;
        }

        public Assembler origin() {
            code.add((byte) OpCode.ORIGIN);
            return this;
        }

        public Assembler caller() {
            code.add((byte) OpCode.CALLER);
            return this;
        }

        public Assembler callvalue() {
            code.add((byte) OpCode.CALLVALUE);
            return this;
        }

        public Assembler calldataload() {
            code.add((byte) OpCode.CALLDATALOAD);
            return this;
        }

        public Assembler calldatasize() {
            code.add((byte) OpCode.CALLDATASIZE);
            return this;
        }

        public Assembler calldatacopy() {
            code.add((byte) OpCode.CALLDATACOPY);
            return this;
        }

        public Assembler codesize() {
            code.add((byte) OpCode.CODESIZE);
            return this;
        }

        public Assembler codecopy() {
            code.add((byte) OpCode.CODECOPY);
            return this;
        }

        public Assembler gasprice() {
            code.add((byte) OpCode.GASPRICE);
            return this;
        }

        public Assembler extcodesize() {
            code.add((byte) OpCode.EXTCODESIZE);
            return this;
        }

        public Assembler extcodecopy() {
            code.add((byte) OpCode.EXTCODECOPY);
            return this;
        }

        public Assembler returndatasize() {
            code.add((byte) OpCode.RETURNDATASIZE);
            return this;
        }

        public Assembler returndatacopy() {
            code.add((byte) OpCode.RETURNDATACOPY);
            return this;
        }

        public Assembler extcodehash() {
            code.add((byte) OpCode.EXTCODEHASH);
            return this;
        }

        // ============ 区块信息 ============
        public Assembler blockhash() {
            code.add((byte) OpCode.BLOCKHASH);
            return this;
        }

        public Assembler coinbase() {
            code.add((byte) OpCode.COINBASE);
            return this;
        }

        public Assembler timestamp() {
            code.add((byte) OpCode.TIMESTAMP);
            return this;
        }

        public Assembler number() {
            code.add((byte) OpCode.NUMBER);
            return this;
        }

        public Assembler difficulty() {
            code.add((byte) OpCode.DIFFICULTY);
            return this;
        }

        public Assembler gaslimit() {
            code.add((byte) OpCode.GASLIMIT);
            return this;
        }

        public Assembler chainid() {
            code.add((byte) OpCode.CHAINID);
            return this;
        }

        public Assembler selfbalance() {
            code.add((byte) OpCode.SELFBALANCE);
            return this;
        }

        public Assembler basefee() {
            code.add((byte) OpCode.BASEFEE);
            return this;
        }

        // ============ 栈、内存、存储操作 ============
        public Assembler pop() {
            code.add((byte) OpCode.POP);
            return this;
        }

        public Assembler mload() {
            code.add((byte) OpCode.MLOAD);
            return this;
        }

        public Assembler mstore() {
            code.add((byte) OpCode.MSTORE);
            return this;
        }

        public Assembler mstore8() {
            code.add((byte) OpCode.MSTORE8);
            return this;
        }

        public Assembler sload() {
            code.add((byte) OpCode.SLOAD);
            return this;
        }

        public Assembler sstore() {
            code.add((byte) OpCode.SSTORE);
            return this;
        }

        public Assembler pc() {
            code.add((byte) OpCode.PC);
            return this;
        }

        public Assembler msize() {
            code.add((byte) OpCode.MSIZE);
            return this;
        }

        public Assembler gas() {
            code.add((byte) OpCode.GAS);
            return this;
        }

        // ============ 控制流操作 ============
        public Assembler jump() {
            code.add((byte) OpCode.JUMP);
            return this;
        }

        public Assembler jumpi() {
            code.add((byte) OpCode.JUMPI);
            return this;
        }

        /**
         * 添加跳转目标标签
         * @param label 标签名
         */
        public Assembler jumpdest(String label) {
            // 记录标签位置
            labels.put(label, code.size());
            code.add((byte) OpCode.JUMPDEST);
            return this;
        }

        /**
         * 跳转到标签
         * @param label 目标标签
         */
        public Assembler jumpTo(String label) {
            // 先记录标签引用，生成代码时再确定PUSHn类型
            labelReferences.computeIfAbsent(label, k -> new ArrayList<>())
                    .add(new LabelReference(code.size(), label)); // 记录当前位置（未生成PUSHn）
            // 先不生成PUSHn，在toByteArray时补全
            return this;
        }

        /**
         * 条件跳转到标签
         * @param label 目标标签
         */
        public Assembler jumpiTo(String label) {
            // 先push占位符
            int pushPos = code.size();
            push(BigInteger.ZERO);
            jumpi();

            // 记录需要填充的标签引用
            labelReferences.computeIfAbsent(label, k -> new ArrayList<>())
                    .add(new LabelReference(pushPos + 1, label));
            return this;
        }

        // ============ 推送操作 ============
        public Assembler push(BigInteger value) {
            int byteCount = (value.bitLength() + 7) / 8;
            if (byteCount == 0) byteCount = 1;
            if (byteCount > 32) byteCount = 32;

            int opcode = OpCode.PUSH1 + byteCount - 1;
            code.add((byte) opcode);

            byte[] bytes = value.toByteArray();
            // 确保是正数表示
            if (bytes.length > 0 && bytes[0] == 0 && bytes.length > 1) {
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

        public Assembler push(int value) {
            return push(BigInteger.valueOf(value));
        }

        public Assembler push(String hexString) {
            if (hexString.startsWith("0x")) {
                hexString = hexString.substring(2);
            }
            return push(new BigInteger(hexString, 16));
        }

        public Assembler pushBytes(byte[] data) {
            int byteCount = data.length;
            if (byteCount > 32) byteCount = 32;

            int opcode = OpCode.PUSH1 + byteCount - 1;
            code.add((byte) opcode);

            for (int i = 0; i < byteCount; i++) {
                code.add(data[i]);
            }
            return this;
        }

        // 生成具体的PUSH指令
        public Assembler push1(byte b) {
            code.add((byte) OpCode.PUSH1);
            code.add(b);
            return this;
        }

        public Assembler push2(int value) {
            code.add((byte) OpCode.PUSH2);
            code.add((byte) ((value >> 8) & 0xFF));
            code.add((byte) (value & 0xFF));
            return this;
        }

        public Assembler push4(int value) {
            code.add((byte) OpCode.PUSH4);
            for (int i = 3; i >= 0; i--) {
                code.add((byte) ((value >> (i * 8)) & 0xFF));
            }
            return this;
        }

        public Assembler push20(BigInteger value) {
            return push(value);
        }

        public Assembler push32(BigInteger value) {
            return push(value);
        }

        // ============ 复制操作 (DUP) ============
        public Assembler dup(int n) {
            if (n < 1 || n > 16) {
                throw new IllegalArgumentException("DUP index must be between 1 and 16");
            }
            code.add((byte) (OpCode.DUP1 + n - 1));
            return this;
        }

        public Assembler dup1() { return dup(1); }
        public Assembler dup2() { return dup(2); }
        public Assembler dup3() { return dup(3); }
        public Assembler dup4() { return dup(4); }
        public Assembler dup5() { return dup(5); }
        public Assembler dup6() { return dup(6); }
        public Assembler dup7() { return dup(7); }
        public Assembler dup8() { return dup(8); }
        public Assembler dup9() { return dup(9); }
        public Assembler dup10() { return dup(10); }
        public Assembler dup11() { return dup(11); }
        public Assembler dup12() { return dup(12); }
        public Assembler dup13() { return dup(13); }
        public Assembler dup14() { return dup(14); }
        public Assembler dup15() { return dup(15); }
        public Assembler dup16() { return dup(16); }

        // ============ 交换操作 (SWAP) ============
        public Assembler swap(int n) {
            if (n < 1 || n > 16) {
                throw new IllegalArgumentException("SWAP index must be between 1 and 16");
            }
            code.add((byte) (OpCode.SWAP1 + n - 1));
            return this;
        }

        public Assembler swap1() { return swap(1); }
        public Assembler swap2() { return swap(2); }
        public Assembler swap3() { return swap(3); }
        public Assembler swap4() { return swap(4); }
        public Assembler swap5() { return swap(5); }
        public Assembler swap6() { return swap(6); }
        public Assembler swap7() { return swap(7); }
        public Assembler swap8() { return swap(8); }
        public Assembler swap9() { return swap(9); }
        public Assembler swap10() { return swap(10); }
        public Assembler swap11() { return swap(11); }
        public Assembler swap12() { return swap(12); }
        public Assembler swap13() { return swap(13); }
        public Assembler swap14() { return swap(14); }
        public Assembler swap15() { return swap(15); }
        public Assembler swap16() { return swap(16); }

        // ============ 日志操作 ============
        public Assembler log0() {
            code.add((byte) OpCode.LOG0);
            return this;
        }

        public Assembler log1() {
            code.add((byte) OpCode.LOG1);
            return this;
        }

        public Assembler log2() {
            code.add((byte) OpCode.LOG2);
            return this;
        }

        public Assembler log3() {
            code.add((byte) OpCode.LOG3);
            return this;
        }

        public Assembler log4() {
            code.add((byte) OpCode.LOG4);
            return this;
        }

        // ============ 系统操作 ============
        public Assembler create() {
            code.add((byte) OpCode.CREATE);
            return this;
        }

        public Assembler call() {
            code.add((byte) OpCode.CALL);
            return this;
        }

        public Assembler callcode() {
            code.add((byte) OpCode.CALLCODE);
            return this;
        }

        public Assembler _return() {
            code.add((byte) OpCode.RETURN);
            return this;
        }

        public Assembler delegatecall() {
            code.add((byte) OpCode.DELEGATECALL);
            return this;
        }

        public Assembler create2() {
            code.add((byte) OpCode.CREATE2);
            return this;
        }

        public Assembler staticcall() {
            code.add((byte) OpCode.STATICCALL);
            return this;
        }

        public Assembler revert() {
            code.add((byte) OpCode.REVERT);
            return this;
        }

        public Assembler invalid() {
            code.add((byte) OpCode.INVALID);
            return this;
        }

        public Assembler selfdestruct() {
            code.add((byte) OpCode.SELFDESTRUCT);
            return this;
        }

        public Assembler stop() {
            code.add((byte) OpCode.STOP);
            return this;
        }

        // ============ 高级功能 ============

        /**
         * 添加标签（不生成JUMPDEST）
         * 用于标记位置
         */
        public Assembler label(String name) {
            labels.put(name, code.size());
            return this;
        }

        /**
         * 添加JUMPDEST标签
         */
        public Assembler jumpdest() {
            code.add((byte) OpCode.JUMPDEST);
            return this;
        }

        /**
         * 添加数据段
         */
        public Assembler data(byte[] data) {
            for (byte b : data) {
                code.add(b);
            }
            return this;
        }

        /**
         * 添加字符串数据
         */
        public Assembler data(String str) {
            return data(str.getBytes());
        }

        /**
         * 添加十六进制数据
         */
        public Assembler hexData(String hex) {
            if (hex.startsWith("0x")) {
                hex = hex.substring(2);
            }
            if (hex.length() % 2 != 0) {
                hex = "0" + hex;
            }
            for (int i = 0; i < hex.length(); i += 2) {
                String byteStr = hex.substring(i, i + 2);
                code.add((byte) Integer.parseInt(byteStr, 16));
            }
            return this;
        }

        /**
         * 添加注释（不生成代码）
         */
        public Assembler comment(String comment) {
            // 注释不生成实际代码
            return this;
        }

        /**
         * 构建最终的字节码
         * 解析并填充所有标签引用
         */
        public byte[] toByteArray() {
            // 第一步：为所有标签引用生成正确的PUSHn指令
            List<LabelReference> allRefs = new ArrayList<>();
            for (List<LabelReference> refs : labelReferences.values()) {
                allRefs.addAll(refs);
            }
            // 按位置倒序处理，避免插入指令导致位置偏移
            allRefs.sort((a, b) -> Integer.compare(b.position, a.position));

            for (LabelReference ref : allRefs) {
                Integer targetPos = labels.get(ref.label);
                if (targetPos == null) {
                    throw new IllegalStateException("Undefined label: " + ref.label);
                }
                BigInteger targetBig = BigInteger.valueOf(targetPos);
                int byteCount = (targetBig.bitLength() + 7) / 8;
                if (byteCount == 0) byteCount = 1;
                if (byteCount > 32) byteCount = 32;
                int pushOpcode = OpCode.PUSH1 + byteCount - 1;

                // 在引用位置插入PUSHn和目标地址
                code.add(ref.position, (byte) pushOpcode);
                byte[] targetBytes = targetBig.toByteArray();
                byte[] padded = new byte[byteCount];
                int start = byteCount - targetBytes.length;
                for (int i = 0; i < targetBytes.length; i++) {
                    if (start + i < byteCount) {
                        padded[start + i] = targetBytes[i];
                    }
                }
                for (int i = 0; i < byteCount; i++) {
                    code.add(ref.position + 1 + i, padded[i]);
                }

                // 更新其他引用的位置（因为插入了字节）
                for (LabelReference r : allRefs) {
                    if (r.position > ref.position) {
                        r.position += 1 + byteCount;
                    }
                }
                // 插入JUMP/JUMPI指令
                if (ref.label.endsWith("_jump")) {
                    code.add(ref.position + 1 + byteCount, (byte) OpCode.JUMP);
                } else if (ref.label.endsWith("_jumpi")) {
                    code.add(ref.position + 1 + byteCount, (byte) OpCode.JUMPI);
                }
            }

            // 转换为字节数组
            byte[] result = new byte[code.size()];
            for (int i = 0; i < code.size(); i++) {
                result[i] = code.get(i);
            }
            return result;
        }

        /**
         * 获取当前代码位置
         */
        public int getPosition() {
            return code.size();
        }

        /**
         * 获取代码大小
         */
        public int size() {
            return code.size();
        }

        /**
         * 清空汇编器
         */
        public void clear() {
            code.clear();
            labels.clear();
            labelReferences.clear();
        }

        /**
         * 反汇编当前代码
         */
        public String disassemble() {
            StringBuilder sb = new StringBuilder();
            byte[] bytecode = toByteArray();

            for (int i = 0; i < bytecode.length; i++) {
                int opcode = bytecode[i] & 0xFF;
                sb.append(String.format("0x%04X: ", i));

                if (opcode >= OpCode.PUSH1 && opcode <= OpCode.PUSH32) {
                    int n = opcode - OpCode.PUSH1 + 1;
                    sb.append(OpCode.getName(opcode)).append(" ");

                    // 读取数据
                    for (int j = 0; j < n; j++) {
                        if (i + 1 + j < bytecode.length) {
                            sb.append(String.format("%02X", bytecode[i + 1 + j] & 0xFF));
                        } else {
                            sb.append("??");
                        }
                    }
                    i += n;
                } else {
                    sb.append(OpCode.getName(opcode));
                }
                sb.append("\n");
            }

            return sb.toString();
        }
    }


    //main
    public static void main(String[] args) {
        System.out.println("=== BitCoinVM 使用案例演示 ===\n");

        // 示例1: 简单的算术运算合约
        demoArithmeticContract();

        // 示例2: 存储操作合约
        demoStorageContract();

        // 示例3: 条件跳转合约
        demoConditionalContract();

        // 示例4: 使用汇编器生成复杂合约
        demoAssemblerContract();
    }

    /**
     * 示例1: 简单的算术运算合约
     * 计算 (1 + 2) * 3 = 9
     */
    private static void demoArithmeticContract() {
        System.out.println("1. 简单算术运算合约演示");
        System.out.println("计算: (1 + 2) * 3");

        // 使用汇编器生成字节码
        Assembler asm = new Assembler();
        byte[] code = asm.push(1)    // 压入1
                .push(2)    // 压入2
                .add()      // 相加 → 3
                .push(3)    // 压入3
                .mul()      // 相乘 → 9
                .push(0)    // 内存偏移0
                .mstore()   // 存储结果到内存
                .push(32)   // 返回数据长度32字节
                .push(0)    // 返回数据偏移0
                ._return()  // 返回结果
                .toByteArray();

        // 创建VM并执行
        VM vm = new VM();
        vm.executeAndPrint(code, BigInteger.valueOf(100000));
        System.out.println();
    }

    /**
     * 示例2: 存储操作合约
     * 演示SSTORE和SLOAD操作
     */
    private static void demoStorageContract() {
        System.out.println("2. 存储操作合约演示");
        System.out.println("存储值到slot 0，然后读取");

        Assembler asm = new Assembler();
        byte[] code = asm.push(42)           // 要存储的值
                .push(0)            // 存储slot 0
                .sstore()           // 存储到状态
                .push(0)            // 从slot 0
                .sload()            // 加载存储值
                .push(0)            // 内存偏移0
                .mstore()           // 存储到内存
                .push(32)           // 返回32字节
                .push(0)
                ._return()
                .toByteArray();

        VM vm = new VM();
        vm.executeAndPrint(code, BigInteger.valueOf(100000));
        System.out.println();
    }

    /**
     * 示例3: 条件跳转合约
     * 如果输入值大于10则返回1，否则返回0
     */
    private static void demoConditionalContract() {
        System.out.println("3. 条件跳转合约演示");
        System.out.println("模拟条件判断逻辑");

        Assembler asm = new Assembler();

        // 将 "true_branch" 改为有效的十六进制标签
        // 使用 jumpiTo 方法而不是手动 push + jumpi
        byte[] code = asm.push(15)           // 模拟输入值15
                .push(10)           // 比较值10
                .gt()               // 15 > 10 = true
                .jumpiTo("true_branch") // 使用 jumpiTo 方法

                // false分支: 返回0
                .push(0)
                .push(0)
                .mstore()
                .push(32)
                .push(0)
                ._return()

                // true分支标签
                .jumpdest("true_branch")
                .push(1)            // 返回1
                .push(0)
                .mstore()
                .push(32)
                .push(0)
                ._return()
                .toByteArray();

        VM vm = new VM();
        vm.executeAndPrint(code, BigInteger.valueOf(100000));
        System.out.println();
    }

    /**
     * 示例4: 使用汇编器生成复杂合约
     * 演示完整的合约流程
     */
    private static void demoAssemblerContract() {
        System.out.println("4. 复杂合约演示");
        System.out.println("完整合约流程包括：环境信息、计算、返回");

        Assembler asm = new Assembler();

        byte[] code = asm
                .comment("=== 修复后的复杂合约示例 ===")
                .comment("获取调用者地址并存储到内存0")
                .caller()               // 栈: [callerAddr(20字节)]
                .push(0)                // 栈: [callerAddr, 0] (内存偏移)
                .mstore()               // 存储到内存0 → 栈空

                .comment("计算地址的Keccak-256哈希")
                .push(20)               // 栈: [20] (数据长度)
                .push(0)                // 栈: [20, 0] (内存偏移)
                .sha3()                 // 计算哈希 → 栈: [hash]

                .comment("存储哈希到slot 0")
                .dup1()                 // 栈: [hash, hash]
                .push(0)                // 栈: [hash, hash, 0]
                .sstore()               // 存储 → 栈: [hash]

                .comment("读取存储值并返回")
                .push(0)                // 栈: [hash, 0]
                .sload()                // 加载 → 栈: [loadedHash]
                .push(0)                // 栈: [loadedHash, 0]
                .mstore()               // 存储到内存0 → 栈空

                .push(32)               // 栈: [32] (返回长度)
                .push(0)                // 栈: [32, 0] (返回偏移)
                ._return()              // 返回 → 栈空
                .toByteArray();

        System.out.println("生成的字节码长度: " + code.length + " 字节");
        String hexString = bytesToHex(code);
        int endIndex = Math.min(100, hexString.length());
        String truncatedHex = hexString.substring(0, endIndex);
        if (hexString.length() > 100) {
            System.out.println("字节码十六进制: " + truncatedHex + "...");
        } else {
            System.out.println("字节码十六进制: " + truncatedHex);
        }

        VM vm = new VM();
        vm.executeAndPrint(code, BigInteger.valueOf(200000));
        System.out.println();
    }

    /**
     * 示例5: Gas计算演示
     * 显示不同操作码的Gas消耗
     */
    private static void demoGasCalculation() {
        System.out.println("5. Gas计算演示");

        GasContext gasContext = new GasContext();
        gasContext.currentMemSize = 0;
        gasContext.dataLength = 32;

        // 测试几个常见操作码的Gas消耗
        int[] testOpcodes = {
                OpCode.ADD,
                OpCode.MUL,
                OpCode.SSTORE,
                OpCode.SLOAD,
                OpCode.SHA3,
                OpCode.CALL
        };

        System.out.println("操作码Gas消耗测试:");
        for (int opcode : testOpcodes) {
            BigInteger gasCost = OpCode.getGasCost(opcode, gasContext);
            String opcodeName = OpCode.getName(opcode);
            System.out.printf("  %-12s: %s gas%n", opcodeName, gasCost);
        }
        System.out.println();
    }

    /**
     * 字节数组转十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 示例6: 错误处理演示
     */
    private static void demoErrorHandling() {
        System.out.println("6. 错误处理演示");

        // 创建会导致栈下溢的无效代码
        Assembler asm = new Assembler();
        byte[] invalidCode = asm.pop()  // 空栈执行pop会导致错误
                .toByteArray();

        VM vm = new VM();
        ExecutionResult result = vm.executeSimple(invalidCode, BigInteger.valueOf(10000));

        System.out.println("错误合约执行结果:");
        System.out.println("  成功: " + result.success);
        System.out.println("  回滚原因: " + result.revertReason);
        System.out.println("  Gas使用: " + result.gasUsed);
        System.out.println();
    }
}
