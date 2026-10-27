package com.occ.smartexpense.common;

import lombok.Getter;

/**
 * 业务异常：由 GlobalExceptionHandler 统一转成 Result + HTTP 状态码。
 * 业务代码只抛这个，不返回裸的 null/boolean 表达失败。
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
