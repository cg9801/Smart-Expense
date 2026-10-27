package com.occ.smartexpense.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 统一错误码：code 给前端判断，httpStatus 表达语义，message 可直接展示。
 * 与 docs/03-接口清单.md §5 错误码表一一对应。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, HttpStatus.OK, "ok"),
    PARAM_ERROR(40001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    NOT_FOUND(40401, HttpStatus.NOT_FOUND, "资源不存在"),
    FORBIDDEN(40301, HttpStatus.FORBIDDEN, "无权限"),
    STATE_ILLEGAL(40901, HttpStatus.CONFLICT, "当前状态不允许该操作"),
    BUDGET_NOT_ENOUGH(40902, HttpStatus.CONFLICT, "预算不足"),
    /** 幂等键已用于不同内容，见 docs/03-接口清单.md §5 */
    IDEMPOTENCY_CONFLICT(40903, HttpStatus.CONFLICT, "幂等键已用于不同内容"),
    SYSTEM_ERROR(50001, HttpStatus.INTERNAL_SERVER_ERROR, "系统异常");

    private final int code;
    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(int code, HttpStatus httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
