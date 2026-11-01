package com.occ.smartexpense.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动守卫（docs/12 §7）：把"默认不安全"变成"启动即暴露"。
 *
 * <p>两条规则：
 * <ol>
 *   <li>mode=header 且没显式打开 compat → 直接拒绝启动。这是安全关键项，不能只是日志提醒；</li>
 *   <li>密钥仍是仓库默认值 → 打 ERROR。这里**不**拒绝启动：本地演示、mvnw test、CI 都跑在默认密钥上，
 *       硬失败会让所有本地用例起不来；生产 checklist 里把这条列为必改项（docs/12 §3）。</li>
 * </ol>
 */
@Slf4j
@Component
public class AuthStartupGuard {

    /** 与 application.yaml 的默认值保持一致；只用于检测"没换密钥"，不参与鉴权 */
    private static final String DEV_SECRET = "smart-expense-dev-secret-key-please-change-32";

    @Value("${auth.mode:jwt}")
    private String mode;

    @Value("${auth.compat:false}")
    private boolean compat;

    @Value("${auth.jwt-secret}")
    private String secret;

    @PostConstruct
    public void check() {
        if ("header".equalsIgnoreCase(mode) && !compat) {
            throw new IllegalStateException(
                    "auth.mode=header 仅限本地兼容期：请设置 AUTH_MODE=jwt，或显式设置 AUTH_COMPAT=true 后再用兼容模式");
        }
        if (DEV_SECRET.equals(secret)) {
            log.error("JWT_SECRET 仍是仓库默认值，生产环境必须替换为 >=32 字节的随机字符串（docs/12 §3）");
        }
        if ("header".equalsIgnoreCase(mode)) {
            log.warn("认证运行在 header 兼容模式：X-User-Id 可伪造，仅限本地演示");
        }
    }
}
