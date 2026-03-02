package com.bit.coin.exception;



public class ExceptionMsg {

    // ==================== 交易验证错误 ====================

    /**
     * 手续费验证错误
     */
    public static final String TX_FEE_INSUFFICIENT = "交易手续费不足";
    public static final String TX_FEE_TOO_HIGH = "交易手续费过高";
    public static final String TX_FEE_NEGATIVE = "交易手续费不能为负数";
    public static final String TX_FEE_INCORRECT = "手续费计算不正确";

    /**
     * UTXO验证错误
     */
    public static final String UTXO_NOT_FOUND = "输入引用的UTXO不存在";
    public static final String UTXO_ALREADY_SPENT = "UTXO已被花费";
    public static final String UTXO_INVALID_REF = "UTXO引用无效";
    public static final String UTXO_DOUBLE_SPEND = "双花攻击：UTXO已被使用";
    public static final String UTXO_AMOUNT_MISMATCH = "UTXO金额不匹配";

    /**
     * 签名验证错误
     */
    public static final String SIGNATURE_INVALID = "输入签名不正确";
    public static final String SIGNATURE_VERIFY_FAILED = "签名验证失败";
    public static final String SIGNATURE_FORMAT_ERROR = "签名格式错误";
    public static final String SIGNATURE_MISSING = "缺少必要的签名";
    public static final String PRIVATE_KEY_MISMATCH = "私钥与地址不匹配";

    /**
     * Coinbase交易错误
     */
    public static final String COINBASE_IMMATURE = "Coinbase交易未成熟";
    public static final String COINBASE_REWARD_INVALID = "Coinbase奖励金额无效";
    public static final String COINBASE_MATURITY_NOT_REACHED = "Coinbase未达到成熟度要求";
    public static final String COINBASE_NO_INPUTS = "Coinbase交易不能有输入";

    /**
     * 交易结构错误
     */
    public static final String TX_VERSION_INVALID = "交易版本无效";
    public static final String TX_INPUT_EMPTY = "交易输入不能为空";
    public static final String TX_OUTPUT_EMPTY = "交易输出不能为空";
    public static final String TX_SIZE_EXCEEDED = "交易大小超过限制";
    public static final String TX_LOCKTIME_INVALID = "交易锁定时间无效";
    public static final String TX_FORMAT_INVALID = "交易格式无效";
    public static final String TX_SERIALIZE_ERROR = "交易序列化错误";

    /**
     * 脚本验证错误
     */
    public static final String SCRIPT_EXECUTION_FAILED = "脚本执行失败";
    public static final String SCRIPT_SIG_INVALID = "解锁脚本无效";
    public static final String SCRIPT_PUBKEY_INVALID = "锁定脚本无效";
    public static final String SCRIPT_OPCODE_INVALID = "操作码无效";
    public static final String SCRIPT_STACK_UNDERFLOW = "脚本堆栈下溢";

    // ==================== 区块验证错误 ====================

    /**
     * 区块头验证错误
     */
    public static final String BLOCK_HASH_INVALID = "区块哈希无效";
    public static final String BLOCK_VERSION_INVALID = "区块版本无效";
    public static final String BLOCK_TIMESTAMP_INVALID = "区块时间戳无效";
    public static final String BLOCK_TIMESTAMP_FUTURE = "区块时间戳在未来";
    public static final String BLOCK_DIFFICULTY_INVALID = "区块难度无效";
    public static final String BLOCK_NONCE_INVALID = "区块随机数无效";
    public static final String BLOCK_MERKLE_ROOT_INVALID = "默克尔根无效";
    public static final String BLOCK_PREV_HASH_INVALID = "前一个区块哈希无效";
    public static final String BLOCK_HEIGHT_INVALID = "区块高度无效";

    /**
     * 区块结构验证错误
     */
    public static final String BLOCK_SIZE_EXCEEDED = "区块大小超过限制";
    public static final String BLOCK_TX_COUNT_INVALID = "交易数量无效";
    public static final String BLOCK_TX_LIST_EMPTY = "区块交易列表为空";
    public static final String BLOCK_COINBASE_MISSING = "缺少Coinbase交易";
    public static final String BLOCK_COINBASE_MULTIPLE = "多个Coinbase交易";
    public static final String BLOCK_SERIALIZE_ERROR = "区块序列化错误";

    /**
     * 工作量证明验证错误
     */
    public static final String POW_INVALID = "工作量证明无效";
    public static final String POW_TARGET_NOT_MET = "未达到目标难度";
    public static final String POW_HASH_NOT_VALID = "哈希不满足难度要求";
    public static final String POW_TIME_TOO_SHORT = "出块时间过短";

    /**
     * 区块链验证错误
     */
    public static final String CHAIN_ORPHAN_BLOCK = "孤块：缺少父区块";
    public static final String CHAIN_FORK_DETECTED = "检测到分叉";
    public static final String CHAIN_REORG_EXCEED_LIMIT = "重组超过限制";
    public static final String CHAIN_CONSENSUS_BROKEN = "共识规则被破坏";
    public static final String CHAIN_TIP_STALE = "链顶端已过时";

