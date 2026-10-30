package com.occ.smartexpense.security;

import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.entity.SysUser;
import com.occ.smartexpense.mapper.SysUserMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证与角色拦截器，见 docs/12 §7/§8。
 *
 * <p>解析顺序：
 * <ol>
 *   <li>有 {@code Authorization: Bearer <token>} → 解析 JWT 写入上下文；</li>
 *   <li>没有 token 且 {@code auth.mode=header} → 用 X-User-Id 查库回填（兼容期）；</li>
 *   <li>都没有 → 放行，由业务层的 {@link UserContext#requireUserId()} 抛 40101。</li>
 * </ol>
 * 请求结束一定 {@link UserContext#clear()}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;

    @Value("${auth.mode:header}")
    private String mode;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;   // 静态资源等
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            UserContext.set(jwtUtil.parse(authorization.substring(BEARER.length())));
        } else if ("header".equalsIgnoreCase(mode)) {
            fillFromHeader(request);
        }

        RequireRole requireRole = resolveRole(handlerMethod);
        if (requireRole != null) {
            CurrentUser current = UserContext.get();
            if (current == null) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
            }
            boolean allowed = Arrays.stream(requireRole.value()).anyMatch(current.roles()::contains);
            if (!allowed) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "需要角色 " + String.join("/", requireRole.value()));
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();   // 必须，防止线程复用串号
    }

    /** 兼容期：X-User-Id 回填上下文（含角色），便于现有 curl 与测试平滑迁移 */
    private void fillFromHeader(HttpServletRequest request) {
        String raw = request.getHeader("X-User-Id");
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Long userId = Long.valueOf(raw.trim());
            SysUser user = sysUserMapper.selectById(userId);
            if (user != null) {
                UserContext.set(new CurrentUser(userId, user.getUsername(), user.getRealName(),
                        user.getDeptId(), roleSet(userId)));
            }
        } catch (NumberFormatException e) {
            log.debug("X-User-Id 非法，忽略：{}", raw);
        }
    }

    private Set<String> roleSet(Long userId) {
        List<String> codes = sysUserMapper.selectRoleCodes(userId);
        return codes == null ? Set.of() : new HashSet<>(codes);
    }

    /** 方法注解优先，其次类注解 */
    private RequireRole resolveRole(HandlerMethod handlerMethod) {
        RequireRole onMethod = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return handlerMethod.getBeanType().getAnnotation(RequireRole.class);
    }
}
