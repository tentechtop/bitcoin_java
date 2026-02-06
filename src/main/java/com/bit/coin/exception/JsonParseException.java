package com.bit.coin.exception;

public class JsonParseException extends RuntimeException{

    private final int errorCode;
    private final String errorMsg;

    public JsonParseException(String errorMsg, int errorCode) {
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
