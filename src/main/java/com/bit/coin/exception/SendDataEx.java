package com.bit.coin.exception;

public class SendDataEx extends RuntimeException{
    private final int errorCode;
    private final String errorMsg;

    public SendDataEx(String errorMsg, int errorCode) {
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
