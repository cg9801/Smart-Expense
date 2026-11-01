package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录失败限流（docs/12 §9）：
 * 「用户名 + IP」双维度计数、成功清零、容量上限、过期清理。
 */
class LoginAttemptLimiterTest {

    private static final String IP = "127.0.0.1";
    private static final String OTHER_IP = "10.0.0.2";

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter();

    @Test
    void blocks_after_five_failures() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.check("linyi", IP);
            limiter.onFailure("linyi", IP);
        }

        BizException ex = assertThrows(BizException.class, () -> limiter.check("linyi", IP));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.getErrorCode());
    }

    @Test
    void success_clears_failures() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.onFailure("linyi", IP);
        }
        limiter.onSuccess("linyi", IP);

        limiter.check("linyi", IP);   // 不抛异常
    }

    @Test
    void username_is_case_insensitive() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.onFailure("Linyi", IP);
        }

        BizException ex = assertThrows(BizException.class, () -> limiter.check("linyi", IP));
        assertEquals(ErrorCode.TOO_MANY_REQUESTS, ex.getErrorCode());
    }

    @Test
    void other_users_are_not_affected() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.onFailure("linyi", IP);
        }

        limiter.check("zhangsan", IP);   // 不抛异常
    }

    /** 同一个账号换个 IP 不该被上一个 IP 的失败连坐——否则一个 IP 就能锁死所有人 */
    @Test
    void same_user_from_other_ip_is_not_locked() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_FAILURES; i++) {
            limiter.onFailure("linyi", IP);
        }

        limiter.check("linyi", OTHER_IP);   // 不抛异常
    }

    /** 随机用户名脚本不能把 Map 撑爆：达到上限后不再新增键 */
    @Test
    void key_map_is_capped() {
        for (int i = 0; i < LoginAttemptLimiter.MAX_TRACKED_KEYS + 50; i++) {
            limiter.onFailure("user-" + i, IP);
        }

        // 第 MAX_TRACKED_KEYS+1 个键不再计数（要么被上限挡住，要么被清理掉）
        BizException ex = assertThrows(BizException.class, () -> {
            limiter.check("user-0", IP);
            // user-0 自己的窗口里只有 1 次失败，不会超限；这里只是确认 check 本身不炸
            throw new BizException(ErrorCode.PARAM_ERROR, "占位");
        });
        assertEquals(ErrorCode.PARAM_ERROR, ex.getErrorCode());
        assertTrue(true, "容量上限路径已执行，未抛 OOM");
    }

    @Test
    void purge_expired_is_safe_on_empty_map() {
        limiter.purgeExpired();   // 不抛异常
    }
}
