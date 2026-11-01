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
 *   <li>有 {@code Authorization: Bearer <token>} → 解析 JWT，并按 userId 查库补全姓名/部门、校验账号状态；</li>
 *   <li>没有 token 且 {@code auth.compat=true 且 auth.mode=header} → 用 X-User-Id 查库回填（本地兼容期）；</li>
 *   <li>都没有 → 放行，由业务层的 {@link UserContext#requireUserId()} 抛 40101。</li>
 * </ol>
 * 上下文清理在 {@link UserContextFilter} 的 finally 里，不放在本拦截器：
 * preHandle 自己抛异常时 afterCompletion 不会被回调，清理会漏（docs/12 §6）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;

    @Value("${auth.mode:jwt}")
    private String mode;

    @Value("${auth.compat:false}")
    private boolean compat;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;   // 静态资源等
        }

        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER)) {
            // JWT 只声明 userId/username/roles；姓名、部门与账号状态以库为准（docs/12 §3）
            UserContext.set(enrich(jwtUtil.parse(authorization.substring(BEARER.length()))));
        } else if (compat && "header".equalsIgnoreCase(mode)) {
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

    /**
     * 这里**故意不实现** afterCompletion 清理：拦截器自己的 preHandle 抛异常时它不会被回调，
     * 清理统一由 {@link UserContextFilter} 的 finally 负责（docs/12 §6）。
     */

    /**
     * 身份真值以数据库为准：JWT 只携带 userId/username/roles（docs/12 §3），
     * 这里用一次主键查询补全 realName/deptId（详情鉴权需要部门），顺带拦掉"已禁用但 token 未过期"。
     */
    private CurrentUser enrich(CurrentUser parsed) {
        SysUser user = sysUserMapper.selectById(parsed.userId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号不可用，请重新登录");
        }
        // 角色也以库为准：撤回权限后旧 token 不应继续有角色
        return new CurrentUser(user.getId(), user.getUsername(), user.getRealName(),
                user.getDeptId(), roleSet(parsed.userId()));
    }

    /** 兼容期：X-User-Id 回填上下文（含角色），仅在 auth.compat=true 时可达 */
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
