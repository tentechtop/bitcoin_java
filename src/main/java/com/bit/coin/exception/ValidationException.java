package com.bit.coin.exception;

//// 在交易验证中使用异常消息
//public class TransactionValidator {
//    public void validate(Transaction tx) throws ValidationException {
//        if (isDoubleSpend(tx)) {
//            throw new ValidationException(
//                ExceptionMsg.UTXO_DOUBLE_SPEND,
//                ExceptionMsg.ErrorCode.TX_DOUBLE_SPEND_CODE
//            );
//        }
//
//        if (!validateSignatures(tx)) {
//            throw new ValidationException(
//                ExceptionMsg.SIGNATURE_INVALID,
//                ExceptionMsg.ErrorCode.SIGNATURE_INVALID_CODE
//            );
//        }
//    }
//}
//

/// / 在区块验证中使用异常消息
//public class BlockValidator {
//    public void validate(Block block) throws ValidationException {
//        if (!validateProofOfWork(block)) {
//            throw new ValidationException(
//                ExceptionMsg.buildBlockValidationError(
//                    block.getHash(),
//                    block.getHeight(),
//                    ExceptionMsg.POW_INVALID
//                ),
//                ExceptionMsg.ErrorCode.BLOCK_POW_INVALID_CODE
//            );
//        }
//    }
//}

// 自定义异常类示例
public class ValidationException extends RuntimeException {
    private final int errorCode;
    private final String errorMsg;

    public ValidationException(String errorMsg, int errorCode) {
        super(errorMsg);
        this.errorMsg = errorMsg;
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMsg() {
        return errorMsg;
    }
}