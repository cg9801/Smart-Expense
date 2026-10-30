package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;

/**
 * ThreadLocal 用户上下文，见 docs/12 §6。
 *
 * <p>铁律：
 * <ol>
 *   <li>请求结束必须在拦截器 afterCompletion 里 {@link #clear()}，否则 Tomcat 线程复用会串号；</li>
 *   <li>MQ 消费者、定时任务等异步线程没有上下文，必须显式传 operatorId。</li>
 * </ol>
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 可能为 null（未登录），调用方自行判断 */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 业务层取当前用户：未登录直接 40101 */
    public static Long requireUserId() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return user.userId();
    }

    public static CurrentUser requireUser() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