    /**
     * 默克尔树验证错误
     */
    public static final String MERKLE_ROOT_MISMATCH = "默克尔根不匹配";
    public static final String MERKLE_PROOF_INVALID = "默克尔证明无效";
    public static final String MERKLE_TREE_BUILD_FAILED = "默克尔树构建失败";

    // ==================== 网络和同步错误 ====================

    public static final String NETWORK_PEER_INVALID = "无效的对等节点";
    public static final String NETWORK_TIMEOUT = "网络超时";
    public static final String NETWORK_PROTOCOL_ERROR = "网络协议错误";
    public static final String SYNC_HEIGHT_MISMATCH = "同步高度不匹配";
    public static final String SYNC_BLOCK_MISSING = "同步时缺少区块";

    // ==================== 内存池验证错误 ====================

    public static final String MEMPOOL_TX_EXISTS = "交易已在内存池中";
    public static final String MEMPOOL_FULL = "内存池已满";
    public static final String MEMPOOL_DOUBLE_SPEND = "内存池中存在双花交易";
    public static final String MEMPOOL_TX_TOO_LOW_FEE = "交易手续费过低";
    public static final String MEMPOOL_TX_CONFLICT = "交易冲突";

    // ==================== 钱包相关错误 ====================

    public static final String WALLET_BALANCE_INSUFFICIENT = "钱包余额不足";
    public static final String WALLET_ADDRESS_INVALID = "钱包地址无效";
    public static final String WALLET_KEY_NOT_FOUND = "未找到对应的密钥";
    public static final String WALLET_SIGN_FAILED = "钱包签名失败";
    public static final String WALLET_ENCRYPTION_ERROR = "钱包加密错误";

    // ==================== 共识规则错误 ====================

    public static final String CONSENSUS_BLOCK_REWARD_INVALID = "区块奖励不符合共识规则";
    public static final String CONSENSUS_COINBASE_MATURITY_NOT_REACHED = "Coinbase未达到共识要求的成熟度";
    public static final String CONSENSUS_TX_SIZE_LIMIT_EXCEEDED = "交易超过共识大小限制";
    public static final String CONSENSUS_BLOCK_SIZE_LIMIT_EXCEEDED = "区块超过共识大小限制";
    public static final String CONSENSUS_SCRIPT_OPCODE_DISABLED = "脚本操作码被禁用";

    // ==================== 验证器特定错误 ====================

    /**
     * 验证过程中使用的错误消息
     */
    public static final String VALIDATOR_TX_INVALID = "交易验证失败";
    public static final String VALIDATOR_BLOCK_INVALID = "区块验证失败";
    public static final String VALIDATOR_CHAIN_INVALID = "区块链验证失败";
    public static final String VALIDATOR_UNKNOWN_ERROR = "未知验证错误";

    // ==================== 错误码定义 ====================

    /**
     * 为每个错误定义错误码（可选，便于日志分析和监控）
     */
    public static class ErrorCode {
        // 交易验证错误码: 1000-1999
        public static final int TX_FEE_INSUFFICIENT_CODE = 1001;
        public static final int UTXO_NOT_FOUND_CODE = 1002;
        public static final int SIGNATURE_INVALID_CODE = 1003;
        public static final int COINBASE_IMMATURE_CODE = 1004;
        public static final int TX_DOUBLE_SPEND_CODE = 1005;

        // 区块验证错误码: 2000-2999
        public static final int BLOCK_HASH_INVALID_CODE = 2001;
        public static final int BLOCK_POW_INVALID_CODE = 2002;
        public static final int BLOCK_TIMESTAMP_INVALID_CODE = 2003;
        public static final int BLOCK_MERKLE_ROOT_INVALID_CODE = 2004;

        // 共识错误码: 3000-3999
        public static final int CONSENSUS_BLOCK_REWARD_INVALID_CODE = 3001;

        // 网络错误码: 4000-4999
        public static final int NETWORK_PEER_INVALID_CODE = 4001;

        // 钱包错误码: 5000-5999
        public static final int WALLET_BALANCE_INSUFFICIENT_CODE = 5001;
    }

    // ==================== 异常构建辅助方法 ====================

    /**
     * 构建交易验证异常消息
     */
    public static String buildTxValidationError(String txHash, String errorMsg) {
        return String.format("交易验证失败 [TX: %s]: %s",
                txHash.substring(0, Math.min(txHash.length(), 16)), errorMsg);
    }

    /**
     * 构建区块验证异常消息
     */
    public static String buildBlockValidationError(String blockHash, int height, String errorMsg) {
        return String.format("区块验证失败 [Height: %d, Hash: %s]: %s",
                height, blockHash.substring(0, Math.min(blockHash.length(), 16)), errorMsg);
    }

    /**
     * 构建UTXO引用异常消息
     */
    public static String buildUTXOError(String txHash, int outputIndex, String errorMsg) {
        return String.format("UTXO引用错误 [TX: %s, Index: %d]: %s",
                txHash.substring(0, Math.min(txHash.length(), 16)), outputIndex, errorMsg);
    }

    /**
     * 构建Coinbase成熟度异常消息
     */
    public static String buildCoinbaseMaturityError(int confirmations, int requiredConfirmations) {
        return String.format("Coinbase未成熟: 当前确认数 %d, 需要 %d",
                confirmations, requiredConfirmations);
    }
}